package dev.fortress.scanner

import android.content.Context
import dev.fortress.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Explicit operator-controlled isolation for evidence paths. The allowlist
 * prevents accidental writes to system partitions; no automatic quarantine is
 * performed from a scan result.
 */
object QuarantineManager {

    data class Result(val ok: Boolean, val message: String)

    suspend fun isolate(context: Context, rawPath: String): Result = withContext(Dispatchers.IO) {
        val path = rawPath.trim()
        if (path.isBlank() || path.any { it == '\u0000' || it == '\n' || it == '\r' }) {
            return@withContext Result(false, "invalid path")
        }
        if (!allowed(path)) {
            return@withContext Result(false, "blocked: only user storage, /data/local/tmp, or /data/adb/modules may be isolated")
        }

        val quarantineDir = File(context.filesDir, "quarantine").apply { mkdirs() }
        val name = File(path).name.ifBlank { "evidence" }
        val destination = File(quarantineDir, "${System.currentTimeMillis()}_$name")
        val rooted = RootShell.isAvailable()

        if (rooted) {
            val command = "mkdir -p ${quote(quarantineDir.absolutePath)} && " +
                "cp -p -- ${quote(path)} ${quote(destination.absolutePath)} && " +
                "chmod 000 ${quote(destination.absolutePath)} && " +
                "rm -f -- ${quote(path)}"
            val result = RootShell.exec(command, timeoutMs = 30_000)
            if (!result.ok) {
                return@withContext Result(false, "root isolation failed (rc=${result.exitCode})")
            }
        } else {
            val source = File(path)
            if (!source.isFile || !source.canRead()) {
                return@withContext Result(false, "file is not readable without root")
            }
            val copyError = runCatching {
                source.copyTo(destination, overwrite = false)
                if (!source.delete()) error("source delete rejected")
                destination.setReadable(false, false)
                destination.setWritable(false, false)
            }.exceptionOrNull()
            if (copyError != null) {
                destination.delete()
                return@withContext Result(false, "isolation failed: ${copyError.message ?: "copy error"}")
            }
        }

        Result(true, "isolated to ${destination.name}; original path removed")
    }

    private fun allowed(path: String): Boolean = listOf(
        "/storage/emulated/0/",
        "/sdcard/",
        "/data/local/tmp/",
        "/data/adb/modules/",
    ).any { path.startsWith(it) }

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}
