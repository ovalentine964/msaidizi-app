package com.msaidizi.app.onboarding.bootstrap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.msaidizi.app.MainActivity
import com.msaidizi.app.R
import com.msaidizi.app.voice.VoicePipeline
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Bootstrap Activity — Msaidizi's first conversation with the worker.
 *
 * THIS IS NOT A FORM. IT'S A CONVERSATION.
 *
 * One Activity. One conversation. Voice in, voice out.
 * Msaidizi speaks first, listens, learns, and builds the worker's
 * profile through natural dialogue — exactly like OpenClaw's bootstrap.
 *
 * ## The OpenClaw Naming Pattern
 *
 * Right after learning the worker's name, Msaidizi asks:
 * "Unataka kuniita nani?" — What do you want to call me?
 *
 * The worker picks ANY name: "Rafiki", "Biashara Yangu", "Mkufunzi", anything.
 * That name becomes Msaidizi's identity. It's not "the app" — it's "Rafiki".
 * "Karibu! Mimi ni Rafiki. Nakumbuka biashara yako, Mary."
 *
 * ## What the worker experiences (10 turns, ~3-5 min):
 *
 * 1. Msaidizi: "Karibu! Mimi ni Msaidizi wako. Unaitwa nani?"
 *    Worker: "Naitwa Mary"
 *
 * 2. Msaidizi: "Mary, nzuri sana! Unataka kuniita nani?"
 *    Worker: "Rafiki"
 *
 * 3. Msaidizi: "Rafiki! Napenda jina hilo. Sasa nieleze — unafanya biashara gani?"
 *    Worker: "Nauza mboga sokoni"
 *
 * 4-8. ... business questions ...
 *
 * 9. Msaidizi: Summary + first insight
 *    Worker: Taps "Endelea"
 *
 * 10. Msaidizi: "Weka PIN yako ya tarakimu 4"
 *     Worker: "Moja mbili tatu nne"
 *
 * ## UI Layout (minimal, 2GB-friendly):
 *
 * ```
 * ┌─────────────────────────┐
 *  │  🤝 Rafiki               │  (header — shows chosen name!)
 * │  ████░░░░ 2/10           │  (progress)
 * │                          │
 * │  "Mary, nzuri sana!     │  (prompt - large text)
 * │   Unataka kuniita nani?" │
 * │                          │
 * │  [🎤 Sema Sasa]          │  (voice button)
 * │                          │
 │ │  —— au ——               │
 * │  [Andika jibu lako...]   │  (text input - fallback)
 * │                          │
 │ │  Ninapakia akili yako... │  (model download status)
 * └─────────────────────────┘
 * ```
 */
@AndroidEntryPoint
class BootstrapActivity : AppCompatActivity() {

    companion object {
        private const val VOICE_PERMISSION_REQUEST = 1002
        private const val TAG = "BootstrapActivity"
    }

    @Inject
    lateinit var voicePipelineProvider: dagger.Lazy<VoicePipeline>
    private val voicePipeline by lazy { voicePipelineProvider.get() }

    private lateinit var viewModel: BootstrapViewModel
    private var isVoiceListening = false
    private var lastSpokenPrompt = ""

    // ── UI Elements ──
    private lateinit var rootLayout: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var headerText: TextView
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var promptText: TextView
    private lateinit var hintText: TextView
    private lateinit var workerInputText: TextView
    private lateinit var voiceButton: Button
    private lateinit var textInput: EditText
    private lateinit var textSubmitButton: Button
    private lateinit var skipButton: Button
    private lateinit var errorText: TextView
    private lateinit var continueButton: Button
    private lateinit var startButton: Button
    private lateinit var modelStatusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            viewModel = ViewModelProvider(this)[BootstrapViewModel::class.java]

            // Check if onboarding already completed
            val prefs = getSharedPreferences("worker_profile", 0)
            if (prefs.getBoolean("onboarding_complete", false)) {
                navigateToMain()
                return
            }

