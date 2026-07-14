package com.msaidizi.app.onboarding.bootstrap

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.onboarding.ModelDownloadManager
import com.msaidizi.app.onboarding.WorkerProfile
import com.msaidizi.app.loops.BriefingNotificationWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for the Bootstrap conversation.
 *
 * Manages the conversation lifecycle:
 * 1. Initialize with greeting
 * 2. Start model downloads in background
 * 3. Drive conversation state machine (9 steps)
 * 4. Handle voice input/output cycle
 * 5. Build WorkerProfile incrementally
 * 6. Save profile on completion
 *
 * ## The OpenClaw Naming Pattern
 *
 * Step 2 is AskAgentNaming — the worker names their AI BEFORE it starts
 * working. This creates ownership. The chosen name flows through the
 * entire ViewModel state and gets saved to WorkerProfile.
 *
 * The UI observes [uiState] and renders accordingly.
 * The ViewModel handles all business logic — the Activity is just a view.
 */
@HiltViewModel
class BootstrapViewModel @Inject constructor(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "BootstrapVM"
    }

    // ── Dependencies ──
    private val conversation = BootstrapConversation()
    private val modelDownloadManager = ModelDownloadManager(application)
    // VoicePipeline will be injected in production; for bootstrap we use a simplified flow
    // private lateinit var voicePipeline: VoicePipeline

    // ── UI State ──
    private val _uiState = MutableStateFlow(BootstrapUiState())
    val uiState: StateFlow<BootstrapUiState> = _uiState.asStateFlow()

    // ── Voice State ──
    private val _voiceState = MutableStateFlow(VoiceInputState.IDLE)
    val voiceState: StateFlow<VoiceInputState> = _voiceState.asStateFlow()

    // ── Model Download State ──
    val modelProgress = modelDownloadManager.modelProgress
    val modelStatusMessage = modelDownloadManager.statusMessage
    val modelsReady = modelDownloadManager.allReady

    // ── Worker Understanding (the real output of onboarding) ──
    val understanding: WorkerUnderstanding get() = conversation.understanding

    // ── Current Step ──
    private var currentStep: BootstrapStep = BootstrapStep.Greeting("")

    init {
        // Start with the greeting
        val greetingPrompt = conversation.getGreetingPrompt()
        currentStep = BootstrapStep.Greeting(greetingPrompt)
        updateUiState()

        // Start model downloads in background
        modelDownloadManager.startDownloads(viewModelScope)
    }

    /**
     * Process voice input from the worker.
     * Called when Whisper transcribes the worker's speech.
     *
     * @param transcribedText What the worker said
     * @param confidence ASR confidence (0.0-1.0)
     */
    fun onVoiceInput(transcribedText: String, confidence: Float) {
        if (transcribedText.isBlank()) {
            Timber.w(TAG, "Empty transcription, ignoring")
            _voiceState.value = VoiceInputState.IDLE
            return
        }

        Timber.d(TAG, "Voice input: '%s' (conf: %.2f)", transcribedText.take(50), confidence)

        // Update state to show we're processing
        _voiceState.value = VoiceInputState.PROCESSING
        _uiState.value = _uiState.value.copy(
            lastWorkerInput = transcribedText,
            isProcessing = true
        )

        // Process through conversation state machine
        viewModelScope.launch {
            try {
                val nextStep = conversation.processResponse(currentStep, transcribedText)
                currentStep = nextStep

                // Update UI with new prompt
                updateUiState()

                // If complete, save profile and schedule briefing notifications
                if (nextStep is BootstrapStep.Complete) {
                    saveProfile(nextStep.profile)
                    try {
                        BriefingNotificationWorker.scheduleAllBriefings(getApplication())
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to schedule briefing notifications")
                    }
                }

                _voiceState.value = VoiceInputState.IDLE
            } catch (e: Exception) {
                Timber.e(e, "Error processing voice input")
                _voiceState.value = VoiceInputState.ERROR
                _uiState.value = _uiState.value.copy(
                    errorMessage = if (_uiState.value.language == "sw")
                        "Samahani, jaribu tena." else "Sorry, try again.",
                    isProcessing = false
                )
            }
        }
    }

    /**
     * Process text input (fallback for when voice isn't available).
     * Same as voice input but without ASR confidence.
     */
    fun onTextInput(text: String) {
        onVoiceInput(text, confidence = 1.0f)
    }

    /**
     * Skip the current question with a sensible default.
     * For naming: skips and keeps "Msaidizi" as the default name.
     */
    fun skipCurrentStep() {
        val defaultResponse = when (currentStep) {
            is BootstrapStep.Greeting -> "Rafiki"
            is BootstrapStep.AskAgentNaming -> "Msaidizi" // Skip naming — keep default
            is BootstrapStep.AskBusinessType -> "biashara"
            is BootstrapStep.AskProducts -> "bidhaa mbalimbali"
            is BootstrapStep.AskLocation -> "sokoni"
            is BootstrapStep.AskWorkingHours -> "asubuhi mpaka jioni"
            is BootstrapStep.AskCustomersAndPayment -> "wanakuja mwenyewe, pesa taslimu"
            is BootstrapStep.AskChallenges -> "changamoto za kawaida"
            is BootstrapStep.Summary -> return // Summary is display-only, no skip needed
            is BootstrapStep.AskPin -> return // Can't skip PIN — security requirement
            is BootstrapStep.Complete -> return // Can't skip completion
        }
        onVoiceInput(defaultResponse, confidence = 0.5f)
    }

    /**
     * Called when TTS finishes speaking the current prompt.
     * The worker can now respond.
     */
    fun onTtsFinished() {
        _uiState.value = _uiState.value.copy(isSpeaking = false)
        _voiceState.value = VoiceInputState.LISTENING
    }

    /**
     * Called when TTS starts speaking.
     */
    fun onTtsStarted() {
        _uiState.value = _uiState.value.copy(isSpeaking = true)
        _voiceState.value = VoiceInputState.SPEAKING
    }

    /**
     * Proceed from Summary to Complete (after worker sees the summary).
     */
    fun proceedFromSummary() {
        if (currentStep is BootstrapStep.Summary) {
            // After summary, ask for PIN via voice
            val pinStep = conversation.processResponse(currentStep as BootstrapStep.Summary, "")
            currentStep = pinStep
            updateUiState()
            // Speak the PIN prompt
            val agentName = _uiState.value.agentName
            _uiState.value = _uiState.value.copy(
                isSummary = false,
                isProcessing = false
            )
            // Return — the Activity will speak the prompt
            // Briefings will be scheduled when PIN is complete → onVoiceInput → Complete
        }
    }

    /**
     * Retry after an error.
     */
    fun retry() {
        _voiceState.value = VoiceInputState.IDLE
        _uiState.value = _uiState.value.copy(errorMessage = null, isProcessing = false)
    }

    /**
     * Get the current conversation progress (0.0 to 1.0).
     */
    fun getProgress(): Float = conversation.getProgress(currentStep)

    /**
     * Get the current step number (1-indexed).
     */
    fun getStepNumber(): Int = conversation.getStepNumber(currentStep)

    // ═══════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun updateUiState() {
        val step = currentStep
        val prompt = when (step) {
            is BootstrapStep.Greeting -> step.prompt
            is BootstrapStep.AskAgentNaming -> step.prompt
            is BootstrapStep.AskBusinessType -> step.prompt
            is BootstrapStep.AskProducts -> step.prompt
            is BootstrapStep.AskLocation -> step.prompt
            is BootstrapStep.AskWorkingHours -> step.prompt
            is BootstrapStep.AskCustomersAndPayment -> step.prompt
            is BootstrapStep.AskChallenges -> step.prompt
            is BootstrapStep.Summary -> step.prompt
            is BootstrapStep.Complete -> step.readyMessage
        }

        val hint = when (step) {
            is BootstrapStep.AskProducts -> step.hint
            else -> ""
        }

        val isSummary = step is BootstrapStep.Summary
        val isComplete = step is BootstrapStep.Complete

        // Get the agent name for display (only meaningful after naming step)
        val agentName = when (step) {
            is BootstrapStep.Complete -> step.agentName
            else -> {
                // Build profile temporarily to get agent name
                val profile = conversation.buildProfile()
                profile.msaidiziName
            }
        }

        _uiState.value = _uiState.value.copy(
            prompt = prompt,
            hint = hint,
            stepNumber = conversation.getStepNumber(step),
            progress = conversation.getProgress(step),
            isComplete = isComplete,
            isSummary = isSummary,
            isProcessing = false,
            agentName = agentName,
            language = "sw", // Default — will be updated by conversation
            profile = if (isComplete) (step as BootstrapStep.Complete).profile else null
        )
    }

    /**
     * Save the completed worker profile to SharedPreferences.
     * In production, this would also save to Room database.
     */
    private fun saveProfile(profile: WorkerProfile) {
        val prefs = getApplication<Application>()
            .getSharedPreferences("worker_profile", 0)

        // Also save the understanding
        val u = conversation.understanding

        prefs.edit().apply {
            putString("worker_name", profile.workerName)
            putString("msaidizi_name", profile.msaidiziName)
            putString("business_type", profile.businessType.name)
            putString("business_description", profile.businessDescription)
            putStringSet("products", profile.products.toSet())
            putString("location", profile.location)
            putString("market_name", profile.marketName)
            putInt("work_start_hour", profile.workingHours.startHour)
            putInt("work_end_hour", profile.workingHours.endHour)
            putInt("work_days_per_week", profile.workingHours.daysPerWeek)
            putString("payment_method", profile.paymentMethod.name)
            putString("record_method", profile.keepsRecords.name)
            putBoolean("work_alone", profile.workAlone)
            putString("customer_find_method", profile.customerFindMethod)
            putString("biggest_challenge", profile.biggestChallenge)
            putString("language", profile.language)
            putString("dialect", profile.dialect)
            putDouble("classification_confidence", profile.classificationConfidence)
            putInt("conversation_turns", profile.conversationTurns)
            putLong("onboarding_completed_at", profile.onboardingCompletedAt)
            putBoolean("onboarding_complete", true)

            // Save PIN (set during voice onboarding)
            val pin = conversation.pin
            if (pin.isNotEmpty()) {
                val salt = java.util.UUID.randomUUID().toString()
                val pinHash = hashPin(pin, salt)
                getApplication<Application>()
                    .getSharedPreferences("app_lock", 0)
                    .edit()
                    .putString("pin_hash", pinHash)
                    .putString("pin_salt", salt)
                    .putBoolean("pin_setup_done", true)
                    .apply()
            }

            // Understanding — drives personalization after onboarding
            putString("archetype", u.archetype.name)
            putString("relationship_type", u.relationshipType.name)
            putString("preferred_tone", u.communicationStyle.preferredTone.name)
            putString("formality", u.communicationStyle.formality.name)
            putString("sheng_likelihood", u.communicationStyle.shengLikelihood.name)
            putString("tech_comfort", u.techProfile.comfortLevel.name)
            putBoolean("uses_whatsapp", u.techProfile.usesWhatsApp)
            putBoolean("uses_mpesa", u.techProfile.usesMPesa)
            putString("business_maturity", u.businessSophistication.maturity.name)
            putString("worker_identity", u.businessSophistication.selfIdentity.name)
            putBoolean("is_perishable", u.businessSophistication.isPerishable)
            putBoolean("is_high_value", u.businessSophistication.isHighValue)
            putString("primary_help", u.helpPriority.primary.name)
            putString("secondary_help", u.helpPriority.secondary.name)
            putString("report_type", u.helpPriority.reportType.name)
            putString("report_delivery", u.helpPriority.reportDelivery.name)
            putString("immediate_action", u.helpPriority.immediateAction)
            putString("emotional_state", u.emotionalState.name)
            putString("pain_points", u.painPoints.joinToString(",") { it.name })
            putString("greeting_style", u.greetingStyle)
            putBoolean("has_regulars", u.customerProfile.hasRegulars)
            putString("customer_acquisition", u.customerProfile.acquisitionMethod.name)
            putBoolean("is_urban", u.marketContext.isUrban)
            putBoolean("is_mobile", u.marketContext.isMobile)
            putInt("work_intensity", u.workPatterns.intensity.ordinal)
            putBoolean("peak_aware", u.workPatterns.peakHourAwareness)

            apply()
        }

        Timber.i(TAG, "Profile saved: %s (%s, %s, agent='%s', %d turns)",
            profile.workerName, profile.businessType.name,
            profile.location, profile.msaidiziName, profile.conversationTurns)
    }

    override fun onCleared() {
        super.onCleared()
        modelDownloadManager.destroy()
    }

    private fun hashPin(pin: String, salt: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest((salt + pin).toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

// ═══════════════════════════════════════════════════════════════
// UI STATE
// ═══════════════════════════════════════════════════════════════

/**
 * UI state for the Bootstrap conversation.
 * The Activity observes this and renders accordingly.
 */
data class BootstrapUiState(
    /** Current prompt from Msaidizi (in Kiswahili or English) */
    val prompt: String = "",
    /** Optional hint text below the prompt */
    val hint: String = "",
    /** Current step number (1-indexed, out of 9) */
    val stepNumber: Int = 1,
    /** Progress (0.0 to 1.0) */
    val progress: Float = 0f,
    /** What the worker last said */
    val lastWorkerInput: String = "",
    /** Whether Msaidizi is speaking (TTS active) */
    val isSpeaking: Boolean = false,
    /** Whether processing the worker's response */
    val isProcessing: Boolean = false,
    /** Whether onboarding is complete */
    val isComplete: Boolean = false,
    /** Whether we're on the summary step (shows "Continue" button) */
    val isSummary: Boolean = false,
    /** Error message (if any) */
    val errorMessage: String? = null,
    /** Detected language */
    val language: String = "sw",
    /** The agent name the worker chose (e.g., "Rafiki", "Biashara Yangu") */
    val agentName: String = "Msaidizi",
    /** Completed profile (only set when isComplete) */
    val profile: WorkerProfile? = null
)

// ═══════════════════════════════════════════════════════════════
// VOICE INPUT STATE
// ═══════════════════════════════════════════════════════════════

/**
 * Voice input states for the microphone button.
 */
enum class VoiceInputState {
    /** Idle — waiting for worker to tap mic */
    IDLE,
    /** Listening — recording audio, waiting for speech end */
    LISTENING,
    /** Speaking — TTS is outputting, worker should listen */
    SPEAKING,
    /** Processing — Whisper is transcribing */
    PROCESSING,
    /** Error — something went wrong */
    ERROR
}
