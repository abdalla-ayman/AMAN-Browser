package com.aman.browser.ui.apps

import android.app.Activity
import android.app.ActivityOptions
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import com.aman.browser.AmanApplication
import com.aman.browser.R
import com.aman.browser.quickaccess.WebApp
import com.aman.browser.quickaccess.WebAppActivity
import com.aman.browser.ui.compose.AmanHeroCard
import com.aman.browser.ui.compose.AmanTheme
import com.aman.browser.ui.compose.BottomNavSpacer
import com.aman.browser.ui.compose.colorFromHex

class AppsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { AmanTheme { AppsRoute() } }
    }

    @Composable
    private fun AppsRoute() {
        val context = LocalContext.current
        val apps by AmanApplication.quickAccess.apps.collectAsStateWithLifecycle(initialValue = emptyList())

        AppsScreen(
            apps = apps,
            onBrowserClick = { findNavController().navigate(R.id.browserFragment) },
            onAppClick = { app ->
                val activity = context as? Activity
                val intent = WebAppActivity.launchIntent(context, app)
                if (activity != null) {
                    activity.startActivity(
                        intent,
                        ActivityOptions.makeCustomAnimation(activity, android.R.anim.fade_in, android.R.anim.fade_out).toBundle(),
                    )
                } else {
                    context.startActivity(intent)
                }
            },
        )
    }
}

@Composable
private fun AppsScreen(
    apps: List<WebApp>,
    onBrowserClick: () -> Unit,
    onAppClick: (WebApp) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AmanHeroCard(
                    title = stringResource(R.string.apps_title),
                    subtitle = stringResource(R.string.apps_subtitle),
                    iconRes = R.drawable.ic_aman_logo,
                )
            }
            item {
                AppRow(
                    title = stringResource(R.string.apps_browser_card_title),
                    subtitle = stringResource(R.string.apps_browser_card_subtitle),
                    iconRes = R.drawable.ic_browser,
                    accent = MaterialTheme.colorScheme.primary,
                    onClick = onBrowserClick,
                )
            }
            items(apps, key = { it.id }) { app ->
                AppRow(
                    title = app.name,
                    subtitle = app.url,
                    iconRes = app.iconRes,
                    letter = app.letter,
                    accent = colorFromHex(app.colorHex),
                    onClick = { onAppClick(app) },
                )
            }
            item { BottomNavSpacer() }
        }
    }
}

@Composable
private fun AppRow(
    title: String,
    subtitle: String,
    iconRes: Int,
    accent: Color,
    onClick: () -> Unit,
    letter: String = title.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                if (iconRes != 0) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = if (iconRes == R.drawable.ic_aman_logo) Color.Unspecified else accent,
                        modifier = Modifier.size(30.dp),
                    )
                } else {
                    Text(
                        text = letter,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    modifier = Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}