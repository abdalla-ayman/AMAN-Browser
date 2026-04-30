package com.aman.browser

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.aman.browser.ui.compose.AmanTheme
import com.aman.browser.ui.compose.VpnBlockerOverlay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavController
    private lateinit var bottomNavView: ComposeView
    private lateinit var vpnBlockerView: ComposeView

    private var currentDestinationId by mutableIntStateOf(R.id.homeFragment)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createChrome()
        setupNavigation()
        setupBackHandling()
        observeVpn()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun createChrome() {
        val root = FrameLayout(this)
        val navHostContainer = androidx.fragment.app.FragmentContainerView(this).apply {
            id = R.id.nav_host_fragment
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        bottomNavView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                AmanTheme {
                    AmanBottomNavigation(
                        currentDestinationId = currentDestinationId,
                        onDestinationSelected = ::navigateBottom,
                    )
                }
            }
        }

        vpnBlockerView = ComposeView(this).apply {
            visibility = View.GONE
            elevation = 24f * resources.displayMetrics.density
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { AmanTheme { VpnBlockerOverlay() } }
        }

        root.addView(navHostContainer)
        root.addView(
            bottomNavView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        root.addView(
            vpnBlockerView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)
    }

    private fun setupNavigation() {
        var navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        if (navHostFragment == null) {
            navHostFragment = NavHostFragment.create(R.navigation.nav_graph)
            supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment, navHostFragment)
                .setPrimaryNavigationFragment(navHostFragment)
                .commitNow()
        }

        navController = navHostFragment.navController
        currentDestinationId = navController.currentDestination?.id ?: R.id.homeFragment
        bottomNavView.visibility = if (currentDestinationId == R.id.onboardingFragment) View.GONE else View.VISIBLE

        navController.addOnDestinationChangedListener { _, destination, _ ->
            currentDestinationId = destination.id
            bottomNavView.visibility = if (destination.id == R.id.onboardingFragment) View.GONE else View.VISIBLE
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val navHostFragment = supportFragmentManager
                    .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                val currentFragment = navHostFragment.childFragmentManager.primaryNavigationFragment
                if (currentFragment is BackPressHandler && currentFragment.onBackPressed()) {
                    return
                }
                if (!navController.popBackStack()) {
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

    private fun handleIntent(intent: Intent) {
        intent.getStringExtra("nav_to_browser_url")?.let { url ->
            val bundle = Bundle().apply { putString("url", url) }
            navController.navigate(R.id.browserFragment, bundle, defaultNavOptions())
        }

        val navTabId = intent.getIntExtra("nav_to_tab", 0)
        if (navTabId != 0) {
            navigateBottom(navTabId)
        }
    }

    private fun navigateBottom(@IdRes destinationId: Int) {
        if (!::navController.isInitialized) return
        if (navController.currentDestination?.id == destinationId) return

        navController.navigate(destinationId, null, defaultNavOptions())
    }

    private fun defaultNavOptions(): NavOptions {
        return NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setRestoreState(true)
            .setEnterAnim(android.R.anim.fade_in)
            .setExitAnim(android.R.anim.fade_out)
            .setPopEnterAnim(android.R.anim.fade_in)
            .setPopExitAnim(android.R.anim.fade_out)
            .setPopUpTo(navController.graph.startDestinationId, false, true)
            .build()
    }
}

private data class AmanNavItem(
    @IdRes val destinationId: Int,
    @DrawableRes val iconRes: Int,
    val labelRes: Int,
)

private val bottomNavItems = listOf(
    AmanNavItem(R.id.homeFragment, R.drawable.ic_home, R.string.nav_home),
    AmanNavItem(R.id.browserFragment, R.drawable.ic_browser, R.string.nav_browser),
    AmanNavItem(R.id.appsFragment, R.drawable.ic_apps, R.string.nav_apps),
    AmanNavItem(R.id.settingsFragment, R.drawable.ic_settings, R.string.nav_settings),
)

@Composable
private fun AmanBottomNavigation(
    currentDestinationId: Int,
    onDestinationSelected: (Int) -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        bottomNavItems.forEach { item ->
            NavigationBarItem(
                selected = currentDestinationId == item.destinationId,
                onClick = { onDestinationSelected(item.destinationId) },
                icon = {
                    Icon(
                        painter = painterResource(item.iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                },
                label = { Text(text = stringResource(item.labelRes)) },
                alwaysShowLabel = true,
            )
        }
    }
}

interface BackPressHandler {
    fun onBackPressed(): Boolean
}
