package com.aman.browser.ui.home

import android.app.Activity
import android.app.ActivityOptions
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.aman.browser.AmanApplication
import com.aman.browser.R
import com.aman.browser.quickaccess.ShortcutPinner
import com.aman.browser.quickaccess.WebApp
import com.aman.browser.quickaccess.WebAppActivity
import com.aman.browser.ui.compose.AmanHeroCard
import com.aman.browser.ui.compose.AmanIconBubble
import com.aman.browser.ui.compose.AmanLogoLockup
import com.aman.browser.ui.compose.AmanOutlinedPanel
import com.aman.browser.ui.compose.AmanSectionHeader
import com.aman.browser.ui.compose.AmanTheme
import com.aman.browser.ui.compose.BottomNavSpacer
import com.aman.browser.ui.compose.colorFromHex
import com.aman.browser.util.UrlUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { AmanTheme { HomeRoute() } }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            AmanApplication.quickAccess.isOnboarded.collectLatest { done ->
                if (!done && isAdded) {
                    findNavController().navigate(R.id.onboardingFragment)
                    return@collectLatest
                }
            }
        }
    }

    private fun openInBrowser(input: String) {
        val url = UrlUtils.normalizeBrowserInput(input) ?: return
        val bundle = Bundle().apply { putString("url", url) }
        findNavController().navigate(R.id.action_home_to_browser, bundle)
    }

    @Composable
    private fun HomeRoute() {
        val context = LocalContext.current
        val apps by AmanApplication.quickAccess.apps.collectAsStateWithLifecycle(initialValue = emptyList())
        val scope = rememberCoroutineScope()
        var editMode by rememberSaveable { mutableStateOf(false) }
        var showAddDialog by remember { mutableStateOf(false) }
        var showCustomDialog by remember { mutableStateOf(false) }
        var actionTarget by remember { mutableStateOf<WebApp?>(null) }
        var removeTarget by remember { mutableStateOf<WebApp?>(null) }

        HomeScreen(
            apps = apps,
            editMode = editMode,
            onEditToggle = { editMode = !editMode },
            onSearch = ::openInBrowser,
            onAppClick = { app ->
                if (editMode) {
                    removeTarget = app
                } else {
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
                }
            },
            onAppLongClick = { actionTarget = it },
            onAddClick = { showAddDialog = true },
        )

        if (showAddDialog) {
            AddQuickAccessDialog(
                apps = apps,
                onDismiss = { showAddDialog = false },
                onCustom = {
                    showAddDialog = false
                    showCustomDialog = true
                },
                onAddDefault = { app ->
                    showAddDialog = false
                    scope.launch { AmanApplication.quickAccess.add(app) }
                },
            )
        }

        if (showCustomDialog) {
            CustomQuickAccessDialog(
                onDismiss = { showCustomDialog = false },
                onAdd = { name, rawUrl ->
                    val url = UrlUtils.normalizeWebsiteUrl(rawUrl) ?: return@CustomQuickAccessDialog
                    val id = UrlUtils.customWebAppId(name)
                    showCustomDialog = false
                    scope.launch {
                        AmanApplication.quickAccess.add(WebApp(id = id, name = name, url = url, iconRes = 0))
                    }
                },
            )
        }

        actionTarget?.let { app ->
            QuickAccessActionsDialog(
                app = app,
                onDismiss = { actionTarget = null },
                onPin = {
                    actionTarget = null
                    ShortcutPinner.pin(context, app)
                },
                onRemove = {
                    actionTarget = null
                    removeTarget = app
                },
                onOpenBrowser = {
                    actionTarget = null
                    openInBrowser(app.url)
                },
            )
        }

        removeTarget?.let { app ->
            ConfirmRemoveDialog(
                app = app,
                onDismiss = { removeTarget = null },
                onConfirm = {
                    removeTarget = null
                    scope.launch { AmanApplication.quickAccess.remove(app.id) }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeScreen(
    apps: List<WebApp>,
    editMode: Boolean,
    onEditToggle: () -> Unit,
    onSearch: (String) -> Unit,
    onAppClick: (WebApp) -> Unit,
    onAppLongClick: (WebApp) -> Unit,
    onAddClick: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AmanLogoLockup()
                    Text(
                        text = stringResource(R.string.home_welcome_title),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.home_welcome_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                AmanHeroCard(
                    title = stringResource(R.string.protection_active_title),
                    subtitle = stringResource(R.string.protection_active_subtitle),
                    overline = stringResource(R.string.app_name_en),
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                SearchPanel(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = {
                        keyboard?.hide()
                        onSearch(query)
                    },
                )
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                AmanSectionHeader(
                    title = stringResource(R.string.quick_access_title),
                    modifier = Modifier.padding(top = 8.dp),
                    action = {
                        TextButton(onClick = onEditToggle) {
                            Text(text = stringResource(if (editMode) R.string.quick_access_done else R.string.quick_access_edit))
                        }
                    },
                )
            }

            if (apps.isEmpty() && !editMode) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyQuickAccessPanel()
                }
            }

            items(apps, key = { it.id }) { app ->
                QuickAccessTile(
                    app = app,
                    editMode = editMode,
                    modifier = Modifier.combinedClickable(
                        onClick = { onAppClick(app) },
                        onLongClick = { onAppLongClick(app) },
                    ),
                )
            }

            if (editMode) {
                item { AddQuickAccessTile(onClick = onAddClick) }
            }

            item(span = { GridItemSpan(maxLineSpan) }) { BottomNavSpacer() }
        }
    }
}

@Composable
private fun SearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, end = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(text = stringResource(R.string.search_hint)) },
                singleLine = true,
                shape = CircleShape,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            )
            Button(
                onClick = onSearch,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                shape = CircleShape,
            ) {
                Text(text = stringResource(R.string.btn_go))
            }
        }
    }
}

