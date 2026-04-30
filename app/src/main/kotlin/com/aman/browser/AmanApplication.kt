package com.aman.browser

import android.app.Application
import android.util.Log
import com.aman.browser.browser.BrowserTabManager
import com.aman.browser.browser.WebExtensionManager
import com.aman.browser.data.PreferencesManager
import com.aman.browser.data.StatsRepository
import com.aman.browser.downloads.DownloadsRepository
import com.aman.browser.history.HistoryRepository
import com.aman.browser.ml.InferenceEngine
import com.aman.browser.network.VpnDetector
import com.aman.browser.quickaccess.QuickAccessRepository
import com.aman.browser.session.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class AmanApplication : Application() {

    companion object {
        private const val TAG = "AmanApp"

        // DNS-over-HTTPS: CleanBrowsing Family Filter — the strictest option:
        // blocks all adult content AND enforces safe search on Google/Bing/YouTube/DDG
        // at the DNS level before the request even leaves the device.
        // Fallback: Cloudflare 1.1.1.3 (family filter) if CleanBrowsing is unreachable.
        private const val DOH_URI        = "https://doh.cleanbrowsing.org/doh/family-filter/"
        private const val DOH_URI_BACKUP = "https://family.cloudflare-dns.com/dns-query"
        private const val TRR_MODE_DOH_ONLY = 3

        lateinit var geckoRuntime:      GeckoRuntime       private set
        lateinit var prefs:             PreferencesManager  private set
        lateinit var statsRepository:   StatsRepository    private set
        lateinit var vpnDetector:       VpnDetector        private set
        lateinit var quickAccess:       QuickAccessRepository private set
        lateinit var tabManager:        BrowserTabManager  private set
        lateinit var historyRepository: HistoryRepository  private set
        lateinit var downloadsRepository: DownloadsRepository private set
        lateinit var sessionRepository: SessionRepository  private set

        private var _webExtMgr: WebExtensionManager? = null
        /**
         * Shared, application-scoped WebExtensionManager. Created once after
         * GeckoRuntime initialises. Returns null on the brief window before
         * the runtime is up (used by [BrowserTabManager.wireSessionIfReady]).
         */
        fun webExtensionManagerOrNull(): WebExtensionManager? = _webExtMgr
        fun webExtensionManager(): WebExtensionManager =
            _webExtMgr ?: error("WebExtensionManager not initialized yet")

        private var cachedIsMainProcess: Boolean? = null
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // GeckoView spawns child processes (:tab, :gpu, etc.) that also call
        // Application.onCreate(). We must only initialise in the main process,
        // otherwise multiple GeckoRuntime instances are created and GeckoView
        // throws BindException / Cannot connect to process.
        if (!isMainProcess()) return

        prefs               = PreferencesManager(this)
        statsRepository     = StatsRepository(this)
        quickAccess         = QuickAccessRepository(this)
        vpnDetector         = VpnDetector(this)
        historyRepository   = HistoryRepository(this)
        downloadsRepository = DownloadsRepository(this)
        sessionRepository   = SessionRepository(this)
        tabManager          = BrowserTabManager()

        initGeckoRuntime()
        initWebExtensionManager()
        initMlEngine()
        vpnDetector.start()

        // Prune old stats / history every cold start
        appScope.launch { statsRepository.pruneOldEvents() }
        appScope.launch { historyRepository.pruneOld() }
    }

    private fun isMainProcess(): Boolean {
        cachedIsMainProcess?.let { return it }
        val result = try {
            val bytes = java.io.File("/proc/self/cmdline").readBytes()
            val end = bytes.indexOfFirst { it == 0.toByte() }.takeIf { it >= 0 } ?: bytes.size
            String(bytes, 0, end).trim() == packageName
        } catch (_: Exception) {
            true // assume main process if we can't determine
        }
        cachedIsMainProcess = result
        return result
    }

    private fun initWebExtensionManager() {
        val mgr = WebExtensionManager(
            context = this,
            runtime = geckoRuntime,
            prefs   = prefs,
        )
        _webExtMgr = mgr
        mgr.install { _ ->
            // Once the extension is installed, wire it into every tab that
            // already exists (e.g. tabs created before install completed).
            tabManager.wireAllSessions { session -> mgr.wireSessionIfReady(session) }
        }
    }

    // ── GeckoView runtime ─────────────────────────────────────────────────────
    private fun initGeckoRuntime() {
        val settings = GeckoRuntimeSettings.Builder()
            .aboutConfigEnabled(false)           // hide about:config
            .remoteDebuggingEnabled(false)
            // Enable saved-password autofill so that Google / GitHub / etc.
            // can stay signed-in across cold starts. GeckoView keeps the
            // password store inside the runtime profile dir, so it persists
            // until the user explicitly clears app data.
            .loginAutofillEnabled(true)
            .build()

        // Force DNS-over-HTTPS (TRR mode 3 = DoH only, never fallback to system DNS)
        // network.trr.mode is an integer pref; network.trr.allow-rfc1918 is boolean
        settings.extras.putInt("network.trr.mode", TRR_MODE_DOH_ONLY)
        settings.extras.putString("network.trr.uri", DOH_URI)
        settings.extras.putString("network.trr.backup-uri", DOH_URI_BACKUP)
        settings.extras.putBoolean("network.trr.allow-rfc1918", false)
        // Default DoH timeouts in mode-3 are 30s — every blocked-DoH page hangs
        // 30s before failing. Tighten so users get a fast retry on flaky links.
        settings.extras.putInt("network.trr.request_timeout_ms", 1500)
        settings.extras.putInt("network.trr.request_timeout_mode_trronly_ms", 3000)

        // ── Page-load / responsiveness perf ─────────────────────────────────
        // 4 content processes ⇒ tabs render in parallel and one heavy tab can't
        // freeze every other tab on the same renderer.
        settings.extras.putInt("dom.ipc.processCount", 4)
        // Prefetch DNS + speculatively open TCP/TLS to improve cold-tap latency.
        settings.extras.putBoolean("network.dns.disablePrefetch", false)
        settings.extras.putBoolean("network.predictor.enabled", true)
        settings.extras.putInt("network.http.speculative-parallel-limit", 10)
        // HTTP/3 (QUIC) — measurable wins on mobile networks with high RTT.
        settings.extras.putBoolean("network.http.http3.enable", true)
        // Don't pause background tabs aggressively (keeps tab-switch instant
        // for users who hop between 2-3 tabs while reading).
        settings.extras.putInt("dom.min_background_timeout_value", 1000)

        // ── Login persistence ──────────────────────────────────────────────
        // Many SSO providers (Google, Microsoft, Apple) bounce the OAuth flow
        // through third-party iframes/cookies. Default Gecko settings are
        // strict-mode, which silently breaks "Sign in with Google". Loosen
        // *only* for the cookie path; tracking-protection at the page level
        // still catches advertisers.
        settings.extras.putInt("network.cookie.cookieBehavior", 0)
        settings.extras.putBoolean("signon.rememberSignons", true)
        settings.extras.putBoolean("signon.autofillForms", true)
        settings.extras.putBoolean("signon.formlessCapture.enabled", true)
        // Persist the disk cache + profile across runs (this is also
        // GeckoView's default, made explicit here for clarity).
        settings.extras.putBoolean("browser.cache.disk.enable", true)
        settings.extras.putBoolean("browser.sessionstore.resume_from_crash", true)

        geckoRuntime = GeckoRuntime.create(this, settings)
        Log.i(TAG, "GeckoRuntime created with DoH: $DOH_URI")
    }

    // ── ML engine ─────────────────────────────────────────────────────────────
    private fun initMlEngine() {
        appScope.launch {
            val useGpu   = prefs.useGpu.first()
            val useNnapi = prefs.useNnapi.first()
            InferenceEngine.get(this@AmanApplication).initialize(useGpu, useNnapi)
        }
    }

    override fun onTerminate() {
        if (isMainProcess()) {
            vpnDetector.stop()
            _webExtMgr?.destroy()
            _webExtMgr = null
            InferenceEngine.get(this).destroy()
            geckoRuntime.shutdown()
        }
        super.onTerminate()
    }
}