            // Build UI programmatically (no XML layout needed)
            setupUi()

            // Observe state
            observeState()

            // Initialize voice pipeline
            lifecycleScope.launch {
                try {
                    voicePipeline.initialize()
                    Timber.i(TAG, "VoicePipeline initialized")
                } catch (e: Throwable) {
                    Timber.e(e, "VoicePipeline init failed")
                }
            }

            // Collect transcriptions from voice pipeline
            lifecycleScope.launch {
                voicePipeline.transcription.collect { result ->
                    if (result.success && result.text.isNotBlank()) {
                        viewModel.onVoiceInput(result.text, result.confidence)
                    } else {
                        viewModel.onVoiceInput("", 0f)
                        errorText.text = result.error ?: "Sikujielewa. Jaribu tena."
                        errorText.visibility = View.VISIBLE
                    }
                    isVoiceListening = false
                }
            }

            // Check microphone permission
            checkMicPermission()
        } catch (e: Throwable) {
            Timber.e(e, "BootstrapActivity.onCreate() FAILED")
            // Show a basic error screen instead of crashing
            try {
                val errorLayout = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(48, 48, 48, 48)
                    gravity = android.view.Gravity.CENTER
                }
                val errorText = android.widget.TextView(this).apply {
                    text = "Kuna hitilafu. Tafadhali fungua tena programu.\n\n${e.message}"
                    textSize = 18f
                    gravity = android.view.Gravity.CENTER
                }
                val retryButton = android.widget.Button(this).apply {
                    text = "Jaribu Tena"
                    setOnClickListener { recreate() }
                }
                errorLayout.addView(errorText)
                errorLayout.addView(retryButton)
                setContentView(errorLayout)
            } catch (_: Throwable) {
                // Last resort — can't even show error UI
                finish()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // UI SETUP
    // ═══════════════════════════════════════════════════════════════

    private fun setupUi() {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(ContextCompat.getColor(context, android.R.color.white))
        }

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = true
        }

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(16, 32, 16, 32)
        }

        // ── Header (shows the chosen agent name!) ──
        headerText = TextView(this).apply {
            text = "🤝 Msaidizi"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        contentLayout.addView(headerText)

        // ── Progress (1/10 through 10/10) ──
        progressText = TextView(this).apply {
            text = "1/10"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 4)
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }
        contentLayout.addView(progressText)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 48) }
        }
        contentLayout.addView(progressBar)

        // ── Prompt (main conversation text) ──
        promptText = TextView(this).apply {
            text = ""
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 16)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        contentLayout.addView(promptText)

        // ── Hint text ──
        hintText = TextView(this).apply {
            text = ""
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            visibility = View.GONE
        }
        contentLayout.addView(hintText)

        // ── Worker's last input (show what was heard) ──
        workerInputText = TextView(this).apply {
            text = ""
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(24, 16, 24, 16)
            setTextColor(ContextCompat.getColor(context, R.color.primary))
            visibility = View.GONE
        }
        contentLayout.addView(workerInputText)

        // ── Voice Button ──
        voiceButton = Button(this).apply {
            text = "🎤 Sema Sasa"
            textSize = 22f
            setPadding(64, 32, 64, 32)
            setOnClickListener { onVoiceButtonClicked() }
        }
        contentLayout.addView(voiceButton)

        // ── Text Input (hidden by default, for fallback) ──
        val textInputDivider = TextView(this).apply {
            text = "—— au ——"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 8)
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }
        contentLayout.addView(textInputDivider)

        textInput = EditText(this).apply {
            hint = "Andika jibu lako hapa..."
            textSize = 16f
            setPadding(24, 16, 24, 16)
            visibility = View.GONE
            maxLines = 2
        }
        contentLayout.addView(textInput)

        textSubmitButton = Button(this).apply {
            text = "Tuma"
            textSize = 16f
            visibility = View.GONE
            setOnClickListener {
                val text = textInput.text.toString().trim()
                if (text.isNotBlank()) {
                    viewModel.onTextInput(text)
                    textInput.text.clear()
                }
            }
        }
        contentLayout.addView(textSubmitButton)

        // ── Skip Button ──
        skipButton = Button(this).apply {
            text = "Ruka swali hili →"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            setOnClickListener { viewModel.skipCurrentStep() }
        }
        contentLayout.addView(skipButton)

        // ── Error text ──
        errorText = TextView(this).apply {
            text = ""
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 8)
            setTextColor(ContextCompat.getColor(context, android.R.color.holo_red_dark))
            visibility = View.GONE
        }
        contentLayout.addView(errorText)

        // ── Continue button (for Summary step) ──
        continueButton = Button(this).apply {
            text = "Endelea →"
            textSize = 18f
            setPadding(48, 24, 48, 24)
            visibility = View.GONE
            setOnClickListener { viewModel.proceedFromSummary() }
        }
        contentLayout.addView(continueButton)

        // ── Start button (for final completion) ──
        startButton = Button(this).apply {
            text = "Anza Kufanya Kazi 🚀"
            textSize = 20f
            setPadding(48, 24, 48, 24)
            visibility = View.GONE
            setOnClickListener { navigateToMain() }
        }
        contentLayout.addView(startButton)

        // ── Model download status ──
        modelStatusText = TextView(this).apply {
            text = ""
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }
        contentLayout.addView(modelStatusText)

        scrollView.addView(contentLayout)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)
    }

    // ═══════════════════════════════════════════════════════════════
    // STATE OBSERVATION
    // ═══════════════════════════════════════════════════════════════

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe UI state
                launch {
                    viewModel.uiState.collectLatest { state -> renderState(state) }
                }

                // Observe voice state
                launch {
                    viewModel.voiceState.collectLatest { state -> renderVoiceState(state) }
                }

                // Observe model download status
                launch {
                    viewModel.modelStatusMessage.collectLatest { message ->
                        modelStatusText.text = message
                        modelStatusText.visibility = if (message.isNotBlank()) View.VISIBLE else View.GONE
                    }
                }

                // Observe models ready
                launch {
                    viewModel.modelsReady.collectLatest { ready ->
                        if (ready) {
                            modelStatusText.text = "✅ Akili tayari"
                        }
                    }
                }
            }
        }
    }

    /**
     * Render the UI state.
     */
    private fun renderState(state: BootstrapUiState) {
        // Update prompt
        promptText.text = state.prompt

        // Speak the prompt via TTS when it changes (and not processing worker input)
        if (state.prompt.isNotBlank() && !state.isProcessing && !state.isSpeaking) {
            speakPrompt(state.prompt)
        }

        // Update hint
        if (state.hint.isNotBlank()) {
            hintText.text = state.hint
            hintText.visibility = View.VISIBLE
        } else {
            hintText.visibility = View.GONE
        }

        // Update progress
        progressText.text = "${state.stepNumber}/10"
        progressBar.progress = (state.progress * 100).toInt()

        // Update header to show chosen agent name after naming step
        if (state.agentName != "Msaidizi") {
            headerText.text = "🤝 ${state.agentName}"
        }

        // Show worker's last input
        if (state.lastWorkerInput.isNotBlank()) {
            workerInputText.text = "\"${state.lastWorkerInput}\""
            workerInputText.visibility = View.VISIBLE
        } else {
            workerInputText.visibility = View.GONE
        }

        // Handle Summary step — show Continue button
        if (state.isSummary && !state.isComplete) {
            voiceButton.visibility = View.GONE
            textInput.visibility = View.GONE
            textSubmitButton.visibility = View.GONE
            skipButton.visibility = View.GONE
            continueButton.visibility = View.VISIBLE
            promptText.textSize = 18f // Slightly smaller for summary
        }

        // Handle Completion — show Start button
        if (state.isComplete) {
            voiceButton.visibility = View.GONE
            textInput.visibility = View.GONE
            textSubmitButton.visibility = View.GONE
            skipButton.visibility = View.GONE
            continueButton.visibility = View.GONE
            startButton.visibility = View.VISIBLE
            promptText.textSize = 20f
        }

        // Handle errors
        if (state.errorMessage != null) {
            errorText.text = state.errorMessage
            errorText.visibility = View.VISIBLE
        } else {
            errorText.visibility = View.GONE
        }

        // Handle processing state
        if (state.isProcessing) {
            voiceButton.isEnabled = false
            voiceButton.text = "⏳ Nasikiliza..."
        }
    }

    /**
     * Render voice input state — updates the mic button appearance.
     */
    private fun renderVoiceState(state: VoiceInputState) {
        when (state) {
            VoiceInputState.IDLE -> {
                voiceButton.text = "🎤 Sema Sasa"
                voiceButton.isEnabled = true
            }
            VoiceInputState.LISTENING -> {
                voiceButton.text = "🔴 Nasikiliza... (bonyeza kuacha)"
                voiceButton.isEnabled = true
            }
            VoiceInputState.SPEAKING -> {
                voiceButton.text = "🔊 Msaidizi anazungumza..."
                voiceButton.isEnabled = false
            }
            VoiceInputState.PROCESSING -> {
                voiceButton.text = "⏳ Ninafikiri..."
                voiceButton.isEnabled = false
            }
            VoiceInputState.ERROR -> {
                voiceButton.text = "🎤 Jaribu Tena"
                voiceButton.isEnabled = true
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // VOICE HANDLING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Handle voice button click.
     * Toggles between listening (recording via Whisper ASR) and idle.
     * When recording stops, Whisper transcribes → processes response → TTS speaks next prompt.
     */
    private fun onVoiceButtonClicked() {
        if (!hasMicPermission()) {
            requestMicPermission()
            return
        }

        if (isVoiceListening) {
            // Stop listening — Whisper will transcribe
            isVoiceListening = false
            lifecycleScope.launch {
                try {
                    voicePipeline.stopListening()
                } catch (e: Throwable) {
                    Timber.e(e, "Error stopping voice")
                    viewModel.onVoiceInput("", 0f)
                }
            }
        } else {
            // Start listening
            isVoiceListening = true
            viewModel.retry() // Clear any previous errors
            lifecycleScope.launch {
                try {
                    voicePipeline.startListening(this)
                } catch (e: Throwable) {
                    Timber.e(e, "Error starting voice")
                    isVoiceListening = false
                    // Fall back to text input
                    showTextInput()
                }
            }
        }
    }

    /**
     * Show text input as fallback when voice is unavailable.
     */
    private fun showTextInput() {
        textInput.visibility = View.VISIBLE
        textSubmitButton.visibility = View.VISIBLE
        textInput.requestFocus()
    }

    // ═══════════════════════════════════════════════════════════════
    // PERMISSIONS
    // ═══════════════════════════════════════════════════════════════

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun checkMicPermission() {
        if (!hasMicPermission()) {
            // Show text input by default if no mic permission
            showTextInput()
        }
    }

    private fun requestMicPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            VOICE_PERMISSION_REQUEST
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == VOICE_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Timber.i(TAG, "Microphone permission granted")
            } else {
                Timber.w(TAG, "Microphone permission denied — using text input")
                textInput.visibility = View.VISIBLE
                textSubmitButton.visibility = View.VISIBLE
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // NAVIGATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Speak a prompt using Piper TTS (VoicePipeline).
     * Called when the prompt text changes so Msaidizi speaks to the worker.
     */
    private fun speakPrompt(text: String) {
        if (text.isBlank() || text == lastSpokenPrompt) return
        lastSpokenPrompt = text

        lifecycleScope.launch {
            try {
                viewModel.onTtsStarted()
                voicePipeline.speakAndWait(text, "sw")
                viewModel.onTtsFinished()
            } catch (e: Throwable) {
                Timber.e(e, "TTS error")
                viewModel.onTtsFinished()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voicePipeline.stopSpeaking()
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
