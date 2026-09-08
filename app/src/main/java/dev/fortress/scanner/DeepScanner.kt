package dev.fortress.scanner

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import dev.fortress.root.RootShell
import java.io.File

/**
 * Four-phase suspend scan engine. Emits [ScanProgress] through a StateFlow so
 * ScannerFragment (and any future dashboard widget) can render live progress.
 *
 * ANDROID 10+ /proc NOTE: since Android 10, the kernel's hidepid-style
 * protections plus SELinux `proc` restrictions mean an untrusted app can no
 * longer read other processes' /proc/<pid>/cmdline, /maps or /task. Every
 * phase therefore has two paths:
 *   * unrooted — only self-visible data is read; findings are tagged with a
 *     degraded confidence and the UI shows "limited mode";
 *   * rooted — reads are re-attempted through [RootShell] (`su` stream), which
 *     restores full /proc visibility. All shell use here is read-only.
 */
class DeepScanner(private val context: Context) {

    sealed class ScanProgress {
        /** Emitted when no scan is running (initial state). */
        object Idle : ScanProgress()

        data class Phase(val index: Int, val total: Int, val label: String) : ScanProgress()

        /** Single line of phase detail (process/map/module observation). */
        data class Detail(val phase: Int, val text: String) : ScanProgress()

        data class Finding(
            val severity: Severity,
            val title: String,
            val detail: String,
        ) : ScanProgress()

        data class Finished(val score: Int, val verdict: String, val findings: Int) : ScanProgress()
    }

    enum class Severity { INFO, WARN, CRITICAL }

    private val _progress = MutableStateFlow<ScanProgress>(ScanProgress.Idle)
    val progress: StateFlow<ScanProgress> = _progress

    private val findings = mutableListOf<ScanProgress.Finding>()

    private suspend fun emit(p: ScanProgress) {
        _progress.value = p
    }

    /** Runs all four phases sequentially; safe to call from any scope. */
    suspend fun runScan(): ScanProgress.Finished = withContext(Dispatchers.IO) {
        findings.clear()
        val rooted = RootShell.isAvailable()
        emit(ScanProgress.Detail(0, if (rooted) "root session acquired — deep mode" else "no root — limited mode"))

        phase1Processes(rooted)
        phase2MemoryMaps(rooted)
        phase3FileAudit(rooted)
        phase4RootkitHeuristics(rooted)

        val deduction = findings.sumOf { f ->
            when (f.severity) {
                Severity.INFO -> 2
                Severity.WARN -> 10
                Severity.CRITICAL -> 25
            }
        }
        val score = (100 - deduction).coerceIn(0, 100)
        val verdict = when {
            score >= 85 -> "CLEAN"
            score >= 55 -> "SUSPICIOUS"
            else -> "CRITICAL"
        }
        val done = ScanProgress.Finished(score, verdict, findings.size)
        emit(done)
        done
    }

    fun reset() {
        _progress.value = ScanProgress.Idle
    }

    // -- phase 1: process walk (/proc/<pid>/cmdline) ------------------------

    /** Name fragments that justify elevating a process to a WARN finding.
     *  Detection only: we observe and report, we never kill on a match. */
    private val suspiciousProcNames = listOf(
        "zygisk", "shamiko", "magiskhide", "hideproc", "denyproc",
    )

    private suspend fun phase1Processes(rooted: Boolean) {
        emit(ScanProgress.Phase(1, 4, "process walk /proc/*/cmdline"))
        val pids = File("/proc").listFiles()?.filter { it.name.all(Char::isDigit) } ?: emptyList()
        var inspected = 0
        for (pidDir in pids) {
            val cmdline = readProcFile(pidDir.resolve("cmdline"), rooted) ?: continue
            inspected++
            val name = cmdline.trim().replace('\u0000', ' ').ifBlank { "[unnamed ${pidDir.name}]" }
            if (suspiciousProcNames.any { name.lowercase().contains(it) }) {
                addFinding(
                    Severity.WARN,
                    "suspicious process",
                    "pid ${pidDir.name}: $name matches hide/anti-detect pattern"
                )
            }
        }
        emit(ScanProgress.Detail(1, "inspected $inspected/${pids.size} readable processes"))
    }

    // -- phase 2: memory maps heuristics ------------------------------------

