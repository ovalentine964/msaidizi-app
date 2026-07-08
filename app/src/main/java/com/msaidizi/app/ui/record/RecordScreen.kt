package com.msaidizi.app.ui.record

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.msaidizi.app.R
import com.msaidizi.app.ui.accessibility.AccessibilityTtsHelper
import com.msaidizi.app.ui.accessibility.VoiceInputHelper
import com.msaidizi.app.voice.PipelineState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Record screen — voice recording and interaction UI.
 * Large animated microphone button for easy access.
 *
 * ACCESSIBILITY:
 * - Voice input fallback for text input field
 * - Error messages spoken aloud, not just displayed
 * - Voice recognition failure recovery with clear instructions
 * - Minimum touch targets 48dp on all interactive elements
 * - Content descriptions on all UI elements
 * - TTS feedback on state changes
 */
@AndroidEntryPoint
class RecordFragment : Fragment() {

    private val viewModel: RecordViewModel by viewModels()

    private lateinit var micButton: FloatingActionButton
    private lateinit var statusText: TextView
    private lateinit var transcribedText: TextView
    private lateinit var responseText: TextView
    private lateinit var textInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var conversationRecycler: RecyclerView
    private lateinit var pronunciationFeedback: PronunciationFeedbackView

    // ── Accessibility ──
    private var ttsHelper: AccessibilityTtsHelper? = null
    private var textInputVoiceHelper: VoiceInputHelper? = null
    private lateinit var textInputMicButton: ImageButton

