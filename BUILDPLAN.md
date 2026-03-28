# PlayTranslate Immersion Fork — Claude Code Build Plan

> **Instructions:** Do everything you can autonomously — clone the repo, make all code changes, create files, run builds, fix errors, commit. When you hit something that requires my manual action (forking on GitHub, Android Studio GUI steps, installing the APK on a device, granting permissions on-device, or anything else that needs a browser/screen/physical device), stop and tell me exactly what to do step by step, then wait for me to confirm before continuing.

## Project Overview

Fork PlayTranslate (https://github.com/dominostars/playtranslate) into an immersion-first Japanese reading tool for the AYN Thor dual-screen handheld. The app already does OCR → translation → word lookup. We are reorienting it so the default workflow is OCR → raw Japanese text → tap-to-lookup dictionary, with translation available but not shown by default.

**The codebase is Kotlin, uses View Binding (not Jetpack Compose), targets Android SDK 34, and builds with Gradle 8.6 / JDK 17.**

---

## Repository Structure (key files only)

```
app/src/main/java/com/playtranslate/
├── MainActivity.kt                         # Main UI, mode toggles, capture trigger buttons
├── CaptureService.kt                       # Foreground service: OCR + translation pipeline
├── OcrManager.kt                           # ML Kit Japanese OCR wrapper
├── TranslationManager.kt                   # ML Kit on-device translation
├── LingvaTranslator.kt                     # Google Translate API (free, no key)
├── DeepLTranslator.kt                      # DeepL API (optional, key required)
├── Prefs.kt                                # SharedPreferences wrapper for all settings
├── AnkiManager.kt                          # AnkiDroid intent-based export
├── ScreenshotManager.kt                    # Screenshot capture management
├── PlayTranslateAccessibilityService.kt    # Accessibility service: screenshots, overlays, key events
├── DetectionLog.kt                         # Debug logging for scene detection
├── ThemeUtils.kt                           # Theme helper functions
├── dictionary/
│   ├── DictionaryManager.kt                # Offline JMdict SQLite dictionary (44MB bundled DB)
│   └── Deinflector.kt                      # Kuromoji tokenizer + verb/adj deinflection
├── model/
│   ├── JishoModels.kt                      # Data classes: JishoWord, JishoSense, KanjiDetail, etc.
│   └── TranslationResult.kt                # OCR+translation result data class
└── ui/
    ├── TranslationResultFragment.kt        # Main results display: original text, translation, word list
    ├── WordLookupPopup.kt                  # Overlay popup: word + furigana + definitions + Anki button
    ├── WordDetailBottomSheet.kt            # Expanded word detail dialog
    ├── DragLookupController.kt             # Drag-to-lookup on game screen overlay
    ├── ClickableTextView.kt                # Tappable segmented text view
    ├── SettingsBottomSheet.kt              # Settings UI (full-screen dialog)
    ├── FloatingOverlayIcon.kt              # Floating icon on game screen
    ├── FloatingIconMenu.kt                 # Menu that appears from floating icon
    ├── TranslationOverlayView.kt           # Translation text overlay on game screen
    ├── RegionPickerSheet.kt                # Capture region selector
    ├── RegionDragView.kt                   # Visual region drag editor
    ├── AddCustomRegionSheet.kt             # Custom region creator
    ├── AnkiReviewBottomSheet.kt            # Anki card review before export
    ├── SentenceAnkiContentFragment.kt      # Sentence-level Anki card content
    ├── SentenceAnkiHtmlBuilder.kt          # HTML builder for Anki cards
    ├── WordAnkiReviewSheet.kt              # Word-level Anki review
    ├── WordAnkiReviewActivity.kt           # Standalone Anki review activity
    ├── TranslationResultActivity.kt        # Standalone results activity
    ├── LastSentenceCache.kt                # Cache for cross-component sentence data
    ├── OcrDebugOverlayView.kt              # Debug: OCR bounding box visualization
    └── RegionOverlayView.kt                # Region indicator overlay

app/src/main/res/layout/
├── activity_main.xml                       # Main activity layout
├── fragment_translation_result.xml         # Results fragment: Translation → Original → Words sections
├── dialog_settings.xml                     # Settings dialog layout
├── item_word_lookup.xml                    # Word list row layout
├── dialog_word_detail.xml                  # Word detail dialog layout
└── ... (other layouts)

app/src/main/res/values/
├── strings.xml                             # All string resources
├── styles.xml                              # Theme styles
├── colors.xml                              # Color definitions
└── attrs.xml                               # Custom theme attributes

app/src/main/assets/
└── jmdict.db                               # 44MB offline JMdict + KANJIDIC2 SQLite database

scripts/
└── build_jmdict.py                         # Python script to rebuild the dictionary DB from source
```

---

## Existing Capabilities (DO NOT rebuild these)

These features already work. Do not rewrite them. Modifications should be minimal and surgical.

1. **ML Kit Japanese OCR** — `OcrManager.kt` handles screenshot → text recognition with image upscaling, line grouping by proximity, menu detection, UI decoration filtering, and source-language character filtering. Returns `OcrResult` with full text, segments, group texts, and bounding boxes.

2. **Offline JMdict dictionary** — `DictionaryManager.kt` backed by a 44MB SQLite database (`jmdict.db` in assets). Contains entries from JMdict (kanji, readings, senses with POS, glosses, misc tags) and KANJIDIC2 (kanji meanings, on/kun readings, JLPT level, grade, stroke count). Supports exact match, deinflection via `Deinflector.kt` (Kuromoji IPADIC tokenizer), and batch phrase detection for multi-token expressions.

3. **Kuromoji tokenization** — `Deinflector.kt` wraps the Kuromoji IPADIC tokenizer. Provides `tokenize()` (base forms), `tokenizeWithSurfaces()` (surface + base form pairs for position mapping), `candidates()` (deinflection candidates for inflected forms), `toKana()` / `toKanaTokens()` (kana conversion for romaji).

4. **Tap-to-lookup popup** — `WordLookupPopup.kt` is a WindowManager overlay popup showing: word (kanji, large), furigana/reading above it, scrollable numbered definitions, "common" badge, frequency stars (1–5), and an Anki export button. Dismissable by tap outside or joystick movement.

5. **Word list panel** — `TranslationResultFragment.kt` method `startWordLookups()` tokenizes the OCR text, looks up each word in JMdict, and displays rows with word, reading, frequency stars, and definitions. Clicking a row opens `WordDetailBottomSheet`.

6. **Furigana tap** — Tapping a word in the original text view shows a small popup with the reading above the word (uses `PopupWindow` positioned relative to `ClickableTextView` layout offsets).

7. **Anki export** — `AnkiManager.kt` exports to AnkiDroid via intent API. `AnkiReviewBottomSheet` shows a preview before export. Cards include: original sentence, translation, word list with readings/meanings, and screenshot. Both sentence-level and word-level export are supported.

8. **Live mode** — `CaptureService.startLive()` runs a continuous screenshot loop with scene change detection. Captures only when text changes (dedup via character bag comparison). Shows translation overlay on game screen. Pauses during user input (gamepad buttons, touch) via interaction debounce.

9. **Capture regions** — `RegionPickerSheet` lists preset regions (Full screen, Bottom 50/33/25%, Top 50/33%) and custom regions. `RegionDragView` provides visual drag-to-select. Regions stored as fractional coordinates in `Prefs`.

10. **Translation pipeline** — `CaptureService.translate()` uses a waterfall: DeepL (if API key set) → Lingva (free Google Translate proxy) → ML Kit on-device fallback. Each can be independently disabled.

11. **Section visibility toggles** — `Prefs` already has `hideTranslationSection`, `hideOriginalSection`, `hideWordsSection` booleans. `TranslationResultFragment` has `applyTranslationVisibility()` etc. that toggle `View.GONE`/`View.VISIBLE`.

12. **Gamepad event interception** — `PlayTranslateAccessibilityService.onKeyEvent()` already intercepts all gamepad button presses (`isGamepadButton` check). The accessibility config has `flagRequestFilterKeyEvents` and `canRequestFilterKeyEvents="true"`. Currently used only for live mode interaction detection (fires `onGameInput` callback, tracks `buttonHeld` state).

13. **Dual-screen handling** — App runs on display 1 (bottom screen), captures display 0 (top screen) via `AccessibilityService.takeScreenshot(displayId)`. `Prefs.captureDisplayId` is configurable. `debugForceSingleScreen` flag enables single-screen testing.

---

## Phase 1: Immersion Mode

**Goal:** Make "read Japanese + tap to look up words" the default experience. Translation still works but is hidden by default and skipped in the pipeline for speed.

### Step 1.1: Add immersion mode preference

**File: `Prefs.kt`**

Add a new boolean preference:

```
var immersionMode: Boolean
    get() = sp.getBoolean(KEY_IMMERSION_MODE, true)
    set(v) = sp.edit().putBoolean(KEY_IMMERSION_MODE, v).apply()
```

Add the key constant:

```
private const val KEY_IMMERSION_MODE = "immersion_mode"
```

Default is `true` — immersion mode is the default experience.

When `immersionMode` is true, it implies `hideTranslationSection = true`. But the user can independently toggle translation visibility, so immersion mode controls the *pipeline behavior* (skip translation call), while `hideTranslationSection` controls the *UI visibility*. Both should be synchronized: when enabling immersion mode, also set `hideTranslationSection = true`.

### Step 1.2: Skip translation in the capture pipeline

**File: `CaptureService.kt`**

In `runCaptureCycle()` (the manual capture method, around the section after OCR completes where `translateGroups()` is called), wrap the translation call in an immersion mode check:

Find the section in the manual capture flow (around line 261) that does:
```kotlin
onStatusUpdate?.invoke(getString(R.string.status_translating))
val (translated, note) = translateGroups(ocrResult.groupTexts)
```

Change to:
```kotlin
val prefs = Prefs(this)
val translated: String
val note: String?
if (prefs.immersionMode) {
    translated = ""
    note = null
} else {
    onStatusUpdate?.invoke(getString(R.string.status_translating))
    val result = translateGroups(ocrResult.groupTexts)
    translated = result.first
    note = result.second
}
```

Do the same in `runProcessCycle()` (around line 261-262) which has the same pattern.

Do the same in `runLiveCaptureCycle()` (around line 1220) where `translateGroupsSeparately()` is called. When immersion mode is on, skip translation and create overlay boxes with empty translated text (skeleton placeholders are fine, or hide the overlay entirely):

```kotlin
if (prefs.immersionMode) {
    // Skip translation, just emit OCR result
    val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    onResult?.invoke(
        TranslationResult(
            originalText = newText,
            segments = ocrResult.segments,
            translatedText = "",
            timestamp = timestamp,
            screenshotPath = screenshotPath,
            note = null
        )
    )
    // Don't show translation overlay on game screen in immersion mode
    PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
} else {
    // existing translation code
}
```

### Step 1.3: Update the results fragment for immersion mode

**File: `TranslationResultFragment.kt`**

In `displayResult()`, when immersion mode is active:
- Force-hide the translation section regardless of toggle state
- Force-show the original section (the Japanese text)  
- Force-show the words section
- Skip setting `tvTranslation.text` (it's empty anyway)

After the existing `applyTranslationVisibility()` / `applyOriginalVisibility()` / `applyWordsVisibility()` calls, add:

```kotlin
if (prefs.immersionMode) {
    translationContent.visibility = View.GONE
    btnCopyTranslation.visibility = View.INVISIBLE
    btnToggleTranslation.visibility = View.GONE
    labelTranslation.visibility = View.GONE
    // Also hide the divider above translation
    originalContent.visibility = View.VISIBLE
    btnCopyOriginal.visibility = View.VISIBLE
    wordsContent.visibility = View.VISIBLE
}
```

Also modify the layout ordering consideration: currently the fragment layout has Translation section first, then Original, then Words. In immersion mode, the user primarily wants to see the Japanese text and word list. The layout order in the XML is Translation → Original → Words. Since we're hiding Translation entirely, Original will naturally be at the top — this is correct behavior. No XML layout change needed.

### Step 1.4: Add "Translate" button for on-demand translation in immersion mode

**File: `TranslationResultFragment.kt`**

Add a "Translate" button that appears only in immersion mode, allowing the user to request translation for the current OCR text when they want it. This button should be placed near the original text section.

Add a new button in the layout or create it programmatically. When tapped:

```kotlin
// In the button click handler:
val service = host?.getCaptureService() ?: return
val text = lastResult?.originalText ?: return
viewLifecycleOwner.lifecycleScope.launch {
    tvTranslation.text = getString(R.string.status_translating)
    translationContent.visibility = View.VISIBLE
    val (translated, note) = withContext(Dispatchers.IO) {
        service.translateOnce(text)
    }
    tvTranslation.text = translated
    tvTranslationNote.text = note ?: ""
    tvTranslationNote.visibility = if (note != null) View.VISIBLE else View.GONE
    lastResult = lastResult?.copy(translatedText = translated, note = note)
}
```

**File: `fragment_translation_result.xml`**

Add a small button (e.g., an `ImageButton` with a translate icon, or a simple `TextView` button labeled "Translate") in the original section header row, visible only in immersion mode. You can add it programmatically in `TranslationResultFragment` based on the immersion pref, or add it to the XML with initial `visibility="gone"` and toggle it.

### Step 1.5: Add immersion mode toggle to Settings

**File: `SettingsBottomSheet.kt`**

Add an "Immersion Mode" toggle switch near the top of the settings. This should be a prominent toggle since it's the primary mode switch.

Find the settings layout building code (it's programmatic, not XML-inflated for most rows). Add a toggle row:

```kotlin
// Add near the top of the settings, before the display/capture settings
// Label: "Immersion Mode"
// Subtitle: "Show Japanese text only — tap words to look up. Translation available on demand."
// Toggle: Switch bound to prefs.immersionMode
```

When the toggle changes:
```kotlin
prefs.immersionMode = isChecked
if (isChecked) {
    prefs.hideTranslationSection = true
}
```

### Step 1.6: Add string resources

**File: `res/values/strings.xml`**

Add:
```xml
<string name="pref_immersion_mode">Immersion Mode</string>
<string name="pref_immersion_mode_desc">Show Japanese text with tap-to-lookup dictionary. Translation available on demand.</string>
<string name="btn_translate_once">Translate</string>
```

---

## Phase 2: Gamepad Hotkey Trigger

**Goal:** Press a configurable gamepad button to trigger an OCR capture, keeping hands on the controller.

### Step 2.1: Add hotkey preferences

**File: `Prefs.kt`**

Add:
```kotlin
var hotkeyEnabled: Boolean
    get() = sp.getBoolean(KEY_HOTKEY_ENABLED, false)
    set(v) = sp.edit().putBoolean(KEY_HOTKEY_ENABLED, v).apply()

var hotkeyKeyCode: Int
    get() = sp.getInt(KEY_HOTKEY_KEYCODE, KeyEvent.KEYCODE_BUTTON_R1)
    set(v) = sp.edit().putInt(KEY_HOTKEY_KEYCODE, v).apply()

var volumeKeyTriggerEnabled: Boolean
    get() = sp.getBoolean(KEY_VOLUME_TRIGGER, false)
    set(v) = sp.edit().putBoolean(KEY_VOLUME_TRIGGER, v).apply()
```

Add key constants:
```kotlin
private const val KEY_HOTKEY_ENABLED = "hotkey_enabled"
private const val KEY_HOTKEY_KEYCODE = "hotkey_keycode"
private const val KEY_VOLUME_TRIGGER = "volume_key_trigger"
```

### Step 2.2: Add hotkey capture trigger to AccessibilityService

**File: `PlayTranslateAccessibilityService.kt`**

Modify `onKeyEvent()` (currently at line 572). The existing code intercepts all gamepad buttons for interaction detection. Add a hotkey check BEFORE the existing logic:

```kotlin
override fun onKeyEvent(event: KeyEvent): Boolean {
    val prefs = Prefs(this)
    
    // ── Hotkey capture trigger ──
    if (prefs.hotkeyEnabled && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
        val keyCode = event.keyCode
        val isHotkeyMatch = keyCode == prefs.hotkeyKeyCode
        val isVolumeMatch = prefs.volumeKeyTriggerEnabled && 
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        
        if (isHotkeyMatch || isVolumeMatch) {
            CaptureService.instance?.captureOnce()
            return true  // consume the event so the game/system doesn't also react
        }
    }
    
    // ── Existing gamepad interaction detection (for live mode) ──
    val src = event.source
    val isGameInput = src and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        || src and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
        || KeyEvent.isGamepadButton(event.keyCode)
    if (isGameInput) {
        // ... existing code unchanged ...
    }
    return false
}
```

**Important:** The hotkey check must return `true` to consume the event. This prevents the game from also receiving the button press. The user should choose a button they don't use in-game (bumpers are common). The `repeatCount == 0` check ensures only the first press triggers, not key repeat.

**Important:** When the hotkey matches, skip the existing `onGameInput?.invoke()` code so it doesn't also reset the live mode debounce timer.

### Step 2.3: Add volume key handling in MainActivity

**File: `MainActivity.kt`**

Override `onKeyDown` to handle volume keys when the app window has focus (bottom screen):

```kotlin
override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    val prefs = Prefs(this)
    if (prefs.hotkeyEnabled && prefs.volumeKeyTriggerEnabled) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            CaptureService.instance?.captureOnce()
            return true
        }
    }
    return super.onKeyDown(keyCode, event)
}
```

Note: Volume keys are handled differently from gamepad buttons. On Android, volume keys are dispatched to the focused window first, not through the accessibility service. So both the AccessibilityService handler (for when the game has focus) and the MainActivity handler (for when the app has focus) are needed.

### Step 2.4: Add hotkey configuration UI to Settings

**File: `SettingsBottomSheet.kt`**

Add a "Capture Hotkey" section with:

1. **Enable toggle** — "Gamepad Hotkey" switch bound to `prefs.hotkeyEnabled`

2. **Button picker** — When the toggle is on, show a row that displays the currently configured button name (e.g., "R1") and on tap shows a dialog:
   - Dialog text: "Press any gamepad button..."
   - Use `setOnKeyListener` on the dialog to capture the next `KeyEvent` and save its `keyCode` to `prefs.hotkeyKeyCode`
   - Display the button name using `KeyEvent.keyCodeToString(keyCode)` with cleanup (strip "KEYCODE_BUTTON_" prefix for readability)

3. **Volume key toggle** — "Volume Key Trigger" switch bound to `prefs.volumeKeyTriggerEnabled`, shown as a sub-option when hotkey is enabled

**File: `res/values/strings.xml`**

Add:
```xml
<string name="pref_hotkey_section">Capture Hotkey</string>
<string name="pref_hotkey_enabled">Gamepad Hotkey</string>
<string name="pref_hotkey_enabled_desc">Press a gamepad button to trigger OCR capture</string>
<string name="pref_hotkey_button">Trigger Button</string>
<string name="pref_hotkey_press_prompt">Press any gamepad button…</string>
<string name="pref_volume_trigger">Volume Key Trigger</string>
<string name="pref_volume_trigger_desc">Use volume up/down as capture trigger</string>
```

### Step 2.5: Add hotkey indicator feedback

When the hotkey triggers a capture, the user needs visual feedback that something happened. The existing `onStatusUpdate` callback already updates the status text on the bottom screen. Additionally, you could briefly flash the floating overlay icon or show a small toast.

**File: `PlayTranslateAccessibilityService.kt`**

After calling `CaptureService.instance?.captureOnce()` in the hotkey handler, optionally flash the floating icon:

```kotlin
floatingIcon?.flash()  // brief visual pulse — implement as a short alpha animation
```

**File: `FloatingOverlayIcon.kt`**

Add a simple flash method:
```kotlin
fun flash() {
    animate().alpha(0.3f).setDuration(100).withEndAction {
        animate().alpha(1.0f).setDuration(100).start()
    }.start()
}
```

---

## Phase 3: Dictionary Popup Improvements

**Goal:** Make the word lookup popup more informative, closer to a Yomitan-like experience.

### Step 3.1: Add JLPT level to word lookup popup

**File: `WordLookupPopup.kt`**

The popup currently shows: word, reading, definitions, "common" badge, and frequency stars. Add JLPT level display.

The challenge: `JishoWord.jlpt` is currently always `emptyList()` because the JMdict data doesn't reliably include JLPT tags. However, `KanjiDetail.jlpt` in the KANJIDIC2 table DOES have JLPT levels (N5=5, N4=4, N3=3, N2=2, N1=1).

Two approaches (implement one):

**Approach A (simpler):** Show JLPT level for the kanji in the word by looking up each kanji character via `DictionaryManager.lookupKanji()` and taking the highest (hardest) JLPT level. Display as a small badge like "N3" next to the common badge.

**Approach B (better, more work):** Add JLPT word-level data to the dictionary build script. Download a JLPT word list (many are available as JSON/CSV), add a `jlpt_level` column to the `entry` table in `build_jmdict.py`, and populate it. Then expose it through `DictionaryManager.lookup()` → `JishoWord`.

For the MVP, implement Approach A since it requires no DB rebuild.

In `WordLookupPopup.buildCardView()`, after the common badge / frequency stars row (around line 279-308), add JLPT display:

```kotlin
// Add JLPT badge if available
// The caller should pass jlptLevel as a parameter (0 = unknown, 1-5 = N1-N5)
if (jlptLevel in 1..5) {
    val jlptBadge = TextView(ctx).apply {
        text = "N$jlptLevel"
        setTextColor(Color.parseColor("#A0A0A0"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(5), dp(1), dp(5), dp(1))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#383838"))
            cornerRadius = dp(4).toFloat()
        }
    }
    if (isCommon) jlptBadge.setPadding(dp(6), dp(1), dp(5), dp(1))
    metaRow.addView(jlptBadge)
}
```

Update the `show()` method signature to accept `jlptLevel: Int = 0`.

### Step 3.2: Compute JLPT level from kanji

**File: `DragLookupController.kt`** and wherever `WordLookupPopup.show()` is called

Before showing the popup, look up the JLPT level. The simplest approach:

```kotlin
// After getting the JishoWord entry from DictionaryManager.lookup():
val jlptLevel = withContext(Dispatchers.IO) {
    val word = entry.japanese.firstOrNull()?.word ?: return@withContext 0
    word.filter { it.code > 0x4E00 }  // kanji only
        .mapNotNull { DictionaryManager.get(ctx).lookupKanji(it)?.jlpt }
        .filter { it > 0 }
        .maxOrNull() ?: 0
}
```

Pass this to `popup.show(..., jlptLevel = jlptLevel)`.

### Step 3.3: Add frequency rank display

The popup already shows frequency stars (1-5). Optionally enhance this with the actual frequency rank number or a more descriptive label.

**File: `WordLookupPopup.kt`**

In `buildCardView()`, replace or supplement the star display:
```kotlin
// Instead of just stars, show "★★★ Top 3k" or similar
val freqLabel = when (freqScore) {
    5 -> "Top 3k"
    4 -> "Top 6k"
    3 -> "Top 12k"
    2 -> "Top 20k"
    1 -> "Top 30k"
    else -> null
}
```

### Step 3.4: Show more senses in the popup

Currently the popup shows senses but is limited in height (`maxCardH = dp(160)`). For immersion mode where the popup is the primary interaction, consider increasing the max height or making the popup more generous:

**File: `WordLookupPopup.kt`**

Change:
```kotlin
val maxCardH = dp(160)
```
To:
```kotlin
val maxCardH = dp(220)  // more room for definitions in immersion mode
```

The popup is already scrollable, so this just shows more content before requiring scroll.

### Step 3.5: Add misc tags display

The `JishoSense` model already includes `misc: List<String>` which contains tags like "Colloquial", "Kana only", "Polite", etc. (these are already abbreviated in the build script). Currently these are not displayed in the popup.

**File: `WordLookupPopup.kt`**

In the senses display loop, after showing the POS and definition, add misc tags:

```kotlin
if (sense.misc.isNotEmpty()) {
    rightCol.addView(TextView(ctx).apply {
        text = sense.misc.joinToString(" · ")
        setTextColor(Color.parseColor("#808080"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
    })
}
```

This requires the `SenseDisplay` data class to carry misc tags. Currently it only has `pos` and `definition`. Add a `misc` field:

```kotlin
data class SenseDisplay(
    val pos: String,
    val definition: String,
    val misc: List<String> = emptyList()
)
```

Update all call sites that create `SenseDisplay` instances to pass misc data from the `JishoSense`.

---

## Phase 4: Live Mode Immersion Behavior

**Goal:** Live mode works correctly with immersion mode — auto-refreshes Japanese text without translation.

### Step 4.1: Live mode respects immersion mode

Most of this is already handled by the Phase 1 changes to `CaptureService.runLiveCaptureCycle()`. But verify these additional behaviors:

**File: `CaptureService.kt`**

In `runLiveCaptureCycle()`:
- When immersion mode is on, skip the `translateGroupsSeparately()` call entirely
- Skip showing translation overlay on game screen (call `hideTranslationOverlay()` instead of `showLiveOverlay()` with translated text)
- Still emit `onResult` with the OCR text so the bottom screen updates
- Still do dedup checking (don't re-emit identical text)
- Still run scene change detection (so new dialogue triggers a new capture)

The skeleton placeholder overlay (which appears briefly before translation loads) should also be hidden in immersion mode, since there's no translation coming. The user just sees the Japanese text on the bottom screen.

### Step 4.2: Live mode status text

**File: `TranslationResultFragment.kt`** or **`MainActivity.kt`**

When live mode is active in immersion mode, the status hint should say something appropriate:
```xml
<string name="live_hint_immersion">Live — auto-updating Japanese text</string>
```

Rather than anything about translation.

---

## Phase 5: Polish and UX

### Step 5.1: Rename/rebrand for the fork

**Files: `strings.xml`, `AndroidManifest.xml`, `build.gradle.kts`**

Change the app name and package for your fork so it doesn't conflict with the original:

- `app_name` string: Change to something like "PlayRead" or "ImmersionLens" or whatever you want
- `applicationId` in `build.gradle.kts`: Change from `com.playtranslate` to your own (e.g., `com.yourname.playread`)
- Note: Changing applicationId means refactoring the package. For a quick fork you can just change the `applicationId` in gradle without renaming all the Java packages — Android allows this. The package in the source stays `com.playtranslate` but the installed app ID differs.

### Step 5.2: Default settings for immersion workflow

**File: `Prefs.kt`**

Ensure these defaults match the immersion-first experience:
- `immersionMode` = `true` (already set in Step 1.1)
- `hideTranslationSection` = `true` 
- `hideOriginalSection` = `false`
- `hideWordsSection` = `false`
- `showTransliteration` = `false` (romaji not needed for intermediate learners)
- `captureIntervalSec` = `1.0f` (already default)

### Step 5.3: Simplify onboarding for immersion users

The current onboarding flow (in `MainActivity`) guides users through capture permissions. For the immersion fork, consider:
- Skip the language selection step (hardcode Japanese → English since that's the only use case)
- Add a brief explanation of immersion mode during onboarding
- Auto-enable immersion mode on first launch

This is low priority — the existing onboarding works fine.

---

## Build & Test Checklist

### Before building:
- [ ] Fork the repo on GitHub
- [ ] Clone your fork locally
- [ ] Open in Android Studio, let Gradle sync
- [ ] Verify clean build: `./gradlew assembleDebug`
- [ ] Run on emulator in single-screen mode (`debugForceSingleScreen = true`) to verify base app works

### Phase 1 verification:
- [ ] Immersion mode toggle appears in Settings
- [ ] When immersion mode is on, translation section is hidden
- [ ] When immersion mode is on, OCR still runs and Japanese text appears
- [ ] Word list still populates with dictionary lookups
- [ ] Tapping a word in the original text shows furigana popup
- [ ] Tapping a word in the word list opens WordDetailBottomSheet
- [ ] "Translate" button appears and works in immersion mode
- [ ] Anki export still works (sentence + word level)
- [ ] Capture is faster (no translation latency)

### Phase 2 verification:
- [ ] Hotkey toggle appears in Settings
- [ ] Button picker dialog captures gamepad button press
- [ ] Pressing the configured button triggers OCR capture
- [ ] Volume key trigger works when enabled
- [ ] Hotkey event is consumed (doesn't also go to game)
- [ ] Visual feedback on hotkey press (icon flash or similar)
- [ ] Hotkey works during live mode (triggers immediate re-capture)

### Phase 3 verification:
- [ ] JLPT level badge appears in word popup
- [ ] Frequency rank label appears alongside stars
- [ ] Misc tags (Colloquial, Kana only, etc.) appear in popup
- [ ] Popup height is adequate for viewing definitions
- [ ] SenseDisplay changes don't break existing callers

### On-device testing (when Thor arrives):
- [ ] Dual-screen capture works (game top, app bottom)
- [ ] Gamepad hotkey triggers capture from game screen
- [ ] Live mode auto-updates Japanese text as dialogue changes
- [ ] Capture region cropping works for dialogue boxes
- [ ] OCR accuracy is acceptable for game fonts
- [ ] Popup is readable on 3.92" bottom screen
- [ ] Anki export includes correct screenshot

---

## Files Changed Summary

| File | Phase | Type of Change |
|------|-------|---------------|
| `Prefs.kt` | 1, 2 | Add immersion mode, hotkey, volume trigger prefs |
| `CaptureService.kt` | 1, 4 | Skip translation when immersion mode on (3 methods) |
| `TranslationResultFragment.kt` | 1 | Hide translation UI, show translate button in immersion mode |
| `PlayTranslateAccessibilityService.kt` | 2 | Add hotkey capture trigger in onKeyEvent |
| `MainActivity.kt` | 2 | Add volume key handler |
| `SettingsBottomSheet.kt` | 1, 2 | Add immersion toggle, hotkey config section |
| `WordLookupPopup.kt` | 3 | Add JLPT badge, freq rank label, misc tags, taller popup |
| `DragLookupController.kt` | 3 | Compute JLPT level before showing popup |
| `FloatingOverlayIcon.kt` | 2 | Add flash() method for hotkey feedback |
| `fragment_translation_result.xml` | 1 | Add translate button (or add programmatically) |
| `res/values/strings.xml` | 1, 2, 4 | New string resources |
| `build.gradle.kts` | 5 | Change applicationId for fork |

**Files NOT changed:** `OcrManager.kt`, `DictionaryManager.kt`, `Deinflector.kt`, `AnkiManager.kt`, `TranslationManager.kt`, `LingvaTranslator.kt`, `DeepLTranslator.kt`, `ScreenshotManager.kt`, all Anki UI files, region picker files, overlay views, theme files, the JMdict database, or the build script. These all work correctly as-is.

---

## Potential Gotchas

1. **`CaptureService.instance` is nullable** — Always null-check before calling `captureOnce()`. If the service hasn't started yet (app just launched, permissions not granted), the hotkey silently does nothing.

2. **Hotkey consuming events** — When `onKeyEvent` returns `true`, the game doesn't receive that button press. The user must pick an unused button. If they pick a button the game uses, they'll lose that input. Make the picker dialog very clear about this.

3. **AccessibilityService lifecycle** — The service can be killed by the system. `PlayTranslateAccessibilityService.instance` is a static reference that becomes null. All callers already handle this.

4. **Live mode + hotkey interaction** — If live mode is active and the user presses the hotkey, `captureOnce()` will run a manual capture on top of the live loop. This should be fine — `captureOnce()` cancels any existing `captureJob`. But verify there's no race condition with the live screenshot loop.

5. **Translation cache** — `CaptureService` has a `translationCache` (a map). In immersion mode, nothing gets cached (since translation is skipped). If the user toggles immersion mode off mid-session, the cache is empty and translations will need to run fresh. This is fine.

6. **Anki export in immersion mode** — `AnkiReviewBottomSheet` uses `lastResult?.translatedText`. In immersion mode this will be empty until the user taps "Translate." The Anki card will have an empty translation field unless the user explicitly translated first. Consider: (a) showing a warning, (b) auto-translating before export, or (c) just allowing empty translation (the user might not want English on their cards anyway). Option (c) is most consistent with the immersion philosophy.

7. **`fragment_translation_result.xml` section order** — The XML layout has Translation first, Original second, Words third. In immersion mode, Translation is `GONE` so Original appears at top. This is correct behavior. Don't reorder the XML.

8. **WordLookupPopup `SenseDisplay` changes** — Adding `misc` field to `SenseDisplay` requires updating every call site that constructs `SenseDisplay`. Search the codebase for `SenseDisplay(` to find all instances. There are usages in `DragLookupController.kt` and `TranslationResultFragment.kt` (inside `onWordTapped` flow).
