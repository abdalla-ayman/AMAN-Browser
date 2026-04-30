package com.aman.browser.quickaccess

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.aman.browser.AmanApplication
import com.aman.browser.R
import com.aman.browser.databinding.ActivityWebappBinding
import com.aman.browser.ui.compose.AmanTheme
import com.aman.browser.ui.compose.VpnBlockerOverlay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings

/**
 * WebAppActivity
 *
 * Hosts a single [WebApp] in a chromeless GeckoView. Designed to feel like a
 * native app rather than a browser tab:
 *
 *   • No address bar, no back/forward/refresh buttons exposed by default
 *   • Status bar tinted with the app's accent colour
 *   • Each WebApp gets its own GeckoSession contextId, so cookies and login
 *     tokens are partitioned per-app and survive across launches
 *   • Activity uses documentLaunchMode=intoExisting + a per-app taskAffinity so
 *     each Quick Access app shows up as its own card in Recents and can be
 *     pinned to the launcher
 *   • The Aman blur WebExtension is wired into the session, so blurring still
 *     works exactly the same way it does in the regular browser
 *   • Long-pressing the small overflow handle opens an in-app menu (refresh,
 *     pin to launcher, open in regular browser, sign out)
 */
class WebAppActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APP_ID = "app_id"

        fun launchIntent(context: Context, app: WebApp): Intent {
            return Intent(context, WebAppActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                // Stable URI so each app is its own task and is reused across
                // launches (matches documentLaunchMode=intoExisting in manifest).
                data = android.net.Uri.parse("aman-app://${app.id}")
                putExtra(EXTRA_APP_ID, app.id)
                flags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }

    private lateinit var binding: ActivityWebappBinding
    private lateinit var vpnBlockerView: ComposeView
    private var session: GeckoSession? = null
    private var app: WebApp? = null
    private var canGoBack = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appId = intent.getStringExtra(EXTRA_APP_ID)
            ?: intent.data?.host
        if (appId == null) {
            finish()
            return
        }

        binding = ActivityWebappBinding.inflate(layoutInflater)
        setContentView(binding.root)
        addVpnBlockerOverlay()

        // Remove default status bar / navigation bar tint — we'll paint them
        // with the WebApp's accent colour for the "native app" feel.
        WindowCompat.setDecorFitsSystemWindows(window, true)

        binding.progressBar.visibility = View.GONE
        binding.menuHandle.setOnClickListener { showMenu() }
        binding.menuHandle.setOnLongClickListener { showMenu(); true }
        setupBottomNav()
        setupBackHandling()
        observeVpn()

        lifecycleScope.launch {
            val webApp = AmanApplication.quickAccess.findById(appId)
                ?: WebApp.CATALOG.firstOrNull { it.id == appId }
            if (webApp == null) {
                Toast.makeText(this@WebAppActivity, "App not found", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            app = webApp
            applyTheme(webApp)
            val storedUrl = AmanApplication.quickAccess.lastUrlFor(webApp.id)
            val legacyUrl = if (storedUrl == null) legacyLastUrlFor(webApp) else null
            if (legacyUrl != null) {
                AmanApplication.quickAccess.saveLastUrl(webApp.id, legacyUrl)
                clearLegacyLastUrl(webApp)
            }
            startSession(webApp, storedUrl ?: legacyUrl ?: webApp.url)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun addVpnBlockerOverlay() {
        vpnBlockerView = ComposeView(this).apply {
            visibility = View.GONE
            elevation = 24f * resources.displayMetrics.density
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { AmanTheme { VpnBlockerOverlay() } }
        }
        addContentView(
            vpnBlockerView,
            android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (canGoBack) {
                    session?.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun observeVpn() {
        AmanApplication.vpnDetector.vpnActive
            .onEach { active ->
                vpnBlockerView.visibility = if (active) View.VISIBLE else View.GONE
            }
            .launchIn(lifecycleScope)
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            val intent = Intent(this, com.aman.browser.MainActivity::class.java).apply {
                putExtra("nav_to_tab", item.itemId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent, fadeTransition().toBundle())
            finish()
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun applyTheme(webApp: WebApp) {
        title = webApp.name
        val color = try { Color.parseColor(webApp.colorHex) } catch (_: Exception) { Color.BLACK }
        window.statusBarColor = color
        // Update the recent-apps card so Android shows the correct title/icon
        val taskDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityManager.TaskDescription.Builder()
                .setLabel(webApp.name)
                .setPrimaryColor(color)
                .build()
        } else {
            @Suppress("DEPRECATION")
            ActivityManager.TaskDescription(webApp.name, null as Bitmap?, color)
        }
        setTaskDescription(taskDesc)
    }

    private fun startSession(webApp: WebApp, startUrl: String) {
        val settings = GeckoSessionSettings.Builder()
            .useTrackingProtection(true)
            // contextId partitions cookies/IndexedDB/localStorage per app, so
            // the YouTube login does not leak into Instagram and persists
            // across launches of *this* WebApp.
            .contextId("webapp:${webApp.id}")
            .build()

        val gecko = GeckoSession(settings).also { s ->
            s.open(AmanApplication.geckoRuntime)
            s.navigationDelegate = navDelegate
            s.progressDelegate   = progressDelegate
            s.contentDelegate    = contentDelegate
            binding.geckoView.setSession(s)
            s.loadUri(startUrl)
        }
        session = gecko

        // Wire blur extension to this session via the application-scoped
        // WebExtensionManager (no per-activity install — the extension is
        // installed once in AmanApplication.onCreate).
        AmanApplication.webExtensionManagerOrNull()?.wireSessionIfReady(gecko)
    }

    // ── Delegates ─────────────────────────────────────────────────────────────
    private val navDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
        ) {
            val currentApp = app ?: return
            if (!url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
                lifecycleScope.launch { AmanApplication.quickAccess.saveLastUrl(currentApp.id, url) }
            }
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            this@WebAppActivity.canGoBack = canGoBack
        }
    }
    private val progressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            binding.progressBar.visibility = View.VISIBLE
        }
        override fun onPageStop(session: GeckoSession, success: Boolean) {
            binding.progressBar.visibility = View.GONE
        }
        override fun onProgressChange(session: GeckoSession, progress: Int) {
            binding.progressBar.progress = progress
        }
    }
    private val contentDelegate = object : GeckoSession.ContentDelegate {}

    // ── Overflow menu ─────────────────────────────────────────────────────────
    private fun showMenu() {
        val webApp = app ?: return
        val items = arrayOf(
            getString(R.string.webapp_menu_refresh),
            getString(R.string.webapp_menu_home),
            getString(R.string.webapp_menu_pin),
            getString(R.string.webapp_menu_open_browser),
            getString(R.string.webapp_menu_clear_login),
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(webApp.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> session?.reload()
                    1 -> session?.loadUri(webApp.url)
                    2 -> ShortcutPinner.pin(this, webApp)
                    3 -> openInRegularBrowser(webApp.url)
                    4 -> clearLoginAndReload(webApp)
                }
            }
            .show()
    }

    private fun openInRegularBrowser(url: String) {
        val intent = Intent(this, com.aman.browser.MainActivity::class.java).apply {
            putExtra("nav_to_browser_url", url)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent, fadeTransition().toBundle())
        finish()
    }

    private fun fadeTransition(): ActivityOptions =
        ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out)

    private fun clearLoginAndReload(webApp: WebApp) {
        // Closing+reopening the contextId-scoped session does not by itself
        // clear cookies (they are persisted by Gecko). The reliable way to
        // sign out is to use the runtime's storage controller scoped to the
        // contextId.
        try {
            AmanApplication.geckoRuntime
                .storageController
                .clearDataForSessionContext("webapp:${webApp.id}")
            Toast.makeText(this, R.string.webapp_logged_out, Toast.LENGTH_SHORT).show()
            session?.loadUri(webApp.url)
        } catch (_: Throwable) {
            Toast.makeText(this, "Could not clear session data", Toast.LENGTH_SHORT).show()
        }
    }

    private fun legacyLastUrlFor(webApp: WebApp): String? =
        getSharedPreferences("aman_webapp_state", MODE_PRIVATE)
            .getString("last_url_${webApp.id}", null)

    private fun clearLegacyLastUrl(webApp: WebApp) {
        getSharedPreferences("aman_webapp_state", MODE_PRIVATE)
            .edit()
            .remove("last_url_${webApp.id}")
            .apply()
    }

    override fun onDestroy() {
        binding.geckoView.releaseSession()
        session?.close()
        session = null
        super.onDestroy()
    }
}
