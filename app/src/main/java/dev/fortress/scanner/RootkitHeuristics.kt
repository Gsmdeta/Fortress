package dev.fortress.scanner

/**
 * Kotlin boundary for the fortress-native JNI heuristics plus pure-Kotlin
 * kernel-state checks. Everything here is DETECTION: read sysfs/procfs, compare,
 * report. No method in this class writes to any kernel or proc path.
 *
 * WHY safe* wrappers: the native library is compiled for arm ABIs only; on an
 * unsupported image (x86 emulator) System.loadLibrary throws, and any of the
 * readers may hit SecurityException on OEM kernels. The wrappers degrade to
 * null so DeepScanner can report "limited mode" instead of crashing.
 */
object RootkitHeuristics {

    init {
        // libfortress-native.so from app/src/main/cpp (CMake externalNativeBuild).
        System.loadLibrary("fortress-native")
    }

    /** Multi-line report; each line is prefixed '!' suspicious / '-' degraded / '+' ok. */
    external fun scanSyscallTable(): String

    /** Count of watched syscall handlers outside kernel text (-1 = cannot assess). */
    external fun checkInlineHooks(): Int

    /** Count of processes whose /task size disagrees with status Threads (-1 = n/a). */
    external fun detectHiddenThreads(): Int

    // -- safe wrappers used by DeepScanner ------------------------------------

    fun safeScanSyscallTable(): String? = runCatching { scanSyscallTable() }.getOrNull()

    fun safeCheckInlineHooks(): Int? = runCatching { checkInlineHooks() }.getOrNull()

    fun safeDetectHiddenThreads(): Int? = runCatching { detectHiddenThreads() }.getOrNull()

    // -- pure Kotlin checks (no native code) ----------------------------------

    /**
     * /proc/sys/kernel/sysrq — value + whether the app could open it for write.
     * A world-writable sysrq trigger is a classic persistence handle; we only
     * OBSERVE writability (canWrite does not modify anything).
     */
    fun sysrqState(): Pair<Int, Boolean>? = try {
        val f = java.io.File("/proc/sys/kernel/sysrq")
        if (!f.canRead()) null
        else f.readText().trim().toIntOrNull()?.let { v -> v to f.canWrite() }
    } catch (e: SecurityException) {
        null
    }

    /**
     * kallsyms readability: readable AND containing non-zero addresses means
     * kptr_restrict is relaxed — convenient for us, but also for rootkits.
     */
    fun kallsymsReadable(): Boolean = try {
        val f = java.io.File("/proc/kallsyms")
        f.canRead() && f.readLines().firstOrNull()?.startsWith("0000000000000000") == false
    } catch (e: SecurityException) {
        false
    } catch (e: java.io.IOException) {
        false
    }

    /**
     * Yama ptrace_scope: 0 allows any-process ptrace (weak), 1+ is the hardened
     * default. Reported as an exposure metric, never modified.
     */
    fun ptraceScope(): Int = try {
        java.io.File("/proc/sys/kernel/yama/ptrace_scope").readText().trim().toIntOrNull() ?: -1
    } catch (e: SecurityException) {
        -1
    } catch (e: java.io.IOException) {
        -1
    }
}
