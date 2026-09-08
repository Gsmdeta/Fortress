package dev.fortress.firewall

import android.content.Context
import android.content.SharedPreferences
import dev.fortress.root.RootShell
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-app network rules with TWO enforcement paths:
 *
 *  1. VPN path (no root): [verdictFor] is consulted by TrafficMonitor for every
 *     packet read off the TUN; `blocked` verdicts are never re-injected. The
 *     tap cannot see the physical interface, so wifi/data split rules are
 *     treated as "block on any interface" here.
 *  2. iptables path (root): [applyIptables] installs an owner-match DROP chain
 *     (FORTRESS_FW) with per-interface matches (wlan0 / rmnet+ / ccmni+), which
 *     is the reliable enforcement used by production Android firewalls.
 *
 * INTEGRITY GUARANTEE: rules only ever DROP traffic. No REDIRECT/DNAT/TPROXY
 * entries are ever installed — the firewall must not be able to silently
 * re-route the user's traffic, only refuse it.
 *
 * Persistence: SharedPreferences holds one string per package
 * ("wifi:1,data:0"); hit counters stay in memory until [flushHits] to keep the
 * packet path free of disk I/O.
 */
class FirewallManager(private val context: Context) {

    enum class Net { WIFI, DATA, BOTH }

    data class FirewallRule(
        val id: String,
        val app: String,              // package name
        val action: String,           // "allow" | "block"
        val net: String,              // "wifi" | "data" | "both"
        val enabled: Boolean,
        var hits: Int,
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("fortress_fw", Context.MODE_PRIVATE)

    private val hits = ConcurrentHashMap<String, Int>()

    // -- rule editing ----------------------------------------------------------

    fun setBlockRule(pkg: String, net: Net, enabled: Boolean) {
        if (!enabled) {
            prefs.edit().remove("rule:$pkg").apply()
            return
        }
        val encoded = when (net) {
            Net.WIFI -> "wifi:1,data:0"
            Net.DATA -> "wifi:0,data:1"
            Net.BOTH -> "wifi:1,data:1"
        }
        prefs.edit().putString("rule:$pkg", encoded).apply()
    }

    fun ruleFor(pkg: String): FirewallRule? {
        val raw = prefs.getString("rule:$pkg", null) ?: return null
        val wifi = raw.substringAfter("wifi:").substringBefore(',').toIntOrNull() ?: 0
        val data = raw.substringAfter("data:").toIntOrNull() ?: 0
        val net = when {
            wifi == 1 && data == 1 -> "both"
            wifi == 1 -> "wifi"
            else -> "data"
        }
        return FirewallRule(
            id = pkg,
            app = pkg,
            action = "block",
            net = net,
            enabled = true,
            hits = hits[pkg] ?: prefs.getInt("hits:$pkg", 0),
        )
    }

    fun rules(): List<FirewallRule> =
        prefs.all.keys.filter { it.startsWith("rule:") }
            .mapNotNull { ruleFor(it.removePrefix("rule:")) }

    // -- packet-path verdicts (called per packet — must stay cheap) ------------

    /**
     * Returns (verdict, reason). Unknown/unattributable flows are FLAGGED, not
     * allowed silently and not dropped (a rootless tap cannot read the socket
     * table; blocking on missing data would blind-freeze the device).
     */
    fun verdictFor(uid: Int, proto: String, port: Int): Pair<String, String?> {
        if (uid <= 0) {
            // uid 0 (root daemons) and -1 (unattributed) are observed, not policed.
            return if (uid == -1) "flagged" to "unattributed (limited /proc visibility)"
            else "allow" to null
        }
        val pkg = context.packageManager.getPackagesForUid(uid)?.firstOrNull()
            ?: return "flagged" to "uid $uid has no package mapping"
        val rule = ruleFor(pkg)
        if (rule == null) {
            recordHit(pkg, 0)
            return "allow" to null
        }
        recordHit(pkg, 1)
        return "blocked" to "rule ${rule.net} · $proto/$port"
    }

    fun labelForUid(uid: Int): String {
        if (uid <= 0) return if (uid == 0) "system (uid 0)" else "unknown (proc restricted)"
        val pm = context.packageManager
        val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: return "uid:$uid"
        return try {
            "${pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))}"
        } catch (e: Exception) {
            pkg
        }
    }

    private fun recordHit(pkg: String, delta: Int) {
        hits.merge(pkg, delta, Int::plus)
    }

    /** Persist hit counters (called from UI refresh, not the packet path). */
    fun flushHits() {
        val edit = prefs.edit()
        hits.forEach { (pkg, n) -> edit.putInt("hits:$pkg", n) }
        edit.apply()
    }

    // -- root enforcement --------------------------------------------------------

    /**
     * Installs the FORTRESS_FW chain. Idempotent: flushed then rebuilt on every
     * call, hooked into OUTPUT exactly once. Runs only when [rooted] is true —
     * without root this is a no-op and the VPN path carries enforcement.
     */
    suspend fun applyIptables(rooted: Boolean, onLine: (String) -> Unit = {}): Boolean {
        if (!rooted) {
            onLine("no root — VPN-path-only enforcement")
            return false
        }
        val cmds = mutableListOf(
            "iptables -N FORTRESS_FW 2>/dev/null || true",
            "iptables -F FORTRESS_FW",
        )
        for (rule in rules()) {
            val uid = context.packageManager.getPackageUid(rule.app, 0)
            // One rule per interface prefix — iptables accepts only a single -o
            // per rule, and carrier OEMs name their modem ifaces differently.
            val ifaces = when (rule.net) {
                "wifi" -> listOf("wlan0+")
                "data" -> listOf("rmnet+", "ccmni+", "usb+") // qualcomm / mtk / tether
                else -> listOf("", "", "") // empty -o = any interface, one rule only
            }.distinct()
            for (iface in ifaces) {
                val o = if (iface.isEmpty()) "" else "-o $iface"
                // Owner match needs the UID, not the package name (iptables has
                // no cgroup match on stock kernels).
                cmds += "iptables -A FORTRESS_FW $o -m owner --uid-owner $uid -j DROP"
            }
        }
        cmds += "iptables -C OUTPUT -j FORTRESS_FW 2>/dev/null || iptables -A OUTPUT -j FORTRESS_FW"

        var allOk = true
        for (c in cmds) {
            val r = RootShell.exec(c, onLine = null, timeoutMs = 8_000)
            if (!r.ok && !c.endsWith("|| true")) {
                allOk = false
                onLine("iptables: '$c' failed rc=${r.exitCode}")
            }
        }
        onLine(if (allOk) "FORTRESS_FW chain installed (${rules().size} drop rules)" else "chain installed with errors")
        return allOk
    }

    /** Removes every trace of our chain (called on shield-off / uninstall intent). */
    suspend fun clearIptables(onLine: (String) -> Unit = {}) {
        RootShell.exec("iptables -D OUTPUT -j FORTRESS_FW 2>/dev/null; iptables -F FORTRESS_FW 2>/dev/null; iptables -X FORTRESS_FW 2>/dev/null", onLine = onLine)
        onLine("FORTRESS_FW chain removed")
    }
}
