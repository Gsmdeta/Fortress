package dev.fortress.net

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import dev.fortress.ConsoleActivity
import dev.fortress.R
import dev.fortress.firewall.FirewallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream

/**
 * Packet tap built on Android's VpnService.
 *
 * WHAT it does: claims the 10.111.0.2/32 tunnel address, routes 0.0.0.0/0 into
 * the TUN fd, reads each IP packet, hands it to [TrafficMonitor] for
 * classification/attribution, and DROPS packets whose app matches a blocking
 * firewall rule (drop = simply not consumed/re-injected).
 *
 * WHY there is no userspace forwarding: a full TCP virtual stack (SYN
 * termination, per-flow protected sockets via protect(), sequence rewriting)
 * is out of scope for this defensive reference build — adding half a TCP stack
 * would add attack surface to the very tool meant to reduce it. Consequences:
 *   * blocked packets    → dropped (the firewall enforcement path),
 *   * allowed packets    → logged, then dropped as well; connectivity pauses
 *     while the tap is up. Treat it as a capture session, not a daily driver.
 *     When root is available, [FirewallManager.applyIptables] enforces blocks
 *     OUTSIDE the VPN and normal traffic keeps flowing.
 *
 * ANDROID 10+ CAVEATS: (a) VpnService.prepare() always shows the system consent
 * dialog; (b) other apps' /proc/net socket tables are hidden from our UID, so
 * packet→app attribution needs the root shell path (see TrafficMonitor);
 * (c) "Always-on VPN" from another app makes establish() fail with null.
 */
class PacketVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private lateinit var monitor: TrafficMonitor

    override fun onCreate() {
        super.onCreate()
        ConsoleActivity.createNotificationChannels(this)
        monitor = TrafficMonitor(this, FirewallManager(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTap()
            return START_NOT_STICKY
        }
        startForeground(
            NOTIF_ID,
            buildNotification(getString(R.string.vpn_notif_title), getString(R.string.vpn_notif_text))
        )
        scope.launch { runTap() }
        return START_STICKY
    }

    private suspend fun runTap() {
        // System consent gate — prepare() returns a pending Intent when the user
        // has not granted VPN permission to this app (or revoked it since).
        if (prepare(this) != null) {
            isRunning = false
            stopSelf()
            return
        }
        val fd = try {
            Builder()
                .setSession("fortress-tap")
                .addAddress("10.111.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .setMtu(MTU)
                .establish() ?: return // consent revoked / always-on conflict
        } catch (e: Exception) {
            isRunning = false
            stopSelf()
            return
        }
        tun = fd
        isRunning = true

        val input = FileInputStream(fd.fileDescriptor)
        val buffer = ByteArray(MTU)
        while (scope.isActive && isRunning) {
            val len = try {
                input.read(buffer) // blocking read on the TUN char device
            } catch (e: Exception) {
                break
            }
            if (len > 0) monitor.inspect(buffer, len)
        }
    }

    private fun buildNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, ConsoleActivity.CHANNEL_VPN)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, dev.fortress.ConsoleActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

    private fun stopTap() {
        isRunning = false
        runCatching { tun?.close() }
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { tun?.close() }
        tun = null
        scope.cancel()
        super.onDestroy()
    }

    /**
     * onRevoke fires when another VPN takes over or the user disconnects us
     * from the quick settings tile. We must release the fd promptly.
     */
    override fun onRevoke() {
        stopTap()
        super.onRevoke()
    }

    companion object {
        const val ACTION_STOP = "dev.fortress.net.STOP"
        const val MTU = 32768
        const val NOTIF_ID = 1101

        @Volatile
        var isRunning: Boolean = false
            internal set

        fun start(context: android.content.Context) {
            context.startForegroundService(
                Intent(context, PacketVpnService::class.java)
            )
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, PacketVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
