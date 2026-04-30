package com.aman.browser.browser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.aman.browser.AmanApplication
import com.aman.browser.BackPressHandler
import com.aman.browser.R
import com.aman.browser.blocklist.AppCategoryBlocklist
import com.aman.browser.ui.compose.AmanTheme
import com.aman.browser.util.UrlUtils
import kotlinx.coroutines.launch
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class BrowserFragment : Fragment(), BackPressHandler {

    private val viewModel: BrowserViewModel by viewModels()

    private val tabManager get() = AmanApplication.tabManager
    private val tabs get() = tabManager.tabs
    private val selectedTab: BrowserTab? get() = tabManager.selectedTab

    private var geckoView: GeckoView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = GeckoView(requireContext())
        geckoView = view
        // Pipe one-shot view-model events to Toast.
        lifecycleScope.launch {
            viewModel.toasts.collect { msg ->
                android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AmanTheme {
                    BrowserScreen(
                        geckoView = view,
                        tabs = tabs,
                        selectedTab = selectedTab,
                        onNavigate = { input -> selectedTab?.let { tab -> navigate(tab, input) } },
                        onBack = { selectedTab?.session?.goBack() },
                        onForward = { selectedTab?.session?.goForward() },
                        onRefresh = { selectedTab?.session?.reload() },
                        onNewTab = { createTab("https://www.google.com", true) },
                        onSelectTab = ::selectTab,
                        onCloseTab = ::closeTab,
                        onCloseAll = ::closeAllTabs,
                        onShowHistory = {
                            findNavController().navigate(R.id.historyFragment)
                        },
                        onShowDownloads = {
                            findNavController().navigate(R.id.downloadsFragment)
                        },
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Either restore an existing session into the (re-created) GeckoView,
        // or initialise the very first tab on cold launch / app upgrade.
        if (tabs.isEmpty()) {
            val initialUrl = arguments?.getString(ARG_URL) ?: "https://www.google.com"
            tabManager.ensureInitialTab(initialUrl) { tab -> buildListeners(tab) }
        } else {
            // Optionally honour a deep-link: if MainActivity routed an intent here
            // with a URL, navigate the currently selected tab to it instead of
            // creating a brand-new tab on every nav.
            arguments?.getString(ARG_URL)?.let { url ->
                if (url.isNotBlank() && url != "https://www.google.com") {
                    selectedTab?.let { navigate(it, url) }
                    arguments?.remove(ARG_URL)
                }
            }
        }
        attachSelectedSessionToView()
        selectedTab?.let { tab ->
            viewModel.onUrlChanged(tab.url)
            viewModel.onTitleChanged(tab.title)
            viewModel.onLoadingChanged(tab.loading)
        }
    }

    private fun attachSelectedSessionToView() {
        val view = geckoView ?: return
        val tab = selectedTab ?: return
        // releaseSession() is a no-op if no session is attached, but is needed
        // when the previous fragment view was destroyed and the session is
        // still bound to the (now-detached) old GeckoView.
        view.releaseSession()
        // Deactivate any previously-bound session so Gecko stops painting it.
        activeSession?.takeIf { it !== tab.session }?.let { prev ->
            runCatching { prev.setFocused(false); prev.setActive(false) }
        }
        view.setSession(tab.session)
        // Mark the new session active+focused if we're currently resumed —
        // otherwise onResume() will do it. Without this Gecko throttles the
        // page and the UI feels frozen / laggy after returning from another
        // app or unminimising Aman.
        if (isResumed) {
            runCatching {
                tab.session.setActive(true)
                tab.session.setFocused(true)
            }
        }
        activeSession = tab.session
    }

    /** Tracks the GeckoSession we last marked active, to deactivate cleanly. */
    private var activeSession: GeckoSession? = null

    override fun onResume() {
        super.onResume()
        // Re-activate the visible session. GeckoSession.setActive(false) is
        // called in onPause; without flipping it back on we get a frozen /
        // laggy page when the user returns from another app.
        selectedTab?.session?.let { s ->
            runCatching {
                s.setActive(true)
                s.setFocused(true)
            }
            activeSession = s
        }
    }

    override fun onPause() {
        // Throttle the page while Aman is in the background. This is also
        // what lets Gecko free GPU resources for the foreground app.
        activeSession?.let { s ->
            runCatching {
                s.setFocused(false)
                s.setActive(false)
            }
        }
        super.onPause()
    }

    private fun createTab(url: String, select: Boolean): BrowserTab {
        val tab = tabManager.createTab(url, select) { newTab -> buildListeners(newTab) }
        if (select) attachSelectedSessionToView()
        return tab
    }

    private fun selectTab(tabId: Int) {
        tabManager.selectTab(tabId)
        attachSelectedSessionToView()
        selectedTab?.let { tab ->
            viewModel.onUrlChanged(tab.url)
            viewModel.onTitleChanged(tab.title)
            viewModel.onLoadingChanged(tab.loading)
        }
    }

    private fun closeTab(tabId: Int) {
        tabManager.closeTab(tabId, "https://www.google.com") { tab -> buildListeners(tab) }
        attachSelectedSessionToView()
    }

    private fun closeAllTabs() {
        tabManager.closeAll("https://www.google.com") { tab -> buildListeners(tab) }
        attachSelectedSessionToView()
    }

    private fun buildListeners(tab: BrowserTab): TabListeners = TabListeners(
        navigation = buildNavigationDelegate(tab),
        progress   = buildProgressDelegate(tab),
        content    = buildContentDelegate(tab),
    )

    private fun buildNavigationDelegate(tab: BrowserTab) = object : GeckoSession.NavigationDelegate {
        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest,
        ): GeckoResult<AllowOrDeny>? {
            val verdict = AppCategoryBlocklist.classifyUrl(request.uri)
            return if (verdict.blocked) {
                viewModel.notifyBlocked(verdict.reason)
                GeckoResult.fromValue(AllowOrDeny.DENY)
            } else {
                GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }
        }

        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
        ) {
            tab.url = url.orEmpty()
            if (tab.id == tabManager.selectedTabId) viewModel.onUrlChanged(tab.url)
            // Persist to history (debounced inside the repository).
            url?.takeIf { it.isNotBlank() }?.let { committed ->
                lifecycleScope.launch {
                    AmanApplication.historyRepository.recordVisit(committed, tab.title)
                }
            }
            tabManager.persistSession()
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            tab.canGoBack = canGoBack
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            tab.canGoForward = canGoForward
        }
    }

    private fun buildProgressDelegate(tab: BrowserTab) = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            tab.loading = true
            tab.progress = 0
            if (tab.id == tabManager.selectedTabId) viewModel.onLoadingChanged(true)
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            tab.loading = false
            tab.progress = 100
            if (tab.id == tabManager.selectedTabId) viewModel.onLoadingChanged(false)
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            tab.progress = progress
        }
    }

    private fun buildContentDelegate(tab: BrowserTab) = object : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            val safe = title.takeUnless { it.isNullOrBlank() } ?: "New tab"
            tab.title = safe
            if (tab.id == tabManager.selectedTabId) viewModel.onTitleChanged(tab.title)
            // Update history title for the latest visit of this URL.
            if (tab.url.isNotBlank()) {
                lifecycleScope.launch {
                    AmanApplication.historyRepository.updateTitle(tab.url, safe)
                }
            }
            tabManager.persistSession()
        }

        override fun onExternalResponse(
            session: GeckoSession,
            response: org.mozilla.geckoview.WebResponse,
        ) {
            // Every "download" Gecko can't render in-page funnels here. Run
            // it through the interceptor: it will block app-store / VPN /
            // alt-browser packages and surface the rest in the Downloads
            // screen.
            AmanApplication.downloadsRepository.intercept(response)
            viewModel.notifyDownloadStarted()
        }
    }

    private fun navigate(tab: BrowserTab, input: String) {
        val url = UrlUtils.normalizeBrowserInput(input) ?: return
        tab.url = url
        tab.session.loadUri(url)
    }

    override fun onBackPressed(): Boolean {
        val tab = selectedTab ?: return false
        if (tab.canGoBack) {
            tab.session.goBack()
            return true
        }
        return false
    }

    override fun onDestroyView() {
        // Detach the session from the view that's about to be destroyed, but
        // DO NOT close it — the singleton BrowserTabManager keeps it alive so
        // that returning to this fragment doesn't reload every page.
        activeSession?.let { s -> runCatching { s.setFocused(false); s.setActive(false) } }
        activeSession = null
        geckoView?.releaseSession()
        geckoView = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        geckoView?.releaseSession()
        super.onDestroy()
    }

    companion object {
        const val ARG_URL = "url"

        fun newInstance(url: String) = BrowserFragment().apply {
            arguments = Bundle().apply { putString(ARG_URL, url) }
        }
    }
}

