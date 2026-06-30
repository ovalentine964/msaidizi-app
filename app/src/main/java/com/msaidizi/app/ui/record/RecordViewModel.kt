package com.msaidizi.app.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.agent.AgentResponse
import com.msaidizi.app.agent.Orchestrator
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
    private val orchestrator: Orchestrator
) : ViewModel() {

    // UI State
    private val _uiState = MutableStateFlow(RecordUiState())
    val uiState: StateFlow<RecordUiState> = _uiState.asStateFlow()

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
                    _uiState.value = _uiState.value.copy(
                        transcribedText = result.text,
                        statusMessage = "Processing..."
                    )
                    // Process through orchestrator
                    processTranscription(result.text)
                } else {
                    _uiState.value = _uiState.value.copy(
                        statusMessage = result.error ?: "Could not understand",
                        transcribedText = ""
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
     */
    private suspend fun processTranscription(text: String) {
        try {
            val response = orchestrator.processInput(text, currentLanguage)

            _uiState.value = _uiState.value.copy(
                responseText = response.text,
                lastResponse = response,
                statusMessage = "Ready"
            )

            // Speak the response
            if (response.shouldSpeak) {
                voicePipeline.speak(response.text, currentLanguage)
            }

            // Add to conversation history
            addToHistory(text, response.text)
        } catch (e: Exception) {
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
    val conversationHistory: List<ConversationEntry> = emptyList()
)

/**
 * Conversation history entry.
 */
data class ConversationEntry(
    val userText: String,
    val responseText: String,
    val timestamp: Long
)
