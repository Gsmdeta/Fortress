package dev.fortress

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.fortress.ui.dpPx
import dev.fortress.ConsoleRuntime.RootProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Entry activity: environment triage before the console opens.
 *
 * Root is OPTIONAL but strongly shapes what the console can do, so we probe:
 *  1. `su -c id`   — canonical root check; works for Magisk, KernelSU, APatch.
 *  2. Magisk presence — package name scan via `pm list packages`, plus a
 *     guarded existence check of /data/adb/magisk (readable only with root;
 *     a SecurityException here is EXPECTED on non-rooted devices and is
 *     swallowed rather than treated as a finding).
 *
 * WHY Runtime.exec and not libsu: this build keeps zero third-party shell
 * dependencies; the same primitives back dev.fortress.root.RootShell.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* The console remains usable when notifications are declined. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // No layout XML in this delivery — a minimal programmatic splash keeps
        // the triage output visible without res/ churn. The background is set
        // explicitly so the splash stays zinc-950 even if the window theme
        // ever drifts; paddings are density-independent via dpPx().
        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#09090B"))
            setPadding(dpPx(density, 24), dpPx(density, 24), dpPx(density, 24), dpPx(density, 24))
        }
        status = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFFE4E4E7.toInt())
            text = "fortress · environment triage…"
        }
        root.addView(status)
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        startTriage()
    }

    private fun startTriage() {
        lifecycleScope.launch(Dispatchers.IO) {
            val rooted = checkRoot()
            val rootProvider = detectRootProvider(rooted)
            val magisk = rootProvider == RootProvider.MAGISK
            val summary = buildString {
                append("su: ").append(if (rooted) "AVAILABLE" else "absent").append('\n')
                append("root manager: ").append(rootProvider.name).append('\n')
                append(if (rooted) "opening console (deep mode)…" else "opening console (limited mode)…")
            }
            withContext(Dispatchers.Main) { status.text = summary }

            // Brief dwell so the operator reads the triage before routing.
            delay(900)
            startActivity(
                Intent(this@MainActivity, ConsoleActivity::class.java).putExtras(
                    Bundle().apply {
                        putBoolean(EXTRA_ROOTED, rooted)
                        putBoolean(EXTRA_MAGISK, magisk)
                        putString(EXTRA_ROOT_PROVIDER, rootProvider.name)
                    }
                )
            )
            finish()
        }
    }

    /** Executes `su -c id` and inspects the response for uid=0. */
    private fun checkRoot(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec("su -c id")
            if (!proc.waitFor(4, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                false
            } else {
                val out = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
                out.contains("uid=0")
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Two-signal Magisk detection (detection only — we never attempt to hide
     * or modify the manager):
     *  A. package scan: `pm list packages` piped through a Kotlin filter for
     *     com.topjohnwu.magisk (also matches the randomized-name variant via
     *     the stub path marker below);
     *  B. filesystem probe of /data/adb/magisk — requires root to succeed and
     *     is wrapped in try/catch because SecurityException/IOException on
     *     non-rooted builds is the COMMON case, not an error.
     */
    private fun detectRootProvider(rooted: Boolean): RootProvider {
        if (!rooted) return RootProvider.NONE
        val packages = buildString {
            runCatching {
                Runtime.getRuntime().exec("pm list packages").inputStream.bufferedReader().use { append(it.readText()) }
            }
        }.lowercase()
        return when {
            packages.contains("com.topjohnwu.magisk") ||
                packages.contains("io.github.huskydg.magisk") ||
                rootPathExists("/data/adb/magisk") -> RootProvider.MAGISK
            packages.contains("me.weishu.kernelsu") ||
                packages.contains("com.rifsxd.ksunext") ||
                rootPathExists("/data/adb/ksu") ||
                rootPathExists("/data/adb/ksud") -> RootProvider.KERNELSU
            packages.contains("me.bmax.apatch") ||
                packages.contains("me.bmax.apatch.ui") ||
                rootPathExists("/data/adb/ap") ||
                rootPathExists("/data/adb/apatch") -> RootProvider.APATCH
            else -> RootProvider.UNKNOWN
        }
    }

    private fun rootPathExists(path: String): Boolean = try {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "test -e $path"))
        if (!proc.waitFor(2, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            false
        } else {
            proc.exitValue() == 0
        }
    } catch (_: Exception) {
        false
    }

    companion object {
        const val EXTRA_ROOTED = "rooted"
        const val EXTRA_MAGISK = "magisk"
        const val EXTRA_ROOT_PROVIDER = "root_provider"
    }
}