@Composable
private fun BrowserScreen(
    geckoView: GeckoView,
    tabs: List<BrowserTab>,
    selectedTab: BrowserTab?,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onNewTab: () -> Unit,
    onSelectTab: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onCloseAll: () -> Unit,
    onShowHistory: () -> Unit,
    onShowDownloads: () -> Unit,
) {
    var showTabs by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            BrowserToolbar(
                committedUrl = selectedTab?.url.orEmpty(),
                canGoBack = selectedTab?.canGoBack == true,
                canGoForward = selectedTab?.canGoForward == true,
                tabCount = tabs.size,
                onNavigate = onNavigate,
                onBack = onBack,
                onForward = onForward,
                onRefresh = onRefresh,
                onShowTabs = { showTabs = true },
                onShowMenu = { showMenu = true },
            )
            if (selectedTab?.loading == true) {
                val s = selectedTab
                LinearProgressIndicator(
                    progress = { (s.progress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 86.dp),
            ) {
                AndroidView(
                    factory = { geckoView },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (showTabs) {
        TabsDialog(
            tabs = tabs,
            selectedTabId = selectedTab?.id ?: 0,
            onDismiss = { showTabs = false },
            onNewTab = {
                showTabs = false
                onNewTab()
            },
            onSelectTab = { id ->
                showTabs = false
                onSelectTab(id)
            },
            onCloseTab = onCloseTab,
        )
    }

    if (showMenu) {
        BrowserOverflowMenu(
            onDismiss = { showMenu = false },
            onNewTab = { showMenu = false; onNewTab() },
            onCloseAll = { showMenu = false; onCloseAll() },
            onHistory = { showMenu = false; onShowHistory() },
            onDownloads = { showMenu = false; onShowDownloads() },
        )
    }
}

@Composable
private fun BrowserToolbar(
    committedUrl: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    tabCount: Int,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onShowTabs: () -> Unit,
    onShowMenu: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // Local typed state — decouples typing from the live tab.url so that
    // (a) every keystroke does NOT mutate tab state / trigger gecko side
    // effects, and (b) gecko's onLocationChange (which fires on every
    // redirect) can never overwrite the text the user is currently typing.
    var typed by remember { mutableStateOf(committedUrl) }
    var isFocused by remember { mutableStateOf(false) }
    // Sync the displayed text from the loaded URL only when the bar is NOT
    // focused — i.e. when navigation finishes / a tab is switched.
    androidx.compose.runtime.LaunchedEffect(committedUrl, isFocused) {
        if (!isFocused) typed = committedUrl
    }
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrowserIconButton(R.drawable.ic_arrow_back, canGoBack, onBack)
            BrowserIconButton(R.drawable.ic_arrow_forward, canGoForward, onForward)
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp)
                    .onFocusChanged { state ->
                        val nowFocused = state.isFocused
                        if (nowFocused && !isFocused) {
                            // Select-all on focus — standard browser UX.
                            typed = committedUrl
                        }
                        isFocused = nowFocused
                    },
                placeholder = {
                    Text(
                        text = stringResource(R.string.address_bar_hint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                singleLine = true,
                shape = CircleShape,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                    autoCorrect = false,
                ),
                keyboardActions = KeyboardActions(onGo = {
                    keyboard?.hide()
                    focusManager.clearFocus()
                    onNavigate(typed)
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
            BrowserIconButton(R.drawable.ic_refresh, true, onRefresh)
            TabsIconButton(tabCount = tabCount, onClick = onShowTabs)
            BrowserIconButton(R.drawable.ic_more, true, onShowMenu)
        }
    }
}

@Composable
private fun BrowserIconButton(iconRes: Int, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(42.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f),
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun TabsIconButton(tabCount: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(42.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_tabs),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = tabCount.coerceAtMost(99).toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun TabsDialog(
    tabs: List<BrowserTab>,
    selectedTabId: Int,
    onDismiss: () -> Unit,
    onNewTab: () -> Unit,
    onSelectTab: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Browser tabs") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tabs, key = { it.id }) { tab ->
                    TabRow(
                        tab = tab,
                        selected = tab.id == selectedTabId,
                        canClose = tabs.size > 1,
                        onSelect = { onSelectTab(tab.id) },
                        onClose = { onCloseTab(tab.id) },
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(android.R.string.cancel)) }
        },
        confirmButton = {
            Button(onClick = onNewTab) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(text = "New tab", modifier = Modifier.padding(start = 8.dp))
            }
        },
    )
}

@Composable
private fun TabRow(
    tab: BrowserTab,
    selected: Boolean,
    canClose: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.48f) else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tab.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = tab.url,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (canClose) {
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
@Composable
private fun BrowserOverflowMenu(
    onDismiss: () -> Unit,
    onNewTab: () -> Unit,
    onCloseAll: () -> Unit,
    onHistory: () -> Unit,
    onDownloads: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Menu") },
        text = {
            Column {
                MenuRow(R.drawable.ic_add, "New tab", onNewTab)
                MenuRow(R.drawable.ic_close, "Close all tabs", onCloseAll)
                MenuRow(R.drawable.ic_history, "History", onHistory)
                MenuRow(R.drawable.ic_download, "Downloads", onDownloads)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = "Close") }
        },
    )
}

@Composable
private fun MenuRow(iconRes: Int, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
