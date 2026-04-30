package com.aman.browser.ui.downloads

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
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
import com.aman.browser.data.browser.DownloadEntry
import com.aman.browser.ui.compose.AmanTheme
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class DownloadsFragment : Fragment() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        val repo = AmanApplication.downloadsRepository
        setContent {
            AmanTheme {
                val items by repo.recent(500).collectAsState(initial = emptyList())
                val scope = rememberCoroutineScope()
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Downloads") },
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
                        if (items.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "No downloads yet",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(items, key = { it.id }) { entry ->
                                    DownloadRow(
                                        entry = entry,
                                        onClick = {
                                            if (entry.status == "COMPLETE") {
                                                lifecycleScope.launch { repo.openFile(entry) }
                                            }
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

@Composable
private fun DownloadRow(
    entry: DownloadEntry,
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
                text = entry.filename,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                StatusPill(entry.status)
                val cat = entry.category
                if (!cat.isNullOrBlank() && cat != "NONE") {
                    CategoryBadge(cat)
                }
                Text(
                    text = formatSize(entry.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.status == "BLOCKED" && !entry.blockReason.isNullOrBlank()) {
                Text(
                    text = entry.blockReason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = entry.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = DateFormat.getDateTimeInstance().format(Date(entry.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = "Delete")
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    val (bg, fg, label) = when (status) {
        "COMPLETE" -> Triple(Color(0xFF1B5E20), Color.White, "Done")
        "BLOCKED"  -> Triple(Color(0xFFB71C1C), Color.White, "Blocked")
        "FAILED"   -> Triple(Color(0xFF6A1B9A), Color.White, "Failed")
        else       -> Triple(Color(0xFF455A64), Color.White, status.ifBlank { "Pending" })
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text = label, color = fg, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CategoryBadge(category: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = category,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format("%.1f MB", mb)
        kb >= 1.0 -> String.format("%.0f KB", kb)
        else -> "$bytes B"
    }
}
