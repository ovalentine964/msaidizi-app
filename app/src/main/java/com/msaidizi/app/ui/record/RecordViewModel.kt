package com.msaidizi.app.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.agent.AgentResponse
import com.msaidizi.app.agent.Orchestrator
import com.msaidizi.app.agent.VoicePersonality
import com.msaidizi.app.core.language.CalibratedConfidence
import com.msaidizi.app.core.language.ConfidenceCalibrator
import com.msaidizi.app.core.util.SwahiliParser
import com.msaidizi.app.voice.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for the Record screen.
 * Manages voice recording, transcription, and agent interaction.
 */
@HiltViewModel
class RecordViewModel @Inject constructor(
    private val voicePipeline: VoicePipeline,
    private val orchestrator: Orchestrator,
    private val confidenceCalibrator: ConfidenceCalibrator,
    private val voicePersonality: VoicePersonality
) : ViewModel() {

    // UI State
    private val _uiState = MutableStateFlow(RecordUiState())
    val uiState: StateFlow<RecordUiState> = _uiState.asStateFlow()

    // One-shot events (launch scanner, etc.)
    private val _events = MutableSharedFlow<RecordEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<RecordEvent> = _events

    // Language preference
    private var currentLanguage = "sw"

    init {
        // Observe pipeline state
        viewModelScope.launch {
            voicePipeline.pipelineState.collect { state ->
                _uiState.value = _uiState.value.copy(
                    pipelineState = state,
                    isRecording = state == PipelineState.LISTENING,
                    isProcessing = state == PipelineState.PROCESSING,
                    isSpeaking = state == PipelineState.SPEAKING
                )
            }
        }

        // Observe transcriptions
        viewModelScope.launch {
            voicePipeline.transcription.collect { result ->
                if (result.success) {
                    // Check confidence and route to feedback if needed
                    val calibrated = confidenceCalibrator.calibrate(
                        rawConfidence = result.confidence,
                        language = currentLanguage
                    )

                    when {
                        calibrated.shouldReject -> {
                            // Low confidence — show feedback
                            _uiState.value = _uiState.value.copy(
                                transcribedText = result.text,
                                statusMessage = "Sikuelewi vizuri…",
                                showPronunciationFeedback = true,
                                pronunciationConfidence = calibrated
                            )
                        }
                        calibrated.shouldConfirm -> {
                            // Medium confidence — ask for confirmation
                            _uiState.value = _uiState.value.copy(
                                transcribedText = result.text,
                                statusMessage = "Je, ni sawa?",
                                showPronunciationFeedback = true,
                                pronunciationConfidence = calibrated
                            )
                        }
                        else -> {
                            // High confidence — process directly
                            _uiState.value = _uiState.value.copy(
                                transcribedText = result.text,
                                statusMessage = "Processing...",
                                showPronunciationFeedback = false
                            )
                            processTranscription(result.text)
                        }
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        statusMessage = result.error ?: "Could not understand",
                        transcribedText = "",
                        showPronunciationFeedback = false
                    )
                }
            }
        }

        // Observe responses
        viewModelScope.launch {
            voicePipeline.response.collect { text ->
                _uiState.value = _uiState.value.copy(
                    responseText = text,
                    statusMessage = "Ready"
                )
            }
        }
    }

    /**
     * Initialize voice pipeline.
     */
    fun initialize() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(statusMessage = "Initializing...")
            voicePipeline.initialize()
            _uiState.value = _uiState.value.copy(statusMessage = "Ready")
        }
    }

    /**
     * Start voice recording.
     */
    fun startRecording() {
        viewModelScope.launch {
            voicePipeline.startListening(viewModelScope)
            _uiState.value = _uiState.value.copy(
                statusMessage = "Listening...",
                transcribedText = "",
                responseText = ""
            )
        }
    }

    /**
     * Stop voice recording.
     */
    fun stopRecording() {
        viewModelScope.launch {
            voicePipeline.stopListening()
        }
    }

    /**
     * Called when user confirms pronunciation was correct.
     */
    fun onPronunciationConfirmed(transcription: String) {
        _uiState.value = _uiState.value.copy(
            showPronunciationFeedback = false,
            statusMessage = "Sawa!"
        )
        // Record positive signal for calibration learning
        viewModelScope.launch {
            confidenceCalibrator.recordOutcome(
                rawConfidence = _uiState.value.pronunciationConfidence?.rawConfidence ?: 0.8f,
                language = currentLanguage,
                wasCorrect = true
            )
        }
        // Process the confirmed transcription
        viewModelScope.launch { processTranscription(transcription) }
    }

    /**
     * Called when user provides a correction.
     */
    fun onPronunciationCorrection(original: String, corrected: String) {
        _uiState.value = _uiState.value.copy(
            showPronunciationFeedback = false,
            transcribedText = corrected,
            statusMessage = "Asante! Nimejifunza."
        )
        // Record negative signal for calibration learning
        viewModelScope.launch {
            confidenceCalibrator.recordOutcome(
                rawConfidence = _uiState.value.pronunciationConfidence?.rawConfidence ?: 0.5f,
                language = currentLanguage,
                wasCorrect = false
            )
            // Store correction for LoRA training
            // This will be picked up by FederatedLearningClient
        }
        // Process the corrected transcription
        viewModelScope.launch { processTranscription(corrected) }
    }

    /**
     * Called when user selects an alternative transcription.
     */
    fun onAlternativeSelected(alternative: String) {
        _uiState.value = _uiState.value.copy(
            showPronunciationFeedback = false,
            transcribedText = alternative,
            statusMessage = "Processing..."
        )
        viewModelScope.launch { processTranscription(alternative) }
    }

    /**
     * Called when pronunciation feedback is dismissed.
     */
    fun onPronunciationDismissed() {
        _uiState.value = _uiState.value.copy(
            showPronunciationFeedback = false,
            statusMessage = "Ready"
        )
    }

    /**
     * Process text input (typed, not spoken).
     */
    fun processTextInput(text: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                transcribedText = text,
                statusMessage = "Processing..."
            )
            processTranscription(text)
        }
    }

    /**
     * Process transcription through the agent orchestrator.
     *
     * Plays a short processing feedback audio cue so the worker knows
     * Msaidizi heard them and is thinking — prevents the "app froze" feeling.
     */
    private suspend fun processTranscription(text: String) {
        try {
            // Play processing feedback — "Sawa, nimesikia..." style cue
            // This tells the worker: "I heard you, give me a moment"
            val processingFeedback = voicePersonality.getProcessingFeedback(currentLanguage)
            _uiState.value = _uiState.value.copy(
                statusMessage = processingFeedback
            )
            // Speak the processing cue briefly (non-blocking pattern)
            voicePipeline.speak(processingFeedback, currentLanguage)

            // Process through orchestrator (now with personality wrapping)
            val response = orchestrator.processInput(text, currentLanguage)

            _uiState.value = _uiState.value.copy(
                responseText = response.text,
                lastResponse = response,
                statusMessage = "Ready"
            )

            // Check for special actions (e.g., launch receipt scanner)
            val action = response.data["action"]
            if (action == "LAUNCH_RECEIPT_SCANNER") {
                _events.tryEmit(RecordEvent.LaunchReceiptScanner)
            }

            // Speak the full response
            if (response.shouldSpeak) {
                voicePipeline.speak(response.text, currentLanguage)
            }

            // Add to conversation history
            addToHistory(text, response.text)
        } catch (e: Throwable) {
            Timber.e(e, "Error processing transcription")
            _uiState.value = _uiState.value.copy(
                statusMessage = "Error: ${e.message}",
                responseText = ""
            )
        }
    }

    /**
     * Add to conversation history.
     */
    private fun addToHistory(userText: String, responseText: String) {
        val history = _uiState.value.conversationHistory.toMutableList()
        history.add(ConversationEntry(userText, responseText, System.currentTimeMillis()))

        // Keep only last 20 entries
        if (history.size > 20) {
            history.removeAt(0)
        }

        _uiState.value = _uiState.value.copy(conversationHistory = history)
    }

    /**
     * Set language.
     */
    fun setLanguage(language: String) {
        currentLanguage = language
    }

    /**
     * Stop speaking.
     */
    fun stopSpeaking() {
        voicePipeline.stopSpeaking()
    }

    /**
     * Release resources when going to background.
     */
    fun onBackground() {
        voicePipeline.onBackground()
    }

    /**
     * Reload models when returning to foreground.
     */
    fun onForeground() {
        viewModelScope.launch {
            voicePipeline.onForeground()
        }
    }

    override fun onCleared() {
        super.onCleared()
        voicePipeline.release()
    }
}

/**
 * UI State for the Record screen.
 */
data class RecordUiState(
    val pipelineState: PipelineState = PipelineState.IDLE,
    val isRecording: Boolean = false,
    val isProcessing: Boolean = false,
    val isSpeaking: Boolean = false,
    val transcribedText: String = "",
    val responseText: String = "",
    val statusMessage: String = "Ready",
    val lastResponse: AgentResponse? = null,
    val conversationHistory: List<ConversationEntry> = emptyList(),
    // Pronunciation feedback state
    val showPronunciationFeedback: Boolean = false,
    val pronunciationConfidence: CalibratedConfidence? = null,
    val pronunciationAlternatives: List<String> = emptyList(),
    val expectedText: String? = null
)

/**
 * Conversation history entry.
 */
data class ConversationEntry(
    val userText: String,
    val responseText: String,
    val timestamp: Long
)

/**
 * One-shot events from the Record ViewModel.
 */
sealed class RecordEvent {
    /** Launch the receipt scanner activity */
    object LaunchReceiptScanner : RecordEvent()
}
