package dev.fortress.guard

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.FileObserver
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.fortress.ConsoleActivity
import dev.fortress.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

/** One realtime feed event, consumed by the Shield tab. */
data class GuardEvent(
    val type: String,       // MODULE_ADDED | MODULE_REMOVED | PACKAGE_ADDED | PACKAGE_REMOVED | CLIPBOARD
    val subject: String,
    val ts: Long = System.currentTimeMillis(),
) {
    companion object {
        const val TYPE_MODULE_ADDED = "MODULE_ADDED"
        const val TYPE_MODULE_REMOVED = "MODULE_REMOVED"
        const val TYPE_PACKAGE_ADDED = "PACKAGE_ADDED"
        const val TYPE_PACKAGE_REPLACED = "PACKAGE_REPLACED"
        const val TYPE_PACKAGE_REMOVED = "PACKAGE_REMOVED"
        const val TYPE_CLIPBOARD = "CLIPBOARD"
    }
}

/**
 * Foreground realtime monitor (specialUse FGS — see manifest property).
 *
 * Three watchers:
 *  1. FileObserver on /data/adb/modules — the standard Magisk drop-in dir; a
 *     CREATE/MOVED_TO there means a new module landed (rootkits love Magisk
 *     modules, so any arrival is worth a notification).
 *  2. PACKAGE_ADDED/REPLACED/REMOVED broadcast — registered at runtime so it
 *     only lives while the guard does. QUERY_ALL_PACKAGES lets us resolve any
 *     package's label; on Android 14+ we pass RECEIVER_NOT_EXPORTED because we
 *     only want SYSTEM broadcasts.
 *  3. Clipboard guard — Android 10+ deliberately blocks BACKGROUND apps from
 *     READING the clip (returns null unless focused). We therefore only listen
 *     for the CHANGE EVENT (still delivered) and record WHEN it happened; we
 *     never attempt to bypass the read restriction. Metadata-only by design.
 *
 * All events flow out via [events] AND surface as a notification for
 * module arrivals, since those change the device's privilege posture.
 */
class RealtimeGuard : android.app.Service() {

    private var moduleObserver: FileObserver? = null
    private var packageReceiver: BroadcastReceiver? = null
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    override fun onCreate() {
        super.onCreate()
        ConsoleActivity.createNotificationChannels(this)
        startModuleObserver()
        startPackageWatcher()
        startClipboardGuard()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // API 34+ requires an explicit FGS type; specialUse is declared in the
        // manifest with PROPERTY_SPECIAL_USE_FGS_SUBTYPE="security-monitoring".
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(getString(R.string.guard_notif_title)),
            if (Build.VERSION.SDK_INT >= 34)
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else 0,
        )
        isRunning = true
        return START_STICKY // guard must survive memory pressure
    }

    // -- watcher 1: Magisk module directory -----------------------------------

    private fun startModuleObserver() {
        val modulesDir = File("/data/adb/modules")
        if (!modulesDir.exists()) return // non-rooted device — nothing to watch
        // String-path constructor (API 1+): the File overloads are API 29+ and
        // this app's minSdk is 26 — the path string behaves identically.
        moduleObserver = object : FileObserver(modulesDir.absolutePath, CREATE or MOVED_TO or DELETE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                val removed = (event and DELETE) != 0
                val type = if (removed) GuardEvent.TYPE_MODULE_REMOVED else GuardEvent.TYPE_MODULE_ADDED
                GuardCenter.emit(GuardEvent(type, path))
                if (type == GuardEvent.TYPE_MODULE_ADDED) {
                    notifyUser("module installed: $path", "review it in Shield → Guard feed")
                }
            }
        }.also { it.startWatching() }
    }

    // -- watcher 2: package lifecycle -------------------------------------------

    private fun startPackageWatcher() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        packageReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val pkg = intent.data?.schemeSpecificPart ?: return
                val type = when (intent.action) {
                    Intent.ACTION_PACKAGE_ADDED -> GuardEvent.TYPE_PACKAGE_ADDED
                    Intent.ACTION_PACKAGE_REPLACED -> GuardEvent.TYPE_PACKAGE_REPLACED
                    else -> GuardEvent.TYPE_PACKAGE_REMOVED
                }
                GuardCenter.emit(GuardEvent(type, pkg))
            }
        }.also { receiver ->
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
        }
    }

    // -- watcher 3: clipboard change (metadata only, Android 10+ aware) ---------

    private fun startClipboardGuard() {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            // We cannot (and do not try to) read the clip in background on Q+;
            // emitting a timestamped event is the whole feature.
            GuardCenter.emit(GuardEvent(GuardEvent.TYPE_CLIPBOARD, "primary clip changed"))
        }.also { cm.addPrimaryClipChangedListener(it) }
    }

    // -- notifications ---------------------------------------------------------

    private fun buildNotification(title: String): Notification =
        NotificationCompat.Builder(this, ConsoleActivity.CHANNEL_GUARD)
            .setContentTitle(title)
            .setContentText(getString(R.string.guard_notif_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, ConsoleActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

    private fun notifyUser(title: String, text: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID + 1, NotificationCompat.Builder(this, ConsoleActivity.CHANNEL_GUARD)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true).build())
    }

    override fun onDestroy() {
        moduleObserver?.stopWatching()
        packageReceiver?.let { runCatching { unregisterReceiver(it) } }
        clipboardListener?.let {
            getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(it)
        }
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val ACTION_STOP = "dev.fortress.guard.STOP"
        const val NOTIF_ID = 1102

        @Volatile
        var isRunning: Boolean = false
            internal set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RealtimeGuard::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RealtimeGuard::class.java).setAction(ACTION_STOP))
        }
    }
}

/**
 * Re-arms the guard after reboot. Guarded by a SharedPreferences opt-in
 * ("guard_auto_start") so the user, not the app, decides persistence.
 * (Declared in the manifest under .guard.BootReceiver — kept in this file to
 * honor the documented delivery manifest.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        val prefs = context.getSharedPreferences("fortress_fw", Context.MODE_PRIVATE)
        if (prefs.getBoolean("guard_auto_start", false)) {
            RealtimeGuard.start(context)
        }
    }
}
