package dev.fortress.net

import android.content.Context
import dev.fortress.firewall.FirewallManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.util.UUID

/**
 * Minimal IP/TCP/UDP header parser + per-app attribution.
 *
 * ATTRIBUTION PIPELINE (why /proc/net is involved at all): a TUN packet only
 * carries 5-tuple info. To say WHICH app sent it we match the source port in
 * the kernel socket tables (/proc/net/{tcp,tcp6,udp,udp6}), read the socket
 * inode, and map inode→UID.
 *
 * ANDROID 10+ RESTRICTION: for apps targeting Q+, the kernel filters these
 * tables to the caller's own UID. Fortress handles this two ways:
 *   * root available → a batched `su cat` sweep sees every socket on the device;
 *   * no root        → only Fortress's own sockets resolve; everything else is
 *     reported with uid = -1 and label "unknown (proc restricted)". We never
 *     guess an attribution we cannot actually read.
 *
 * Threading: inspect() is called from the TUN read loop (single thread), so the
 * uid-table refresh is deliberately synchronous and throttled to 1/10s — no
 * locks needed, and the packet path never awaits a coroutine.
 */
class TrafficMonitor(
    @Suppress("unused") private val context: Context,
    private val firewall: FirewallManager,
) {

    /** Mirrors the web console PacketEvent contract (proto/verdict stringified). */
    data class PacketEvent(
        val id: String = UUID.randomUUID().toString(),
        val ts: Long = System.currentTimeMillis(),
        val app: String,
        val uid: Int,
        val dst: String,
        val port: Int,
        val proto: String,            // TCP | UDP | ICMP
        val bytes: Int,
        val verdict: String,          // allow | flagged | blocked
        val reason: String? = null,
    )

    private val _events = MutableSharedFlow<PacketEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<PacketEvent> = _events

    // Key = LOCAL PORT from the socket tables, value = owning UID. (The tables
    // carry the inode in a later column, but port→uid is all verdicts need.)
    private var inodeToUid: Map<Int, Int> = emptyMap()
    private var tableRefreshedAt: Long = 0L

    /** Hot-path entry point from the TUN read loop. */
    fun inspect(packet: ByteArray, len: Int) {
        if (len < 20) return
        when ((packet[0].toInt() shr 4) and 0xF) {
            4 -> inspectIpv4(packet, len)
            6 -> inspectIpv6(packet, len)
        }
    }

    // -- IPv4 -----------------------------------------------------------------

    private fun inspectIpv4(p: ByteArray, len: Int) {
        val ihl = (p[0].toInt() and 0xF) * 4
        if (len < ihl) return
        val protoNum = p[9].toInt() and 0xFF
        val proto = when (protoNum) {
            6 -> "TCP"; 17 -> "UDP"; 1 -> "ICMP"; else -> return
        }
        val dst = "%d.%d.%d.%d".format(
            p[16].toInt() and 0xFF, p[17].toInt() and 0xFF,
            p[18].toInt() and 0xFF, p[19].toInt() and 0xFF
        )
        // ICMP has no ports; TCP/UDP carry src(be16 @ihl) and dst(be16 @ihl+2).
        var srcPort = 0; var dstPort = 0
        if (protoNum != 1) {
            if (len < ihl + 4) return
            srcPort = ((p[ihl].toInt() and 0xFF) shl 8) or (p[ihl + 1].toInt() and 0xFF)
            dstPort = ((p[ihl + 2].toInt() and 0xFF) shl 8) or (p[ihl + 3].toInt() and 0xFF)
        }
        val uid = attribute(srcPort)
        val (verdict, reason) = firewall.verdictFor(uid, proto, dstPort)
        emit(PacketEvent(labelFor(uid), uid, dst, dstPort, proto, len, verdict, reason))
    }

    // -- IPv6 -----------------------------------------------------------------

    /** Fixed 40-byte base header; extension-header chains are counted but not
     *  parsed (documented tap limitation — mis-parsing would be worse). */
    private fun inspectIpv6(p: ByteArray, len: Int) {
        if (len < 44) return
        val nextHeader = p[6].toInt() and 0xFF
        if (nextHeader != 6 && nextHeader != 17) return
        val base = 40
        val proto = if (nextHeader == 6) "TCP" else "UDP"
        val dst = (0 until 16).joinToString(":") { "%02x".format(p[24 + it].toInt() and 0xFF) }
        val srcPort = ((p[base].toInt() and 0xFF) shl 8) or (p[base + 1].toInt() and 0xFF)
        val dstPort = ((p[base + 2].toInt() and 0xFF) shl 8) or (p[base + 3].toInt() and 0xFF)
        val uid = attribute(srcPort)
        val (verdict, reason) = firewall.verdictFor(uid, proto, dstPort)
        emit(PacketEvent(labelFor(uid), uid, dst, dstPort, proto, len, verdict, reason))
    }

    // -- attribution ----------------------------------------------------------

    /** Port→UID lookup with a 10s refresh throttle. */
    private fun attribute(srcPort: Int): Int {
        val now = System.currentTimeMillis()
        if (now - tableRefreshedAt > 10_000) {
            refreshTable()
            tableRefreshedAt = now
        }
        if (srcPort == 0) return -1
        return inodeToUid[srcPort] ?: -1
    }

    private fun refreshTable() {
        val map = mutableMapOf<Int, Int>()
        for (tbl in listOf("tcp", "tcp6", "udp", "udp6")) {
            val f = File("/proc/net/$tbl")
            val lines = try {
                f.readLines()
            } catch (e: SecurityException) {
                continue // Android 10+ filtering — shell sweep below may still see it
            } catch (e: java.io.IOException) {
                continue
            }
            parseTable(lines, map)
        }
        if (map.isEmpty()) {
            // Direct reads yielded nothing: batched root sweep (read-only).
            parseTable(
                runShellBlocking("cat /proc/net/tcp /proc/net/tcp6 /proc/net/udp /proc/net/udp6"),
                map,
            )
        }
        inodeToUid = map
    }

    /**
     * Column layout: sl local_address rem_address st tx:rx tr:tm when
     * retrnsmt uid timeout inode. We key purely on the local port — the tables
     * cover both IPv4/IPv6 variants, and verdict logic only needs the UID.
     */
    private fun parseTable(lines: List<String>, into: MutableMap<Int, Int>) {
        for (line in lines.drop(1)) {
            val c = line.trim().split(Regex("\\s+"))
            if (c.size < 10) continue
            val localPort = c[1].substringAfterLast(':').toIntOrNull(16) ?: continue
            val uid = c[7].toIntOrNull() ?: continue
            val inode = c[9]
            if (inode == "0") continue // TIME_WAIT / unbound sockets carry no owner
            into.putIfAbsent(localPort, uid)
        }
    }

    private fun runShellBlocking(cmd: String): List<String> = try {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = proc.inputStream.bufferedReader().readLines()
        proc.waitFor()
        out
    } catch (e: Exception) {
        emptyList()
    }

    private fun labelFor(uid: Int): String = firewall.labelForUid(uid)

    private fun emit(event: PacketEvent) {
        _events.tryEmit(event) // drop-on-overflow: the feed is advisory, not a queue
    }
}
