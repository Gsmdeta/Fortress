package dev.fortress.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fortress.cve.CveRepository
import kotlinx.coroutines.launch

/**
 * CVE tab — bundled catalog (assets/cve-db.json) with severity chips, local
 * mitigation status, and one-tap APPLY PATCH for entries with a local
 * mitigation (sysctl/chmod/chcon/settings through RootShell — root required).
 * PATCHED badges persist in shared prefs.
 */
@Composable
fun CveTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { CveRepository(context.applicationContext) }
    val prefs = remember { context.getSharedPreferences("fortress_console", Context.MODE_PRIVATE) }

    var entries by remember { mutableStateOf(repo.load()) }
    var patched by remember { mutableStateOf(prefs.getStringSet("patched_cves", emptySet()) ?: emptySet<String>()) }
    var applying by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    val output = remember { mutableStateListOf<String>() }

    val sorted = entries.sortedWith(
        compareBy({ CveRepository.SEVERITY_RANK[it.severity] ?: 9 }, { it.id })
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConsolePanel(label = "CVE FEED · ${entries.size} known") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip("bundled catalog", ChipKind.NEUTRAL)
                StatusChip("patched ${patched.size}", ChipKind.OK)
            }
            Spacer(Modifier.height(8.dp))
            MonoText(
                "local mitigations are sysctl/chmod/chcon/settings hardening only — never binary patching",
                size = 11.sp,
                color = ConsoleColors.TextMuted,
            )
        }

        sorted.forEach { e ->
            val isPatched = e.id in patched
            ConsolePanel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonoText(e.id, size = 13.sp)
                    StatusChip(
                        e.severity,
                        when (e.severity) {
                            "critical" -> ChipKind.CRIT
                            "high" -> ChipKind.WARN
                            else -> ChipKind.NEUTRAL
                        },
                    )
                    MonoText("cvss %.1f".format(e.cvss), size = 11.sp, color = ConsoleColors.TextMuted)
                    if (isPatched) {
                        Spacer(Modifier.weight(1f))
                        StatusChip("PATCHED", ChipKind.OK)
                    }
                }
                MonoText(
                    "${e.component} · android ${e.androidVersion}",
                    size = 10.sp,
                    color = ConsoleColors.TextFaint,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(6.dp))
                MonoText(
                    e.desc,
                    size = 11.sp,
                    color = ConsoleColors.TextMuted,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        isPatched -> Unit // badge shown above
                        e.softwarePatchAvailable -> ConsoleButton(
                            if (applying == e.id) "APPLYING…" else "APPLY PATCH",
                            enabled = applying == null,
                        ) {
                            applying = e.id
                            output.clear()
                            scope.launch {
                                val result = repo.applySoftwarePatch(e) { line -> output.add(line) }
                                if (result.success) {
                                    patched = patched + e.id
                                    prefs.edit().putStringSet("patched_cves", patched).apply()
                                }
                                applying = null
                            }
                        }
                        else -> StatusChip("OTA ONLY · no local mitigation", ChipKind.NEUTRAL)
                    }
                    if (e.softwarePatchAvailable && !isPatched) {
                        MonoText(
                            "needs root + will run shell hardening",
                            size = 10.sp,
                            color = ConsoleColors.TextFaint,
                        )
                    }
                }
            }
        }

        if (output.isNotEmpty()) {
            ConsolePanel(label = "PATCH OUTPUT") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    output.takeLast(12).forEach { line ->
                        MonoText(line, size = 11.sp, color = ConsoleColors.TextMuted)
                    }
                }
            }
        }
    }
}
