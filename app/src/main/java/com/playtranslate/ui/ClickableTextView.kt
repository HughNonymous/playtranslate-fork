package com.playtranslate.ui

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView
import com.playtranslate.model.TextSegment

/**
 * A [TextView] that renders OCR text and fires [onTapAtOffset] with the character
 * position corresponding to where the user tapped, so the caller can open an editor
 * with the cursor pre-placed at the right spot.
 */
class ClickableTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    var onTapAtOffset: ((charOffset: Int) -> Unit)? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTapAtOffset?.invoke(offsetAt(e.x, e.y))
            return true
        }
    })

    fun setSegments(segments: List<TextSegment>) {
        text = segments.joinToString("") { it.text }
        highlightColor = 0x00000000
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        super.onTouchEvent(event)
        return true
    }

    private fun offsetAt(x: Float, y: Float): Int {
        val raw = text?.toString() ?: return 0
        val lyt = layout ?: return 0
        val tx = (x - totalPaddingLeft + scrollX).toInt()
        val ty = (y - totalPaddingTop + scrollY).toInt()
        val line = lyt.getLineForVertical(ty)
        return lyt.getOffsetForHorizontal(line, tx.toFloat())
            .coerceIn(0, (raw.length - 1).coerceAtLeast(0))
    }
}
