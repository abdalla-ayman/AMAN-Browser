package com.aman.browser.ui.compose

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aman.browser.R

// ────────────────────────────────────────────────────────────────────────────
//  Accent tint — green / blue / amber tinted icon bubbles for feature rows.
// ────────────────────────────────────────────────────────────────────────────

enum class AmanAccent { Green, Blue, Amber }

@Composable
fun AmanAccent.background(): Color {
    val a = LocalAmanAccents.current
    return when (this) {
        AmanAccent.Green -> a.greenBg
        AmanAccent.Blue -> a.blueBg
        AmanAccent.Amber -> a.amberBg
    }
}

@Composable
fun AmanAccent.border(): Color {
    val a = LocalAmanAccents.current
    return when (this) {
        AmanAccent.Green -> a.greenBorder
        AmanAccent.Blue -> a.blueBorder
        AmanAccent.Amber -> a.amberBorder
    }
}

@Composable
fun AmanAccent.icon(): Color {
    val a = LocalAmanAccents.current
    return when (this) {
        AmanAccent.Green -> a.greenIcon
        AmanAccent.Blue -> a.blueIcon
        AmanAccent.Amber -> a.amberIcon
    }
}

// ────────────────────────────────────────────────────────────────────────────
//  Icon bubble (8dp rounded square, tinted background, tinted icon).
// ────────────────────────────────────────────────────────────────────────────

@Composable
fun AmanAccentBubble(
    @DrawableRes iconRes: Int,
    accent: AmanAccent,
    modifier: Modifier = Modifier,
    sizeDp: Int = 36,
    iconDp: Int = 18,
) {
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(accent.background())
            .border(1.dp, accent.border(), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (iconRes == R.drawable.ic_aman_logo) Color.Unspecified else accent.icon(),
            modifier = Modifier.size(iconDp.dp),
        )
    }
}

@Composable
fun AmanIconBubble(
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    sizeDp: Int = 44,
    iconDp: Int = 22,
) {
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (iconRes == R.drawable.ic_aman_logo) Color.Unspecified else contentColor,
            modifier = Modifier.size(iconDp.dp),
        )
    }
}

// ────────────────────────────────────────────────────────────────────────────
//  Site / brand hero — gradient mint card with domain row, title, desc, shield pill.
// ────────────────────────────────────────────────────────────────────────────

@Composable
fun AmanSiteHero(
    domain: String,
    title: String,
    description: String,
    shieldText: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, Color(0xFFB8E8CF)),
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFFE8F8F0), Color(0xFFD1F0E3)),
                    )
                )
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(AmanPalette.G500),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = domain,
                        style = MaterialTheme.typography.labelLarge,
                        color = AmanPalette.G600,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = AmanPalette.G800,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AmanPalette.Text2,
                )
                Spacer(Modifier.height(12.dp))
                AmanShieldPill(text = shieldText)
            }
        }
    }
}

@Composable
fun AmanShieldPill(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = AmanPalette.G500,
    contentColor: Color = Color.White,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_shield),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.2.sp),
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
// ────────────────────────────────────────────────────────────────────────────
//  Stat triplet — three small bordered cards with green numerals.
// ────────────────────────────────────────────────────────────────────────────

@Composable
fun AmanStatTriplet(
    items: List<Pair<String, String>>, // value to label
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (value, label) ->
            AmanStatCell(
                value = value,
                label = label,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun AmanStatCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
//  Feature row — white card, tinted bubble, name + sub, trailing slot.
// ────────────────────────────────────────────────────────────────────────────

@Composable
fun AmanFeatureRow(
    @DrawableRes iconRes: Int,
    accent: AmanAccent,
    name: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AmanAccentBubble(iconRes = iconRes, accent = accent)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailing?.invoke(this)
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
//  Eyebrow — uppercase tracked label used above section groups.
// ────────────────────────────────────────────────────────────────────────────

@Composable
fun AmanEyebrow(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ────────────────────────────────────────────────────────────────────────────
//  Pill row — single-select chips (used for People-filter style choices).
// ────────────────────────────────────────────────────────────────────────────

@Composable
fun AmanPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val contentColor =
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
            fontWeight = FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AmanPillRowSingle(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEachIndexed { index, label ->
            AmanPill(
                text = label,
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
//  Category bar — label + thin progress bar + count, used in stats lists.
// ────────────────────────────────────────────────────────────────────────────

@Composable
fun AmanCategoryRow(
    label: String,
    count: String,
    progress: Float,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(110.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = count,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
fun AmanLabeledRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
//  Surface card — clean white card with subtle outline (matches mockup card-r 16).
// ────────────────────────────────────────────────────────────────────────────

@Composable
fun AmanSurfaceCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable () -> Unit,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(modifier = Modifier.padding(contentPadding)) { content() }
    }
}

// ────────────────────────────────────────────────────────────────────────────
//  Backwards-compatible Composables retained for existing callers.
// ────────────────────────────────────────────────────────────────────────────

@Composable
fun AmanHeroCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int = R.drawable.ic_aman_logo,
    overline: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, Color(0xFFB8E8CF)),
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFFE8F8F0), Color(0xFFD1F0E3)),
                    )
                )
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AmanIconBubble(
                    iconRes = iconRes,
                    background = AmanPalette.G500.copy(alpha = 0.16f),
                    contentColor = AmanPalette.G500,
                    sizeDp = 44,
                    iconDp = 22,
                )
                Column(modifier = Modifier.weight(1f)) {
                    overline?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = AmanPalette.G600,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = AmanPalette.G800,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AmanPalette.Text2,
                    )
                }
                trailing?.invoke()
            }
        }
    }
}

@Composable
fun AmanLogoLockup(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_aman_logo),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(80.dp),
        )
        Text(
            text = "أمان",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "AMAN BROWSER",
            modifier = Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun AmanSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AmanEyebrow(text = title)
        action?.invoke()
    }
}

@Composable
fun AmanOutlinedPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        content = { content() },
    )
}

@Composable
fun AmanStatHeroCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    AmanSiteHero(
        domain = label,
        title = value,
        description = caption ?: "",
        shieldText = label,
        modifier = modifier,
    )
}

@Composable
fun BottomNavSpacer() {
    Spacer(modifier = Modifier.height(96.dp))
}

fun colorFromHex(hex: String, fallback: Color = AmanPalette.Primary): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: IllegalArgumentException) {
    fallback
}
