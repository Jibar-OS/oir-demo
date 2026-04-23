/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
 * Licensed under the Apache License, Version 2.0
 */
package com.oir.demo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Gantt-style timeline showing each in-flight OIR request as a
 * horizontal bar. Primary purpose is the Loom: a viewer can see
 * bars overlap (concurrency), see audio admit ahead of queued text
 * (priority scheduling), and see bars clip short when Cancel All
 * fires.
 *
 * Coordinate system: horizontal axis is time (most recent
 * [windowMs] milliseconds shown; "now" is the right edge). Vertical
 * axis is lanes — one lane per distinct request. Bars older than
 * the window are pruned so memory stays bounded during long demo
 * sessions.
 *
 * Not a general-purpose Gantt — lane assignment is first-free greedy
 * (reuse any lane whose last bar already closed) and colors are
 * passed in by the caller rather than derived from state. Keeps the
 * view logic tiny and the status-color contract owned by whoever
 * manages the Status enum.
 */
class TimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private data class Bar(
        val id: String,
        val label: String,
        val startMs: Long,
        var endMs: Long? = null,
        var color: Int,
        var lane: Int = -1,
    )

    private val bars = mutableListOf<Bar>()
    private val paintBar  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
    }
    private val paintAxis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF30363D.toInt()
        strokeWidth = 1f
    }
    private val paintAxisLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7D8590.toInt()
        typeface = Typeface.MONOSPACE
    }
    private val paintNow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF58A6FF.toInt()
        strokeWidth = 2f
    }

    private val windowMs: Long = 30_000L
    private val laneHeightDp = 22
    private val laneGapDp    = 3
    private val labelPadDp   = 6
    private val textSizeDp   = 10f

    init {
        paintText.textSize      = dp(textSizeDp)
        paintAxisLabel.textSize = dp(9f)
    }

    // ------------------------------------------------------------------
    // Public API — MainActivity calls these as jobs move through their
    // lifecycle. Safe to call on the main thread only; internal state
    // is not synchronised.
    // ------------------------------------------------------------------

    fun startBar(id: String, label: String, color: Int) {
        val now = System.currentTimeMillis()
        // Reuse any lane whose last bar has already closed and been
        // pruned or ended before a short grace period — gives lanes
        // "breathing room" so a new bar doesn't immediately overwrite
        // the previous bar's label. Greedy first-fit.
        val usedLanes = bars.filter { (it.endMs ?: now) > now - 500L }.map { it.lane }.toSet()
        var lane = 0
        while (lane in usedLanes) lane++
        bars.add(Bar(id, label, now, null, color, lane))
        invalidate()
    }

    fun updateBarColor(id: String, color: Int) {
        for (b in bars) if (b.id == id && b.endMs == null) {
            b.color = color
            invalidate()
            return
        }
    }

    fun endBar(id: String, finalColor: Int) {
        val now = System.currentTimeMillis()
        for (b in bars) if (b.id == id && b.endMs == null) {
            b.endMs = now
            b.color = finalColor
            invalidate()
            return
        }
    }

    fun clear() {
        bars.clear()
        invalidate()
    }

    // ------------------------------------------------------------------
    // Draw
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        val windowStart = now - windowMs

        // Prune bars that ended before the window
        bars.removeAll { (it.endMs ?: now) < windowStart }
        if (bars.isEmpty()) {
            drawAxis(canvas, now, windowStart)
            drawNowMarker(canvas)
            return
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val laneH = dp(laneHeightDp.toFloat())
        val laneGap = dp(laneGapDp.toFloat())
        val maxLanes = ((h - laneH) / (laneH + laneGap)).toInt().coerceAtLeast(1)

        drawAxis(canvas, now, windowStart)

        for (b in bars) {
            val lane = b.lane % maxLanes
            val x0 = timeToX(b.startMs, windowStart, w)
            val x1 = timeToX(b.endMs ?: now, windowStart, w)
            val y0 = lane * (laneH + laneGap)
            val y1 = y0 + laneH
            paintBar.color = b.color
            canvas.drawRoundRect(x0, y0, x1.coerceAtLeast(x0 + dp(2f)), y1, dp(3f), dp(3f), paintBar)

            // Label inside the bar if there's room; otherwise to the
            // right of it. Keeps labels readable for 0.5 s-long bars.
            val labelX = if (x1 - x0 > dp(60f)) x0 + dp(labelPadDp.toFloat()) else x1 + dp(labelPadDp.toFloat())
            val labelY = y1 - dp(5f)
            val truncated = b.label.take(38)
            canvas.drawText(truncated, labelX, labelY, paintText)
        }

        drawNowMarker(canvas)

        // Keep re-drawing while at least one bar is open — cheap
        // postInvalidateOnAnimation ties us to the display refresh.
        if (bars.any { it.endMs == null }) postInvalidateOnAnimation()
    }

    private fun drawAxis(canvas: Canvas, now: Long, windowStart: Long) {
        // Tick every 5 seconds. Labels show "-5s", "-10s", ... from
        // the right edge (right edge = now).
        val w = width.toFloat()
        val h = height.toFloat()
        for (offsetSec in 5..30 step 5) {
            val t = now - offsetSec * 1000L
            if (t < windowStart) continue
            val x = timeToX(t, windowStart, w)
            canvas.drawLine(x, 0f, x, h, paintAxis)
            canvas.drawText("-${offsetSec}s", x + dp(2f), h - dp(2f), paintAxisLabel)
        }
    }

    private fun drawNowMarker(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawLine(w - 1, 0f, w - 1, h, paintNow)
    }

    private fun timeToX(t: Long, windowStart: Long, w: Float): Float =
        ((t - windowStart).coerceIn(0L, windowMs).toFloat() / windowMs) * w

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
