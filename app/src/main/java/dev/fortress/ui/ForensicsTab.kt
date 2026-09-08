package dev.fortress.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fortress.data.ScanSession
import dev.fortress.data.SessionStore
import dev.fortress.scanner.RootkitHeuristics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FORENSICS tab — persisted scan sessions with full finding tables, live
 * kernel-posture snapshot chips, and markdown export via the system share
 * sheet (ports the web console's sessionToMarkdown renderer).
 */
@Composable
fun ForensicsTab() {
    val context = LocalContext.current
    val store = remember { SessionStore(context.applicationContext) }

    var sessions by remember { mutableStateOf(store.sessions()) }
    var expanded by remember { mutableStateOf<String?>(null) }

    // kernel posture snapshot (pure Kotlin /proc reads; degrade to nulls quietly)
    var sysrq by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    var kallsyms by remember { mutableStateOf<Boolean?>(null) }
    var ptrace by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            sysrq = RootkitHeuristics.sysrqState()
            kallsyms = RootkitHeuristics.kallsymsReadable()
            ptrace = RootkitHeuristics.ptraceScope()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConsolePanel(label = "KERNEL POSTURE SNAPSHOT") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(
                    "sysrq ${sysrq?.first ?: "—"}",
                    if ((sysrq?.second) == true) ChipKind.WARN else ChipKind.NEUTRAL,
                )
                StatusChip(
                    "kallsyms ${if (kallsyms == true) "readable" else "sealed"}",
                    if (kallsyms == true) ChipKind.WARN else ChipKind.OK,
                )
                StatusChip(
                    "ptrace_scope ${ptrace ?: "—"}",
                    if ((ptrace ?: 1) == 0) ChipKind.WARN else ChipKind.OK,
                )
            }
        }

        if (sessions.isEmpty()) {
            ConsolePanel(label = "SESSIONS") {
                MonoText(
                    "no scan sessions yet — run a deep scan and it will land here",
                    size = 12.sp,
                    color = ConsoleColors.TextFaint,
                )
            }
        }

        sessions.forEach { s ->
            val open = expanded == s.id
            ConsolePanel {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    StatusChip(
                        "score ${s.score}",
                        when {
                            s.verdict == "CLEAN" -> ChipKind.OK
                            s.verdict == "SUSPICIOUS" -> ChipKind.WARN
                            else -> ChipKind.CRIT
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        MonoText(s.label, size = 13.sp)
                        MonoText(
                            "verdict ${s.verdict} · ${s.findings.size} finding(s) · ${dateFormat(s.finishedAt)}",
                            size = 10.sp,
                            color = ConsoleColors.TextFaint,
                        )
                    }
                    ConsoleButton(
                        if (open) "HIDE" else "OPEN",
                        primary = false,
                    ) { expanded = if (open) null else s.id }
                }

                if (open) {
                    Spacer(Modifier.height(10.dp))
                    if (s.findings.isEmpty()) {
                        MonoText("no findings recorded", size = 11.sp, color = ConsoleColors.TextFaint)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            s.findings.forEach { f ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StatusChip(
                                        f.severity,
                                        when (f.severity) {
                                            "CRITICAL" -> ChipKind.CRIT
                                            "WARN" -> ChipKind.WARN
                                            else -> ChipKind.NEUTRAL
                                        },
                                    )
                                    Column {
                                        MonoText(f.title, size = 12.sp)
                                        MonoText(f.detail, size = 11.sp, color = ConsoleColors.TextMuted)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    ConsoleButton("EXPORT MARKDOWN") {
                        shareSession(context, s)
                    }
                }
            }
        }
    }
}

private fun dateFormat(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ts))

/** Ports the web console's sessionToMarkdown() renderer. */
private fun sessionToMarkdown(s: ScanSession): String = buildString {
    appendLine("# Fortress — ${s.label}")
    appendLine()
    appendLine("- verdict: **${s.verdict}** (score ${s.score})")
    appendLine("- finished: ${dateFormat(s.finishedAt)}")
    appendLine("- findings: ${s.findings.size}")
    appendLine()
    if (s.findings.isNotEmpty()) {
        appendLine("| severity | title | detail |")
        appendLine("| --- | --- | --- |")
        s.findings.forEach { f ->
            appendLine("| ${f.severity} | ${f.title.replace("|", "\\|")} | ${f.detail.replace("|", "\\|")} |")
        }
        appendLine()
    }
    appendLine("_generated by the Fortress console — defensive tooling for devices you own._")
}

private fun shareSession(context: Context, s: ScanSession) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Fortress — ${s.label}")
        putExtra(Intent.EXTRA_TEXT, sessionToMarkdown(s))
    }
    context.startActivity(Intent.createChooser(intent, "Export session"))
}
