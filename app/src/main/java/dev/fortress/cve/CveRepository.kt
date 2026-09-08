package dev.fortress.cve

import android.content.Context
import dev.fortress.root.RootShell
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bundled CVE catalog (assets/cve-db.json, mirrored 1:1 from the web console's
 * CVE_CATALOG) plus LOCAL MITIGATION stubs.
 *
 * WHAT "software patch" means here: on-device hardening commands (sysctl,
 * chmod, chcon, settings) that REDUCE EXPOSURE to the vulnerability until the
 * real fix ships via OEM/OTA. We never binary-patch a framework or vendor
 * image — that would be tampering, not defense, and could brick the device.
 * Entries whose fix cannot be approximated locally (modem/WLAN firmware,
 * framework internals) report otaOnly instead of pretending to patch.
 */
class CveRepository(private val context: Context) {

    data class CveEntry(
        val id: String,
        val component: String,
        val severity: String,      // critical | high | medium | low
        val cvss: Double,
        val desc: String,
        val patched: Boolean,
        val softwarePatchAvailable: Boolean,
        val androidVersion: String,
    )

    private var cache: List<CveEntry>? = null

    /** Loads and caches the bundled catalog. Never hits the network. */
    fun load(): List<CveEntry> {
        cache?.let { return it }
        val raw = context.assets.open("cve-db.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(raw)
        val entries = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CveEntry(
                id = o.getString("id"),
                component = o.getString("component"),
                severity = o.getString("severity"),
                cvss = o.getDouble("cvss"),
                desc = o.getString("desc"),
                patched = o.optBoolean("patched", false),
                softwarePatchAvailable = o.optBoolean("softwarePatchAvailable", false),
                androidVersion = o.getString("androidVersion"),
            )
        }
        cache = entries
        return entries
    }

    /** Entries the console surfaces as actionable (unpatched + locally mitigable). */
    fun actionable(): List<CveEntry> = load().filter { !it.patched && it.softwarePatchAvailable }

    // -- local mitigation stubs -------------------------------------------------

    /**
     * CVE → mitigation command mapping. Every command is defensive: it only
     * restricts access (chmod/chcon), flips a hardening sysctl, or stops a
     * service. Unknown/unmapped ids return a failure with reason, never guess.
     */
    private fun mitigationFor(id: String): List<String> = when (id) {
        // adb backup runAs abuse — disable the adb surface until OTA
        "CVE-2024-0044" -> listOf("settings put global adb_enabled 0")

        // procfs kernel-memory leak — tighten kernel pointer/dmesg exposure
        "CVE-2022-22071" -> listOf(
            "sysctl -w kernel.kptr_restrict=2",
            "sysctl -w kernel.dmesg_restrict=1",
            "chmod 0400 /proc/kallsyms",
        )

        // WebView UAF — restart the WebView process to clear poisoned renderers;
        // the durable fix is the Play-updated WebView, which we surface in UI
        "CVE-2023-40082" -> listOf("am force-stop com.google.android.webview")

        // OEM SELinux policy hole — restore default contexts on vendor binaries
        "CVE-2024-27197" -> listOf("restorecon -Rv /vendor/bin/hw")

        // framework/modem/firmware entries: NO local approximation is honest.
        else -> emptyList()
    }

    /** Result of an attempted local mitigation. */
    data class PatchResult(val success: Boolean, val message: String)

    suspend fun applySoftwarePatch(entry: CveEntry, onLine: (String) -> Unit): PatchResult {
        val cmds = mitigationFor(entry.id)
        if (cmds.isEmpty()) {
            return PatchResult(
                success = false,
                message = "${entry.id}: no local mitigation — requires OEM/OTA update",
            )
        }
        onLine("applying local mitigation for ${entry.id} (${cmds.size} step(s))")
        for (cmd in cmds) {
            val r = RootShell.exec(cmd, onLine = null, timeoutMs = 8_000)
            if (!r.ok) {
                onLine("!! '$cmd' failed rc=${r.exitCode}")
                return PatchResult(false, "$cmd failed (rc=${r.exitCode})")
            }
            onLine("ok: $cmd")
        }
        return PatchResult(true, "${entry.id}: ${cmds.size} mitigation(s) applied")
    }

    companion object {
        /** Severity ordering used by the CVE tab sort. */
        val SEVERITY_RANK = mapOf("critical" to 0, "high" to 1, "medium" to 2, "low" to 3)
    }
}
