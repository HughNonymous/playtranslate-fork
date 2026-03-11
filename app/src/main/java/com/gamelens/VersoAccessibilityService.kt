package com.gamelens

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.gamelens.ui.FloatingOverlayIcon
import com.gamelens.ui.RegionDragView
import com.gamelens.ui.RegionOverlayView

/**
 * Minimal AccessibilityService whose only purpose is to call
 * [takeScreenshot] on a specific display.
 *
 * WHY AN ACCESSIBILITY SERVICE?
 * ─────────────────────────────
 * MediaProjection captures the display the requesting Activity runs on.
 * Launching a bridge Activity on the game display caused the whole app
 * to move there. AccessibilityService.takeScreenshot(displayId) captures
 * any display by ID with no UI, no focus change, and no app relocation.
 *
 * SETUP (one-time)
 * ─────────────────
 * Settings → Accessibility → Installed apps → PlayTranslate → Enable
 * The app detects the enabled state via [isEnabled].
 */
class PlayTranslateAccessibilityService : AccessibilityService() {

    private var overlayView: RegionOverlayView? = null
    private var overlayWm: WindowManager? = null
    private var dragView: RegionDragView? = null
    private var dragWm: WindowManager? = null
    private var floatingIcon: FloatingOverlayIcon? = null
    private var floatingIconWm: WindowManager? = null
    private var floatingIconDisplayId: Int = -1

    /** True while MainActivity is visible. Set only by notifyAppResumed/notifyAppStopped. */
    private var appVisible = false

    override fun onServiceConnected() {
        instance = this
        ensureFloatingIcon()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hideRegionOverlay()
        hideRegionDragOverlay()
        hideFloatingIcon()
        instance = null
        return super.onUnbind(intent)
    }

