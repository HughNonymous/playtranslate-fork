package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View

/**
 * Transparent overlay that draws translated text inside bounding boxes
 * on the game screen during live mode. Each box corresponds to an OCR
 * text group and is filled with a semi-transparent background so the
 * translated text is readable over game graphics. Font size auto-scales
 * to fill each box.
 */
class TranslationOverlayView(context: Context) : View(context) {

    data class TextBox(
        val translatedText: String,
        /** Bounding box in original bitmap pixel coordinates. */
        val bounds: Rect,
        /** Average color of the game content behind this box (ARGB). */
        val bgColor: Int = Color.argb(200, 0, 0, 0),
        /** Text color — estimated from game text or chosen for contrast. */
        val textColor: Int = Color.WHITE
    )

    private val dp = context.resources.displayMetrics.density

    private val bgPaint = Paint().apply {
        color = Color.argb(200, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }

    private val minTextSizePx = 8f * dp
    private val boxPadding = 6f * dp
    /** Small inset so text doesn't touch the edges of the background. */
    private val textMargin = 3f * dp

    private var boxes: List<TextBox> = emptyList()
    private var cropOffsetX = 0
    private var cropOffsetY = 0
    private var displayScaleX = 1f
    private var displayScaleY = 1f
    private var screenshotW = 1
    private var screenshotH = 1

    private data class DrawnBox(val rect: RectF, val layout: StaticLayout, val bgColor: Int)
    /** Cached layouts to avoid recomputation on every draw. */
    private var cachedLayouts: List<DrawnBox>? = null

    fun setBoxes(
        boxes: List<TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int
    ) {
        this.boxes = boxes
        cropOffsetX = cropLeft
        cropOffsetY = cropTop
        this.screenshotW = screenshotW
        this.screenshotH = screenshotH
        if (width > 0 && height > 0) updateScales()
        cachedLayouts = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateScales()
        cachedLayouts = null
    }

    private fun updateScales() {
        displayScaleX = width.toFloat() / screenshotW
        displayScaleY = height.toFloat() / screenshotH
    }

    private fun mapRect(r: Rect): RectF {
        val left   = (r.left   + cropOffsetX) * displayScaleX
        val top    = (r.top    + cropOffsetY) * displayScaleY
        val right  = (r.right  + cropOffsetX) * displayScaleX
        val bottom = (r.bottom + cropOffsetY) * displayScaleY
        return RectF(left, top, right, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        if (boxes.isEmpty()) return

        val layouts = cachedLayouts ?: buildLayouts()
        cachedLayouts = layouts

        for (drawn in layouts) {
            bgPaint.color = drawn.bgColor
            canvas.drawRect(drawn.rect, bgPaint)

            canvas.save()
            val textX = drawn.rect.left + textMargin
            val textY = drawn.rect.top + (drawn.rect.height() - drawn.layout.height) / 2f
            canvas.translate(textX, textY)
            drawn.layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun buildLayouts(): List<DrawnBox> {
        val displayW = width.toFloat()
        val displayH = height.toFloat()

        // Map OCR bounds to screen coordinates, expanded by boxPadding
        // so the background extends beyond the tight OCR region.
        val screenRects = boxes.map { box ->
            val r = mapRect(box.bounds)
            RectF(
                (r.left - boxPadding).coerceAtLeast(0f),
                (r.top - boxPadding).coerceAtLeast(0f),
                (r.right + boxPadding).coerceAtMost(displayW),
                (r.bottom + boxPadding).coerceAtMost(displayH)
            )
        }

        // Widen each box horizontally if it won't overlap others
        val widenedRects = screenRects.mapIndexed { i, rect ->
            val desiredExpand = rect.width() * 0.3f
            var newLeft = (rect.left - desiredExpand / 2).coerceAtLeast(0f)
            var newRight = (rect.right + desiredExpand / 2).coerceAtMost(displayW)

            for (j in screenRects.indices) {
                if (j == i) continue
                val other = screenRects[j]
                if (rect.top < other.bottom && rect.bottom > other.top) {
                    if (newLeft < other.right && rect.left >= other.right) {
                        newLeft = other.right
                    }
                    if (newRight > other.left && rect.right <= other.left) {
                        newRight = other.left
                    }
                }
            }
            RectF(newLeft, rect.top, newRight, rect.bottom)
        }

        // Resolve vertical overlaps: for each pair of rects that overlap,
        // trim their shared edge to the midpoint so backgrounds don't stack.
        val finalRects = widenedRects.map { RectF(it) } // mutable copies
        // Sort indices by top coordinate for pairwise overlap checks
        val sortedIndices = finalRects.indices.sortedBy { finalRects[it].top }
        for (a in sortedIndices.indices) {
            for (b in a + 1 until sortedIndices.size) {
                val i = sortedIndices[a]
                val j = sortedIndices[b]
                val ri = finalRects[i]
                val rj = finalRects[j]
                // Check if they overlap vertically AND horizontally
                if (ri.bottom > rj.top && ri.left < rj.right && ri.right > rj.left) {
                    val mid = (ri.bottom + rj.top) / 2f
                    ri.bottom = mid
                    rj.top = mid
                }
            }
        }

        return boxes.zip(finalRects).map { (box, screenRect) ->
            // Text fills the full background box minus a small margin
            val availW = (screenRect.width() - 2 * textMargin).toInt().coerceAtLeast(1)
            val availH = (screenRect.height() - 2 * textMargin).coerceAtLeast(0f)
            val layout = fitText(box.translatedText, availW, availH, box.textColor)
            DrawnBox(screenRect, layout, box.bgColor)
        }
    }

    /**
     * Creates a [StaticLayout] for [text] that fits within [availW] x [availH] pixels.
     * Binary search for the largest font size where the text fits.
     */
    private fun fitText(text: String, availW: Int, availH: Float, textColor: Int = Color.WHITE): StaticLayout {
        var lo = minTextSizePx
        var hi = availH.coerceAtLeast(minTextSizePx)

        // Binary search using the shared paint for measurement
        repeat(10) {
            val mid = (lo + hi) / 2f
            textPaint.textSize = mid
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, availW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setMaxLines(100)
                .build()
            if (layout.height <= availH) {
                lo = mid
            } else {
                hi = mid
            }
        }

        // Create the final layout with its own TextPaint copy so the
        // text size is frozen — otherwise all layouts share the same
        // paint and render at whatever size was set last.
        val frozenPaint = TextPaint(textPaint)
        frozenPaint.textSize = lo
        frozenPaint.color = textColor
        return StaticLayout.Builder.obtain(text, 0, text.length, frozenPaint, availW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(100)
            .build()
    }
}
