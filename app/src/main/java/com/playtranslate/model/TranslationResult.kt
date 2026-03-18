package com.playtranslate.model

/**
 * Represents one tappable segment of recognised text.
 * Each segment maps to a single ML Kit TextElement so the user
 * can tap it for a Jisho dictionary lookup.
 */
data class TextSegment(
    val text: String,
    /** True if this segment is just whitespace / punctuation used as a separator */
    val isSeparator: Boolean = false
)

/**
 * Full result returned after one capture → OCR → translate cycle.
 */
data class TranslationResult(
    /** Original text reconstructed from ML Kit blocks, with newlines between blocks */
    val originalText: String,
    /** Flat list of tappable segments derived from TextElements */
    val segments: List<TextSegment>,
    /** Translated full text */
    val translatedText: String,
    /** Human-readable timestamp, e.g. "14:32:05" */
    val timestamp: String,
    /** Absolute path to the saved screenshot JPEG, or null if saving failed */
    val screenshotPath: String? = null,
    /** Optional inline warning shown below the translation (e.g. offline-mode notice) */
    val note: String? = null
)
