package dev.fortress.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fortress.scanner.DeepScanner
import kotlinx.coroutines.delay

/**
 * SCANNER tab — live view over [ScanCenter] / [DeepScanner.progress]:
 * 4-phase tracker, findings with severity chips, verdict banner, elapsed
 * timer, START/STOP, and the streaming console log. The scan keeps running
 * while other tabs are open because the state lives in ScanCenter.
 */
@Composable
fun ScannerTab() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { ScanCenter.ensure(context) }

    val progress by ScanCenter.progress.collectAsState()
    val logLines by ScanCenter.log.collectAsState()
    val findings by ScanCenter.findings.collectAsState()
    val scanning by ScanCenter.scanning.collectAsState()

    // elapsed seconds readout, ticking only while a scan runs
    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(scanning) {
        if (scanning) {
            while (true) {
                delay(1000)
                elapsed += 1
            }
        } else {
            elapsed = 0
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val p = progress
        val finished = p is DeepScanner.ScanProgress.Finished

        // -- verdict banner ---------------------------------------------------
        ConsolePanel(label = "DEEP SCANNER") {
            when (p) {
                is DeepScanner.ScanProgress.Finished -> {
                    val kind = when {
                        p.verdict == "CLEAN" -> ChipKind.OK
                        p.verdict == "SUSPICIOUS" -> ChipKind.WARN
                        else -> ChipKind.CRIT
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusChip("verdict: ${p.verdict}", kind)
                        StatusChip("score ${p.score}", ChipKind.NEUTRAL)
                        StatusChip("${p.findings} findings", ChipKind.NEUTRAL)
                    }
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (scanning) StatusChip("scanning…", ChipKind.WARN) else StatusChip("verdict: —", ChipKind.NEUTRAL)
                    MonoText(
                        "%02d:%02d".format(elapsed / 60, elapsed % 60),
                        size = 12.sp,
                        color = ConsoleColors.TextMuted,
                    )
                }
            }
        }

        // -- 4-phase tracker ---------------------------------------------------
        ConsolePanel(label = "PHASES · process walk → memory maps → file audit → heuristics") {
            val currentPhase = when {
                finished -> 4
                p is DeepScanner.ScanProgress.Phase -> (p as DeepScanner.ScanProgress.Phase).index
                else -> 0
            }
            listOf("process walk /proc", "memory maps rwx", "file audit priv-app + modules", "rootkit heuristics (native)")
                .forEachIndexed { i, name ->
                    val idx = i + 1
                    val done = idx < currentPhase || finished
                    val active = idx == currentPhase && scanning
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {
                        MonoText(
                            when {
                                done -> "✔"
                                active -> "▶"
                                else -> "·"
                            },
                            color = when {
                                done -> ConsoleColors.Emerald
                                active -> ConsoleColors.Amber
                                else -> ConsoleColors.TextFaint
                            },
                            size = 12.sp,
                        )
                        Spacer(Modifier.width(10.dp))
                        MonoText(
                            name,
                            size = 12.sp,
                            color = if (done || active) ConsoleColors.TextPrimary else ConsoleColors.TextFaint,
                        )
                    }
                }
            Spacer(Modifier.height(10.dp))
            val fraction = when {
                finished -> 1f
                p is DeepScanner.ScanProgress.Phase -> (p as DeepScanner.ScanProgress.Phase).index / 4f
                else -> 0f
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(ConsoleColors.PanelBorder)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .background(if (scanning) ConsoleColors.Amber else ConsoleColors.Emerald)
                )
            }
        }

        // -- findings -----------------------------------------------------------
        if (findings.isNotEmpty()) {
            ConsolePanel(label = "FINDINGS · ${findings.size}") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    findings.forEach { f ->
                        Row {
                            StatusChip(
                                f.severity.name,
                                when (f.severity) {
                                    DeepScanner.Severity.CRITICAL -> ChipKind.CRIT
                                    DeepScanner.Severity.WARN -> ChipKind.WARN
                                    DeepScanner.Severity.INFO -> ChipKind.NEUTRAL
                                },
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                MonoText(f.title, size = 12.sp)
                                MonoText(f.detail, size = 11.sp, color = ConsoleColors.TextMuted)
                            }
                        }
                    }
                }
            }
        }

        // -- actions -------------------------------------------------------------
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!scanning) {
                ConsoleButton("START SCAN", onClick = { ScanCenter.start() })
            } else {
                ConsoleButton("STOP", onClick = { ScanCenter.stop() }, primary = false)
            }
        }

        // -- console log -----------------------------------------------------------
        ConsolePanel(label = "CONSOLE LOG") {
            if (logLines.isEmpty()) {
                MonoText("idle · run a scan to stream phase output", size = 11.sp, color = ConsoleColors.TextFaint)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    logLines.takeLast(60).forEach { line ->
                        MonoText(line, size = 11.sp, color = ConsoleColors.TextMuted)
                    }
                }
            }
        }
    }
}
