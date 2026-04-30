package com.aman.browser.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.aman.browser.AmanApplication
import com.aman.browser.session.SessionRepository
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings

/**
 * Application-scoped owner of all GeckoSessions / browser tabs.
 *
 * Hoisting state out of [BrowserFragment] means navigating away from the
 * browser tab in the bottom nav (or recreating the view on a configuration
 * change) no longer destroys sessions, closes connections to the :gecko
 * process, or wipes the user's open tabs. This is the single biggest
 * contributor to the "browser lags after I open the app again" symptom.
 */
class BrowserTabManager {

    val tabs: SnapshotStateList<BrowserTab> = mutableStateListOf()

    private var nextTabId = 1
    var selectedTabId by mutableIntStateOf(0)
        private set

    val selectedTab: BrowserTab?
        get() = tabs.firstOrNull { it.id == selectedTabId }

    fun ensureInitialTab(defaultUrl: String, listenerFactory: (BrowserTab) -> TabListeners) {
        if (tabs.isNotEmpty()) return
        // Try restoring the previous session first.
        val restored = AmanApplication.sessionRepository.load()
        if (restored != null && restored.tabs.isNotEmpty()) {
            restored.tabs.forEachIndexed { index, snap ->
                createTab(
                    url = snap.url,
                    select = index == restored.selectedIndex,
                    listenerFactory = listenerFactory,
                )
            }
            return
        }
        createTab(defaultUrl, select = true, listenerFactory = listenerFactory)
    }

    /** Persist the current tab list so the user can resume after a cold start. */
    fun persistSession() {
        if (tabs.isEmpty()) {
            AmanApplication.sessionRepository.clear()
            return
        }
        val selectedIndex = tabs.indexOfFirst { it.id == selectedTabId }.coerceAtLeast(0)
        val snapshot = SessionRepository.Snapshot(
            tabs = tabs.map { SessionRepository.TabSnapshot(it.url, it.title) },
            selectedIndex = selectedIndex,
        )
        AmanApplication.sessionRepository.saveAsync(snapshot)
    }

    fun createTab(
        url: String,
        select: Boolean,
        listenerFactory: (BrowserTab) -> TabListeners,
    ): BrowserTab {
        val settings = GeckoSessionSettings.Builder()
            .useTrackingProtection(true)
            .build()
        val session = GeckoSession(settings)
        val tab = BrowserTab(
            id = nextTabId++,
            session = session,
            initialTitle = "New tab",
            initialUrl = url,
        )
        session.open(AmanApplication.geckoRuntime)
        val listeners = listenerFactory(tab)
        session.navigationDelegate = listeners.navigation
        session.progressDelegate = listeners.progress
        session.contentDelegate = listeners.content
        tabs += tab
        // Wire blur extension if it has finished installing.
        AmanApplication.webExtensionManagerOrNull()?.wireSessionIfReady(session)
        session.loadUri(url)
        if (select) selectedTabId = tab.id
        return tab
    }

    fun selectTab(tabId: Int) {
        if (tabs.any { it.id == tabId }) selectedTabId = tabId
    }

    /**
     * Close a tab. Returns the tabId that should be selected next (or 0 if a
     * fresh start tab should be created by the caller).
     */
    fun closeTab(tabId: Int, defaultUrlIfEmpty: String, listenerFactory: (BrowserTab) -> TabListeners): Int {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index == -1) return selectedTabId
        val wasSelected = tabs[index].id == selectedTabId
        val tab = tabs.removeAt(index)
        tab.session.close()

        if (tabs.isEmpty()) {
            createTab(defaultUrlIfEmpty, select = true, listenerFactory = listenerFactory)
            return selectedTabId
        }
        if (wasSelected) {
            val nextIndex = index.coerceAtMost(tabs.lastIndex)
            selectedTabId = tabs[nextIndex].id
        }
        return selectedTabId
    }

    /** Wire the WebExtension into every existing session — called once the extension finishes installing. */
    fun wireAllSessions(wire: (GeckoSession) -> Unit) {
        tabs.forEach { wire(it.session) }
    }

    /** Close every tab and start fresh on [defaultUrl]. */
    fun closeAll(defaultUrl: String, listenerFactory: (BrowserTab) -> TabListeners): Int {
        tabs.toList().forEach { tab -> tab.session.close() }
        tabs.clear()
        createTab(defaultUrl, select = true, listenerFactory = listenerFactory)
        return selectedTabId
    }
}

class BrowserTab(
    val id: Int,
    val session: GeckoSession,
    initialTitle: String,
    initialUrl: String,
) {
    var title by mutableStateOf(initialTitle)
    var url by mutableStateOf(initialUrl)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
}

class TabListeners(
    val navigation: GeckoSession.NavigationDelegate,
    val progress: GeckoSession.ProgressDelegate,
    val content: GeckoSession.ContentDelegate,
)
