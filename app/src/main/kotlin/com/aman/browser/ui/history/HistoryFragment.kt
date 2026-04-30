package com.aman.browser.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.aman.browser.AmanApplication
import com.aman.browser.R
import com.aman.browser.data.browser.HistoryEntry
import com.aman.browser.ui.compose.AmanTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class HistoryFragment : Fragment() {

    @OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        val repo = AmanApplication.historyRepository
        setContent {
            AmanTheme {
                var query by remember { mutableStateOf("") }
                val queryFlow = remember { MutableStateFlow("") }
                queryFlow.value = query
                val source: Flow<List<HistoryEntry>> = remember {
                    queryFlow.flatMapLatest { q ->
                        if (q.isBlank()) repo.recent(500) else repo.search(q)
                    }
                }
                val items by source.collectAsState(initial = emptyList())
                val scope = rememberCoroutineScope()

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("History") },
                            navigationIcon = {
                                IconButton(onClick = { findNavController().navigateUp() }) {
                                    Icon(painterResource(R.drawable.ic_close), null)
                                }
                            },
                            actions = {
                                TextButton(onClick = {
                                    scope.launch { repo.clear() }
                                }) { Text("Clear all") }
                            },
                        )
                    },
                ) { padding ->
                    Surface(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("Search history") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            )
                            if (items.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "No history yet",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(items, key = { it.id }) { entry ->
                                        HistoryRow(
                                            entry = entry,
                                            onClick = {
                                                openInBrowser(entry.url)
                                            },
                                            onDelete = {
                                                lifecycleScope.launch { repo.delete(entry.id) }
                                            },
                                        )
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openInBrowser(url: String) {
        val args = Bundle().apply { putString("url", url) }
        findNavController().navigate(R.id.browserFragment, args)
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title.ifBlank { entry.url },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.url,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = DateFormat.getDateTimeInstance().format(Date(entry.visitedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = "Delete")
        }
    }
}
