package dev.fortress.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * PIN gate ported from the web lock screen: 4-digit code (default 1357),
 * wrong code shakes the dot row with a red flash. The PIN lives in shared
 * prefs on-device; this is a lab-console gate, not cryptographic auth.
 */
object PinLock {
    private const val PREFS = "fortress_console"
    private const val KEY_PIN = "pin"
    private const val KEY_ENABLED = "lock_enabled"
    const val DEFAULT_PIN = "1357"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun check(context: Context, pin: String): Boolean =
        pin == prefs(context).getString(KEY_PIN, DEFAULT_PIN)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

@Composable
fun PinLockScreen(onUnlock: () -> Unit) {
    val context = LocalContext.current
    var entry by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val shakeX = remember { Animatable(0f) }

    LaunchedEffect(wrong) {
        if (wrong) {
            repeat(3) {
                shakeX.animateTo(14f, tween(45))
                shakeX.animateTo(-14f, tween(45))
            }
            shakeX.animateTo(0f, tween(45))
            wrong = false
        }
    }

    fun onDigit(d: Char) {
        if (wrong || entry.length >= 4) return
        val next = entry + d
        entry = next
        if (next.length == 4) {
            if (PinLock.check(context, next)) {
                onUnlock()
            } else {
                wrong = true
                entry = ""
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ConsoleColors.Bg)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MonoText("FORTRESS", size = 22.sp, color = ConsoleColors.Emerald)
        Spacer(Modifier.height(8.dp))
        SectionLabel("SECURITY CONSOLE · PIN GATED")
        Spacer(Modifier.height(36.dp))

        // dot row (shakes on wrong entry)
        Row(Modifier.offset { IntOffset(shakeX.value.roundToInt(), 0) }) {
            repeat(4) { i ->
                val filled = i < entry.length
                val dotColor = when {
                    wrong -> ConsoleColors.Red
                    filled -> ConsoleColors.Emerald
                    else -> ConsoleColors.TextFaint
                }
                Box(
                    Modifier
                        .padding(horizontal = 8.dp)
                        .size(16.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (filled) dotColor else Color.Transparent)
                        .border(1.dp, dotColor, RoundedCornerShape(50))
                )
            }
        }
        Spacer(Modifier.height(36.dp))

        // 3x4 keypad: 1-9, backspace, 0, blank
        val keys = listOf(
            listOf('1', '2', '3'),
            listOf('4', '5', '6'),
            listOf('7', '8', '9'),
            listOf(null, '0', '⌫'),
        )
        keys.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                row.forEach { key ->
                    Box(
                        modifier = Modifier
                            .padding(6.dp)
                            .size(64.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ConsoleColors.Panel)
                            .border(1.dp, ConsoleColors.PanelBorder, RoundedCornerShape(10.dp))
                            .clickable(enabled = key != null) {
                                when (key) {
                                    null -> Unit
                                    '⌫' -> if (entry.isNotEmpty()) entry = entry.dropLast(1)
                                    else -> onDigit(key)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (key != null) {
                            Text(
                                text = key.toString(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ConsoleColors.TextPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}
