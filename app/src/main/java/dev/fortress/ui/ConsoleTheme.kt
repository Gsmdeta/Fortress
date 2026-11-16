@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.fortress.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Fortress console design language — ported 1:1 from the web command center:
 * zinc-950 background, zinc-900/60 panels with zinc-800 borders, mono
 * readouts, uppercase spaced micro-labels, and ONLY emerald/amber/red accents
 * (ok / warn / critical). No blues, no material default colors.
 */
object ConsoleColors {
    val Bg = Color(0xFF09090B)          // zinc-950
    val Panel = Color(0x9918181B)       // zinc-900 at 60%
    val PanelSolid = Color(0xFF18181B)  // zinc-900 (tab strip)
    val PanelBorder = Color(0xFF27272A) // zinc-800
    val Emerald = Color(0xFF34D399)     // emerald-400 — ok / active
    val Amber = Color(0xFFFBBF24)       // amber-400 — warn / in-progress
    val Red = Color(0xFFEF4444)         // red-500 — critical
    val TextPrimary = Color(0xFFD4D4D8) // zinc-300
    val TextMuted = Color(0xFFA1A1AA)   // zinc-400
    val TextFaint = Color(0xFF71717A)   // zinc-500
}

@Composable
fun FortressConsoleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ConsoleColors.Emerald,
            onPrimary = ConsoleColors.Bg,
            secondary = ConsoleColors.Amber,
            onSecondary = ConsoleColors.Bg,
            error = ConsoleColors.Red,
            background = ConsoleColors.Bg,
            onBackground = ConsoleColors.TextPrimary,
            surface = ConsoleColors.Bg,
            onSurface = ConsoleColors.TextPrimary,
            surfaceVariant = ConsoleColors.PanelSolid,
            onSurfaceVariant = ConsoleColors.TextMuted,
            outline = ConsoleColors.PanelBorder,
        ),
        content = content,
    )
}

@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = ConsoleColors.TextFaint,
    size: TextUnit = 11.sp,
) {
    Text(
        text = text.uppercase(),
        fontFamily = FontFamily.Monospace,
        fontSize = size,
        letterSpacing = 2.sp,
        color = color,
        modifier = modifier,
    )
}

@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 13.sp,
    color: Color = ConsoleColors.TextPrimary,
) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = size,
        color = color,
        modifier = modifier,
    )
}

/** The standard console card: bordered zinc panel with an optional micro-label header. */
@Composable
fun ConsolePanel(
    modifier: Modifier = Modifier,
    label: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ConsoleColors.Panel)
            .border(1.dp, ConsoleColors.PanelBorder, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        if (label != null) {
            SectionLabel(label)
            Spacer(Modifier.height(12.dp))
        }
        content()
    }
}

enum class ChipKind { OK, WARN, CRIT, NEUTRAL }

@Composable
fun StatusChip(text: String, kind: ChipKind, modifier: Modifier = Modifier) {
    val color = when (kind) {
        ChipKind.OK -> ConsoleColors.Emerald
        ChipKind.WARN -> ConsoleColors.Amber
        ChipKind.CRIT -> ConsoleColors.Red
        ChipKind.NEUTRAL -> ConsoleColors.TextMuted
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, color, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { contentDescription = text },
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            color = color,
        )
    }
}

/** Emerald action button (primary) or outlined ghost variant, 48dp touch target.
 *  onClick is the LAST parameter so call sites can use trailing-lambda syntax. */
@Composable
fun ConsoleButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val contentPadding = androidx.compose.foundation.layout.PaddingValues(
        horizontal = 20.dp,
        vertical = 12.dp,
    )
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 48.dp),
            shape = shape,
            contentPadding = contentPadding,
            colors = ButtonDefaults.buttonColors(
                containerColor = ConsoleColors.Emerald,
                contentColor = ConsoleColors.Bg,
                disabledContainerColor = ConsoleColors.Emerald.copy(alpha = 0.25f),
                disabledContentColor = ConsoleColors.Bg.copy(alpha = 0.55f),
            ),
        ) {
            Text(
                text = text.uppercase(),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 48.dp),
            shape = shape,
            contentPadding = contentPadding,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                ConsoleColors.Emerald.copy(alpha = if (enabled) 1f else 0.4f),
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = ConsoleColors.Emerald,
                disabledContentColor = ConsoleColors.Emerald.copy(alpha = 0.4f),
            ),
        ) {
            Text(
                text = text.uppercase(),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun ConsoleTopAppBar(
    title: String,
    rooted: Boolean,
    magisk: Boolean,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = "FORTRESS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
                SectionLabel(title, size = 9.sp)
            }
        },
        actions = {
            StatusChip(
                if (rooted) "ROOT" else "LIMITED",
                if (rooted) ChipKind.OK else ChipKind.WARN,
                modifier = Modifier.padding(end = 6.dp),
            )
            if (magisk) {
                StatusChip("MAGISK", ChipKind.OK, modifier = Modifier.padding(end = 12.dp))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = ConsoleColors.Bg,
            titleContentColor = ConsoleColors.TextPrimary,
            actionIconContentColor = ConsoleColors.TextPrimary,
        ),
    )
}

