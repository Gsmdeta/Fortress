package dev.fortress

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
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
import dev.fortress.ui.ConsoleNavigationBar
import dev.fortress.ui.ConsoleNavigationRail
import dev.fortress.ui.ConsoleTopAppBar
import dev.fortress.ui.CveTab
import dev.fortress.ui.DashboardTab
import dev.fortress.ui.ForensicsTab
import dev.fortress.ui.FortressConsoleTheme
import dev.fortress.ui.NetworkTab
import dev.fortress.ui.PinLock
import dev.fortress.ui.PinLockScreen
import dev.fortress.ui.ScannerTab
import dev.fortress.ui.ShieldTab
import dev.fortress.ui.ScanCenter
import dev.fortress.ui.SettingsTab

/**
 * Main console — now the Compose port of the web command center.
 *
 * Launch flow mirrors the demo: boot sequence overlay (staggered [ok] lines
 * over REAL triage state from MainActivity) → PIN lock gate → 6-tab console.
 *
 * The console is a Compose tab shell; each tab collects its live engine
 * streams (ScanCenter, RealtimeGuard, and TrafficMonitor) so switching tabs
 * never stops running work. Every console tab is backed by its on-device
 * engine; limited-mode states are shown when root, VPN consent, or a native
 * ABI is unavailable.
 */
class ConsoleActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannels(this)

        val rooted = intent.getBooleanExtra(MainActivity.EXTRA_ROOTED, false)
        val magisk = intent.getBooleanExtra(MainActivity.EXTRA_MAGISK, false)
        val provider = intent.getStringExtra(MainActivity.EXTRA_ROOT_PROVIDER)
            ?.let { runCatching { ConsoleRuntime.RootProvider.valueOf(it) }.getOrNull() }
            ?: if (magisk) ConsoleRuntime.RootProvider.MAGISK else ConsoleRuntime.RootProvider.NONE
        ConsoleRuntime.rooted = rooted
        ConsoleRuntime.magisk = magisk
        ConsoleRuntime.rootProvider = provider

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
private val TAB_GLYPHS = listOf("⌂", "⌕", "≋", "✦", "!", "≡")
private const val SETTINGS_TAB = 6

@Composable
private fun FortressConsoleApp(rooted: Boolean, magisk: Boolean, versionName: String) {
    val context = LocalContext.current
    // Initialise process-scoped scan state before the restored tab is rendered.
    // Without this, Android could restore directly into SCAN/FORENSICS after a
    // rotation and the scanner controls would be inert until DASH was visited.
    androidx.compose.runtime.LaunchedEffect(Unit) { ScanCenter.ensure(context) }
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
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                // Material navigation patterns: bottom navigation on phones,
                // navigation rail on tablets and landscape layouts.
                if (maxWidth >= 600.dp) {
                    androidx.compose.material3.Scaffold(
                        containerColor = ConsoleColors.Bg,
                        topBar = {
                            ConsoleTopAppBar(
                                title = if (selectedTab == SETTINGS_TAB) "SETTINGS" else TABS[selectedTab],
                                rooted = rooted,
                                magisk = magisk,
                                settingsOpen = selectedTab == SETTINGS_TAB,
                                onSettings = { selectedTab = if (selectedTab == SETTINGS_TAB) 0 else SETTINGS_TAB },
                            )
                        },
                    ) { contentPadding ->
                        Row(
                            Modifier
                                .padding(contentPadding)
                                .fillMaxSize(),
                        ) {
                            ConsoleNavigationRail(
                                labels = TABS,
                                glyphs = TAB_GLYPHS,
                                selected = selectedTab,
                                onSelect = { selectedTab = it },
                            )
                            Box(Modifier.weight(1f).fillMaxSize()) { TabContent(selectedTab) }
                        }
                    }
                } else {
                    androidx.compose.material3.Scaffold(
                        containerColor = ConsoleColors.Bg,
                        topBar = {
                            ConsoleTopAppBar(
                                title = if (selectedTab == SETTINGS_TAB) "SETTINGS" else TABS[selectedTab],
                                rooted = rooted,
                                magisk = magisk,
                                settingsOpen = selectedTab == SETTINGS_TAB,
                                onSettings = { selectedTab = if (selectedTab == SETTINGS_TAB) 0 else SETTINGS_TAB },
                            )
                        },
                        bottomBar = {
                            ConsoleNavigationBar(
                                labels = TABS,
                                glyphs = TAB_GLYPHS,
                                selected = if (selectedTab < TABS.size) selectedTab else -1,
                                onSelect = { selectedTab = it },
                            )
                        },
                    ) { contentPadding ->
                        Box(
                            Modifier
                                .padding(contentPadding)
                                .fillMaxSize(),
                        ) { TabContent(selectedTab) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabContent(selectedTab: Int) {
    when (selectedTab) {
        0 -> DashboardTab()
        1 -> ScannerTab()
        2 -> NetworkTab()
        3 -> ShieldTab()
        4 -> CveTab()
        SETTINGS_TAB -> SettingsTab()
        else -> ForensicsTab()
    }
}
