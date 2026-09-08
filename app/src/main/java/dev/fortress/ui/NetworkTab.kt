package dev.fortress.ui

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import dev.fortress.ConsoleRuntime
import dev.fortress.firewall.FirewallManager
import dev.fortress.net.PacketVpnService
import dev.fortress.net.TapCenter
import dev.fortress.net.TrafficMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class AppRuleRow(
    val pkg: String,
    val label: String,
    val blocked: Boolean,
    val isSelf: Boolean,
)

/**
 * NET tab — packet tap control + live feed + per-app firewall. Everything is
 * driven by the real engines: PacketVpnService (TUN tap), TrafficMonitor
 * (classification/attribution via TapCenter), FirewallManager (rules that the
 * tap enforces and, with root, iptables enforces outside the VPN).
 */
@Composable
fun NetworkTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val firewall = remember { FirewallManager(context.applicationContext) }

    var tapRunning by remember { mutableStateOf(PacketVpnService.isRunning) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            tapRunning = PacketVpnService.isRunning
        }
    }

    val total by TapCenter.total.collectAsState()
    val flagged by TapCenter.flagged.collectAsState()
    val blocked by TapCenter.blocked.collectAsState()
    val packets = remember { mutableStateListOf<TrafficMonitor.PacketEvent>() }
    LaunchedEffect(Unit) {
        TapCenter.events.collect { e ->
            packets.add(0, e)
            while (packets.size > 80) packets.removeAt(packets.size - 1)
        }
    }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            PacketVpnService.start(context)
        }
    }

    // firewall state
    var rulesExpanded by remember { mutableStateOf(false) }
    var appRows by remember { mutableStateOf<List<AppRuleRow>>(emptyList()) }
    var rulesLoading by remember { mutableStateOf(false) }
    fun reloadRules() {
        val blocked = firewall.rules().mapNotNull { r -> r.app }.toSet()
        val pm = context.packageManager
        val self = context.packageName
        appRows = pm.getInstalledPackages(0)
            .asSequence()
            .filter { it.applicationInfo != null }
            .filter { it.packageName != self }
            .filter { it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .sortedBy { (pm.getApplicationLabel(it.applicationInfo) ?: it.packageName).toString().lowercase() }
            .take(120)
            .map { AppRuleRow(it.packageName, pm.getApplicationLabel(it.applicationInfo).toString(), it.packageName in blocked, false) }
            .toList()
    }
    LaunchedEffect(rulesExpanded) {
        if (rulesExpanded) {
            rulesLoading = true
            reloadRules()
            rulesLoading = false
        }
    }

    // iptables enforcement log
    val iptablesLog = remember { mutableStateListOf<String>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // -- tap control -------------------------------------------------------
        ConsolePanel(label = "PACKET TAP · VpnService") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(
                    if (tapRunning) "tap up" else "tap down",
                    if (tapRunning) ChipKind.OK else ChipKind.NEUTRAL,
                )
                MonoText(
                    if (tapRunning) "traffic is being classified through the TUN" else "start to request VPN consent",
                    size = 11.sp,
                    color = ConsoleColors.TextMuted,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ConsoleButton(
                    if (tapRunning) "STOP TAP" else "START TAP",
                    modifier = Modifier.weight(1f),
                ) {
                    if (tapRunning) {
                        PacketVpnService.stop(context)
                    } else {
                        val consent = VpnService.prepare(context)
                        if (consent != null) vpnLauncher.launch(consent) else PacketVpnService.start(context)
                    }
                }
                ConsoleButton(
                    "APPLY IPTABLES",
                    primary = false,
                    enabled = ConsoleRuntime.rooted,
                    modifier = Modifier.weight(1f),
                ) {
                    scope.launch {
                        iptablesLog.add("> applying FORTRESS_FW chain (root)…")
                        val ok = firewall.applyIptables(ConsoleRuntime.rooted) { line -> iptablesLog.add(line) }
                        iptablesLog.add(if (ok) "✔ chain installed" else "!! completed with errors")
                    }
                }
            }
            if (!ConsoleRuntime.rooted) {
                Spacer(Modifier.height(8.dp))
                MonoText(
                    "no root — iptables path disabled, VPN-path enforcement only",
                    size = 11.sp,
                    color = ConsoleColors.Amber,
                )
            }
            if (iptablesLog.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column {
                    iptablesLog.takeLast(6).forEach { line ->
                        MonoText(line, size = 11.sp, color = ConsoleColors.TextMuted)
                    }
                }
            }
        }

        // -- live counters -------------------------------------------------------
        ConsolePanel(label = "LIVE COUNTERS") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("packets", total.toString(), Modifier.weight(1f))
                StatTile("flagged", flagged.toString(), Modifier.weight(1f))
                StatTile("blocked", blocked.toString(), Modifier.weight(1f))
            }
        }

        // -- live packet feed ------------------------------------------------------
        ConsolePanel(label = "PACKET FEED · newest first") {
            if (packets.isEmpty()) {
                MonoText("no packets yet — start the tap and generate traffic", size = 11.sp, color = ConsoleColors.TextFaint)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    packets.take(40).forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusChip(
                                e.verdict,
                                when (e.verdict) {
                                    "blocked" -> ChipKind.CRIT
                                    "flagged" -> ChipKind.WARN
                                    "allow" -> ChipKind.OK
                                    else -> ChipKind.NEUTRAL
                                },
                            )
                            MonoText("${e.app} → ${e.dst}:${e.port}", size = 11.sp)
                            MonoText("${e.proto} · ${e.bytes}B", size = 10.sp, color = ConsoleColors.TextFaint)
                        }
                    }
                }
            }
        }

        // -- firewall rules ------------------------------------------------------------
        ConsolePanel(label = "FIREWALL · per-app block rules") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                MonoText("user apps (${appRows.size} loaded)", size = 12.sp)
                Spacer(Modifier.weight(1f))
                ConsoleButton(
                    if (rulesExpanded) "HIDE" else "SHOW",
                    primary = false,
                ) { rulesExpanded = !rulesExpanded }
            }
            if (rulesExpanded) {
                Spacer(Modifier.height(10.dp))
                if (rulesLoading) {
                    MonoText("loading installed apps…", size = 11.sp, color = ConsoleColors.TextFaint)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        appRows.forEach { row ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    MonoText(row.label, size = 12.sp)
                                    MonoText(row.pkg, size = 9.sp, color = ConsoleColors.TextFaint)
                                }
                                Switch(
                                    checked = row.blocked,
                                    onCheckedChange = { enable ->
                                        firewall.setBlockRule(row.pkg, FirewallManager.Net.BOTH, enable)
                                        appRows = appRows.map {
                                            if (it.pkg == row.pkg) it.copy(blocked = enable) else it
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedTrackColor = ConsoleColors.Emerald,
                                        uncheckedTrackColor = ConsoleColors.PanelBorder,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
