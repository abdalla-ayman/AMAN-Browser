package com.aman.browser.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aman.browser.AmanApplication
import com.aman.browser.R
import com.aman.browser.data.CategoryCount
import com.aman.browser.data.DomainCount
import com.aman.browser.ui.compose.AmanCategoryRow
import com.aman.browser.ui.compose.AmanEyebrow
import com.aman.browser.ui.compose.AmanLabeledRow
import com.aman.browser.ui.compose.AmanSiteHero
import com.aman.browser.ui.compose.AmanStatTriplet
import com.aman.browser.ui.compose.AmanSurfaceCard
import com.aman.browser.ui.compose.AmanTheme
import com.aman.browser.ui.compose.BottomNavSpacer
import java.text.DecimalFormat

class StatsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { AmanTheme { StatsRoute() } }
    }

    @Composable
    private fun StatsRoute() {
        val stats = AmanApplication.statsRepository
        val today by stats.todayCount().collectAsStateWithLifecycle(initialValue = 0L)
        val total by stats.totalCount().collectAsStateWithLifecycle(initialValue = 0L)
        val categories by stats.categoryCounts().collectAsStateWithLifecycle(initialValue = emptyList())
        val topDomains by stats.topDomains().collectAsStateWithLifecycle(initialValue = emptyList())
        val avgElapsed by stats.avgElapsedMs().collectAsStateWithLifecycle(initialValue = null)

        StatsScreen(
            today = today,
            total = total,
            categories = categories,
            topDomains = topDomains,
            avgSpeed = avgElapsed?.let { DecimalFormat("0.0").format(it) + " ms" } ?: "—",
        )
    }
}

@Composable
private fun StatsScreen(
    today: Long,
    total: Long,
    categories: List<CategoryCount>,
    topDomains: List<DomainCount>,
    avgSpeed: String,
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
                        text = stringResource(R.string.stats_title),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.stats_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                AmanSiteHero(
                    domain = "aman.local",
                    title = "$today blurs today",
                    description = "Aman is actively protecting your browsing on this device.",
                    shieldText = "Protected — $today today",
                )
            }

            item {
                AmanStatTriplet(
                    items = listOf(
                        total.toString() to stringResource(R.string.stats_total),
                        today.toString() to stringResource(R.string.stats_today),
                        avgSpeed to stringResource(R.string.stats_avg_speed),
                    ),
                )
            }

            item { AmanEyebrow(stringResource(R.string.stats_category_breakdown)) }

            item { CategoryBreakdownCard(categories = categories) }

            item { AmanEyebrow(stringResource(R.string.stats_top_domain)) }

            item { TopDomainsCard(topDomains = topDomains) }

            item { BottomNavSpacer() }
        }
    }
}

@Composable
private fun CategoryBreakdownCard(categories: List<CategoryCount>) {
    AmanSurfaceCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (categories.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "No blur events recorded yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            } else {
                val max = (categories.maxOfOrNull { it.cnt } ?: 1L).coerceAtLeast(1L)
                categories.forEachIndexed { index, item ->
                    AmanCategoryRow(
                        label = item.category,
                        count = item.cnt.toString(),
                        progress = item.cnt.toFloat() / max.toFloat(),
                        showDivider = index < categories.lastIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun TopDomainsCard(topDomains: List<DomainCount>) {
    AmanSurfaceCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (topDomains.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "No domains tracked yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            } else {
                topDomains.take(6).forEachIndexed { index, item ->
                    AmanLabeledRow(
                        label = item.domain,
                        value = item.cnt.toString(),
                        showDivider = index < topDomains.take(6).lastIndex,
                    )
                }
            }
        }
    }
}
