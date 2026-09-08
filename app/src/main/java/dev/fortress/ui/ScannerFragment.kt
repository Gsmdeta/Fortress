package dev.fortress.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.fortress.scanner.DeepScanner
import dev.fortress.scanner.RootkitHeuristics
import dev.fortress.ui.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Scanner tab: binds the UI to DeepScanner.progress (StateFlow<ScanProgress>).
 *
 * Design notes:
 *  * programmatic UI (no layout XML — see build.gradle comment);
 *  * the detail log is capped at 200 lines so a long /proc walk cannot grow
 *    the TextView unbounded;
 *  * the scan job is cancelled with the view lifecycle — a rotated device
 *    never leaves a zombie scan coroutine holding the root shell.
 */
class ScannerFragment : Fragment() {

    private lateinit var phaseLabel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var logView: TextView
    private lateinit var verdictView: TextView
    private lateinit var startButton: Button

    private var scanJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(20), ctx.dp(20), ctx.dp(20))
            setBackgroundColor(Color.parseColor("#09090B"))
        }

        root.addView(TextView(ctx).apply {
            text = "■ DEEP SCANNER"
            textSize = 15f
            letterSpacing = 0.15f
            setTextColor(Color.parseColor("#34D399"))
        })

        verdictView = TextView(ctx).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#D4D4D8"))
            text = "verdict: —"
            setPadding(0, 24, 0, 12)
        }
        root.addView(verdictView)

        progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 4
            progress = 0
        }
        root.addView(progressBar)

        phaseLabel = TextView(ctx).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#A1A1AA"))
            text = "idle · 4 phases · process walk → memory maps → file audit → rootkit heuristics"
            setPadding(0, 12, 0, 12)
        }
        root.addView(phaseLabel)

        // Emerald action button — the console accent with dark text, matching
        // the tab highlight; explicit tint so it never inherits a light-theme
        // default from the platform.
        startButton = Button(ctx).apply {
            text = "START SCAN"
            setTextColor(Color.parseColor("#09090B"))
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#34D399"))
        }
        root.addView(startButton)

        logView = TextView(ctx).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#D4D4D8"))
            text = ""
        }
        root.addView(ScrollView(ctx).apply {
            addView(logView)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val scanner = DeepScanner(requireContext().applicationContext)

        startButton.setOnClickListener {
            startButton.isEnabled = false
            verdictView.text = "verdict: scanning…"
            scanJob = viewLifecycleOwner.lifecycleScope.launch {
                scanner.runScan()
                startButton.isEnabled = true
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            scanner.progress.collect { p ->
                when (p) {
                    is DeepScanner.ScanProgress.Phase -> {
                        progressBar.progress = p.index
                        phaseLabel.text = "phase ${p.index}/${p.total}: ${p.label}"
                        appendLog("▶ ${p.label}")
                    }
                    is DeepScanner.ScanProgress.Detail -> appendLog("  ${p.text}")
                    is DeepScanner.ScanProgress.Finding -> appendLog(
                        "  [${p.severity}] ${p.title} — ${p.detail}",
                        highlight = true,
                    )
                    is DeepScanner.ScanProgress.Finished -> {
                        verdictView.text = "verdict: ${p.verdict} · score ${p.score} · findings ${p.findings}"
                        appendLog("✔ scan finished — ${p.findings} finding(s), score ${p.score}")
                        appendKernelSummary()
                    }
                    DeepScanner.ScanProgress.Idle -> Unit
                }
            }
        }
    }

    /** Pure-Kotlin kernel posture checks, appended after each finished scan. */
    private fun appendKernelSummary() {
        RootkitHeuristics.sysrqState()?.let { (value, writable) ->
            appendLog("  sysrq=$value writable=$writable")
        }
        appendLog("  kallsyms readable=${RootkitHeuristics.kallsymsReadable()}")
        appendLog("  yama ptrace_scope=${RootkitHeuristics.ptraceScope()}")
    }

    private fun appendLog(line: String, highlight: Boolean = false) {
        val sb = logView.text
        val lines = sb.split('\n')
        val capped = if (lines.size > MAX_LOG_LINES) lines.takeLast(MAX_LOG_LINES / 2) else lines
        logView.text = (capped + line).joinToString("\n")
        if (highlight) logView.setTextColor(Color.parseColor("#FBBF24"))
        else logView.setTextColor(Color.parseColor("#D4D4D8"))
        logView.gravity = Gravity.BOTTOM
    }

    override fun onDestroyView() {
        scanJob?.cancel()
        super.onDestroyView()
    }

    companion object {
        private const val MAX_LOG_LINES = 200
    }
}
