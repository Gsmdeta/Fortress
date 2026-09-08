package dev.fortress.ui

import android.content.Context
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import dev.fortress.guard.GuardEvent
import dev.fortress.guard.RealtimeGuard
import dev.fortress.tasks.TaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SHIELD tab — realtime guard + task manager. The guard feed streams real
 * events from RealtimeGuard (Magisk module watcher, package lifecycle,
 * clipboard-change metadata); the task list comes from TaskManager and the
 * force-stop button runs `am force-stop` through RootShell (root).
 */
@Composable
fun ShieldTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tasks = remember { TaskManager(context.applicationContext) }

    // guard state
    var guardRunning by remember { mutableStateOf(RealtimeGuard.isRunning) }
    val prefs = remember { context.getSharedPreferences("fortress_fw", Context.MODE_PRIVATE) }
    var autoStart by remember { mutableStateOf(prefs.getBoolean("guard_auto_start", false)) }
    val guardEvents = remember { mutableStateListOf<GuardEvent>() }
    LaunchedEffect(Unit) {
        RealtimeGuard.events.collect { e ->
            guardEvents.add(0, e)
            while (guardEvents.size > 40) guardEvents.removeAt(guardEvents.size - 1)
        }
    }

    // task list state
    var taskRows by remember { mutableStateOf<List<TaskManager.TaskItem>>(emptyList()) }
    var loadingTasks by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshTick) {
        loadingTasks = true
        taskRows = withContext(Dispatchers.IO) { tasks.snapshot() }
        loadingTasks = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // -- realtime guard ---------------------------------------------------
        ConsolePanel(label = "REALTIME GUARD") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    MonoText("guard service", size = 13.sp)
                    MonoText(
                        if (guardRunning) "watching modules, packages, clipboard" else "stopped",
                        size = 11.sp,
                        color = ConsoleColors.TextMuted,
                    )
                }
                StatusChip(
                    if (guardRunning) "armed" else "off",
                    if (guardRunning) ChipKind.OK else ChipKind.NEUTRAL,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    MonoText("re-arm after reboot", size = 13.sp)
                    MonoText("starts the guard automatically on boot", size = 11.sp, color = ConsoleColors.TextMuted)
                }
                Switch(
                    checked = autoStart,
                    onCheckedChange = { enable ->
                        autoStart = enable
                        prefs.edit().putBoolean("guard_auto_start", enable).apply()
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = ConsoleColors.Emerald,
                        uncheckedTrackColor = ConsoleColors.PanelBorder,
                    ),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ConsoleButton(
                    if (guardRunning) "STOP GUARD" else "ARM GUARD",
                    modifier = Modifier.weight(1f),
                ) {
                    if (guardRunning) RealtimeGuard.stop(context) else RealtimeGuard.start(context)
                }
            }
        }

        // -- guard feed -----------------------------------------------------------
        ConsolePanel(label = "GUARD FEED · newest first") {
            if (guardEvents.isEmpty()) {
                MonoText("no events yet — the guard reports module/package/clipboard activity here", size = 11.sp, color = ConsoleColors.TextFaint)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    guardEvents.take(30).forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusChip(
                                e.type,
                                when {
                                    e.type.startsWith("MODULE") -> ChipKind.WARN
                                    else -> ChipKind.NEUTRAL
                                },
                            )
                            MonoText(e.subject, size = 11.sp)
                        }
                    }
                }
            }
        }

        // -- task manager -----------------------------------------------------------
        ConsolePanel(label = "TASK MANAGER · running apps") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                MonoText(
                    if (loadingTasks) "loading…" else "${taskRows.size} processes",
                    size = 12.sp,
                    color = ConsoleColors.TextMuted,
                )
                Spacer(Modifier.weight(1f))
                ConsoleButton("REFRESH", primary = false) { refreshTick++ }
            }
            Spacer(Modifier.height(10.dp))
            if (taskRows.isEmpty() && !loadingTasks) {
                MonoText("no visible processes — refresh or grant usage access", size = 11.sp, color = ConsoleColors.TextFaint)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    taskRows.take(40).forEach { t ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                MonoText(t.label, size = 12.sp)
                                MonoText(
                                    "${t.state} · pid ${t.pid}",
                                    size = 9.sp,
                                    color = ConsoleColors.TextFaint,
                                )
                            }
                            ConsoleButton(
                                "KILL",
                                primary = false,
                                modifier = Modifier.width(72.dp),
                            ) {
                                scope.launch {
                                    tasks.kill(t.app) { }
                                    refreshTick++
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
