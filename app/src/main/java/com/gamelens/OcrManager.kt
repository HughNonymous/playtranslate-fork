package com.gamelens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.gamelens.model.TextSegment
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps ML Kit's Japanese text recogniser.
 *
 * OCR pipeline:
 *  1. Scale up small crops so fine text has enough pixels to be read accurately.
 *  2. Group TextBlocks by similar line height so same-size text stays together
 *     and different-size text (dialogue vs. UI labels) is split into paragraphs.
 *  3. Filter out groups whose text is entirely ASCII — these are target-language
 *     labels (e.g. "TALK", "HP: 100") that need no translation.
 *  4. Drop individual elements that are purely UI decoration (arrows, angle
 *     brackets used as dialogue cursors, etc.).
 */
class OcrManager {

    private val recognizer = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build()
    )

    data class OcrResult(
        /** Full text joined across groups, suitable for bulk translation. */
        val fullText: String,
        /** Flat list of segments (one per TextElement) for tappable display. */
        val segments: List<TextSegment>
    )

    suspend fun recognise(bitmap: Bitmap, sourceLang: String = "ja"): OcrResult? {
        // 1. Scale up if the shorter dimension is small — improves OCR on fine text.
        val scaled = scaleBitmapForOcr(bitmap)
        // 2. Boost contrast — makes small diacritic marks (dakuten: ぞ vs そ, ば vs は, etc.)
        //    unambiguous by pushing near-white pixels to white and near-black to black.
        //    Game text on flat backgrounds binarises cleanly, reducing ML Kit flip-flopping.
        val enhanced = enhanceContrast(scaled)

        val visionText: Text = try {
            suspendCancellableCoroutine { cont ->
                recognizer.process(InputImage.fromBitmap(enhanced, 0))
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } finally {
            if (enhanced !== scaled) enhanced.recycle()
            if (scaled !== bitmap) scaled.recycle()
        }

        if (visionText.textBlocks.isEmpty()) return null

        // 2. Group by similar line height.
        // 3. Discard groups that contain no character from the source language's script.
        //    This correctly excludes pure-Latin romanizations ("Yuko"), symbols ("★☆:"),
        //    and Latin-with-diacritics ("Yūko") while keeping mixed text ("裕子Hello").
        val groups = groupBlocksBySize(visionText.textBlocks)
            .filter { group ->
                group.any { block -> block.text.any { c -> isSourceLangChar(c, sourceLang) } }
            }

        if (groups.isEmpty()) return null

        val segments = mutableListOf<TextSegment>()
        val fullTextBuilder = StringBuilder()

        groups.forEachIndexed { gi, group ->
            if (gi > 0) {
                fullTextBuilder.append(" ")  // space for translation (no paragraph breaks)
                segments += TextSegment("\n\n", isSeparator = true)
            }
            // Blocks within the same group are continuous text (merged by proximity).
            // No separator between them — they flow as one sentence.
            group.forEach { block ->
                block.lines.forEachIndexed { li, line ->
                    if (li > 0) {
                        fullTextBuilder.append(" ")  // space for translation (no line breaks)
                        segments += TextSegment("\n", isSeparator = true)
                    }
                    line.elements.forEach { element ->
                        if (!isUiDecoration(element.text)) {
                            fullTextBuilder.append(element.text)
                            segments += TextSegment(element.text)
                        }
                    }
                }
            }
        }

        val fullText = fullTextBuilder.toString().trim()
        return if (fullText.isBlank()) null else OcrResult(fullText, segments)
    }

    /**
     * Returns true for OCR elements that are pure UI decoration rather than
     * dialogue text — arrows used as "more text" cursors, angle brackets used
     * as decorative dialogue borders, etc.  Only matches elements whose entire
     * text content is made up of these symbols so real Japanese text containing
     * similar characters is never silently dropped.
     */
    private fun isUiDecoration(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        return t.all { it in UI_DECORATION_CHARS }
    }

    /**
     * Applies a strong contrast boost so that small diacritic marks (dakuten, handakuten)
     * are pushed to clearly-on or clearly-off rather than sitting in a grey zone that
     * ML Kit reads inconsistently across frames.
     *
     * The ColorMatrix formula: output = input * scale + translate.
     * With scale=2.0, translate=-127: a pixel at 200 → 273 (clipped to 255, stays white);
     * a pixel at 30 → -67 (clipped to 0, stays black). Grey mid-tones snap to one extreme.
     */
    private fun enhanceContrast(bitmap: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val scale = 2.0f
        val translate = (1f - scale) / 2f * 255f   // -127.5
        val cm = ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(bitmap, 0f, 0f, paint)
        return out
    }

    /**
     * Scales the bitmap up if its shorter side is ≤ 1600 px.
     * Targets ~2000 px on that axis (capped at 3×) so that small dialogue text
     * has enough pixels to be read correctly.
     *
     * The threshold is set above the Ayn Thor game-screen width (1080 px) so
     * full-screen captures are also upscaled, giving ML Kit larger kanji strokes
     * and reducing confusion between visually similar characters (e.g. 機 vs 横).
     */
    private fun scaleBitmapForOcr(bitmap: Bitmap): Bitmap {
        val minDim = minOf(bitmap.width, bitmap.height)
        if (minDim > 1600) return bitmap
        val scale = (2000f / minDim).coerceAtMost(3f)
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true   // bilinear filtering for better quality
        )
    }

    /**
     * Groups TextBlocks into paragraphs based on proximity and size similarity.
     *
     * Blocks are processed in top-to-bottom order. A block is merged into the
     * current group when ALL of the following hold:
     *  1. Its median line height is within 50 % of the previous block's height.
     *  2. The vertical gap between them is ≤ 1.5× the larger line height
     *     (typical dialogue line spacing; larger gaps indicate separate sections).
     *  3. The current group's text does not end with a sentence-final punctuation
     *     mark (。！？!?…) — those indicate a complete sentence boundary.
     */
    private fun groupBlocksBySize(blocks: List<Text.TextBlock>): List<List<Text.TextBlock>> {
        val sorted = blocks.sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }
        if (sorted.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<Text.TextBlock>>()

        for (block in sorted) {
            val blockH = medianLineHeight(block)
            val blockTop = block.boundingBox?.top ?: Int.MAX_VALUE

            val lastGroup = groups.lastOrNull()
            if (lastGroup != null && blockH > 0) {
                val prev = lastGroup.last()
                val prevH = medianLineHeight(prev)
                val prevBottom = prev.boundingBox?.bottom ?: 0

                val gap = blockTop - prevBottom
                val refH = maxOf(blockH, prevH)

                val sizeMatch = prevH > 0 && run {
                    val lo = minOf(blockH, prevH)
                    val hi = maxOf(blockH, prevH)
                    (hi - lo).toDouble() / lo <= 0.50
                }
                val closeEnough = refH > 0 && gap <= (refH * 2.5f).toInt()
                val noSentenceEnd = run {
                    val tail = lastGroup.joinToString("") { it.text }.trimEnd()
                    tail.isEmpty() || tail.last() !in setOf('。', '！', '!', '？', '?', '…', '.')
                }

                // Merge even past sentence-end punctuation if a Japanese quote is still open
                val hasOpenQuote = run {
                    val text = lastGroup.joinToString("") { it.text }
                    text.count { it == '「' || it == '『' } > text.count { it == '」' || it == '』' }
                }

                if (sizeMatch && closeEnough && (noSentenceEnd || hasOpenQuote)) {
                    lastGroup += block
                    continue
                }
            }

            groups += mutableListOf(block)
        }

        return groups
    }

    private fun medianLineHeight(block: Text.TextBlock): Int {
        val heights = block.lines.mapNotNull { it.boundingBox?.height() }.sorted()
        return if (heights.isEmpty()) 0 else heights[heights.size / 2]
    }

    /** A line of OCR text with its bounding box in original (pre-scale) screen coordinates. */
    data class OcrLine(
        val text: String,
        val bounds: Rect
    )

    /**
     * Runs OCR and returns lines with bounding boxes mapped back to the original
     * bitmap's coordinate space (undoing the internal upscale).
     * Used by drag-to-lookup to hit-test finger position against text lines.
     */
    suspend fun recogniseWithPositions(bitmap: Bitmap, sourceLang: String = "ja"): List<OcrLine>? {
        val scaled = scaleBitmapForOcr(bitmap)
        val scaleFactor = scaled.width.toFloat() / bitmap.width
        val enhanced = enhanceContrast(scaled)

        val visionText: Text = try {
            suspendCancellableCoroutine { cont ->
                recognizer.process(InputImage.fromBitmap(enhanced, 0))
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } finally {
            if (enhanced !== scaled) enhanced.recycle()
            if (scaled !== bitmap) scaled.recycle()
        }

        if (visionText.textBlocks.isEmpty()) return null

        val lines = mutableListOf<OcrLine>()
        for (block in visionText.textBlocks) {
            if (!block.text.any { isSourceLangChar(it, sourceLang) }) continue
            for (line in block.lines) {
                val b = line.boundingBox ?: continue
                val text = line.elements
                    .filter { !isUiDecoration(it.text) }
                    .joinToString("") { it.text }
                if (text.isBlank()) continue
                // Map bounding box back to original bitmap coordinates
                lines += OcrLine(
                    text = text,
                    bounds = Rect(
                        (b.left / scaleFactor).toInt(),
                        (b.top / scaleFactor).toInt(),
                        (b.right / scaleFactor).toInt(),
                        (b.bottom / scaleFactor).toInt()
                    )
                )
            }
        }
        return lines.ifEmpty { null }
    }

    fun close() = recognizer.close()

    companion object {
        /**
         * Returns true if [c] belongs to a script that is native to [sourceLang].
         * Used to filter out OCR groups that contain no source-language characters —
         * e.g. romanizations, symbols, or Latin-with-diacritics when translating from Japanese.
         */
        fun isSourceLangChar(c: Char, sourceLang: String): Boolean = when (sourceLang) {
            "ja" -> c in '\u3040'..'\u309F'   // Hiragana
                 || c in '\u30A0'..'\u30FF'   // Katakana
                 || c in '\u4E00'..'\u9FFF'   // CJK Unified Ideographs (kanji)
                 || c in '\u3400'..'\u4DBF'   // CJK Extension A
                 || c in '\uFF65'..'\uFF9F'   // Half-width Katakana
            "zh", "zh-TW" ->
                   c in '\u4E00'..'\u9FFF'
                 || c in '\u3400'..'\u4DBF'
            "ko" -> c in '\uAC00'..'\uD7AF'   // Hangul Syllables
                 || c in '\u1100'..'\u11FF'   // Hangul Jamo
                 || c in '\u3130'..'\u318F'   // Hangul Compatibility Jamo
            "ar" -> c in '\u0600'..'\u06FF'   // Arabic
            "ru", "bg", "uk" ->
                   c in '\u0400'..'\u04FF'   // Cyrillic
            "th" -> c in '\u0E00'..'\u0E7F'   // Thai
            "hi", "mr", "ne" ->
                   c in '\u0900'..'\u097F'   // Devanagari
            else -> c.code > 0x007F            // Generic: any non-ASCII
        }

        /** UI-only symbols that are never meaningful dialogue text on their own. */
        private val UI_DECORATION_CHARS = setOf(
            // Arrows / triangles used as dialogue-advance or selection cursors
            '▼', '▽', '▲', '△', '▸', '▾', '◂', '◀', '▶', '►', '◄',
            '↓', '↑', '←', '→', '↵', '↩',
            // Angle brackets used as decorative dialogue borders
            '<', '>', '＜', '＞', '〈', '〉', '《', '》', '«', '»'
        )
    }
}
