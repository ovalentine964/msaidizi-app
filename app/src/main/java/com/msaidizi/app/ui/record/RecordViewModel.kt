package com.msaidizi.app.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.agent.AgentInput
import com.msaidizi.app.agent.AgentOutput
import com.msaidizi.app.agent.SuperAgent
import com.msaidizi.app.security.WorkerIdProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class RecordViewModel @Inject constructor(
    private val superAgent: SuperAgent,
    private val workerIdProvider: WorkerIdProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecordUiState())
    val uiState: StateFlow<RecordUiState> = _uiState.asStateFlow()

    private val sessionId = UUID.randomUUID().toString()

    fun toggleVoice() {
        _uiState.value = _uiState.value.copy(isListening = !_uiState.value.isListening)
    }

    fun sendText(text: String) {
        viewModelScope.launch {
            val input = AgentInput(text = text, language = "sw", sessionId = sessionId, workerId = workerIdProvider.getWorkerId())
            val output = superAgent.processInput(input)
            _uiState.value = _uiState.value.copy(lastResponse = output)
        }
    }

    fun quickRecord(type: String) {
        val prompt = when (type) {
            "sale" -> "Nimeuza"
            "purchase" -> "Nimenunua"
            "expense" -> "Nimetumia"
            "mpesa" -> "M-Pesa"
            else -> type
        }
        sendText(prompt)
    }
}

data class RecordUiState(
    val isListening: Boolean = false,
    val lastResponse: AgentOutput? = null
)