    private var isRecording = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_record, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ttsHelper = AccessibilityTtsHelper(requireContext())
        setupViews(view)
        observeState()
        viewModel.initialize()
    }

    private fun setupViews(view: View) {
        micButton = view.findViewById(R.id.mic_button)
        statusText = view.findViewById(R.id.status_text)
        transcribedText = view.findViewById(R.id.transcribed_text)
        responseText = view.findViewById(R.id.response_text)
        textInput = view.findViewById(R.id.text_input)
        sendButton = view.findViewById(R.id.send_button)
        conversationRecycler = view.findViewById(R.id.conversation_recycler)

        // ACCESSIBILITY: Content descriptions
        micButton.contentDescription = "Kitufe kikubcha cha sauti. Gusa kuanza kurekodi."
        textInput.contentDescription = "Andika ujumbe wako hapa, au tumia sauti"
        sendButton.contentDescription = "Tuma ujumbe"
        statusText.contentDescription = "Hali ya sasa"
        transcribedText.contentDescription = "Maandishi yaliyosikika"
        responseText.contentDescription = "Jibu la mfumo"

        // ACCESSIBILITY: Minimum touch targets (48dp)
        val minTouch = (48 * resources.displayMetrics.density).toInt()
        micButton.minimumWidth = minTouch
        micButton.minimumHeight = minTouch
        sendButton.minimumWidth = minTouch
        sendButton.minimumHeight = minTouch

        // Mic button click
        micButton.setOnClickListener {
            if (isRecording) {
                viewModel.stopRecording()
            } else {
                if (hasAudioPermission()) {
                    viewModel.startRecording()
                } else {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
                }
            }
        }

        // Text input send
        sendButton.setOnClickListener {
            val text = textInput.text.toString().trim()
            if (text.isNotEmpty()) {
                viewModel.processTextInput(text)
                textInput.text.clear()
            }
        }

        // ── Accessibility: Voice input fallback for text field ──
        // Create a mic button for the text input area
        textInputMicButton = ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            contentDescription = "Gusa kusema badala ya kuandika"
            background = null
            minimumWidth = minTouch
            minimumHeight = minTouch
            setPadding(8, 8, 8, 8)
        }
        // Add mic button to the text input row if parent allows
        val textInputParent = textInput.parent as? ViewGroup
        textInputParent?.addView(textInputMicButton)

        textInputVoiceHelper = VoiceInputHelper.attach(
            context = requireContext(),
            editText = textInput,
            micButton = textInputMicButton,
            language = "sw",
            ttsHelper = ttsHelper,
            onResult = { text ->
                // Auto-send on voice input if text is substantial
                if (text.length > 3) {
                    viewModel.processTextInput(text)
                    textInput.text.clear()
                }
            }
        )

        // Setup conversation recycler
        conversationRecycler.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }

        // Setup pronunciation feedback
        pronunciationFeedback = view.findViewById(R.id.pronunciation_feedback)
        pronunciationFeedback.onConfirmed = { transcription ->
            viewModel.onPronunciationConfirmed(transcription)
        }
        pronunciationFeedback.onCorrection = { original, corrected ->
            viewModel.onPronunciationCorrection(original, corrected)
        }
        pronunciationFeedback.onAlternativeSelected = { alternative ->
            viewModel.onAlternativeSelected(alternative)
        }
        pronunciationFeedback.onDismissed = {
            viewModel.onPronunciationDismissed()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(state)
                }
            }
        }
    }

    private fun updateUI(state: RecordUiState) {
        // Update recording state
        isRecording = state.isRecording
        micButton.setImageResource(
            if (state.isRecording) R.drawable.ic_stop else R.drawable.ic_mic
        )

        // Update mic button color based on state
        val colorRes = when (state.pipelineState) {
            PipelineState.LISTENING -> R.color.recording_active
            PipelineState.PROCESSING -> R.color.processing
            PipelineState.SPEAKING -> R.color.speaking
            PipelineState.ERROR -> R.color.error
            else -> R.color.primary
        }
        micButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
            resources.getColor(colorRes, null)
        )

        // Update status text
        statusText.text = state.statusMessage

        // Update transcribed text
        transcribedText.text = state.transcribedText
        transcribedText.visibility = if (state.transcribedText.isNotEmpty()) View.VISIBLE else View.GONE

        // Update response text
        responseText.text = state.responseText
        responseText.visibility = if (state.responseText.isNotEmpty()) View.VISIBLE else View.GONE

        // Update conversation history
        if (state.conversationHistory.isNotEmpty()) {
            conversationRecycler.adapter = ConversationAdapter(state.conversationHistory)
            conversationRecycler.scrollToPosition(state.conversationHistory.size - 1)
        }

        // Update pronunciation feedback
        if (state.showPronunciationFeedback && state.pronunciationConfidence != null) {
            pronunciationFeedback.showFeedback(
                transcription = state.transcribedText,
                confidence = requireNotNull(state.pronunciationConfidence) { "Pronunciation confidence must be non-null when feedback is shown" },
                expectedText = state.expectedText,
                alternatives = state.pronunciationAlternatives
            )
        } else if (!state.showPronunciationFeedback && pronunciationFeedback.isShowingFeedback()) {
            pronunciationFeedback.dismiss()
        }

        // ── Accessibility: Speak errors, don't just display ──
        if (state.statusMessage.contains("Error", ignoreCase = true) ||
            state.statusMessage.contains("Tatizo", ignoreCase = true)) {
            ttsHelper?.speakError(state.statusMessage)
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        viewModel.onForeground()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onBackground()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ttsHelper?.release()
        ttsHelper = null
        textInputVoiceHelper?.destroy()
        textInputVoiceHelper = null
    }
}

/**
 * RecyclerView adapter for conversation history.
 * ACCESSIBILITY: Content descriptions on conversation entries.
 */
class ConversationAdapter(
    private val entries: List<ConversationEntry>
) : RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val userText: TextView = view.findViewById(R.id.user_text)
        val responseText: TextView = view.findViewById(R.id.response_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        holder.userText.text = "🎤 ${entry.userText}"
        holder.responseText.text = "🤖 ${entry.responseText}"
        // ACCESSIBILITY: Full content description
        holder.itemView.contentDescription =
            "Wewe: ${entry.userText}. Msaidizi: ${entry.responseText}"
    }

    override fun getItemCount() = entries.size
}
