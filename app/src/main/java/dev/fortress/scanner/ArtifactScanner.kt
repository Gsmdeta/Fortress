package dev.fortress.scanner

import android.content.Context
import dev.fortress.root.RootShell
import dev.fortress.vt.VirusTotalClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Bounded deep artifact sweep. It inventories user-visible and root-owned
 * files, hashes small candidates, and checks high-signal malware indicators.
 * It deliberately does not delete or quarantine anything automatically.
 */
class ArtifactScanner(private val context: Context) {

    data class Result(val scanned: Int, val bytes: Long, val findings: List<DeepScanner.ScanProgress.Finding>)

    private val suspiciousNames = listOf(
        "frida", "frida-gadget", "meterpreter", "metasploit", "payload",
        "inject", "ransom", "spyware", "rat-client", "xposed-hook",
    )
    private val suspiciousMarkers = listOf(
        "frida-gadget", "meterpreter", "metasploit", "reverse_tcp", "credential-dump",
    )
    private val virusTotal = VirusTotalClient(context)

    suspend fun scan(rooted: Boolean, onDetail: suspend (String) -> Unit): Result = withContext(Dispatchers.IO) {
        val candidates = if (rooted) rootCandidates() else localCandidates()
        val findings = mutableListOf<DeepScanner.ScanProgress.Finding>()
        var scanned = 0
        var bytes = 0L
        for (path in candidates.take(MAX_FILES)) {
            val file = File(path)
            val length = if (rooted) rootSize(path) else file.length()
            if (length < 0 || length > MAX_FILE_BYTES) continue
            val lower = path.lowercase()
            val nameHit = suspiciousNames.any { lower.contains(it) }
            val digest = if (rooted) rootSha256(path) else sha256(file)
            val markerHit = if (rooted) rootMarkerHit(path) else localMarkerHit(file)
            val vt = if ((nameHit || markerHit) && digest != null && virusTotal.hasKey()) {
                virusTotal.lookupByHash(digest)
            } else null
            scanned++
            bytes += length
            if (scanned % 25 == 0) onDetail("artifact sweep: $scanned files · ${bytes / (1024 * 1024)} MiB")
            if (nameHit || markerHit) {
                val vtDetail = vt?.let { " · VT ${it.malicious} malicious/${it.suspicious} suspicious" }.orEmpty()
                findings += DeepScanner.ScanProgress.Finding(
                    if ((vt?.malicious ?: 0) > 0) DeepScanner.Severity.CRITICAL else DeepScanner.Severity.WARN,
                    "suspicious artifact",
                    "${File(path).name} · ${digest ?: "hash unavailable"}$vtDetail · review before removal",
                )
            }
        }
        onDetail("artifact sweep complete: $scanned files · ${bytes / (1024 * 1024)} MiB")
        Result(scanned, bytes, findings)
    }

    private fun localCandidates(): List<String> = buildList {
        listOf("/storage/emulated/0", "/sdcard", "/data/local/tmp").forEach { root ->
            File(root).walkTopDown()
                .onEnter { it.canRead() }
                .filter { it.isFile && it.length() <= MAX_FILE_BYTES }
                .take(MAX_FILES)
                .forEach { add(it.absolutePath) }
        }
    }.distinct()

    private suspend fun rootCandidates(): List<String> = RootShell.exec(
        "find /storage/emulated/0 /data/local/tmp /data/adb/modules /system/app /system/priv-app -type f -size -20M 2>/dev/null | head -300"
    ).output.filter { it.isNotBlank() }.distinct()

    private suspend fun rootSize(path: String): Long = RootShell.exec("stat -c %s ${quote(path)} 2>/dev/null").output
        .lastOrNull()?.trim()?.toLongOrNull() ?: -1L

    private suspend fun rootSha256(path: String): String? = RootShell.exec("sha256sum ${quote(path)} 2>/dev/null").output
        .lastOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull()

    private suspend fun rootMarkerHit(path: String): Boolean = RootShell.exec(
        "head -c 65536 ${quote(path)} 2>/dev/null | strings | grep -E -i '${suspiciousMarkers.joinToString("|")}'"
    ).output.isNotEmpty()

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (total < MAX_FILE_BYTES) {
                val read = input.read(buffer).takeIf { it > 0 } ?: break
                digest.update(buffer, 0, read)
                total += read
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun localMarkerHit(file: File): Boolean = runCatching {
        val buffer = ByteArray(64 * 1024)
        val count = file.inputStream().use { input ->
            var offset = 0
            while (offset < buffer.size) {
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read <= 0) break
                offset += read
            }
            offset
        }
        val sample = buffer.copyOf(count).toString(Charsets.UTF_8)
            .lowercase()
        suspiciousMarkers.any { sample.contains(it) }
    }.getOrDefault(false)

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val MAX_FILES = 300
        const val MAX_FILE_BYTES = 20L * 1024L * 1024L
    }
}
