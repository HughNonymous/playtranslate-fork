package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Transparent overlay that darkens everything outside the selected capture region.
 * Drawn directly on the game display via TYPE_ACCESSIBILITY_OVERLAY.
 */
class RegionOverlayView(context: Context) : View(context) {

    private val darkPaint = Paint().apply {
        color = Color.argb((0.78f * 255).toInt(), 0, 0, 0)
        style = Paint.Style.FILL
    }

    private var topFraction    = 0f
    private var bottomFraction = 1f
    private var leftFraction   = 0f
    private var rightFraction  = 1f

    fun updateRegion(
        top: Float, bottom: Float,
        left: Float = 0f, right: Float = 1f
    ) {
        topFraction    = top
        bottomFraction = bottom
        leftFraction   = left
        rightFraction  = right
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val topPx    = h * topFraction
        val bottomPx = h * bottomFraction
        val leftPx   = w * leftFraction
        val rightPx  = w * rightFraction

        // Above the region
        if (topPx > 0f) canvas.drawRect(0f, 0f, w, topPx, darkPaint)
        // Below the region
        if (bottomPx < h) canvas.drawRect(0f, bottomPx, w, h, darkPaint)
        // Left of the region (between top and bottom)
        if (leftPx > 0f) canvas.drawRect(0f, topPx, leftPx, bottomPx, darkPaint)
        // Right of the region
        if (rightPx < w) canvas.drawRect(rightPx, topPx, w, bottomPx, darkPaint)
    }
}
