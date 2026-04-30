package com.aman.browser.ui.onboarding

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.aman.browser.AmanApplication
import com.aman.browser.R
import com.aman.browser.quickaccess.WebApp
import com.aman.browser.ui.compose.AmanHeroCard
import com.aman.browser.ui.compose.AmanTheme
import kotlinx.coroutines.launch

class OnboardingFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { AmanTheme { OnboardingRoute() } }
    }

    @Composable
    private fun OnboardingRoute() {
        val scope = rememberCoroutineScope()
        var selectedIds by rememberSaveable { mutableStateOf(WebApp.CATALOG.map { it.id }) }

        fun finishWith(apps: List<WebApp>) {
            scope.launch {
                AmanApplication.quickAccess.setApps(apps)
                AmanApplication.quickAccess.setOnboarded(true)
                findNavController().navigate(
                    R.id.homeFragment,
                    null,
                    NavOptions.Builder()
                        .setPopUpTo(R.id.onboardingFragment, true)
                        .build(),
                )
            }
        }

        OnboardingScreen(
            selectedIds = selectedIds,
            onToggle = { id ->
                selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
            },
            onContinue = { finishWith(WebApp.CATALOG.filter { it.id in selectedIds }) },
            onSkip = { finishWith(emptyList()) },
        )
    }
}

@Composable
private fun OnboardingScreen(
    selectedIds: List<String>,
    onToggle: (String) -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    AmanHeroCard(
                        title = stringResource(R.string.onboarding_title),
                        subtitle = stringResource(R.string.onboarding_subtitle),
                    )
                }
                items(WebApp.CATALOG, key = { it.id }) { app ->
                    OnboardingAppCard(
                        app = app,
                        selected = app.id in selectedIds,
                        onToggle = { onToggle(app.id) },
                    )
                }
            }

            Surface(
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onSkip) {
                        Text(text = stringResource(R.string.onboarding_skip))
                    }
                    Button(
                        onClick = onContinue,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text(text = stringResource(R.string.onboarding_continue))
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingAppCard(
    app: WebApp,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    OutlinedCard(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f) else MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(app.iconRes),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(42.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = Uri.parse(app.url).host ?: app.url,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
        }
    }
}