package dev.fortress.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicLong

/**
 * Singleton `su` shell.
 *
 * WHY a persistent stream instead of `su -c <cmd>` per call: every `su`
 * invocation triggers a Magisk/KernelSU prompt; a single long-lived session
 * asks once and then streams commands over stdin. Commands are serialised by
 * a mutex because a `su` process multiplexes stdout across writers.
 *
 * ETHICAL CONTRACT: RootShell is for READ-ONLY state inspection (kallsyms,
 * /proc, module listings) and DEFENSIVE rule application (iptables owner-drop,
 * sysctl hardening, chmod/chcon mitigations in CveRepository). Nothing in the
 * codebase spawns offensive tooling through it; keep it that way.
 */
object RootShell {

    private val mutex = Mutex()

    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private var stdout: BufferedReader? = null

    private const val SENTINEL = "__FORTRESS_RC_"
    private const val TAG = "RootShell"
    private val commandSequence = AtomicLong(0)

    /** Result envelope for a completed (or timed-out) command. */
    data class ShellResult(
        val exitCode: Int,
        val output: List<String>,
        val timedOut: Boolean = false,
    ) {
        val ok: Boolean get() = !timedOut && exitCode == 0
    }

    /** Cheap liveness probe: `id` should answer with uid=0 through the su stream. */
    suspend fun isAvailable(): Boolean = try {
        exec("id", timeoutMs = 4_000).output.any { it.contains("uid=0") }
    } catch (e: Exception) {
        false
    }

    /**
     * Executes [cmd] through the su stream, streaming each stdout line to
     * [onLine] as it arrives (used to animate the scanner console). Returns
     * the command's exit code parsed from the sentinel marker.
     */
    suspend fun exec(
        cmd: String,
        onLine: ((String) -> Unit)? = null,
        timeoutMs: Long = 10_000,
    ): ShellResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (cmd.isBlank() || cmd.length > MAX_COMMAND_LENGTH ||
                cmd.any { it == '\u0000' || it == '\r' || it == '\n' }) {
                return@withContext ShellResult(
                    exitCode = 126,
                    output = listOf("rejected unsafe root command"),
                )
            }
            val proc = ensureShell()
            val sentinel = "$SENTINEL${commandSequence.incrementAndGet()}_"

            // Sentinel trick: echo the exit code with a unique token so we know
            // exactly where OUR command's output ends inside the shared stream.
            stdin!!.apply {
                write("($cmd); echo $sentinel\$?\n")
                flush()
            }

            val lines = mutableListOf<String>()
            val rc = withTimeoutOrNull(timeoutMs) {
                var exit = -1
                while (true) {
                    val line = stdout!!.readLine() ?: break
                    if (line.startsWith(sentinel)) {
                        exit = line.removePrefix(sentinel).trim().toIntOrNull() ?: -1
                        break
                    }
                    lines += line
                    onLine?.invoke(line)
                }
                exit
            }

            if (rc == null) {
                // Timed out: the stream state is ambiguous (partial output), so
                // we tear the shell down. The next exec() re-spawns a fresh su.
                android.util.Log.w(TAG, "timeout: $cmd")
                killShell()
                ShellResult(exitCode = -1, output = lines, timedOut = true)
            } else {
                ShellResult(exitCode = rc, output = lines)
            }
        }
    }

    /**
     * Magisk module install — streams `magisk --install-module <zip>` output
     * line by line so MagiskInstaller can render installer progress.
     */
    suspend fun magiskInstallModule(zipPath: String, onLine: (String) -> Unit): ShellResult =
        exec("magisk --install-module \"$zipPath\"", onLine = onLine, timeoutMs = 60_000)

    private fun ensureShell(): Process {
        process?.let { p ->
            if (p.isAlive) return p
            killShell()
        }
        android.util.Log.i(TAG, "spawning su session")
        val p = ProcessBuilder("su")
            .redirectErrorStream(true)
            .start()
        process = p
        stdin = BufferedWriter(OutputStreamWriter(p.outputStream))
        stdout = BufferedReader(InputStreamReader(p.inputStream))
        return p
    }

    private fun killShell() {
        runCatching { process?.destroy() }
        process = null
        stdin = null
        stdout = null
    }

    private const val MAX_COMMAND_LENGTH = 4096
}
