package dev.fortress

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.fortress.ui.BootSequenceOverlay
import dev.fortress.ui.ConsoleColors
import dev.fortress.ui.ConsoleTabBar
import dev.fortress.ui.DashboardTab
import dev.fortress.ui.FortressConsoleTheme
import dev.fortress.ui.OfflinePanel
import dev.fortress.ui.PinLock
import dev.fortress.ui.PinLockScreen
import dev.fortress.ui.ScannerTab

/**
 * Main console — now the Compose port of the web command center.
 *
 * Launch flow mirrors the demo: boot sequence overlay (staggered [ok] lines
 * over REAL triage state from MainActivity) → PIN lock gate → 6-tab console.
 *
 * The console is a Compose tab shell; each tab collects its live engine
 * streams (ScanCenter, later RealtimeGuard/TrafficMonitor) so switching tabs
 * never stops running work. Tab screens are wired phase by phase:
 * SCANNER is live in this build; the remaining tabs show an honest OFFLINE
 * panel until their phase lands.
 */
class ConsoleActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannels(this)

        val rooted = intent.getBooleanExtra(MainActivity.EXTRA_ROOTED, false)
        val magisk = intent.getBooleanExtra(MainActivity.EXTRA_MAGISK, false)

        setContent {
            FortressConsoleTheme {
                FortressConsoleApp(
                    rooted = rooted,
                    magisk = magisk,
                    versionName = BuildConfig.VERSION_NAME,
                )
            }
        }
    }

    companion object {
        const val CHANNEL_GUARD = "fortress_guard"
        const val CHANNEL_VPN = "fortress_vpn"
        const val CHANNEL_SCAN = "fortress_scan"

        /** Channels are created idempotently here AND in the services, because a
         *  service can be started before the activity ever runs (boot receiver). */
        fun createNotificationChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val channels = listOf(
                NotificationChannel(CHANNEL_GUARD, context.getString(R.string.channel_guard),
                    NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_VPN, context.getString(R.string.channel_vpn),
                    NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_SCAN, context.getString(R.string.channel_scan),
                    NotificationManager.IMPORTANCE_DEFAULT),
            )
            channels.forEach(nm::createNotificationChannel)
        }
    }
}

private val TABS = listOf("DASH", "SCAN", "NET", "SHIELD", "CVE", "FORENSICS")

@Composable
private fun FortressConsoleApp(rooted: Boolean, magisk: Boolean, versionName: String) {
    val context = LocalContext.current
    var booted by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(PinLock.isEnabled(context)) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize().background(ConsoleColors.Bg)) {
        when {
            !booted -> BootSequenceOverlay(
                rooted = rooted,
                magisk = magisk,
                versionName = versionName,
                onDismiss = { booted = true },
            )
            locked -> PinLockScreen(onUnlock = { locked = false })
            else -> Column(Modifier.fillMaxSize()) {
                ConsoleTabBar(labels = TABS, selected = selectedTab, onSelect = { selectedTab = it })
                Box(Modifier.fillMaxSize().weight(1f)) {
                    when (selectedTab) {
                        0 -> DashboardTab()
                        1 -> ScannerTab()
                        2 -> OfflinePanel("NETWORK TAP", phase = 3)
                        3 -> OfflinePanel("SHIELD", phase = 3)
                        4 -> OfflinePanel("CVE FEED", phase = 4)
                        else -> OfflinePanel("FORENSICS", phase = 4)
                    }
                }
            }
        }
    }
}
