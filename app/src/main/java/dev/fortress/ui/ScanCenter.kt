package dev.fortress.ui

import android.content.Context
import dev.fortress.data.FindingEntry
import dev.fortress.data.PosturePointData
import dev.fortress.data.ScanSession
import dev.fortress.data.SessionStore
import dev.fortress.scanner.DeepScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Application-scoped scan state — the Android counterpart of the web sim hook.
 *
 * WHY a singleton: a scan must keep running (and keep its log/findings) when
 * the operator switches tabs or rotates the device. Compose state inside a
 * tab would die with the composable, so the DeepScanner + its derived UI
 * streams live here and every tab simply collects them.
 *
 * Finished scans are persisted through [SessionStore] and appended to the
 * posture history so the Dashboard chart reflects real scan outcomes.
 */
object ScanCenter {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanner: DeepScanner? = null
    private var store: SessionStore? = null
    private var collectorStarted = false
    private var scanJob: Job? = null

    private val _progress = MutableStateFlow<DeepScanner.ScanProgress>(DeepScanner.ScanProgress.Idle)
    val progress: StateFlow<DeepScanner.ScanProgress> = _progress

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private val _findings = MutableStateFlow<List<DeepScanner.ScanProgress.Finding>>(emptyList())
    val findings: StateFlow<List<DeepScanner.ScanProgress.Finding>> = _findings

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    /** Wall-clock start of the running (or last) scan, for the elapsed readout. */
    var startedAtMs: Long = 0L
        private set

    /** Idempotent: wires the progress collector once per process. */
    fun ensure(context: Context) {
        if (collectorStarted) return
        val appContext = context.applicationContext
        val s = DeepScanner(appContext)
        scanner = s
        store = SessionStore(appContext)
        scope.launch {
            s.progress.collect { p ->
                _progress.value = p
                when (p) {
                    is DeepScanner.ScanProgress.Phase ->
                        appendLog("▶ phase ${p.index}/${p.total}: ${p.label}")
                    is DeepScanner.ScanProgress.Detail ->
                        appendLog("  ${p.text}")
                    is DeepScanner.ScanProgress.Finding -> {
                        _findings.value = _findings.value + p
                        appendLog("  [${p.severity}] ${p.title} — ${p.detail}")
                    }
                    is DeepScanner.ScanProgress.Finished -> {
                        appendLog("✔ scan finished — ${p.findings} finding(s), score ${p.score}")
                        persist(p)
                        _scanning.value = false
                    }
                    DeepScanner.ScanProgress.Idle -> Unit
                }
            }
        }
        collectorStarted = true
    }

    fun start() {
        val s = scanner ?: return
        if (_scanning.value) return
        _findings.value = emptyList()
        _log.value = emptyList()
        _progress.value = DeepScanner.ScanProgress.Idle
        startedAtMs = System.currentTimeMillis()
        _scanning.value = true
        scanJob = scope.launch { s.runScan() }
    }

    /** Aborts the running scan; partial findings/log remain visible. */
    fun stop() {
        scanJob?.cancel()
        scanJob = null
        _scanning.value = false
        appendLog("■ scan aborted by operator")
    }

    /** Persists the finished scan as a session + a posture history point. */
    private fun persist(finished: DeepScanner.ScanProgress.Finished) {
        val st = store ?: return
        try {
            val number = st.sessionCount() + 1
            st.saveSession(
                ScanSession(
                    id = "scan-$number-${finished.score}",
                    label = "Deep scan #$number",
                    startedAt = startedAtMs,
                    finishedAt = System.currentTimeMillis(),
                    score = finished.score,
                    verdict = finished.verdict,
                    findings = _findings.value.map { f ->
                        FindingEntry(f.severity.name, f.title, f.detail)
                    },
                )
            )
            st.appendPosture(
                PosturePointData(
                    score = finished.score,
                    label = "Deep scan #$number",
                    ts = System.currentTimeMillis(),
                )
            )
        } catch (e: Exception) {
            appendLog("  persistence warning: ${e.message}")
        }
    }

    private fun appendLog(line: String) {
        // cap the console log so a long /proc walk cannot grow it unbounded
        _log.value = (_log.value + line).takeLast(200)
    }
}
