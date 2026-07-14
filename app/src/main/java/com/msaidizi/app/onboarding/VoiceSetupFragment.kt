package com.msaidizi.app.onboarding

import androidx.lifecycle.lifecycleScope
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.msaidizi.app.R
import kotlinx.coroutines.*

/**
 * Voice setup — test the microphone with a fun greeting.
 *
 * "Say 'Habari' to test your microphone!"
 * Makes it playful and non-technical.
 * Handles permission requests gracefully.
 */
class VoiceSetupFragment : Fragment() {

    private var isRecording = false
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null

    private lateinit var statusText: TextView
    private lateinit var instructionText: TextView
    private lateinit var micButton: Button
    private lateinit var volumeBar: ProgressBar
    private lateinit var skipButton: Button
    private lateinit var continueButton: Button

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showMicReady()
        } else {
            showPermissionDenied()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 48, 64, 48)
            gravity = android.view.Gravity.CENTER
        }

        // Icon
        val icon = TextView(requireContext()).apply {
            text = "🎤"
            textSize = 64f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        layout.addView(icon)

        // Title
        val title = TextView(requireContext()).apply {
            text = "Let's Test Your Voice"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        layout.addView(title)

        // Instruction
        instructionText = TextView(requireContext()).apply {
            text = "Say \"Habari\" to test your microphone!\n(Or say \"Hello\" — any language works)"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 32)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        layout.addView(instructionText)

        // Mic button (big, friendly)
        micButton = Button(requireContext()).apply {
            text = "🎤  Tap to Speak"
            textSize = 20f
            setPadding(64, 32, 64, 32)
            setOnClickListener {
                if (!isRecording) {
                    startRecording()
                } else {
                    stopRecording()
                }
            }
        }
        layout.addView(micButton)

        // Volume indicator
        volumeBar = ProgressBar(
            requireContext(), null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                16
            ).apply {
                setMargins(0, 24, 0, 24)
            }
            visibility = View.GONE
        }
        layout.addView(volumeBar)

        // Status text
        statusText = TextView(requireContext()).apply {
            text = ""
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        layout.addView(statusText)

        // Spacer
        val spacer = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48
            )
        }
        layout.addView(spacer)

        // Continue button (hidden until mic works)
        continueButton = Button(requireContext()).apply {
            text = "Continue →"
            textSize = 18f
            setPadding(48, 24, 48, 24)
            visibility = View.GONE
            setOnClickListener {
                findNavController().navigate(R.id.action_voice_setup_to_model_download)
            }
        }
        layout.addView(continueButton)

        // Skip button
        skipButton = Button(requireContext()).apply {
            text = "Skip for now"
            textSize = 14f
            setPadding(32, 16, 32, 16)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
            setOnClickListener {
                findNavController().navigate(R.id.action_voice_setup_to_model_download)
            }
        }
        layout.addView(skipButton)

        // Check permission on load
        checkMicrophonePermission()

        return layout
    }

    private fun checkMicrophonePermission() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                showMicReady()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                showPermissionRationale()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun showMicReady() {
        statusText.text = "✅ Microphone ready! Tap the button and say something."
        statusText.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
        micButton.isEnabled = true
    }

    private fun showPermissionRationale() {
        statusText.text = "Msaidizi needs your microphone to hear your voice.\nThis is how you'll talk to your AI business helper."
        statusText.setTextColor(resources.getColor(android.R.color.darker_gray, null))
        micButton.text = "🎤  Grant Permission"
        micButton.setOnClickListener {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun showPermissionDenied() {
        statusText.text = "⚠️ Microphone permission denied.\nYou can still use Msaidizi by typing."
        statusText.setTextColor(resources.getColor(android.R.color.holo_orange_dark, null))
        micButton.isEnabled = false
        continueButton.visibility = View.VISIBLE
    }

    private fun startRecording() {
        try {
            val sampleRate = 16000
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            audioRecord?.startRecording()
            isRecording = true

            micButton.text = "⏹️  Listening... (tap to stop)"
            volumeBar.visibility = View.VISIBLE
            statusText.text = "🎧 I'm listening! Say \"Habari\"..."
            statusText.setTextColor(resources.getColor(R.color.primary, null))

            // Monitor volume
            recordingJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val buffer = ShortArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (read > 0) {
                        val maxAmplitude = buffer.take(read).maxOrNull()?.toInt() ?: 0
                        val volume = (maxAmplitude * 100 / Short.MAX_VALUE).coerceIn(0, 100)
                        withContext(Dispatchers.Main) {
                            volumeBar.progress = volume
                        }
                    }
                    delay(50)
                }
            }

            // Auto-stop after 5 seconds
            viewLifecycleOwner.lifecycleScope.launch {
                delay(5000)
                if (isRecording) {
                    stopRecording()
                }
            }

        } catch (e: SecurityException) {
            statusText.text = "⚠️ Could not access microphone"
            statusText.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
        }
    }

    private fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // Ignore
        }
        audioRecord = null

        micButton.text = "🎤  Tap to Try Again"
        volumeBar.visibility = View.GONE
        volumeBar.progress = 0

        // Show success
        statusText.text = "🎉 Great! Your microphone works perfectly!\nMsaidizi can hear you."
        statusText.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))

        // Show continue button
        continueButton.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isRecording = false
        recordingJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) { }
    }
}
