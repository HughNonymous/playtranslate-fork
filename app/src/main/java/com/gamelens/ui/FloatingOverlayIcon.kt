package com.gamelens.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/**
 * A circular floating icon that snaps to left/right screen edges,
 * showing only half the circle. Supports drag, fling, and tap.
 *
 * Position is persisted as edge (LEFT=0, RIGHT=1) + fraction (0..1)
 * along that edge vertically.
 *
 * During a drag, switches to a "magnifying glass ring" appearance so the
 * text underneath is visible for screenshot capture.
 */
class FloatingOverlayIcon(context: Context) : View(context) {

    enum class Edge { LEFT, RIGHT }

    /** Diameter of the visible circle. */
    private val circleSizePx = (56 * resources.displayMetrics.density).toInt()
    /** Extra touch padding around the circle for easier grabbing. */
    private val touchPaddingPx = (12 * resources.displayMetrics.density).toInt()
    /** Total view size (circle + padding on each side). */
    val viewSizePx = circleSizePx + touchPaddingPx * 2
    private val circleHalf = circleSizePx / 2
    private val viewHalf = viewSizePx / 2

    // ── Normal mode paints ──────────────────────────────────────────────
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BCD4")
        style = Paint.Style.FILL
    }
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24 * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    // ── Drag mode paints (ring + magnifying glass) ──────────────────────
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BCD4")
        style = Paint.Style.STROKE
        strokeWidth = 3 * resources.displayMetrics.density
    }
    private val magGlassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BCD4")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    var onTap: (() -> Unit)? = null
    var onPositionChanged: ((edge: Int, fraction: Float) -> Unit)? = null
    /** Called once when drag mode activates (past tap threshold). Icon is now transparent. */
    var onDragStart: (() -> Unit)? = null
    /** Called on every ACTION_MOVE during a drag (rawX, rawY screen coords). */
    var onDragMove: ((Float, Float) -> Unit)? = null
    /** Called on ACTION_UP after a drag. Return true if popup is active (icon returns to saved pos). */
    var onDragEnd: (() -> Boolean)? = null

    var wm: WindowManager? = null
    var params: WindowManager.LayoutParams? = null
    var screenW = 0
    var screenH = 0

    private var velocityTracker: VelocityTracker? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var downParamX = 0
    private var downParamY = 0
    private var totalMovement = 0f
    private var snapAnimator: ValueAnimator? = null
    private var lastXVel = 0f
    private var lastYVel = 0f
    /** Saved position before drag started (for restoring when popup is shown). */
    private var savedParamX = 0
    private var savedParamY = 0
    /** True while in drag mode (ring appearance). */
    private var inDragMode = false
    /** Whether onDragStart has already been called for this gesture. */
    private var dragStartFired = false

    private val tapThresholdPx = TAP_THRESHOLD_DP * resources.displayMetrics.density
    /** Inset from top/bottom to avoid system gesture zones. */
    private val gestureInsetPx = (48 * resources.displayMetrics.density).toInt()
    private val minCy get() = gestureInsetPx
    private val maxCy get() = screenH - gestureInsetPx

    companion object {
        private const val FLING_THRESHOLD = 600f // px/s
        private const val TAP_THRESHOLD_DP = 10f
        private const val SNAP_DURATION_MS = 250L
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
        }
    }

    override fun onDraw(canvas: Canvas) {
        val center = viewSizePx / 2f
        val r = circleSizePx / 2f

        if (inDragMode) {
            // Ring only (transparent inside so text is visible for screenshot)
            canvas.drawCircle(center, center, r - ringPaint.strokeWidth / 2, ringPaint)
            // Small magnifying glass icon in center
            drawMagnifyingGlass(canvas, center, center, r * 0.4f)
        } else {
            // Normal: solid circle with "G"
            canvas.drawCircle(center, center, r, circlePaint)
            val textY = center - (letterPaint.descent() + letterPaint.ascent()) / 2f
            canvas.drawText("G", center, textY, letterPaint)
        }
    }

    private fun drawMagnifyingGlass(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val glassR = size * 0.55f
        val glassOffY = -size * 0.15f
        // Glass circle
        canvas.drawCircle(cx + glassOffY, cy + glassOffY, glassR, magGlassPaint)
        // Handle line from bottom-right of circle
        val handleStartX = cx + glassOffY + glassR * 0.707f
        val handleStartY = cy + glassOffY + glassR * 0.707f
        val handleLen = size * 0.45f
        canvas.drawLine(
            handleStartX, handleStartY,
            handleStartX + handleLen * 0.707f, handleStartY + handleLen * 0.707f,
            magGlassPaint
        )
    }

    private fun enterDragMode() {
        if (inDragMode) return
        inDragMode = true
        invalidate()
    }

    private fun exitDragMode() {
        if (!inDragMode) return
        inDragMode = false
        dragStartFired = false
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapAnimator?.cancel()
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
                downRawX = event.rawX
                downRawY = event.rawY
                downParamX = params?.x ?: 0
                downParamY = params?.y ?: 0
                savedParamX = downParamX
                savedParamY = downParamY
                totalMovement = 0f
                lastXVel = 0f
                lastYVel = 0f
                dragStartFired = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                lastXVel = velocityTracker?.xVelocity ?: 0f
                lastYVel = velocityTracker?.yVelocity ?: 0f

                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                totalMovement = abs(dx) + abs(dy)
                val p = params ?: return true
                p.x = (downParamX + dx).toInt()
                p.y = (downParamY + dy).toInt()
                try { wm?.updateViewLayout(this, p) } catch (_: Exception) {}

                if (totalMovement >= tapThresholdPx) {
                    if (!dragStartFired) {
                        // Center the icon on the finger (user may have grabbed off-center)
                        val rawX = event.rawX
                        val rawY = event.rawY
                        p.x = (rawX - viewHalf).toInt()
                        p.y = (rawY - viewHalf).toInt()
                        // Rebase so future moves are relative to this centered position
                        downRawX = rawX
                        downRawY = rawY
                        downParamX = p.x
                        downParamY = p.y
                        try { wm?.updateViewLayout(this, p) } catch (_: Exception) {}

                        // Switch to ring appearance, then notify controller to screenshot
                        enterDragMode()
                        dragStartFired = true
                        // Post so the WM redraws the ring before screenshot is taken
                        post { onDragStart?.invoke() }
                    }
                    onDragMove?.invoke(event.rawX, event.rawY)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                lastXVel = velocityTracker?.xVelocity ?: lastXVel
                lastYVel = velocityTracker?.yVelocity ?: lastYVel
                velocityTracker?.recycle()
                velocityTracker = null

                exitDragMode()

                if (totalMovement < tapThresholdPx) {
                    onTap?.invoke()
                } else if (onDragEnd?.invoke() == true) {
                    restorePosition()
                } else {
                    snapToEdge(lastXVel, lastYVel)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
                exitDragMode()
                snapToEdge(0f, 0f)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun snapToEdge(xVel: Float, yVel: Float) {
        val p = params ?: return
        val cx = p.x + viewHalf
        val cy = p.y + viewHalf

        val dragDx = p.x - downParamX
        val flingMatchesDrag = (xVel > 0) == (dragDx > 0) && dragDx != 0
        val hasFling = abs(xVel) > FLING_THRESHOLD && flingMatchesDrag

        val edge = when {
            hasFling && xVel < 0 -> Edge.LEFT
            hasFling && xVel > 0 -> Edge.RIGHT
            cx < screenW / 2 -> Edge.LEFT
            else -> Edge.RIGHT
        }

        val edgeCx = if (edge == Edge.LEFT) 0 else screenW
        val targetX = edgeCx - viewHalf

        val targetCy: Int = if (hasFling && abs(xVel) > 1f) {
            (cy + (edgeCx - cx).toFloat() / xVel * yVel).toInt()
        } else {
            cy
        }

        val targetY = targetCy.coerceIn(minCy, maxCy) - viewHalf
        animateTo(targetX, targetY, edge)
    }

    private fun animateTo(targetX: Int, targetY: Int, edge: Edge? = null) {
        val p = params ?: return
        val startX = p.x
        val startY = p.y
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                p.x = (startX + (targetX - startX) * t).toInt()
                p.y = (startY + (targetY - startY) * t).toInt()
                try { wm?.updateViewLayout(this@FloatingOverlayIcon, p) } catch (_: Exception) {}
            }
            if (edge != null) {
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        val cy = p.y + viewHalf
                        val range = maxCy - minCy
                        val fraction = if (range > 0) (cy - minCy).toFloat() / range else 0.5f
                        onPositionChanged?.invoke(edge.ordinal, fraction.coerceIn(0f, 1f))
                    }
                })
            }
            start()
        }
    }

    private fun restorePosition() {
        animateTo(savedParamX, savedParamY)
    }

    /** Sets position from persisted edge + fraction without animation. */
    fun setPosition(edgeOrdinal: Int, fraction: Float) {
        val edge = if (edgeOrdinal == Edge.LEFT.ordinal) Edge.LEFT else Edge.RIGHT
        val f = if (fraction in 0f..1f) fraction else 0.5f
        val p = params ?: return
        p.x = if (edge == Edge.LEFT) -viewHalf else screenW - viewHalf
        val cy = (minCy + f * (maxCy - minCy)).toInt().coerceIn(minCy, maxCy)
        p.y = cy - viewHalf
    }
}
