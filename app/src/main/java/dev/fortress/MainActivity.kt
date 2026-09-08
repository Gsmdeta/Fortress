package dev.fortress

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.fortress.ui.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // No layout XML in this delivery — a minimal programmatic splash keeps
        // the triage output visible without res/ churn. The background is set
        // explicitly so the splash stays zinc-950 even if the window theme
        // ever drifts; paddings go through dp() for density independence.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#09090B"))
            setPadding(this.dp(24), this.dp(24), this.dp(24), this.dp(24))
        }
        status = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFFE4E4E7.toInt())
            text = "fortress · environment triage…"
        }
        root.addView(status)
        setContentView(root)

        startTriage()
    }

    private fun startTriage() {
        lifecycleScope.launch(Dispatchers.IO) {
            val rooted = checkRoot()
            val magisk = detectMagisk()
            val summary = buildString {
                append("su: ").append(if (rooted) "AVAILABLE" else "absent").append('\n')
                append("magisk: ").append(if (magisk) "detected" else "not detected").append('\n')
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
                    }
                )
            )
            finish()
        }
    }

    /** Executes `su -c id` and inspects the response for uid=0. */
    private fun checkRoot(): Boolean = try {
        val proc = Runtime.getRuntime().exec("su -c id")
        val out = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
        proc.waitFor()
        out.contains("uid=0")
    } catch (e: Exception) {
        false
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
    private fun detectMagisk(): Boolean {
        var found = false
        try {
            val proc = Runtime.getRuntime().exec("pm list packages")
            BufferedReader(InputStreamReader(proc.inputStream)).forEachLine { line ->
                if (line.contains("com.topjohnwu.magisk") || line.contains("io.github.huskydg.magisk")) {
                    found = true
                }
            }
            proc.waitFor()
        } catch (e: Exception) {
            // PackageManager inaccessible — fall through to the FS probe.
        }
        if (!found) {
            try {
                found = File("/data/adb/magisk").exists()
            } catch (e: SecurityException) {
                // Non-rooted device: /data/adb is not readable. Expected.
            } catch (e: Exception) {
                // Any other I/O failure is treated as "not detected".
            }
        }
        return found
    }

    companion object {
        const val EXTRA_ROOTED = "rooted"
        const val EXTRA_MAGISK = "magisk"
    }
}
