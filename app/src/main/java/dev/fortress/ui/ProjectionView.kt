package dev.fortress.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Posture history curve — renders the score series produced by consecutive
 * deep scans (mirrors the web console's posture-history-chart).
 *
 * INTERACTION CONTRACT:
 *  * tap / click anywhere → selects the nearest sample (dashed crosshair +
 *    halo circle around the selected dot);
 *  * DPAD LEFT/RIGHT cycles the selection so the view is fully navigable on
 *    hardware-key devices (setFocusableInTouchMode + explicit handling);
 *  * every selection change fires announceForAccessibility with the
 *    `mo N · frozen Nd · adopt Nd` readout so TalkBack users get the same
 *    data sighted users see.
 *
 * The view is deliberately dependency-free (raw Canvas/Paint) — it must render
 * identically on a rooted lab phone and an old API 26 tablet alike.
 */
class ProjectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** One historical posture sample. mo/frozen/adopt mirror the web fields:
     *  modules observed, apps frozen, mitigations adopted at scan time. */
    data class PosturePoint(
        val score: Int,
        val label: String,
        val ts: Long,
        val mo: Int,
        val frozen: Int,
        val adopt: Int,
    )

    private val points = mutableListOf<PosturePoint>()
    private var selectedIdx: Int = -1

    /** Set by the dashboard to sync the selected sample into detail panels. */
    var onSelected: ((idx: Int, point: PosturePoint?) -> Unit)? = null

    // -- paints ---------------------------------------------------------------

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#34D399") // emerald-400
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isDither = true
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2234D399") // 13% emerald veil under the curve
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#34D399")
        style = Paint.Style.FILL
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Dashed crosshair — PathEffect.DASH makes the selection overlay read as
        // "guide", not as chart data.
        color = Color.parseColor("#A1A1AA")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3334D399")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A1A1AA")
        textSize = 26f
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#27272A")
        strokeWidth = 1f
    }

    private val plotRect = RectF()

    // -- data -----------------------------------------------------------------

    fun submit(points: List<PosturePoint>) {
        this.points.clear()
        this.points += points
        if (selectedIdx >= points.size) selectedIdx = points.size - 1
        invalidate()
    }

    fun select(idx: Int) {
        if (points.isEmpty()) return
        selectedIdx = idx.coerceIn(0, points.size - 1)
        invalidate()
        announceSelection()
        onSelected?.invoke(selectedIdx, points[selectedIdx])
    }

    // -- layout / drawing -------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        plotRect.set(paddingLeft + 24f, paddingTop + 36f,
            w - paddingRight - 24f, h - paddingBottom - 44f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGrid(canvas)
        if (points.isEmpty()) {
            canvas.drawText("no posture history — run a scan", plotRect.left, plotRect.centerY(), textPaint)
            return
        }
        drawCurve(canvas)
        if (selectedIdx in points.indices) drawSelection(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        for (score in listOf(0, 50, 100)) {
            val y = yFor(score.toFloat())
            canvas.drawLine(plotRect.left, y, plotRect.right, y, gridPaint)
            canvas.drawText("$score", plotRect.left - 4f, y - 6f, textPaint)
        }
    }

    private fun drawCurve(canvas: Canvas) {
        val path = Path()
        val fill = Path()
        points.forEachIndexed { i, p ->
            val x = xFor(i)
            val y = yFor(p.score.toFloat())
            if (i == 0) { path.moveTo(x, y); fill.moveTo(x, plotRect.bottom) }
            else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo(xFor(points.size - 1), plotRect.bottom)
        fill.close()
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(path, linePaint)
        points.indices.forEach { i ->
            canvas.drawCircle(xFor(i), yFor(points[i].score.toFloat()), 5f, dotPaint)
        }
    }

    private fun drawSelection(canvas: Canvas) {
        val p = points[selectedIdx]
        val x = xFor(selectedIdx)
        val y = yFor(p.score.toFloat())
        // dashed crosshair guides
        canvas.drawLine(x, plotRect.top, x, plotRect.bottom, crossPaint)
        canvas.drawLine(plotRect.left, y, plotRect.right, y, crossPaint)
        // halo ring around the selected dot
        canvas.drawCircle(x, y, 16f, haloPaint)
        canvas.drawCircle(x, y, 8f, dotPaint)
        // readout label
        canvas.drawText(
            "${selectedIdx + 1}/${points.size}  score ${p.score}  ${p.label}",
            plotRect.left, plotRect.top - 8f, textPaint,
        )
    }

    private fun xFor(i: Int): Float = if (points.size <= 1) plotRect.centerX()
    else plotRect.left + i * (plotRect.width() / (points.size - 1))

    private fun yFor(score: Float): Float =
        plotRect.bottom - (score.coerceIn(0f, 100f) / 100f) * plotRect.height()

    // -- input -----------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (points.isEmpty()) return false
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP) {
            select(nearestIndex(event.x))
            performClick()
            return true
        }
        return super.onTouchEvent(event)
    }

    /** Lint-satisfying paired click handler (same action as the tap path). */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun nearestIndex(x: Float): Int {
        var best = 0
        var bestDist = Float.MAX_VALUE
        points.indices.forEach { i ->
            val d = abs(xFor(i) - x)
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { select(selectedIdx - 1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { select(selectedIdx + 1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    init {
        // DPAD navigation needs focusability in touch mode.
        isFocusable = true
        isFocusableInTouchMode = true
        // A meaningful a11y label; the per-selection readout comes from
        // announceForAccessibility below.
        contentDescription = "posture history curve"
    }

    private fun announceSelection() {
        if (selectedIdx !in points.indices) return
        val p = points[selectedIdx]
        announceForAccessibility(
            "${selectedIdx + 1} of ${points.size} · score ${p.score} · " +
                "mo ${p.mo} · frozen ${p.frozen} · adopt ${p.adopt}"
        )
    }
}
