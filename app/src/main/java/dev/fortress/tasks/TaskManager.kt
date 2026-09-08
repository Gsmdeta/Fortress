package dev.fortress.tasks

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import dev.fortress.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Task manager: enumerate running apps, estimate battery drain, force-stop.
 *
 * WHY root for enumeration: ActivityManager.getRunningAppProcesses() only ever
 * returned reliable data pre-Android-5, and even UsageStats needs the special
 * PACKAGE_USAGE_STATS grant. We layer three sources, best-effort:
 *   1. ActivityManager running processes (own + visible system procs),
 *   2. UsageStatsManager (permitted when the user grants usage access),
 *   3. root `ps -A` through RootShell when available — the only source that
 *      sees the full picture on Android 10+.
 *
 * WHY `am force-stop` via shell: calling it from an app UID is restricted to
 * the caller's own package; the su stream is the sanctioned way to stop OTHER
 * apps on a rooted lab device. This is the only write operation here, and it
 * mirrors what Settings → Apps does.
 */
class TaskManager(private val context: Context) {

    data class TaskItem(
        val pid: Int,
        val app: String,          // package name
        val label: String,        // human label
        val cpu: Double,          // %CPU estimate (ps snapshot delta)
        val memMb: Int,
        val battery: Double,      // drain %/h ESTIMATE (documented heuristic)
        val state: String,        // foreground | background | cached
    )

    fun snapshot(): List<TaskItem> = buildList {
        val pm = context.packageManager
        val am = context.getSystemService(ActivityManager::class.java)

        // -- source 1: ActivityManager --------------------------------------
        am?.runningAppProcesses?.forEach { proc ->
            val pkg = proc.processName.substringBefore(':')
            if (pkg == context.packageName) return@forEach
            val label = labelFor(pm, pkg)
            val state = when {
                proc.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground"
                proc.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
                else -> "background"
            }
            add(
                TaskItem(
                    pid = proc.pid, app = pkg, label = label,
                    cpu = 0.0, memMb = 0, // filled by source 3 when rooted
                    battery = drainEstimate(state),
                    state = state,
                )
            )
        }

        // -- source 2: usage stats (needs the user grant) ---------------------
        val usm = context.getSystemService(UsageStatsManager::class.java)
        usm?.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            System.currentTimeMillis() - 86_400_000L,
            System.currentTimeMillis(),
        )?.forEach { stat ->
            find { it.app == stat.packageName }?.let { existing ->
                if (stat.totalTimeInForeground > 0 && existing.state == "background") {
                    // long foreground use today ⇒ bump the drain estimate
                    val idx = indexOf(existing)
                    set(idx, existing.copy(battery = drainEstimate("foreground")))
                }
            }
        }
    }

    /** Root-enriched snapshot: RSS + %CPU from toybox `ps -A -o` output. */
    suspend fun snapshotRooted(): List<TaskItem> = withContext(Dispatchers.IO) {
        val base = snapshot()
        if (!RootShell.isAvailable()) return@withContext base
        val r = RootShell.exec("ps -A -o PID,USER,RSS,%CPU,ARGS")
        val byPid = r.output.mapNotNull { line ->
            val c = line.trim().split(Regex("\\s+"))
            if (c.size < 5 || !c[0].all(Char::isDigit)) return@mapNotNull null
            Triple(c[0].toIntOrNull() ?: return@mapNotNull null, c[2].toIntOrNull() ?: 0, c[3])
        }.associateBy({ it.first }, { it.second to it.third })
        base.map { item ->
            val (rssKb, cpu) = byPid[item.pid] ?: (0 to "0.0")
            item.copy(
                memMb = rssKb / 1024,
                cpu = cpu.toDoubleOrNull() ?: 0.0,
            )
        }
    }

    /** force-stop via the su stream — the only write operation in this class. */
    suspend fun kill(pkg: String, onLine: (String) -> Unit = {}): Boolean {
        val r = RootShell.exec("am force-stop $pkg", onLine = null)
        onLine(if (r.ok) "force-stop $pkg ok" else "force-stop $pkg failed rc=${r.exitCode}")
        return r.ok
    }

    // -- battery estimate -------------------------------------------------------
    //
    // HONEST HEURISTIC, not a fuel gauge: real per-app mAh needs BatteryStats
    // (system uid). We approximate drain as a function of foreground state and
    // an assumed wake pattern — clearly labelled "estimate" in the UI.

    private fun drainEstimate(state: String): Double = when (state) {
        "foreground" -> 4.5   // screen-share dominated
        "background" -> 1.2   // network + wakeups
        else -> 0.3           // cached
    }

    private fun labelFor(pm: PackageManager, pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}
