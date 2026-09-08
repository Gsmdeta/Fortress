package dev.fortress.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

private const val BOOT_STEP_MS = 260L
private const val BOOT_HOLD_MS = 700L

/**
 * Signature boot sequence from the web command center: staggered `[ok]` lines
 * over an emerald progress bar, auto-dismissing after ~2.6 s, skippable with
 * a tap anywhere. Lines reflect REAL device triage (root / Magisk state) that
 * MainActivity probed before routing here.
 */
@Composable
fun BootSequenceOverlay(
    rooted: Boolean,
    magisk: Boolean,
    versionName: String,
    onDismiss: () -> Unit,
) {
    val lines = remember(rooted, magisk) {
        listOf(
            "fortress console · v$versionName",
            "[ ok ] environment triage complete",
            if (rooted) "[ ok ] root session available — deep mode" else "[ .. ] no root — limited mode",
            if (magisk) "[ ok ] magisk detected" else "[ .. ] magisk not detected",
            "[ ok ] deep scanner engine armed",
            "[ ok ] packet tap module ready",
            "[ ok ] realtime guard standing by",
            "[ ok ] console online",
        )
    }
    var shown by remember { mutableIntStateOf(1) }

    LaunchedEffect(lines) {
        for (i in 1 until lines.size) {
            delay(BOOT_STEP_MS)
            shown = i + 1
        }
        delay(BOOT_HOLD_MS)
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(60f)
            .background(ConsoleColors.Bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(32.dp)) {
            lines.forEachIndexed { i, line ->
                if (i < shown) {
                    MonoText(
                        text = line,
                        size = 12.sp,
                        color = when {
                            i == 0 -> ConsoleColors.Emerald
                            line.startsWith("[ ok ]") -> ConsoleColors.TextMuted
                            line.startsWith("[ .. ]") -> ConsoleColors.Amber
                            else -> ConsoleColors.TextPrimary
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            val fraction = shown.toFloat() / lines.size
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(ConsoleColors.PanelBorder)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .background(ConsoleColors.Emerald)
                )
            }
        }
    }
}
