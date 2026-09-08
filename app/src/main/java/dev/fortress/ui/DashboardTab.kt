package dev.fortress.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fortress.data.PosturePointData
import dev.fortress.data.SessionStore
import dev.fortress.guard.RealtimeGuard
import dev.fortress.net.PacketVpnService
import dev.fortress.scanner.DeepScanner
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * DASH tab — port of the web dashboard: animated posture-score ring, live
 * engine stat chips, quick actions (scan / guard / packet tap), and the
 * posture history curve with tap-to-inspect. Every number here comes from a
 * real engine or a persisted scan — nothing simulated.
 */
@Composable
fun DashboardTab() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { ScanCenter.ensure(context) }
    val store = remember { SessionStore(context.applicationContext) }

    val progress by ScanCenter.progress.collectAsState()
    val scanning by ScanCenter.scanning.collectAsState()

    var sessions by remember { mutableStateOf(store.sessions()) }
    var posture by remember { mutableStateOf(store.posture()) }
    var guardRunning by remember { mutableStateOf(RealtimeGuard.isRunning) }
    var tapRunning by remember { mutableStateOf(PacketVpnService.isRunning) }

    // engine toggles are plain volatile flags — poll them lightly while visible
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            guardRunning = RealtimeGuard.isRunning
            tapRunning = PacketVpnService.isRunning
        }
    }
    // refresh persisted data as soon as a scan finishes
    LaunchedEffect(progress) {
        if (progress is DeepScanner.ScanProgress.Finished) {
            sessions = store.sessions()
            posture = store.posture()
        }
    }

    val last = sessions.firstOrNull()
    val score = when (val p = progress) {
        is DeepScanner.ScanProgress.Finished -> p.score
        else -> last?.score ?: 0
    }
    val verdict = when (val p = progress) {
        is DeepScanner.ScanProgress.Finished -> p.verdict
        else -> last?.verdict
    }

    // VPN consent flow: prepare() returns the system consent intent, if needed
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            PacketVpnService.start(context)
        }
    }
    fun requestTap() {
        if (tapRunning) {
            PacketVpnService.stop(context)
            return
        }
        val consent = VpnService.prepare(context)
        if (consent != null) vpnLauncher.launch(consent) else PacketVpnService.start(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // -- score ring -------------------------------------------------------
        ConsolePanel(label = "POSTURE DASHBOARD") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                ScoreRing(score = score, scanning = scanning)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(
                        "verdict: ${verdict ?: "—"}",
                        when {
                            scanning -> ChipKind.WARN
                            verdict == "CLEAN" -> ChipKind.OK
                            verdict == "SUSPICIOUS" -> ChipKind.WARN
                            verdict == null -> ChipKind.NEUTRAL
                            else -> ChipKind.CRIT
                        },
                    )
                    StatusChip(
                        if (guardRunning) "guard armed" else "guard off",
                        if (guardRunning) ChipKind.OK else ChipKind.NEUTRAL,
                    )
                    StatusChip(
                        if (tapRunning) "tap up" else "tap down",
                        if (tapRunning) ChipKind.OK else ChipKind.NEUTRAL,
                    )
                }
            }
        }

        // -- quick actions ------------------------------------------------------
        ConsolePanel(label = "QUICK ACTIONS") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ConsoleButton(
                    if (scanning) "SCANNING…" else "START SCAN",
                    enabled = !scanning,
                    modifier = Modifier.weight(1f),
                ) { ScanCenter.start() }
                ConsoleButton(
                    if (guardRunning) "STOP GUARD" else "ARM GUARD",
                    primary = false,
                    modifier = Modifier.weight(1f),
                ) {
                    if (guardRunning) RealtimeGuard.stop(context) else RealtimeGuard.start(context)
                }
                ConsoleButton(
                    if (tapRunning) "STOP TAP" else "START TAP",
                    primary = false,
                    modifier = Modifier.weight(1f),
                ) { requestTap() }
            }
        }

        // -- stat tiles -----------------------------------------------------------
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("last verdict", verdict ?: "—", Modifier.weight(1f))
            StatTile("findings", (last?.findings?.size ?: 0).toString(), Modifier.weight(1f))
            StatTile("sessions", sessions.size.toString(), Modifier.weight(1f))
        }

        // -- posture history -------------------------------------------------------
        ConsolePanel(label = "POSTURE HISTORY") {
            PostureChart(points = posture)
        }

        // -- recent sessions ----------------------------------------------------------
        if (sessions.isNotEmpty()) {
            ConsolePanel(label = "RECENT SESSIONS") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    sessions.take(5).forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusChip(
                                "score ${s.score}",
                                when {
                                    s.verdict == "CLEAN" -> ChipKind.OK
                                    s.verdict == "SUSPICIOUS" -> ChipKind.WARN
                                    else -> ChipKind.CRIT
                                },
                            )
                            MonoText(s.label, size = 12.sp)
                            MonoText(
                                "· ${s.findings.size} finding(s)",
                                size = 11.sp,
                                color = ConsoleColors.TextFaint,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Animated circular posture gauge — emerald when hardened, amber/red as score drops. */
@Composable
private fun ScoreRing(score: Int, scanning: Boolean) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(score, scanning) {
        anim.snapTo(0f)
        anim.animateTo(score / 100f, tween(900))
    }
    val ringColor = when {
        scanning -> ConsoleColors.Amber
        score >= 85 -> ConsoleColors.Emerald
        score >= 55 -> ConsoleColors.Amber
        else -> ConsoleColors.Red
    }
    Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = ConsoleColors.PanelBorder,
                startAngle = 0f, sweepAngle = 360f, useCenter = false, style = stroke,
            )
            drawArc(
                color = ringColor,
                startAngle = -90f, sweepAngle = 360f * anim.value, useCenter = false, style = stroke,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MonoText(
                if (scanning) "…" else score.toString(),
                size = 46.sp,
                color = ringColor,
            )
            SectionLabel("posture score")
        }
    }
}

