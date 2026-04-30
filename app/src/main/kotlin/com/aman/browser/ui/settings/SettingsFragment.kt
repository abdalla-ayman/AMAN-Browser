package com.aman.browser.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aman.browser.AmanApplication
import com.aman.browser.R
import com.aman.browser.ml.InferenceEngine
import com.aman.browser.ui.compose.AmanAccent
import com.aman.browser.ui.compose.AmanEyebrow
import com.aman.browser.ui.compose.AmanFeatureRow
import com.aman.browser.ui.compose.AmanPillRowSingle
import com.aman.browser.ui.compose.AmanSiteHero
import com.aman.browser.ui.compose.AmanSurfaceCard
import com.aman.browser.ui.compose.AmanTheme
import com.aman.browser.ui.compose.BottomNavSpacer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { AmanTheme { SettingsRoute() } }
    }

    @Composable
    private fun SettingsRoute() {
        val prefs = AmanApplication.prefs
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val blurIntensity by prefs.blurIntensity.collectAsStateWithLifecycle(initialValue = 1)
        val sensitivity by prefs.sensitivity.collectAsStateWithLifecycle(initialValue = 1)
        val tapToUnblur by prefs.tapToUnblur.collectAsStateWithLifecycle(initialValue = true)
        val blurOnLoad by prefs.blurOnLoad.collectAsStateWithLifecycle(initialValue = true)
        val useGpu by prefs.useGpu.collectAsStateWithLifecycle(initialValue = true)
        val useNnapi by prefs.useNnapi.collectAsStateWithLifecycle(initialValue = true)
        val genderFilter by prefs.genderFilter.collectAsStateWithLifecycle(initialValue = 0)

        SettingsScreen(
            blurIntensity = blurIntensity,
            sensitivity = sensitivity,
            tapToUnblur = tapToUnblur,
            blurOnLoad = blurOnLoad,
            useGpu = useGpu,
            genderFilter = genderFilter,
            onBlurIntensityChange = { value -> scope.launch { prefs.setBlurIntensity(value) } },
            onSensitivityChange = { value -> scope.launch { prefs.setSensitivity(value) } },
            onTapToUnblurChange = { value -> scope.launch { prefs.setTapToUnblur(value) } },
            onBlurOnLoadChange = { value -> scope.launch { prefs.setBlurOnLoad(value) } },
            onUseGpuChange = { value ->
                scope.launch {
                    prefs.setUseGpu(value)
                    // destroy() drains in-flight inferences with a short
                    // Thread.sleep poll; keep it off the Main dispatcher to
                    // avoid jank/ANR while the user is interacting with the
                    // settings screen.
                    withContext(Dispatchers.IO) {
                        InferenceEngine.get(context.applicationContext).let { engine ->
                            engine.destroy()
                            engine.initialize(value, useNnapi)
                        }
                    }
                }
            },
            onGenderFilterChange = { value -> scope.launch { prefs.setGenderFilter(value) } },
        )
    }
}

@Composable
private fun SettingsScreen(
    blurIntensity: Int,
    sensitivity: Int,
    tapToUnblur: Boolean,
    blurOnLoad: Boolean,
    useGpu: Boolean,
    genderFilter: Int,
    onBlurIntensityChange: (Int) -> Unit,
    onSensitivityChange: (Int) -> Unit,
    onTapToUnblurChange: (Boolean) -> Unit,
    onBlurOnLoadChange: (Boolean) -> Unit,
    onUseGpuChange: (Boolean) -> Unit,
    onGenderFilterChange: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.settings_hero_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                AmanSiteHero(
                    domain = "aman.local",
                    title = stringResource(R.string.settings_hero_title),
                    description = stringResource(R.string.settings_hero_subtitle),
                    shieldText = "Protection on",
                )
            }

            // ── Protection ────────────────────────────────────────────────
            item { AmanEyebrow("Protection") }

            item {
                LevelSliderCard(
                    title = stringResource(R.string.settings_blur_intensity),
                    value = blurIntensity,
                    labels = listOf(
                        stringResource(R.string.settings_blur_low),
                        stringResource(R.string.settings_blur_medium),
                        stringResource(R.string.settings_blur_high),
                    ),
                    onValueChange = onBlurIntensityChange,
                )
            }

            item {
                LevelSliderCard(
                    title = stringResource(R.string.settings_sensitivity),
                    value = sensitivity,
                    labels = listOf(
                        stringResource(R.string.settings_sensitivity_strict),
                        stringResource(R.string.settings_sensitivity_balanced),
                        stringResource(R.string.settings_sensitivity_relaxed),
                    ),
                    onValueChange = onSensitivityChange,
                )
            }

            item {
                AmanSurfaceCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = stringResource(R.string.settings_gender_filter),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        AmanPillRowSingle(
                            options = listOf(
                                stringResource(R.string.settings_gender_everyone),
                                stringResource(R.string.settings_gender_females),
                                stringResource(R.string.settings_gender_males),
                                stringResource(R.string.settings_gender_nofilter),
                            ),
                            selectedIndex = genderFilter.coerceIn(0, 3),
                            onSelect = onGenderFilterChange,
                        )
                    }
                }
            }

            // ── Experience ────────────────────────────────────────────────
            item { AmanEyebrow("Experience") }

            item {
                AmanFeatureRow(
                    iconRes = R.drawable.ic_shield,
                    accent = AmanAccent.Green,
                    name = stringResource(R.string.settings_blur_on_load),
                    subtitle = stringResource(R.string.settings_blur_on_load_hint),
                    trailing = {
                        Switch(checked = blurOnLoad, onCheckedChange = onBlurOnLoadChange)
                    },
                )
            }

            item {
                AmanFeatureRow(
                    iconRes = R.drawable.ic_lock,
                    accent = AmanAccent.Amber,
                    name = stringResource(R.string.settings_tap_to_unblur),
                    subtitle = "Tap a blurred element to peek",
                    trailing = {
                        Switch(checked = tapToUnblur, onCheckedChange = onTapToUnblurChange)
                    },
                )
            }

            item {
                AmanFeatureRow(
                    iconRes = R.drawable.ic_stats,
                    accent = AmanAccent.Blue,
                    name = stringResource(R.string.settings_gpu),
                    subtitle = stringResource(R.string.settings_gpu_hint),
                    trailing = {
                        Switch(checked = useGpu, onCheckedChange = onUseGpuChange)
                    },
                )
            }

            // ── Network ───────────────────────────────────────────────────
            item { AmanEyebrow("Network") }

            item {
                AmanFeatureRow(
                    iconRes = R.drawable.ic_vpn_lock,
                    accent = AmanAccent.Blue,
                    name = "Encrypted DNS",
                    subtitle = stringResource(R.string.settings_dns_note),
                    trailing = {},
                )
            }

            item { BottomNavSpacer() }
        }
    }
}

@Composable
private fun LevelSliderCard(
    title: String,
    value: Int,
    labels: List<String>,
    onValueChange: (Int) -> Unit,
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }

    AmanSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it.roundToInt().coerceIn(0, 2).toFloat() },
                onValueChangeFinished = { onValueChange(sliderValue.roundToInt().coerceIn(0, 2)) },
                valueRange = 0f..2f,
                steps = 1,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                labels.forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
