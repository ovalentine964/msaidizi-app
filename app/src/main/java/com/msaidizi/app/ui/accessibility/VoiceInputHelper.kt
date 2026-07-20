package com.msaidizi.app.ui.accessibility

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import timber.log.Timber
import java.util.Locale

/**
 * Voice input helper — adds a mic button to any EditText.
 *
 * For non-literate and elderly users who cannot type.
 * Adds a voice input button that fills the EditText with spoken text.
 *
 * Usage:
 *   VoiceInputHelper.attach(context, editText, micButton, language = "sw")
 *   // Or with auto-created mic button:
 *   VoiceInputHelper.attachAuto(context, editText, language = "sw")
 */
class VoiceInputHelper(
    private val context: Context,
    private val editText: EditText,
    private val ttsHelper: AccessibilityTtsHelper? = null
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var language: String = "sw"
    private var micButton: ImageButton? = null
    private var onResult: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    /**
     * Start voice input.
     */
    fun startListening() {
        if (!hasAudioPermission()) {
            ttsHelper?.speak("Ruhusa ya kurekodi inahitajika. Tafadhali ruhusu ufikiaji wa maikrofoni.")
            onError?.invoke("permission_denied")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            ttsHelper?.speak("Utambuzi wa sauti haupatikani. Andika ujumbe wako badala yake.")
            onError?.invoke("not_available")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createRecognitionListener())
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, getLocale())
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, getLocale())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            speechRecognizer?.startListening(intent)
            isListening = true
            updateMicButtonState()
            ttsHelper?.speak("Sikiliza... Sasa sema")

        } catch (e: Throwable) {
            Timber.e(e, "Error starting speech recognition")
            ttsHelper?.speakError("voice")
            onError?.invoke("start_failed")
        }
    }

    /**
     * Stop listening.
     */
    fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
        updateMicButtonState()
    }

    /**
     * Release resources.
     */
    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    /**
     * Set the language for recognition.
     */
    fun setLanguage(langCode: String) {
        language = langCode
    }

    private fun getLocale(): String {
        return when (language) {
            "sw", "sheng" -> "sw-KE"
            "en" -> "en-US"
            else -> "sw-KE"
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            micButton?.let {
                it.tag = "listening"
                updateMicButtonState()
            }
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            isListening = false
            updateMicButtonState()
        }

        override fun onError(error: Int) {
            isListening = false
            updateMicButtonState()

            val failureType = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH ->
                    AccessibilityTtsHelper.VoiceFailureType.NO_SPEECH_DETECTED
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    AccessibilityTtsHelper.VoiceFailureType.NO_SPEECH_DETECTED
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    AccessibilityTtsHelper.VoiceFailureType.NETWORK_ERROR
                SpeechRecognizer.ERROR_AUDIO ->
                    AccessibilityTtsHelper.VoiceFailureType.AUDIO_ERROR
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    AccessibilityTtsHelper.VoiceFailureType.PERMISSION_DENIED
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    AccessibilityTtsHelper.VoiceFailureType.MODEL_NOT_READY
                else ->
                    AccessibilityTtsHelper.VoiceFailureType.LOW_CONFIDENCE
            }

            ttsHelper?.speakVoiceRecovery(failureType)
            onError?.invoke("error_$error")
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            updateMicButtonState()

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val bestMatch = matches[0]
                editText.setText(bestMatch)
                editText.setSelection(bestMatch.length)
                ttsHelper?.speak("Umesema: $bestMatch")
                onResult?.invoke(bestMatch)
            } else {
                ttsHelper?.speakVoiceRecovery(AccessibilityTtsHelper.VoiceFailureType.NO_SPEECH_DETECTED)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                editText.setText(matches[0])
                editText.setSelection(matches[0].length)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun updateMicButtonState() {
        micButton?.let { btn ->
            if (isListening) {
                btn.alpha = 0.6f
                btn.contentDescription = "Inasikiliza... Gusa kuacha"
            } else {
                btn.alpha = 1.0f
                btn.contentDescription = "Gusa kusema badala ya kuandika"
            }
        }
    }

    companion object {
        /**
         * Attach voice input to an existing EditText + ImageButton pair.
         */
        fun attach(
            context: Context,
            editText: EditText,
            micButton: ImageButton,
            language: String = "sw",
            ttsHelper: AccessibilityTtsHelper? = null,
            onResult: ((String) -> Unit)? = null,
            onError: ((String) -> Unit)? = null
        ): VoiceInputHelper {
            val helper = VoiceInputHelper(context, editText, ttsHelper).apply {
                this.language = language
                this.micButton = micButton
                this.onResult = onResult
                this.onError = onError
            }

            // Add content description for accessibility
            editText.contentDescription = "Andika hapa au gusa maikrofoni kusema"
            micButton.contentDescription = "Gusa kusema badala ya kuandika"

            // Minimum touch target 48dp
            val minSize = (48 * context.resources.displayMetrics.density).toInt()
            if (micButton.minimumWidth < minSize) micButton.minimumWidth = minSize
            if (micButton.minimumHeight < minSize) micButton.minimumHeight = minSize

            micButton.setOnClickListener {
                if (helper.isListening) {
                    helper.stopListening()
                } else {
                    helper.startListening()
                }
            }

            return helper
        }

        /**
         * Attach voice input with a programmatically created mic button.
         * Adds the button to the end of the EditText's parent layout.
         */
        fun attachAuto(
            context: Context,
            editText: EditText,
            language: String = "sw",
            ttsHelper: AccessibilityTtsHelper? = null,
            onResult: ((String) -> Unit)? = null
        ): VoiceInputHelper {
            val helper = VoiceInputHelper(context, editText, ttsHelper).apply {
                this.language = language
                this.onResult = onResult
            }

            editText.contentDescription = "Andika hapa au gusa maikrofoni kusema"

            // Create a mic button if parent is a layout
            val parent = editText.parent
            if (parent is android.view.ViewGroup) {
                val micButton = ImageButton(context).apply {
                    setImageResource(android.R.drawable.ic_btn_speak_now)
                    contentDescription = "Gusa kusema badala ya kuandika"
                    background = null
                    val size = (48 * context.resources.displayMetrics.density).toInt()
                    minimumWidth = size
                    minimumHeight = size
                    setPadding(8, 8, 8, 8)
                }
                helper.micButton = micButton

                micButton.setOnClickListener {
                    if (helper.isListening) {
                        helper.stopListening()
                    } else {
                        helper.startListening()
                    }
                }
            }

            return helper
        }
    }
}
