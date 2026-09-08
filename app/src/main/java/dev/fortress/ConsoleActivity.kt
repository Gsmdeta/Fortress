package dev.fortress

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import dev.fortress.net.PacketVpnService
import dev.fortress.ui.ScannerFragment
import dev.fortress.ui.dpPx

/**
 * Main dashboard: six tabs mirroring the web command center
 * (Dashboard · Scanner · Network · Shield · CVE · Forensics).
 *
 * WHY a hand-rolled bottom bar instead of BottomNavigationView: Material's
 * BottomNavigationView hard-fails above 5 menu items and the console contract
 * calls for exactly 6 tabs. A HorizontalScrollView of mono-label TextViews is
 * dependency-free, keeps the 44dp touch targets, and survives any tab count.
 *
 * All screens are constructed programmatically (no layout XML) — see the
 * build.gradle note; fragment stubs live at the bottom of this file so the
 * delivered tree stays within the documented file manifest.
 */
class ConsoleActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private val tabLabels = arrayOf("DASH", "SCAN", "NET", "SHIELD", "CVE", "FORENSICS")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannels(this)
        val density = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#09090B")) // zinc-950 console bg
        }

        // --- tab bar -----------------------------------------------------
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#18181B"))
            setPadding(dpPx(density, 8), dpPx(density, 12), dpPx(density, 8), dpPx(density, 12))
        }
        val tabs = tabLabels.map { label ->
            TextView(this).apply {
                text = label
                textSize = 12f
                letterSpacing = 0.15f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#A1A1AA"))
                setPadding(dpPx(density, 16), dpPx(density, 12), dpPx(density, 16), dpPx(density, 12))
                // minWidth keeps the 6 tabs readable on narrow phones (the
                // strip scrolls) while the weight spreads them on tablets.
                minWidth = dpPx(density, 76)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { pager.setCurrentItem(tabLabels.indexOf(label), true) }
            }.also(tabBar::addView)
        }

        val tabScroll = HorizontalScrollView(this).apply {
            addView(tabBar)
            isHorizontalScrollBarEnabled = false
            // Spread tabs across the full width whenever they fit (the bar's
            // natural width is smaller than the viewport), e.g. on tablets.
            isFillViewport = true
        }

        // --- pager -------------------------------------------------------
        pager = ViewPager2(this).apply {
            adapter = ConsolePagerAdapter(supportFragmentManager, lifecycle)
            offscreenPageLimit = tabLabels.size
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    tabs.forEachIndexed { i, tv ->
                        tv.setTextColor(
                            if (i == position) Color.parseColor("#34D399") // emerald-400 accent
                            else Color.parseColor("#A1A1AA")
                        )
                    }
                }
            })
        }

        root.addView(tabScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(pager, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private class ConsolePagerAdapter(
        fm: FragmentManager,
        lifecycle: Lifecycle,
    ) : FragmentStateAdapter(fm, lifecycle) {
        override fun getItemCount() = 6
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> DashboardFragment()
            1 -> ScannerFragment()
            2 -> NetworkFragment()
            3 -> ShieldFragment()
            4 -> CveFragment()
            else -> ForensicsFragment()
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

// ---------------------------------------------------------------------------
// Tab stubs — each renders a mono console panel; full wiring lands per-feature
// (these correspond 1:1 to the web console tabs and their sandbox simulations).
// ---------------------------------------------------------------------------

/** Posture summary + last scan verdict readout. */
class DashboardFragment : Fragment() {
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = panel(
        "POSTURE DASHBOARD",
        "score history renders in dev.fortress.ui.ProjectionView\n" +
            "last verdict: pending first scan\n" +
            "guard feed: attach RealtimeGuard.events flow here"
    )
}

/** Packet tap control + live feed (binds PacketVpnService / TrafficMonitor). */
class NetworkFragment : Fragment() {
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = panel(
        "NETWORK TAP",
        "vpn: ${PacketVpnService.isRunning} · tap START to request VpnService consent\n" +
            "packet events stream from TrafficMonitor.events (Flow<PacketEvent>)\n" +
            "per-app rules live in dev.fortress.firewall.FirewallManager"
    )
}

/** Firewall rules + realtime guard + task manager summary. */
class ShieldFragment : Fragment() {
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = panel(
        "SHIELD",
        "firewall: per-app wifi/data rules (SharedPreferences persisted)\n" +
            "guard: FileObserver on /data/adb/modules + package add/remove\n" +
            "tasks: force-stop via RootShell `am force-stop`"
    )
}

/** Bundled CVE database with local mitigation stubs. */
class CveFragment : Fragment() {
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = panel(
        "CVE FEED",
        "catalog: assets/cve-db.json (8 entries, mirrors web CVE_CATALOG)\n" +
            "softwarePatchAvailable entries expose CveRepository.applySoftwarePatch()\n" +
            "mitigations are chmod/chcon/sysctl only — never binary patching"
    )
}

/** Raw finding explorer + report export. */
class ForensicsFragment : Fragment() {
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = panel(
        "FORENSICS",
        "phase logs from DeepScanner.progress (StateFlow<ScanProgress>)\n" +
            "native heuristics report via RootkitHeuristics.scanSyscallTable()\n" +
            "markdown export mirrors the web sessionToMarkdown() renderer"
    )
}

/**
 * Shared programmatic panel builder for the console stubs.
 *
 * Wrapped in a ScrollView so long readouts stay reachable on small screens and
 * in landscape; all paddings are density-independent (dp).
 */
internal fun Fragment.panel(title: String, body: String): ScrollView {
    val ctx = requireContext()
    val density = ctx.resources.displayMetrics.density
    val content = TextView(ctx).apply {
        setTextColor(Color.parseColor("#D4D4D8"))
        textSize = 13f
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding(dpPx(density, 20), dpPx(density, 20), dpPx(density, 20), dpPx(density, 40))
        text = buildString {
            appendLine("■ $title")
            appendLine()
            append(body)
        }
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    return ScrollView(ctx).apply {
        setBackgroundColor(Color.parseColor("#09090B"))
        addView(content)
    }
}
