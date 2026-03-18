package com.playtranslate.model

import com.google.gson.annotations.SerializedName

data class JishoResponse(
    val meta: JishoMeta,
    val data: List<JishoWord>
)

data class JishoMeta(
    val status: Int
)

data class JishoWord(
    val slug: String,
    @SerializedName("is_common") val isCommon: Boolean?,
    val tags: List<String>,
    val jlpt: List<String>,
    val japanese: List<JapaneseForm>,
    val senses: List<JishoSense>,
    val freqScore: Int = 0
)

data class JapaneseForm(
    val word: String?,
    val reading: String?
)

data class JishoSense(
    @SerializedName("english_definitions") val englishDefinitions: List<String>,
    @SerializedName("parts_of_speech") val partsOfSpeech: List<String>,
    val tags: List<String>,
    val restrictions: List<String>,
    val info: List<String>,
    val misc: List<String> = emptyList()
)

/**
 * Per-kanji detail from KANJIDIC2.
 * [jlpt] uses new N-levels: 5=N5 (easiest) … 2=N2, 0=not in JLPT.
 * [grade] is school grade 1-6, 8=secondary school, 0=ungraded.
 */
data class KanjiDetail(
    val literal: Char,
    val meanings: List<String>,
    val onReadings: List<String>,
    val kunReadings: List<String>,
    val jlpt: Int,
    val grade: Int,
    val strokeCount: Int
)