/**
 * Posture history curve — Compose port of the web posture-history chart:
 * grid at 0/50/100, emerald curve with halo fill, tap to select a sample
 * (dashed crosshair + readout), empty state before the first scan.
 */
@Composable
private fun PostureChart(points: List<PosturePointData>) {
    var selected by remember(points) { mutableIntStateOf(-1) }
    val textMeasurer = rememberTextMeasurer()

    if (points.isEmpty()) {
        MonoText("no posture history — run a scan", size = 12.sp, color = ConsoleColors.TextFaint)
        return
    }

    val padDp = 24.dp
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(170.dp)
            .semantics { contentDescription = "posture history chart, ${points.size} points" }
            .pointerInput(points) {
                detectTapGestures { offset ->
                    val left = padDp.toPx()
                    val plotWidth = size.width - left - padDp.toPx()
                    if (plotWidth <= 0f) return@detectTapGestures
                    val idx = (((offset.x - left) / plotWidth) * (points.size - 1))
                        .roundToInt().coerceIn(0, points.size - 1)
                    selected = idx
                }
            }
    ) {
        val left = padDp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 20.dp.toPx()
        val bottom = size.height - 6.dp.toPx()
        val plotHeight = bottom - top

        fun xFor(i: Int): Float =
            if (points.size <= 1) (left + right) / 2f else left + i * ((right - left) / (points.size - 1))
        fun yFor(score: Int): Float = bottom - (score.coerceIn(0, 100) / 100f) * plotHeight

        // grid + axis labels
        for (score in listOf(0, 50, 100)) {
            val y = yFor(score)
            drawLine(ConsoleColors.PanelBorder, Offset(left, y), Offset(right, y), strokeWidth = 1f)
            drawText(
                textMeasurer = textMeasurer,
                text = "$score",
                topLeft = Offset(left, y - 16.dp.toPx()),
                style = TextStyle(
                    fontSize = 10.sp,
                    color = ConsoleColors.TextFaint,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
            )
        }

        // curve + halo fill
        val path = Path()
        val fill = Path()
        points.forEachIndexed { i, pt ->
            val x = xFor(i)
            val y = yFor(pt.score)
            if (i == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, bottom)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(xFor(points.size - 1), bottom)
        fill.close()
        // 13% emerald veil under the curve (matches the web halo fill)
        drawPath(fill, ConsoleColors.Emerald.copy(alpha = 0.13f))
        drawPath(path, ConsoleColors.Emerald, style = Stroke(width = 3.dp.toPx()))
        points.indices.forEach { i ->
            drawCircle(ConsoleColors.Emerald, radius = 4.dp.toPx(), center = Offset(xFor(i), yFor(points[i].score)))
        }

        // selection: dashed crosshair + halo + solid dot
        if (selected in points.indices) {
            val x = xFor(selected)
            val y = yFor(points[selected].score)
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
            drawLine(ConsoleColors.TextMuted, Offset(x, top), Offset(x, bottom), strokeWidth = 1.5f, pathEffect = dash)
            drawLine(ConsoleColors.TextMuted, Offset(left, y), Offset(right, y), strokeWidth = 1.5f, pathEffect = dash)
            drawCircle(ConsoleColors.Emerald.copy(alpha = 0.2f), radius = 14.dp.toPx(), center = Offset(x, y))
            drawCircle(ConsoleColors.Emerald, radius = 7.dp.toPx(), center = Offset(x, y))
        }
    }

    // selection readout under the chart
    if (selected in points.indices) {
        val pt = points[selected]
        MonoText(
            "${selected + 1}/${points.size} · score ${pt.score} · ${pt.label}",
            size = 11.sp,
            color = ConsoleColors.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