    private suspend fun phase2MemoryMaps(rooted: Boolean) {
        emit(ScanProgress.Phase(2, 4, "memory maps heuristics"))
        // Self maps are always readable; other pids only with root (see class doc).
        val targets = mutableListOf("self")
        if (rooted) {
            // NOTE: onLine is a named argument here because it is not the last
            // parameter of RootShell.exec — trailing-lambda syntax would bind
            // to the wrong parameter.
            RootShell.exec(
                "ls /proc | grep -E '^[0-9]+\$' | head -50",
                onLine = { line -> if (line.all(Char::isDigit)) targets.add(line) },
            )
        }
        var rwxTotal = 0
        for (pid in targets.take(20)) { // bounded walk keeps the scan under ~2s
            val maps = readProcFile(File("/proc/$pid/maps"), rooted) ?: continue
            val rwx = maps.lineSequence()
                .filter { it.contains("rwx") && !it.contains('/') } // anon + exec-writable
                .count()
            if (rwx > 0) {
                rwxTotal += rwx
                addFinding(
                    Severity.WARN,
                    "anonymous RWX mapping",
                    "pid $pid has $rwx anon rwx mapping(s) — hook/trampoline signature"
                )
            }
        }
        emit(ScanProgress.Detail(2, "rwx anon mappings total: $rwxTotal"))
    }

    // -- phase 3: file audit -------------------------------------------------

    private suspend fun phase3FileAudit(rooted: Boolean) {
        emit(ScanProgress.Phase(3, 4, "file audit /system/priv-app + /data/adb/modules"))
        val privApp = File("/system/priv-app")
        val privDirs = privApp.listFiles()?.size ?: 0
        emit(ScanProgress.Detail(3, "priv-app entries: $privDirs"))
        for (su in listOf("/system/bin/su", "/system/xbin/su", "/sbin/su")) {
            if (File(su).exists()) {
                addFinding(Severity.INFO, "su binary", "$su present (root management expected on lab devices)")
            }
        }

        // /data/adb/modules is only traversable with root; a SecurityException on
        // non-rooted devices is the normal path, not an error condition.
        try {
            val modulesDir = File("/data/adb/modules")
            val modules: Array<File>? = modulesDir.listFiles()
            if (modules != null) {
                for (m in modules) {
                    val disableFlag = File(m, "disable").exists()
                    emit(ScanProgress.Detail(3, "module ${m.name}${if (disableFlag) " [disabled]" else ""}"))
                    if (m.name.lowercase().contains("shamiko") || m.name.lowercase().contains("hide")) {
                        addFinding(Severity.WARN, "hide-capable module", "${m.name} can conceal root state from scanners")
                    }
                }
            }
        } catch (e: SecurityException) {
            emit(ScanProgress.Detail(3, "/data/adb/modules not readable without root"))
        }
    }

    // -- phase 4: native rootkit heuristics ----------------------------------

    private suspend fun phase4RootkitHeuristics(rooted: Boolean) {
        emit(ScanProgress.Phase(4, 4, "rootkit heuristics (fortress-native)"))
        val report = RootkitHeuristics.safeScanSyscallTable()
        report?.lineSequence()?.forEach { line ->
            val text = line.drop(1) // strip +/!/- marker
            when (line.firstOrNull()) {
                '!' -> addFinding(Severity.CRITICAL, "kernel anomaly", text)
                '-' -> emit(ScanProgress.Detail(4, "degraded: $text"))
                else -> emit(ScanProgress.Detail(4, text))
            }
        } ?: emit(ScanProgress.Detail(4, "native lib unavailable — userspace checks only"))

        RootkitHeuristics.safeCheckInlineHooks()?.let { hooks ->
            if (hooks > 0) addFinding(Severity.CRITICAL, "syscall outliers", "$hooks watched syscalls point outside kernel text")
        }
        RootkitHeuristics.safeDetectHiddenThreads()?.let { hidden ->
            if (hidden > 0) addFinding(Severity.WARN, "task/status mismatch", "$hidden process(es) disagree on thread count")
        }
    }

    // -- helpers --------------------------------------------------------------

    private fun addFinding(severity: Severity, title: String, detail: String) {
        val f = ScanProgress.Finding(severity, title, detail)
        findings += f
        _progress.value = f // findings double as progress events
    }

    /** Reads a proc file directly, retrying through the root shell when the
     *  direct read is denied and root is available. Returns null if unreadable. */
    private suspend fun readProcFile(file: File, rooted: Boolean): String? {
        return try {
            file.readText()
        } catch (e: SecurityException) {
            if (!rooted) return null
            RootShell.exec("cat ${file.absolutePath}").output.joinToString("\n").ifEmpty { null }
        } catch (e: java.io.IOException) {
            null // process died mid-read — ignore
        }
    }
}
