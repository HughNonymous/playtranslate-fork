package com.gamelens

import android.Manifest
import android.media.projection.MediaProjectionManager
import com.gamelens.themeColor
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.widget.Button
import android.widget.Toast
import android.widget.ImageButton
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gamelens.BuildConfig
import com.gamelens.dictionary.Deinflector
import com.gamelens.dictionary.DictionaryManager
import com.gamelens.model.TranslationResult
import com.gamelens.ui.ClickableTextView
import com.gamelens.AnkiManager
import com.gamelens.TranslationManager
import com.gamelens.ui.AddCustomRegionSheet
import com.gamelens.ui.AnkiReviewBottomSheet
import com.gamelens.ui.RegionPickerSheet
import com.gamelens.ui.SettingsBottomSheet
import com.gamelens.ui.WordDetailBottomSheet
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────────

    private lateinit var tvStatus: TextView
    private lateinit var resultsContent: android.widget.ScrollView
    private lateinit var tvOriginal: ClickableTextView
    private lateinit var tvTranslation: TextView
    private lateinit var tvTranslationNote: TextView
    private lateinit var tvMainWordsLoading: TextView
    private lateinit var mainWordsContainer: LinearLayout
    private lateinit var btnTranslate: MaterialButton
    private lateinit var btnCapturing: MaterialButton
    private lateinit var btnChangeRegion: Button
    private lateinit var btnSettings: ImageButton
    private lateinit var btnClear: ImageButton
    private lateinit var btnLivePlay: ImageButton
    private lateinit var btnLivePause: ImageButton
    private lateinit var liveProgressRing: CircularProgressIndicator
    private lateinit var btnCopyOriginal: ImageButton
    private lateinit var btnCopyTranslation: ImageButton
    private lateinit var btnMainAddToAnki: ImageButton
    private lateinit var statusContainer: android.view.View
    private lateinit var tvStatusHint: TextView
    private lateinit var tvLiveHint: TextView
    private lateinit var liveButtonContainer: android.view.View
    private lateinit var onboardingContainer: View
    private lateinit var pageNotif: View
    private lateinit var pageA11y: View
    private lateinit var pageA11ySingle: View
    private lateinit var labelOriginal: TextView
    private lateinit var labelTranslation: TextView
    private lateinit var tvNoWords: TextView
    private lateinit var tvTransliteration: TextView
    private lateinit var translationSection: android.widget.LinearLayout
    private lateinit var translationHiddenSection: android.widget.LinearLayout
    private lateinit var btnRevealTranslation: com.google.android.material.button.MaterialButton
    private lateinit var btnAnkiNoTranslation: com.google.android.material.button.MaterialButton

    private val romajiTransliterator by lazy {
        try { android.icu.text.Transliterator.getInstance("Any-Latin; NFD; [:Nonspacing Mark:] Remove; NFC") }
        catch (_: Exception) { null }
    }
    private lateinit var editOverlay: android.widget.LinearLayout
    private lateinit var etEditOriginal: android.widget.EditText

    private var wordLookupJob: Job? = null
    private var editTranslationJob: Job? = null
    private val mainWordResults = mutableMapOf<String, Triple<String, String, Int>>()
    private var editTranslationManager: TranslationManager? = null
    private var wasKeyboardVisible = false

    // ── Region quick-dropdown state ────────────────────────────────────────
    private var inDragMode = false
    private var dropdownPopup: PopupWindow? = null
    private var dropdownHighlightedRow = 0
    private var dropdownRegionOrder = listOf<Int>()
    private var dropdownRows = listOf<View>()
    private var dropdownItemHeightPx = 0f
    private var dropdownTopY = 0f
    private var dropdownGameDisplay: Display? = null
    private var dropdownRegions = listOf<RegionEntry>()

    // ── Display listener (detects screen connect/disconnect) ─────────────

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { runOnUiThread {
            checkOnboardingState()
            PlayTranslateAccessibilityService.instance?.ensureFloatingIcon()
        } }
        override fun onDisplayRemoved(displayId: Int) { runOnUiThread {
            checkOnboardingState()
            PlayTranslateAccessibilityService.instance?.ensureFloatingIcon()
        } }
        override fun onDisplayChanged(displayId: Int) {}
    }

    // ── State ─────────────────────────────────────────────────────────────

    private val prefs by lazy { Prefs(this) }

    private var lastResult: TranslationResult? = null
    private var isLiveMode = false
    /** Non-null while a temporary "use once" custom region is active. Cleared when saved config is restored. */
    private var overrideRegionLabel: String? = null
    /** True while programmatic scrollTo(0,0) is in progress to prevent auto-pause. */
    private var suppressScrollPause = false

    // ── Service ───────────────────────────────────────────────────────────

    private var captureService: CaptureService? = null
    private var serviceConnected = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            captureService = (binder as CaptureService.LocalBinder).getService()
            serviceConnected = true
            wireServiceCallbacks()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceConnected = false
            captureService = null
        }
    }

    // ── Notification permission ────────────────────────────────────────────

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            // Permanently blocked — the system dialog will no longer appear.
            // Send the user directly to the app's notification settings.
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
            )
        }
        checkOnboardingState()
    }

    private var pendingAfterMpGrant: (() -> Unit)? = null

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            captureService?.setMediaProjection(result.resultCode, result.data!!)
            prefs.captureMethod = "media_projection"
            pendingAfterMpGrant?.invoke()
            pendingAfterMpGrant = null
        } else {
            pendingAfterMpGrant = null
        }
        checkOnboardingState()
    }

    private val requestAnkiPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, getString(R.string.anki_permission_denied), Toast.LENGTH_SHORT).show()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        // Suppress the window transition that would otherwise flash when recreating for a theme change
        if (prefs.suppressNextTransition) {
            prefs.suppressNextTransition = false
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }
        setContentView(R.layout.activity_main)

        bindViews()
        setupRegionButton()
        setupButtons()
        setupOnboarding()
        setupEditOverlay()
        startAndBindService()
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(displayListener, null)
        lifecycleScope.launch(Dispatchers.IO) {
            DictionaryManager.get(applicationContext).preload()
            Deinflector.preload()
        }
    }

    override fun onResume() {
        super.onResume()
        // Hide the floating icon while our app is in the foreground
        PlayTranslateAccessibilityService.instance?.notifyAppResumed()
        checkOnboardingState()
        if (onboardingContainer.visibility == View.VISIBLE) return
        if (isSingleScreen()) return
        initLiveHintText()
        updateActionButtonState()
        applyLiveModeVisibilitySetting()
    }

    override fun onStop() {
        super.onStop()
        // App is no longer visible — show the floating icon again
        PlayTranslateAccessibilityService.instance?.notifyAppStopped()
    }

    override fun onDestroy() {
        PlayTranslateAccessibilityService.instance?.hideFloatingIcon()
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .unregisterDisplayListener(displayListener)
        if (isLiveMode) captureService?.stopLive()
        if (serviceConnected) unbindService(serviceConnection)
        editTranslationManager?.close()
        super.onDestroy()
    }

    // ── Setup ─────────────────────────────────────────────────────────────

    private fun bindViews() {
        tvStatus             = findViewById(R.id.tvStatus)
        resultsContent       = findViewById(R.id.resultsContent)
        tvOriginal           = findViewById(R.id.tvOriginal)
        tvTranslation        = findViewById(R.id.tvTranslation)
        tvTranslationNote    = findViewById(R.id.tvTranslationNote)
        tvMainWordsLoading   = findViewById(R.id.tvMainWordsLoading)
        mainWordsContainer   = findViewById(R.id.mainWordsContainer)
        btnTranslate         = findViewById(R.id.btnTranslate)
        btnCapturing         = findViewById(R.id.btnCapturing)
        btnChangeRegion      = findViewById(R.id.btnChangeRegion)
        btnSettings          = findViewById(R.id.btnSettings)
        btnClear             = findViewById(R.id.btnClear)
        btnLivePlay          = findViewById(R.id.btnLivePlay)
        btnLivePause         = findViewById(R.id.btnLivePause)
        liveProgressRing     = findViewById(R.id.liveProgressRing)
        btnCopyOriginal      = findViewById(R.id.btnCopyOriginal)
        btnCopyTranslation   = findViewById(R.id.btnCopyTranslation)
        btnMainAddToAnki     = findViewById(R.id.btnMainAddToAnki)
        statusContainer      = findViewById(R.id.statusContainer)
        tvStatusHint         = findViewById(R.id.tvStatusHint)
        tvLiveHint           = findViewById(R.id.tvLiveHint)
        liveButtonContainer  = findViewById(R.id.liveButtonContainer)
        labelOriginal        = findViewById(R.id.labelOriginal)
        labelTranslation     = findViewById(R.id.labelTranslation)
        tvNoWords            = findViewById(R.id.tvNoWords)
        tvTransliteration    = findViewById(R.id.tvTransliteration)
        onboardingContainer  = findViewById(R.id.onboardingContainer)
        pageNotif            = findViewById(R.id.pageNotif)
        pageA11y             = findViewById(R.id.pageA11y)
        pageA11ySingle       = findViewById(R.id.pageA11ySingle)
        editOverlay              = findViewById(R.id.editOverlay)
        etEditOriginal           = findViewById(R.id.etEditOriginal)
        translationSection       = findViewById(R.id.translationSection)
        translationHiddenSection = findViewById(R.id.translationHiddenSection)
        btnRevealTranslation     = findViewById(R.id.btnRevealTranslation)
        btnAnkiNoTranslation     = findViewById(R.id.btnAnkiNoTranslation)
    }

    private fun setupRegionButton() {
        updateRegionButton()

        // Long press on any bottom-bar button → drag-to-select dropdown
        applyRegionDropdownGestures(btnTranslate)
        applyRegionDropdownGestures(btnCapturing)
        applyRegionDropdownGestures(btnChangeRegion)
        applyRegionDropdownGestures(btnLivePlay)
        applyRegionDropdownGestures(btnLivePause)
    }

    /** Attaches long-press + drag-to-select region picker gestures to [btn]. */
    private fun applyRegionDropdownGestures(btn: View) {
        btn.setOnLongClickListener {
            inDragMode = true
            btn.isPressed = false
            showRegionDropdown(btn)
            true
        }
        btn.setOnTouchListener { _, event ->
            if (!inDragMode) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE   -> { updateDropdownHighlight(event.rawY); true }
                MotionEvent.ACTION_UP     -> { commitDropdownSelection(); true }
                MotionEvent.ACTION_CANCEL -> { dismissDropdown(); inDragMode = false; false }
                else -> false
            }
        }
    }

    private fun showRegionPickerSheet() {
        prefs.captureDisplayId = findGameDisplayId()
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val gameDisplay = displayManager.getDisplay(prefs.captureDisplayId) ?: return
        RegionPickerSheet().also { sheet ->
            sheet.gameDisplay = gameDisplay
            sheet.onSaved = { _ ->
                updateRegionButton()
                configureService()
                withAccessibility { captureService?.captureOnce() }
            }
            sheet.onTranslateOnce = { top, bottom, left, right, label ->
                overrideRegionLabel = label
                captureService?.configure(
                    displayId             = prefs.captureDisplayId,
                    sourceLang            = selectedSourceLang(),
                    targetLang            = selectedTargetLang(),
                    captureTopFraction    = top,
                    captureBottomFraction = bottom,
                    captureLeftFraction   = left,
                    captureRightFraction  = right,
                    regionLabel           = label
                )
                updateRegionButton()
                withAccessibility { captureService?.captureOnce() }
            }
        }.show(supportFragmentManager, RegionPickerSheet.TAG)
    }

    private fun updateRegionButton() {
        val list = prefs.getRegionList()
        val entry = list.getOrElse(prefs.captureRegionIndex) { Prefs.DEFAULT_REGION_LIST[0] }
        val label = overrideRegionLabel ?: entry.label
        if (isLiveMode) {
            val prefix = "Capturing "
            btnCapturing.text = SpannableStringBuilder(prefix + label).apply {
                setSpan(StyleSpan(Typeface.BOLD), prefix.length, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            btnTranslate.visibility = View.GONE
            btnCapturing.visibility = View.VISIBLE
        } else {
            val prefix = "Translate "
            btnTranslate.text = SpannableStringBuilder(prefix + label).apply {
                setSpan(StyleSpan(Typeface.BOLD), prefix.length, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            btnCapturing.visibility = View.GONE
            btnTranslate.visibility = View.VISIBLE
        }
    }

    private fun toggleLiveMode() {
        if (isLiveMode) stopLiveMode() else withAccessibility { startLiveMode() }
    }

    private fun startLiveMode() {
        isLiveMode = true
        btnLivePlay.visibility = View.GONE
        btnLivePause.visibility = View.VISIBLE
        btnClear.visibility = View.GONE
        updateRegionButton()
        showStatus(searchingStatusText())
        ensureConfigured()
        captureService?.startLive()
    }

    private fun stopLiveMode() {
        isLiveMode = false
        btnLivePause.visibility = View.GONE
        btnLivePlay.visibility = View.VISIBLE
        liveProgressRing.visibility = View.GONE
        captureService?.stopLive()
        updateRegionButton()
        if (resultsContent.visibility == View.VISIBLE) {
            btnClear.visibility = View.VISIBLE
        } else {
            // Status screen was showing (e.g. "Searching for…") — revert to idle hints
            showStatus(getString(R.string.status_idle))
        }
    }

    private fun pauseLiveMode() {
        if (isLiveMode) stopLiveMode()
    }

    private fun setupButtons() {
        btnChangeRegion.setOnClickListener { showRegionPickerSheet() }
        btnTranslate.setOnClickListener {
            withAccessibility { captureService?.captureOnce() }
        }
        btnCapturing.setOnClickListener { showRegionPickerSheet() }

        btnSettings.setOnClickListener { openSettings() }

        btnLivePlay.setOnClickListener { toggleLiveMode() }
        btnLivePause.setOnClickListener { toggleLiveMode() }

        btnClear.setOnClickListener {
            showStatus(getString(R.string.status_idle))
        }

        btnCopyOriginal.setOnClickListener {
            copyToClipboard(tvOriginal.text?.toString() ?: return@setOnClickListener)
        }

        btnCopyTranslation.setOnClickListener {
            copyToClipboard(tvTranslation.text?.toString() ?: return@setOnClickListener)
        }

        btnMainAddToAnki.setOnClickListener {
            pauseLiveMode()
            val result = lastResult ?: return@setOnClickListener
            val ankiManager = AnkiManager(this)
            when {
                !ankiManager.isAnkiDroidInstalled() ->
                    Toast.makeText(this, getString(R.string.anki_not_installed), Toast.LENGTH_SHORT).show()
                !ankiManager.hasPermission() ->
                    AlertDialog.Builder(this)
                        .setTitle(R.string.anki_permission_rationale_title)
                        .setMessage(R.string.anki_permission_rationale_message)
                        .setPositiveButton(R.string.btn_continue) { _, _ ->
                            requestAnkiPermission.launch(AnkiManager.PERMISSION)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                else ->
                    AnkiReviewBottomSheet.newInstance(
                        result.originalText, result.translatedText, mainWordResults, result.screenshotPath
                    ).show(supportFragmentManager, AnkiReviewBottomSheet.TAG)
            }
        }

        btnRevealTranslation.setOnClickListener {
            val result = lastResult ?: return@setOnClickListener
            val text = result.originalText
            btnRevealTranslation.isEnabled = false
            btnRevealTranslation.text = "…"
            lifecycleScope.launch {
                try {
                    val svc = captureService
                    val (translated, note) = if (svc != null) {
                        svc.translateOnce(text)
                    } else {
                        val tm = editTranslationManager
                            ?: TranslationManager(selectedSourceLang(), selectedTargetLang()).also { editTranslationManager = it }
                        tm.ensureModelReady()
                        Pair(tm.translate(text), null)
                    }
                    lastResult = result.copy(translatedText = translated)
                    tvTranslation.text = translated
                    tvTranslationNote.text = note ?: ""
                    tvTranslationNote.visibility = if (note != null) View.VISIBLE else View.GONE
                    translationSection.visibility       = View.VISIBLE
                    translationHiddenSection.visibility = View.GONE
                } catch (_: Exception) {
                    btnRevealTranslation.isEnabled = true
                    btnRevealTranslation.text = getString(R.string.btn_reveal_translation)
                }
            }
        }

        btnAnkiNoTranslation.setOnClickListener {
            pauseLiveMode()
            val result = lastResult ?: return@setOnClickListener
            val ankiManager = AnkiManager(this)
            when {
                !ankiManager.isAnkiDroidInstalled() ->
                    Toast.makeText(this, getString(R.string.anki_not_installed), Toast.LENGTH_SHORT).show()
                !ankiManager.hasPermission() ->
                    AlertDialog.Builder(this)
                        .setTitle(R.string.anki_permission_rationale_title)
                        .setMessage(R.string.anki_permission_rationale_message)
                        .setPositiveButton(R.string.btn_continue) { _, _ ->
                            requestAnkiPermission.launch(AnkiManager.PERMISSION)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                else ->
                    AnkiReviewBottomSheet.newInstance(
                        result.originalText, "", mainWordResults, result.screenshotPath
                    ).show(supportFragmentManager, AnkiReviewBottomSheet.TAG)
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PlayTranslate", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun applyTheme() {
        val idx = getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .getInt("theme_index", 0)
        setTheme(when (idx) {
            1    -> R.style.Theme_PlayTranslate_White
            2    -> R.style.Theme_PlayTranslate_Rainbow
            3    -> R.style.Theme_PlayTranslate_Purple
            else -> R.style.Theme_PlayTranslate
        })
    }

    private fun openSettings() {
        showSettingsSheet(hideDismiss = false)
    }

    /** Creates and shows a SettingsBottomSheet with all callbacks wired. */
    private fun showSettingsSheet(hideDismiss: Boolean) {
        SettingsBottomSheet.newInstance(hideDismiss = hideDismiss).also { sheet ->
            sheet.onDisplayChanged = {
                captureService?.resetConfiguration()
                configureService()
                PlayTranslateAccessibilityService.instance?.ensureFloatingIcon()
            }
            sheet.onHideLiveModeChanged = {
                applyLiveModeVisibilitySetting()
            }
            sheet.onHideTranslationChanged = {
                configureService()
            }
            sheet.onScreenModeChanged = {
                checkOnboardingState()
            }
        }.show(supportFragmentManager, SettingsBottomSheet.TAG)
    }

    /** True when the user has a working capture method set up (accessibility or screen recording). */
    private val isCaptureReady: Boolean
        get() = PlayTranslateAccessibilityService.isEnabled || prefs.captureMethod == "media_projection"

    /** Dims the action button when no capture method is available. */
    private fun updateActionButtonState() {
        val ready = isCaptureReady
        btnTranslate.alpha = if (ready) 1f else 0.45f
        if (statusContainer.visibility == View.VISIBLE) {
            if (!ready) {
                tvStatus.text = getString(R.string.status_accessibility_needed)
                tvStatusHint.visibility = View.GONE
            } else if (tvStatus.text == getString(R.string.status_accessibility_needed)) {
                showStatus(getString(R.string.status_idle))
            } else if (tvStatus.text == getString(R.string.status_idle)) {
                tvStatusHint.visibility = View.VISIBLE
            }
        }
    }

    // ── Service ───────────────────────────────────────────────────────────

    private fun startAndBindService() {
        val intent = Intent(this, CaptureService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun wireServiceCallbacks() {
        val svc = captureService ?: return
        svc.onResult = { result ->
            runOnUiThread {
                editTranslationJob?.cancel()  // drop any in-flight edit translation
                editTranslationJob = null
                if (!isLiveMode) configureService()  // restore saved region config after one-shot
                liveProgressRing.visibility = View.GONE
                lastResult = result
                tvOriginal.setSegments(result.segments)
                tvOriginal.onTapAtOffset = { offset -> pauseLiveMode(); showEditOverlay(offset) }
                tvTranslation.text = result.translatedText
                tvTranslationNote.text = result.note ?: ""
                tvTranslationNote.visibility = if (result.note != null) View.VISIBLE else View.GONE
                if (prefs.hideTranslation) {
                    translationSection.visibility       = View.GONE
                    translationHiddenSection.visibility = View.VISIBLE
                    btnRevealTranslation.isEnabled = true
                    btnRevealTranslation.text = getString(R.string.btn_reveal_translation)
                } else {
                    translationSection.visibility       = View.VISIBLE
                    translationHiddenSection.visibility = View.GONE
                }
                labelOriginal.text    = langDisplayName(selectedSourceLang())
                labelTranslation.text = langDisplayName(selectedTargetLang())
                statusContainer.visibility = View.GONE
                resultsContent.visibility  = View.VISIBLE
                suppressScrollPause = true
                resultsContent.scrollTo(0, 0)
                resultsContent.post { suppressScrollPause = false }
                if (!isLiveMode) btnClear.visibility = View.VISIBLE
                btnMainAddToAnki.isEnabled = false
                startWordLookups(result.originalText)
            }
        }
        svc.onError = { msg ->
            runOnUiThread { showStatus(getString(R.string.status_error, msg)) }
        }
        svc.onStatusUpdate = { msg ->
            runOnUiThread { showStatus(msg) }
        }
        svc.onTranslationStarted = {
            runOnUiThread { liveProgressRing.visibility = View.VISIBLE }
        }
        svc.onLiveNoText = {
            runOnUiThread { if (isLiveMode) showStatus(searchingStatusText()) }
        }

        ensureConfigured()
    }

    // ── Accessibility service flow ─────────────────────────────────────────

    private fun withAccessibility(action: () -> Unit) {
        if (PlayTranslateAccessibilityService.isEnabled) {
            ensureConfigured()
            action()
            return
        }
        if (prefs.captureMethod == "media_projection") {
            if (captureService?.hasMediaProjection == true) {
                ensureConfigured()
                action()
            } else {
                // Token expired (new session) — re-request
                pendingAfterMpGrant = { ensureConfigured(); action() }
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
            }
            return
        }
        showAccessibilityDialog()
    }

    private fun ensureConfigured() {
        val svc = captureService ?: return
        if (!svc.isConfigured) {
            prefs.captureDisplayId = findGameDisplayId()
            configureService()
        }
    }

    /** Applies all current prefs to the capture service. */
    private fun configureService() {
        val svc = captureService ?: return
        overrideRegionLabel = null
        val entry = prefs.getRegionList().getOrElse(prefs.captureRegionIndex) { Prefs.DEFAULT_REGION_LIST[0] }
        svc.configure(
            displayId             = prefs.captureDisplayId,
            sourceLang            = selectedSourceLang(),
            targetLang            = selectedTargetLang(),
            captureTopFraction    = entry.top,
            captureBottomFraction = entry.bottom,
            captureLeftFraction   = entry.left,
            captureRightFraction  = entry.right,
            regionLabel           = entry.label
        )
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.accessibility_dialog_title))
            .setMessage(getString(R.string.accessibility_dialog_message))
            .setPositiveButton(getString(R.string.accessibility_dialog_open)) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun isSingleScreen(): Boolean = Prefs.isSingleScreen(this)

    private fun checkOnboardingState() {
        val notifGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val a11yEnabled = PlayTranslateAccessibilityService.isEnabled
        val singleScreen = isSingleScreen()
        val existingSheet = supportFragmentManager.findFragmentByTag(SettingsBottomSheet.TAG) as? SettingsBottomSheet

        // Notification permission always comes first regardless of screen mode
        if (!notifGranted) {
            existingSheet?.dismissAllowingStateLoss()
            showOnboardingPage(pageNotif)
            return
        }

        if (singleScreen) {
            // Single-screen: accessibility is required for overlay support
            if (!a11yEnabled) {
                existingSheet?.dismissAllowingStateLoss()
                showOnboardingPage(pageA11ySingle)
                return
            }
            // Single-screen + accessibility ready: settings IS the UI
            onboardingContainer.visibility = View.GONE
            btnSettings.visibility = View.GONE
            val isAlreadySingleScreenSheet = existingSheet != null &&
                existingSheet.arguments?.getBoolean("hide_dismiss", false) == true
            if (!isAlreadySingleScreenSheet) {
                existingSheet?.dismissAllowingStateLoss()
                showSettingsSheet(hideDismiss = true)
            }
            return
        }

        // Multi-screen: dismiss any non-dismissable settings sheet from single-screen mode
        if (existingSheet != null && existingSheet.arguments?.getBoolean("hide_dismiss", false) == true) {
            existingSheet.dismissAllowingStateLoss()
        }

        val captureReady = a11yEnabled || prefs.captureMethod == "media_projection"
        if (captureReady) {
            onboardingContainer.visibility = View.GONE
            btnSettings.visibility = View.VISIBLE
            return
        }
        showOnboardingPage(pageA11y)
    }

    private fun showOnboardingPage(page: View) {
        onboardingContainer.visibility = View.VISIBLE
        btnSettings.visibility = View.GONE
        pageNotif.visibility      = if (page == pageNotif)      View.VISIBLE else View.GONE
        pageA11y.visibility       = if (page == pageA11y)       View.VISIBLE else View.GONE
        pageA11ySingle.visibility = if (page == pageA11ySingle) View.VISIBLE else View.GONE
    }

    private fun setupOnboarding() {
        pageNotif.findViewById<View>(R.id.btnGrantNotif).setOnClickListener {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        pageA11y.findViewById<View>(R.id.btnOpenA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        pageA11y.findViewById<View>(R.id.btnUseScreenRecord).setOnClickListener {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
        }
        pageA11ySingle.findViewById<View>(R.id.btnOpenA11ySingle).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    // ── Display detection ─────────────────────────────────────────────────

    private fun findGameDisplayId(): Int {
        val myDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.displayId ?: Display.DEFAULT_DISPLAY
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.displayId
        }

        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return displayManager.displays
            .firstOrNull { it.displayId != myDisplayId }
            ?.displayId
            ?: prefs.captureDisplayId
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun selectedSourceLang() = TranslateLanguage.JAPANESE
    private fun selectedTargetLang() = TranslateLanguage.ENGLISH

    private fun showStatus(msg: String) {
        tvStatus.text = msg
        val isIdle = msg == getString(R.string.status_idle)
        tvStatusHint.visibility = if (isIdle) View.VISIBLE else View.GONE
        tvLiveHint.visibility   = if (isIdle && !prefs.hideLiveMode && !isLiveMode) View.VISIBLE else View.GONE
        statusContainer.visibility = View.VISIBLE
        resultsContent.visibility  = View.GONE
        btnClear.visibility        = View.GONE
    }

    /**
     * Sets tvLiveHint text with an inline play icon ImageSpan.
     * Called once on resume so the span is ready before first display.
     */
    private fun initLiveHintText() {
        val icon = ContextCompat.getDrawable(this, R.drawable.ic_play)?.mutate() ?: return
        icon.setTint(themeColor(R.attr.colorTextHint))
        val size = (tvLiveHint.textSize * 1.1f).toInt()
        icon.setBounds(0, 0, size, size)
        val span = android.text.style.ImageSpan(icon, android.text.style.ImageSpan.ALIGN_BASELINE)
        val sb = android.text.SpannableString("Press \u0000 button below to start live mode")
        sb.setSpan(span, 6, 7, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvLiveHint.text = sb
    }

    /** Returns the "Searching for X in the Y area" message for live mode. */
    private fun searchingStatusText(): String {
        val lang = langDisplayName(selectedSourceLang())
        val regions = prefs.getRegionList()
        val entry = regions.getOrElse(prefs.captureRegionIndex) { Prefs.DEFAULT_REGION_LIST[0] }
        val label = overrideRegionLabel ?: entry.label
        return "Searching for $lang in the \"$label\" area"
    }

    /**
     * Applies the hideLiveMode preference: hides/shows live buttons and hint.
     * Stops live mode if it is active and the button is being hidden.
     */
    private fun applyLiveModeVisibilitySetting() {
        val hide = prefs.hideLiveMode
        if (hide && isLiveMode) stopLiveMode()
        liveButtonContainer.visibility = if (hide) View.GONE else View.VISIBLE
        // Refresh live hint if the status screen is currently shown
        if (statusContainer.visibility == View.VISIBLE) {
            val isIdle = tvStatus.text.toString() == getString(R.string.status_idle)
            tvLiveHint.visibility = if (isIdle && !hide && !isLiveMode) View.VISIBLE else View.GONE
        }
    }

    /**
     * Converts Japanese [text] to romaji by first running Kuromoji to get the
     * kana reading of every token (including kanji), then applying ICU's
     * Any-Latin transliterator to convert kana to ASCII romaji.
     * Must be called from a coroutine — Kuromoji runs on IO.
     */
    private suspend fun buildRomaji(text: String): String = withContext(Dispatchers.IO) {
        val t = romajiTransliterator ?: return@withContext ""
        Deinflector.toKanaTokens(text).joinToString(" ") { t.transliterate(it) }
    }

    private fun langDisplayName(langCode: String): String =
        Locale(langCode).getDisplayLanguage(Locale.ENGLISH)
            .replaceFirstChar { it.uppercase() }

    private fun showEditOverlay(charOffset: Int) {
        val currentText = tvOriginal.text?.toString() ?: return
        etEditOriginal.setText(currentText)
        val safeOffset = charOffset.coerceIn(0, currentText.length)
        etEditOriginal.setSelection(safeOffset)
        editOverlay.visibility = View.VISIBLE
        etEditOriginal.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(etEditOriginal, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun commitEdit() {
        if (editOverlay.visibility != View.VISIBLE) return
        editOverlay.visibility = View.GONE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(etEditOriginal.windowToken, 0)
        val newText = etEditOriginal.text?.toString()?.trim() ?: return
        if (newText.isBlank()) return

        tvOriginal.text = newText
        tvTranslation.text = "…"
        tvTranslationNote.visibility = View.GONE
        lastResult = lastResult?.copy(originalText = newText, translatedText = "")
        startWordLookups(newText)

        editTranslationJob?.cancel()
        editTranslationJob = lifecycleScope.launch {
            try {
                val tm = editTranslationManager
                    ?: TranslationManager(selectedSourceLang(), selectedTargetLang()).also { editTranslationManager = it }
                tm.ensureModelReady()
                val translated = tm.translate(newText)
                tvTranslation.text = translated
                lastResult = lastResult?.copy(translatedText = translated)
            } catch (_: Exception) {
                tvTranslation.text = "—"
            }
        }
    }

    private fun setupEditOverlay() {
        // Pause live mode when the user scrolls the results (shows intent to read)
        resultsContent.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY != oldScrollY && isLiveMode && !suppressScrollPause) pauseLiveMode()
        }

        etEditOriginal.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                commitEdit()
                true
            } else false
        }

        // Detect keyboard being swiped away while edit overlay is visible
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            window.decorView.getWindowVisibleDisplayFrame(rect)
            val screenHeight = window.decorView.height
            val keyboardVisible = (screenHeight - rect.bottom) > screenHeight * 0.15f
            if (wasKeyboardVisible && !keyboardVisible && editOverlay.visibility == View.VISIBLE) {
                commitEdit()
            }
            wasKeyboardVisible = keyboardVisible
        }
    }

    // ── Word lookups for main results view ────────────────────────────────

    private fun startWordLookups(text: String) {
        wordLookupJob?.cancel()
        mainWordsContainer.removeAllViews()
        mainWordResults.clear()
        tvMainWordsLoading.visibility = View.VISIBLE
        tvMainWordsLoading.text = getString(R.string.words_loading)

        tvNoWords.visibility = View.GONE
        tvTransliteration.visibility = View.GONE

        wordLookupJob = lifecycleScope.launch {
            // Kick off romaji immediately — no need to wait for dictionary lookups.
            val romajiDeferred = async { buildRomaji(text) }

            // N-gram phrase detection runs on IO; this also drives the fallback
            // to plain Deinflector.tokenize() when the database isn't open yet.
            val tokens = withContext(Dispatchers.IO) {
                DictionaryManager.get(applicationContext).tokenize(text)
            }

            if (tokens.isEmpty()) {
                tvMainWordsLoading.visibility = View.GONE
                tvNoWords.visibility = View.VISIBLE
                btnMainAddToAnki.isEnabled = true
                val romaji = romajiDeferred.await()
                if (romaji.isNotBlank() && romaji != text) {
                    tvTransliteration.text = romaji
                    tvTransliteration.visibility = View.VISIBLE
                }
                return@launch
            }

            // Pre-inflate all rows in sentence order (on Main thread).
            val rows = tokens.map { word ->
                val row = layoutInflater.inflate(R.layout.item_word_lookup, mainWordsContainer, false)
                row.findViewById<TextView>(R.id.tvItemWord).text = word
                row.findViewById<TextView>(R.id.tvItemMeaning).text = "…"
                mainWordsContainer.addView(row)
                Pair(word, row)
            }

            // Collect results indexed by sentence position; populated concurrently.
            // Pair<displayWord, Triple<reading, meaning, freqScore>>
            val resultsArr = arrayOfNulls<Pair<String, Triple<String, String, Int>>>(rows.size)

            supervisorScope {
                rows.forEachIndexed { idx, (word, row) ->
                    launch {
                        val tvWord    = row.findViewById<TextView>(R.id.tvItemWord)
                        val tvReading = row.findViewById<TextView>(R.id.tvItemReading)
                        val tvFreq    = row.findViewById<TextView>(R.id.tvItemFreq)
                        val tvMeaning = row.findViewById<TextView>(R.id.tvItemMeaning)
                        var reading = ""
                        var meaning = ""
                        var displayWord = word
                        var freqScore = 0
                        try {
                            val response = withContext(Dispatchers.IO) {
                                DictionaryManager.get(applicationContext).lookup(word)
                            }
                            if (response != null && response.data.isNotEmpty()) {
                                val entry   = response.data.first()
                                val primary = entry.japanese.firstOrNull()
                                displayWord = primary?.word ?: primary?.reading ?: word
                                tvWord.text = displayWord
                                reading = primary?.reading?.takeIf { it != primary.word } ?: ""
                                freqScore = entry.freqScore
                                meaning = entry.senses.mapIndexed { i, sense ->
                                    val glosses = sense.englishDefinitions.joinToString("; ")
                                    if (entry.senses.size > 1) "${i + 1}. $glosses" else glosses
                                }.joinToString("\n")
                            }
                        } catch (_: Exception) {}
                        if (meaning.isNotEmpty()) {
                            tvReading.text = reading
                            tvMeaning.text = meaning
                            if (freqScore > 0) {
                                tvFreq.text = "★".repeat(freqScore)
                                tvFreq.visibility = View.VISIBLE
                            }
                            val lookupWord = displayWord
                            row.setOnClickListener {
                                pauseLiveMode()
                                WordDetailBottomSheet.newInstance(lookupWord, lastResult?.screenshotPath)
                                    .show(supportFragmentManager, WordDetailBottomSheet.TAG)
                            }
                            resultsArr[idx] = Pair(displayWord, Triple(reading, meaning, freqScore))
                        } else {
                            mainWordsContainer.removeView(row)
                        }
                    }
                }
            }

            // All lookups done — populate mainWordResults in sentence order.
            resultsArr.filterNotNull().forEach { (dw, rmt) ->
                mainWordResults[dw] = rmt
            }
            tvMainWordsLoading.visibility = View.GONE
            tvNoWords.visibility = if (mainWordResults.isEmpty()) View.VISIBLE else View.GONE
            btnMainAddToAnki.isEnabled = true

            // Show romaji — deferred was already running concurrently with lookups.
            val romaji = romajiDeferred.await()
            if (romaji.isNotBlank() && romaji != text) {
                tvTransliteration.text = romaji
                tvTransliteration.visibility = View.VISIBLE
            }
        }
    }

    // ── Region quick-dropdown ──────────────────────────────────────────────

    private fun showRegionDropdown(anchor: View) {
        val regions = prefs.getRegionList()
        if (regions.isEmpty()) return

        prefs.captureDisplayId = findGameDisplayId()
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val gameDisplay = displayManager.getDisplay(prefs.captureDisplayId)

        val currentIndex = prefs.captureRegionIndex.coerceIn(0, regions.lastIndex)
        // "Add Custom Region" at top, other regions next, current at bottom (under finger)
        val order = mutableListOf<Int>()
        order.add(-1)  // sentinel for "Add Custom Region"
        for (i in regions.indices) { if (i != currentIndex) order.add(i) }
        order.add(currentIndex)
        dropdownRegionOrder = order
        dropdownHighlightedRow = order.lastIndex   // current starts highlighted
        dropdownGameDisplay = gameDisplay
        dropdownRegions = regions

        val dp = resources.displayMetrics.density
        dropdownItemHeightPx = 48 * dp

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(R.attr.colorBgSurface))
            elevation = 8 * dp
        }
        val rows = mutableListOf<View>()
        order.forEachIndexed { rowIdx, regionIdx ->
            val isHighlighted = rowIdx == order.lastIndex
            val label = if (regionIdx == -1) getString(R.string.label_add_custom_region) else regions[regionIdx].label
            val row = buildDropdownRow(label, isHighlighted, regionIdx == -1)
            container.addView(row)
            rows.add(row)
        }
        dropdownRows = rows

        val anchorLoc = intArrayOf(0, 0)
        anchor.getLocationOnScreen(anchorLoc)
        // Popup goes upward — bottom of popup aligns with top of anchor
        val popupHeight = (order.size * dropdownItemHeightPx).toInt()
        val popupTop = maxOf(0, anchorLoc[1] - popupHeight)
        dropdownTopY = popupTop.toFloat()

        val screenWidth = resources.displayMetrics.widthPixels
        val popupMarginH = (12 * dp).toInt()
        val popupWidth = screenWidth - 2 * popupMarginH
        val popup = PopupWindow(container, popupWidth, LinearLayout.LayoutParams.WRAP_CONTENT, false)
        popup.isTouchable = false
        popup.isOutsideTouchable = false
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, popupMarginH, popupTop)
        dropdownPopup = popup

        // Show overlay for the currently selected region
        if (gameDisplay != null) {
            val entry = regions[currentIndex]
            PlayTranslateAccessibilityService.instance?.showRegionOverlay(
                gameDisplay, entry.top, entry.bottom, entry.left, entry.right
            )
        }
    }

    private fun updateDropdownHighlight(rawY: Float) {
        val relativeY = rawY - dropdownTopY
        val rowIdx = (relativeY / dropdownItemHeightPx).toInt()
            .coerceIn(0, dropdownRegionOrder.size - 1)
        if (rowIdx == dropdownHighlightedRow) return

        updateRowHighlight(dropdownRows[dropdownHighlightedRow], false)
        updateRowHighlight(dropdownRows[rowIdx], true)
        dropdownHighlightedRow = rowIdx

        val regionIdx = dropdownRegionOrder[rowIdx]
        if (regionIdx >= 0) {
            val entry = dropdownRegions[regionIdx]
            PlayTranslateAccessibilityService.instance?.updateRegionOverlay(
                entry.top, entry.bottom, entry.left, entry.right
            )
        }
    }

    private fun commitDropdownSelection() {
        val selectedRegionIdx = dropdownRegionOrder[dropdownHighlightedRow]
        dismissDropdown()
        inDragMode = false
        if (selectedRegionIdx == -1) {
            if (!PlayTranslateAccessibilityService.isEnabled) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.custom_region_a11y_required_title)
                    .setMessage(R.string.custom_region_a11y_required_message)
                    .setPositiveButton(R.string.btn_open_a11y_settings) { _, _ ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return
            }
            openAddCustomRegionFromDropdown()
            return
        }
        val changed = dropdownHighlightedRow != dropdownRegionOrder.lastIndex
        if (changed) {
            prefs.captureRegionIndex = selectedRegionIdx
            updateRegionButton()
            configureService()
            withAccessibility { captureService?.captureOnce() }
        }
    }

    private fun openAddCustomRegionFromDropdown() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val gameDisplay = displayManager.getDisplay(prefs.captureDisplayId)
        AddCustomRegionSheet().also { sheet ->
            sheet.gameDisplay = gameDisplay
            sheet.onRegionAdded = { newIndex ->
                prefs.captureRegionIndex = newIndex
                updateRegionButton()
            }
            sheet.onDismissed = {}
            sheet.onTranslateOnce = { top, bottom, left, right, label ->
                overrideRegionLabel = label
                captureService?.configure(
                    displayId             = prefs.captureDisplayId,
                    sourceLang            = selectedSourceLang(),
                    targetLang            = selectedTargetLang(),
                    captureTopFraction    = top,
                    captureBottomFraction = bottom,
                    captureLeftFraction   = left,
                    captureRightFraction  = right,
                    regionLabel           = label
                )
                updateRegionButton()
                withAccessibility { captureService?.captureOnce() }
            }
        }.show(supportFragmentManager, AddCustomRegionSheet.TAG)
    }

    private fun dismissDropdown() {
        dropdownPopup?.dismiss()
        dropdownPopup = null
        PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
    }

    private fun buildDropdownRow(label: String, highlighted: Boolean, isAddNew: Boolean = false): LinearLayout {
        val dp = resources.displayMetrics.density
        val padH = (12 * dp).toInt()
        val padV = (12 * dp).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, padV, padH, padV)
            setBackgroundColor(themeColor(
                if (highlighted) R.attr.colorBgCard else R.attr.colorBgSurface))

            if (isAddNew) {
                val tv = TextView(this@MainActivity).apply {
                    text = label
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(themeColor(R.attr.colorAccentPrimary))
                }
                addView(tv)
            } else {
                val rb = RadioButton(this@MainActivity).apply {
                    isChecked = highlighted
                    isClickable = false
                    isFocusable = false
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = (8 * dp).toInt() }
                }
                val tv = TextView(this@MainActivity).apply {
                    text = label
                    textSize = 14f
                    setTextColor(themeColor(R.attr.colorTextPrimary))
                }
                addView(rb)
                addView(tv)
            }
        }
    }

    private fun updateRowHighlight(row: View, highlighted: Boolean) {
        row.setBackgroundColor(themeColor(
            if (highlighted) R.attr.colorBgCard else R.attr.colorBgSurface))
        ((row as? LinearLayout)?.getChildAt(0) as? RadioButton)?.isChecked = highlighted
    }

}
