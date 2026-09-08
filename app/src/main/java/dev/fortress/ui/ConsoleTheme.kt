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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
        ),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = ConsoleColors.TextFaint) {
    Text(
        text = text.uppercase(),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
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
            .padding(horizontal = 10.dp, vertical = 4.dp),
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

/** Emerald action button (primary) or outlined ghost variant, 44dp touch target.
 *  onClick is the LAST parameter so call sites can use trailing-lambda syntax. */
@Composable
fun ConsoleButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (primary) ConsoleColors.Emerald.copy(alpha = alpha) else Color.Transparent)
            .then(
                if (!primary) {
                    Modifier.border(1.dp, ConsoleColors.Emerald.copy(alpha = alpha), RoundedCornerShape(8.dp))
                } else {
                    Modifier
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = (if (primary) ConsoleColors.Bg else ConsoleColors.Emerald).copy(alpha = alpha),
        )
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

/** Honest interim screen for tabs whose phase lands later in the rebuild. */
@Composable
fun OfflinePanel(title: String, phase: Int) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ConsolePanel(label = title) {
            StatusChip("OFFLINE · PHASE $phase", ChipKind.WARN)
            Spacer(Modifier.height(12.dp))
            MonoText(
                "module wires to its live engine in phase $phase of the console rebuild — scanner tab is live now",
                color = ConsoleColors.TextMuted,
            )
        }
    }
}