    fun showRegionOverlay(
        display: Display,
        topFraction: Float, bottomFraction: Float,
        leftFraction: Float = 0f, rightFraction: Float = 1f
    ) {
        hideRegionOverlay()
        val wm = createDisplayContext(display).getSystemService(WindowManager::class.java) ?: return
        val view = RegionOverlayView(this).apply {
            updateRegion(topFraction, bottomFraction, leftFraction, rightFraction)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(view, params)
        overlayWm = wm
        overlayView = view
    }

    fun updateRegionOverlay(
        topFraction: Float, bottomFraction: Float,
        leftFraction: Float = 0f, rightFraction: Float = 1f
    ) {
        overlayView?.updateRegion(topFraction, bottomFraction, leftFraction, rightFraction)
    }

    fun hideRegionOverlay() {
        try { overlayView?.let { overlayWm?.removeView(it) } } catch (_: Exception) {}
        overlayView = null
        overlayWm = null
    }

    fun showRegionDragOverlay(display: Display, onRegionChanged: (Float, Float, Float, Float) -> Unit) {
        hideRegionDragOverlay()
        val wm = createDisplayContext(display).getSystemService(WindowManager::class.java) ?: return
        val view = RegionDragView(this).apply {
            setRegion(0.25f, 0.75f, 0.25f, 0.75f)
            this.onRegionChanged = onRegionChanged
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(view, params)
        dragWm = wm
        dragView = view
    }

    fun hideRegionDragOverlay() {
        try { dragView?.let { dragWm?.removeView(it) } } catch (_: Exception) {}
        dragView = null
        dragWm = null
    }

    fun getDragRegion(): Array<Float> = dragView?.getFullRegion() ?: arrayOf(0.25f, 0.75f, 0.25f, 0.75f)

    // ── Floating overlay icon ─────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Called from MainActivity.onResume. */
    fun notifyAppResumed() {
        appVisible = true
        if (iconIsOnAppDisplay()) {
            hideFloatingIcon()
        } else {
            // Dual-screen: icon is on a different display, ensure it's showing
            ensureFloatingIcon()
        }
    }

    /** Called from MainActivity.onStop. */
    fun notifyAppStopped() {
        appVisible = false
        ensureFloatingIcon()
    }

    /**
     * Single entry point for all icon visibility updates.
     * Called from: onServiceConnected, notifyAppResumed/Stopped,
     * settings toggle, capture display change.
     *
     * Shows, hides, or relocates the icon based on current state.
     */
    fun ensureFloatingIcon() {
        val prefs = Prefs(this)
        if (!prefs.showOverlayIcon) {
            hideFloatingIcon()
            return
        }
        val onAppDisplay = iconIsOnAppDisplay()
        if (appVisible && onAppDisplay) {
            Log.d(TAG, "ensureFloatingIcon: hiding (appVisible=$appVisible, onAppDisplay=true)")
            hideFloatingIcon()
            return
        }
        val display = findIconDisplay(prefs) ?: return
        if (floatingIcon != null && floatingIconDisplayId == display.displayId) return
        Log.d(TAG, "ensureFloatingIcon: showing on display ${display.displayId} (current=$floatingIconDisplayId, captureId=${prefs.captureDisplayId})")
        showFloatingIcon(display, prefs)
    }

    /**
     * True when the icon's target display is the same physical display the app runs on.
     * Single-screen (only 1 physical display): always true.
     * Multi-screen: true only if capture display == app display.
     *
     * Note: uses physical display count, NOT Prefs.isSingleScreen().
     * The debug force-single-screen flag changes the UI flow but doesn't
     * change which physical display the icon belongs on.
     */
    private fun iconIsOnAppDisplay(): Boolean {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = dm.displays
        if (displays.size <= 1) return true
        val prefs = Prefs(this)
        val appDisplayId = displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }?.displayId
            ?: Display.DEFAULT_DISPLAY
        return prefs.captureDisplayId == appDisplayId
    }

    private fun showFloatingIcon(display: Display, prefs: Prefs) {
        hideFloatingIcon()
        val displayCtx = createDisplayContext(display)
        val wm = displayCtx.getSystemService(WindowManager::class.java) ?: return

        val screenSize = getDisplaySize(display)
        val icon = FloatingOverlayIcon(displayCtx).apply {
            this.wm = wm
            screenW = screenSize.x
            screenH = screenSize.y
        }

        val params = WindowManager.LayoutParams(
            icon.viewSizePx, icon.viewSizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }

        icon.params = params
        icon.setPosition(prefs.overlayIconEdge, prefs.overlayIconFraction)

        icon.onPositionChanged = { edge, fraction ->
            prefs.overlayIconEdge = edge
            prefs.overlayIconFraction = fraction
        }
        icon.onTap = {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }

        try {
            wm.addView(icon, params)
            floatingIconWm = wm
            floatingIcon = icon
            floatingIconDisplayId = display.displayId
        } catch (e: Exception) {
            Log.e(TAG, "showFloatingIcon: addView failed", e)
        }
    }

    fun hideFloatingIcon() {
        try { floatingIcon?.let { floatingIconWm?.removeView(it) } } catch (_: Exception) {}
        floatingIcon = null
        floatingIconWm = null
        floatingIconDisplayId = -1
    }

    /** Returns the capture display (game screen), or the only display on single-screen. */
    private fun findIconDisplay(prefs: Prefs): Display? {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = dm.displays
        if (displays.size <= 1) return displays.firstOrNull()
        return dm.getDisplay(prefs.captureDisplayId) ?: displays.firstOrNull()
    }

    @Suppress("DEPRECATION")
    private fun getDisplaySize(display: Display): Point {
        val size = Point()
        display.getRealSize(size)
        return size
    }

    // ── Screenshot ────────────────────────────────────────────────────────

    /**
     * Takes a screenshot of [displayId] and returns the result as a software
     * [Bitmap] via [onResult]. Returns null on failure or if API < 30.
     *
     * Must be called on a thread that has a Looper (e.g. main thread).
     */
    fun captureDisplay(displayId: Int, onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "takeScreenshot requires API 30+")
            onResult(null)
            return
        }

        takeScreenshot(
            displayId,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    // HardwareBuffer → software Bitmap so ML Kit can read pixels
                    val bitmap = Bitmap
                        .wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()
                    onResult(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "takeScreenshot failed on display $displayId, code=$errorCode")
                    onResult(null)
                }
            }
        )
    }

    companion object {
        private const val TAG = "PlayTranslateA11y"

        /** Non-null while the service is connected (i.e. user has it enabled). */
        var instance: PlayTranslateAccessibilityService? = null

        val isEnabled: Boolean get() = instance != null
    }
}
