package dev.fortress.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.content.FileProvider
import dev.fortress.ConsoleRuntime
import dev.fortress.root.MagiskInstaller
import dev.fortress.vt.VirusTotalClient
import kotlinx.coroutines.launch
import java.io.File

/** Operator controls for deep scanning, root-module export, and safe local reset. */
@Composable
fun SettingsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val virusTotal = remember { VirusTotalClient(context.applicationContext) }
    val prefs = remember { context.getSharedPreferences("fortress_settings", Context.MODE_PRIVATE) }
    var deepArtifactScan by remember { mutableStateOf(prefs.getBoolean("deep_artifact_scan", false)) }
    var artifactLimit by remember { mutableStateOf(prefs.getInt("artifact_limit", 300)) }
    var includeSystemPaths by remember { mutableStateOf(prefs.getBoolean("include_system_paths", true)) }
    var autoGuard by remember {
        mutableStateOf(
            context.getSharedPreferences("fortress_guard", Context.MODE_PRIVATE)
                .getBoolean("guard_auto_start", false),
        )
    }
    var exporting by remember { mutableStateOf(false) }
    var exportFile by remember { mutableStateOf<File?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var showResetPin by remember { mutableStateOf(false) }
    var vtKey by remember { mutableStateOf("") }
    var vtSaved by remember { mutableStateOf(virusTotal.hasKey()) }

    if (showResetPin) {
        AlertDialog(
            onDismissRequest = { showResetPin = false },
            title = { MonoText("RESET LOCAL PIN", size = 18.sp) },
            text = { MonoText("Restore the local gate to the default PIN 1357?", size = 13.sp, color = ConsoleColors.TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    PinLock.setPin(context, PinLock.DEFAULT_PIN)
                    showResetPin = false
                    Toast.makeText(context, "PIN reset to 1357", Toast.LENGTH_SHORT).show()
                }) { MonoText("RESET", size = 12.sp, color = ConsoleColors.Amber) }
            },
            dismissButton = {
                TextButton(onClick = { showResetPin = false }) { MonoText("CANCEL", size = 12.sp, color = ConsoleColors.TextMuted) }
            },
            containerColor = ConsoleColors.PanelSolid,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConsolePanel(label = "ROOT PROVIDER") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    MonoText(ConsoleRuntime.rootProviderLabel, size = 15.sp)
                    MonoText(
                        if (ConsoleRuntime.rooted) "su session available for explicit privileged actions"
                        else "limited mode — root manager not available",
                        size = 11.sp,
                        color = ConsoleColors.TextMuted,
                    )
                }
                StatusChip(
                    if (ConsoleRuntime.rooted) "ROOT" else "LIMITED",
                    if (ConsoleRuntime.rooted) ChipKind.OK else ChipKind.WARN,
                )
            }
        }

        ConsolePanel(label = "SCAN PROFILE") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    MonoText("bounded deep artifact sweep", size = 13.sp)
                    MonoText("hashes user files and selected root paths with a configurable safety limit", size = 11.sp, color = ConsoleColors.TextMuted)
                }
                Switch(
                    checked = deepArtifactScan,
                    onCheckedChange = {
                        deepArtifactScan = it
                        prefs.edit().putBoolean("deep_artifact_scan", it).apply()
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = ConsoleColors.Emerald,
                        uncheckedTrackColor = ConsoleColors.PanelBorder,
                    ),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    MonoText("artifact sample limit", size = 13.sp)
                    MonoText("higher limits take longer and use more battery", size = 11.sp, color = ConsoleColors.TextMuted)
                }
                ConsoleButton("$artifactLimit", primary = false) {
                    val next = when (artifactLimit) {
                        300 -> 1000
                        1000 -> 5000
                        else -> 300
                    }
                    artifactLimit = next
                    prefs.edit().putInt("artifact_limit", next).apply()
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    MonoText("include system and root module paths", size = 13.sp)
                    MonoText("requires root; user storage remains included", size = 11.sp, color = ConsoleColors.TextMuted)
                }
                Switch(
                    checked = includeSystemPaths,
                    onCheckedChange = {
                        includeSystemPaths = it
                        prefs.edit().putBoolean("include_system_paths", it).apply()
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = ConsoleColors.Emerald,
                        uncheckedTrackColor = ConsoleColors.PanelBorder,
                    ),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    MonoText("guard auto-start after reboot", size = 13.sp)
                    MonoText("keeps the realtime watcher opt-in", size = 11.sp, color = ConsoleColors.TextMuted)
                }
                Switch(
                    checked = autoGuard,
                    onCheckedChange = {
                        autoGuard = it
                        context.getSharedPreferences("fortress_guard", Context.MODE_PRIVATE)
                            .edit().putBoolean("guard_auto_start", it).apply()
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = ConsoleColors.Emerald,
                        uncheckedTrackColor = ConsoleColors.PanelBorder,
                    ),
                )
            }
        }

        ConsolePanel(label = "ROOT MODULE EXPORT") {
            MonoText("Magisk and KernelSU consume the same systemless module format.", size = 12.sp, color = ConsoleColors.TextMuted)
            Spacer(Modifier.height(8.dp))
            ConsoleButton(
                if (exporting) "EXPORTING…" else "EXPORT MODULE ZIP",
                enabled = !exporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                exporting = true
                exportMessage = null
                scope.launch {
                    exportFile = MagiskInstaller.exportModule(context) { line -> exportMessage = line }
                    exporting = false
                    if (exportFile == null) exportMessage = "export failed — source APK unavailable"
                }
            }
            exportMessage?.let {
                Spacer(Modifier.height(8.dp))
                MonoText(it, size = 11.sp, color = ConsoleColors.TextMuted)
            }
            exportFile?.let { file ->
                Spacer(Modifier.height(8.dp))
                ConsoleButton("SHARE ${file.name}", primary = false, modifier = Modifier.fillMaxWidth()) {
                    shareModule(context, file)
                }
            }
        }

        ConsolePanel(label = "DETECTION ENGINES") {
            EngineRow("Fortress heuristics", "enabled · process, maps, modules, kernel symbols")
            EngineRow("VirusTotal", "optional · hash-first lookup, explicit upload consent")
            EngineRow("ClamAV / YARA", "not bundled · add a reviewed ruleset/engine before enabling")
            Spacer(Modifier.height(8.dp))
            SectionLabel("OPTIONAL VIRUSTOTAL KEY")
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = vtKey,
                onValueChange = { vtKey = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                placeholder = { MonoText("paste API key", size = 12.sp, color = ConsoleColors.TextFaint) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ConsoleColors.Emerald,
                    unfocusedBorderColor = ConsoleColors.PanelBorder,
                    focusedTextColor = ConsoleColors.TextPrimary,
                    unfocusedTextColor = ConsoleColors.TextPrimary,
                    cursorColor = ConsoleColors.Emerald,
                ),
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                MonoText(
                    if (vtSaved) "hash-first lookup configured" else "no API key stored",
                    size = 11.sp,
                    color = ConsoleColors.TextMuted,
                    modifier = Modifier.weight(1f),
                )
                ConsoleButton("SAVE", primary = false, enabled = vtKey.isNotBlank()) {
                    virusTotal.setApiKey(vtKey)
                    vtKey = ""
                    vtSaved = true
                }
            }
        }

        ConsolePanel(label = "MEMORY FORENSICS") {
            StatusChip("DEVICE PROFILE REQUIRED", ChipKind.WARN)
            Spacer(Modifier.height(8.dp))
            MonoText(
                "Full RAM acquisition is intentionally disabled until a compatible, signed kernel module is supplied. The scanner uses /proc maps and cross-view heuristics safely.",
                size = 11.sp,
                color = ConsoleColors.TextMuted,
            )
        }

        ConsolePanel(label = "LOCAL ACCESS") {
            ConsoleButton("RESET PIN TO 1357", primary = false, modifier = Modifier.fillMaxWidth()) {
                showResetPin = true
            }
        }
    }
}

@Composable
private fun EngineRow(name: String, detail: String) {
    Column(Modifier.padding(vertical = 5.dp)) {
        MonoText(name, size = 13.sp)
        MonoText(detail, size = 11.sp, color = ConsoleColors.TextMuted)
    }
}

private fun shareModule(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share Fortress root module"))
}