@Composable
fun ConsoleNavigationBar(
    labels: List<String>,
    glyphs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    NavigationBar(
        containerColor = ConsoleColors.PanelSolid,
        contentColor = ConsoleColors.TextPrimary,
        tonalElevation = 4.dp,
    ) {
        labels.forEachIndexed { index, label ->
            NavigationBarItem(
                selected = selected == index,
                onClick = { onSelect(index) },
                icon = {
                    Text(
                        text = glyphs.getOrElse(index) { "•" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                },
                label = null,
                alwaysShowLabel = false,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ConsoleColors.Bg,
                    indicatorColor = ConsoleColors.Emerald,
                    unselectedIconColor = ConsoleColors.TextMuted,
                ),
                modifier = Modifier.semantics { contentDescription = label },
            )
        }
    }
}

@Composable
fun ConsoleNavigationRail(
    labels: List<String>,
    glyphs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    NavigationRail(
        containerColor = ConsoleColors.PanelSolid,
        contentColor = ConsoleColors.TextPrimary,
        header = { SectionLabel("FORTRESS", Modifier.padding(top = 18.dp, bottom = 10.dp), ConsoleColors.Emerald) },
    ) {
        labels.forEachIndexed { index, label ->
            NavigationRailItem(
                selected = selected == index,
                onClick = { onSelect(index) },
                icon = {
                    Text(
                        text = glyphs.getOrElse(index) { "•" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                },
                label = { Text(label, fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                alwaysShowLabel = true,
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = ConsoleColors.Bg,
                    selectedTextColor = ConsoleColors.Emerald,
                    indicatorColor = ConsoleColors.Emerald,
                    unselectedIconColor = ConsoleColors.TextMuted,
                    unselectedTextColor = ConsoleColors.TextMuted,
                ),
                modifier = Modifier.semantics { contentDescription = label },
            )
        }
    }
}

/** Small stat card: micro-label over a large mono value. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    ConsolePanel(modifier = modifier) {
        SectionLabel(label)
        Spacer(Modifier.height(6.dp))
        MonoText(value, size = 18.sp)
    }
}

/**
 * 6-tab console strip with the demo's sliding emerald underline. Tabs are
 * equal-width and always fill the strip (no scroll) — the two-pane tablet
 * layout in a later phase moves tabs to a side rail.
 */
@Composable
fun ConsoleTabBar(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().background(ConsoleColors.PanelSolid)) {
        val tabWidth = maxWidth / labels.size
        val underlineX by animateDpAsState(
            targetValue = tabWidth * selected,
            animationSpec = tween(220),
            label = "tabUnderline",
        )
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                labels.forEachIndexed { i, label ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clickable { onSelect(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp,
                            color = if (i == selected) ConsoleColors.Emerald else ConsoleColors.TextMuted,
                        )
                    }
                }
            }
            Box(
                Modifier
                    .offset(x = underlineX)
                    .width(tabWidth)
                    .height(2.dp)
                    .background(ConsoleColors.Emerald)
            )
        }
    }
}

/** Vertical tab rail for wide screens (>= 600dp) — the responsive counterpart
 *  of the bottom [ConsoleTabBar]. */
@Composable
fun ConsoleSideRail(labels: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Column(modifier.background(ConsoleColors.PanelSolid)) {
        labels.forEachIndexed { i, label ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = label,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    color = if (i == selected) ConsoleColors.Emerald else ConsoleColors.TextMuted,
                )
            }
        }
    }
}