@Composable
private fun EmptyQuickAccessPanel() {
    AmanOutlinedPanel {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AmanIconBubble(iconRes = R.drawable.ic_browser)
            Text(
                text = stringResource(R.string.quick_access_empty),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuickAccessTile(
    app: WebApp,
    editMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = colorFromHex(app.colorHex)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.82f)
            .alpha(if (editMode) 0.76f else 1f),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (app.iconRes == 0) 0.16f else 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                if (app.iconRes != 0) {
                    Icon(
                        painter = painterResource(app.iconRes),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(34.dp),
                    )
                } else {
                    Text(
                        text = app.letter,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                }
            }
            Text(
                text = app.name,
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AddQuickAccessTile(onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.82f),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Text(
                text = stringResource(R.string.quick_access_add),
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AddQuickAccessDialog(
    apps: List<WebApp>,
    onDismiss: () -> Unit,
    onCustom: () -> Unit,
    onAddDefault: (WebApp) -> Unit,
) {
    val installedIds = apps.map { it.id }.toSet()
    val missingDefaults = WebApp.CATALOG.filter { it.id !in installedIds }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.quick_access_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCustom, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.quick_access_add_custom))
                }
                missingDefaults.forEach { app ->
                    TextButton(onClick = { onAddDefault(app) }, modifier = Modifier.fillMaxWidth()) {
                        Text(text = stringResource(R.string.quick_access_add_default, app.name))
                    }
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun CustomQuickAccessDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    val canAdd = name.isNotBlank() && url.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.quick_access_add_custom)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(text = stringResource(R.string.add_custom_name_hint)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(text = stringResource(R.string.add_custom_url_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(android.R.string.cancel)) }
        },
        confirmButton = {
            Button(
                enabled = canAdd,
                onClick = { onAdd(name.trim(), url.trim()) },
            ) {
                Text(text = stringResource(R.string.btn_add))
            }
        },
    )
}

@Composable
private fun QuickAccessActionsDialog(
    app: WebApp,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onRemove: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = app.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onPin, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.quick_access_pin))
                }
                TextButton(onClick = onOpenBrowser, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.webapp_menu_open_browser))
                }
                OutlinedButton(
                    onClick = onRemove,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(text = stringResource(R.string.quick_access_remove))
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ConfirmRemoveDialog(
    app: WebApp,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.quick_access_remove_title, app.name)) },
        text = { Text(text = stringResource(R.string.quick_access_remove_message)) },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(android.R.string.cancel)) }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(text = stringResource(R.string.quick_access_remove))
            }
        },
    )
}