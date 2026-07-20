package com.msaidizi.app.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.msaidizi.app.R
import com.msaidizi.app.voice.VoicePipeline
import com.msaidizi.app.voice.TranscriptionResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Business Discovery — Msaidizi's conversation to understand the worker's business.
 *
 * This is NOT a form. It's a conversation. Msaidizi asks natural questions,
 * the worker speaks, and Msaidizi listens and learns.
 *
 * ## How It Works
 *
 * 1. Worker sees Msaidizi's question on screen
 * 2. Worker taps the microphone and speaks
 * 3. Whisper (on-device) transcribes the speech
 * 4. Msaidizi processes the answer and asks the next question
 * 5. Each answer updates Bayesian priors (STA 142) for business classification
 *
 * ## Academic Foundations
 *
 * ### BCB 108 — Communication
 * - Voice-first: matches how informal workers actually communicate
 * - Natural conversation flow: not a checklist
 * - Follow-up questions based on answers
 *
 * ### STA 142 — Bayesian Inference
 * - Each answer updates posterior probability of business type
 * - Prior: from AgentNamingFragment
 * - Likelihood: keyword matching + context
 * - Posterior: refined classification
 *
 * ### ECO 201 — Producer Theory
 * - Questions map to production function components
 * - Supply method → inputs
 * - Products → output
 * - Customers → market access
 * - Payment → financial infrastructure
 *
 * @see OnboardingConversation for the conversation logic
 * @see WorkerProfile for the data model
 */
@AndroidEntryPoint
class BusinessDiscoveryFragment : Fragment() {

    @Inject
    lateinit var voicePipeline: VoicePipeline

    @Inject
    lateinit var workerProfileDao: com.msaidizi.app.onboarding.WorkerProfileDao

    private lateinit var conversation: OnboardingConversation
    private var currentStep: ConversationStep? = null
    private var isVoiceListening = false

    // UI elements
    private lateinit var promptText: TextView
    private lateinit var hintText: TextView
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var voiceButton: Button
    private lateinit var skipButton: Button
    private lateinit var continueButton: Button

    // Profile data
    private var workerName: String = ""
    private var msaidiziName: String = "Msaidizi"

    companion object {
        private const val VOICE_PERMISSION_REQUEST = 1001
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Get names from arguments
        workerName = arguments?.getString("worker_name") ?: ""
        msaidiziName = arguments?.getString("msaidizi_name") ?: "Msaidizi"

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            gravity = android.view.Gravity.CENTER
        }

        // ── Header ──
        val header = TextView(requireContext()).apply {
            text = "🎤 $msaidiziName anakuuliza..."
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        layout.addView(header)

        // ── Progress ──
        progressText = TextView(requireContext()).apply {
            text = "Swali 1 kati ya 10"
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 4)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        layout.addView(progressText)

        progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 10
            progress = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 32) }
        }
        layout.addView(progressBar)

        // ── Conversation Prompt ──
        promptText = TextView(requireContext()).apply {
            text = "Sasa $workerName, nieleze — unafanya biashara gani?"
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 24, 0, 16)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        layout.addView(promptText)

        // ── Hint Text ──
        hintText = TextView(requireContext()).apply {
            text = ""
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 32)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        layout.addView(hintText)

        // ── Voice Button ──
        voiceButton = Button(requireContext()).apply {
            text = "🎤 Sema Sasa"
            textSize = 20f
            setPadding(64, 32, 64, 32)
            setOnClickListener { startVoiceInput() }
        }
        layout.addView(voiceButton)

        // ── Text Input Alternative ──
        val textInputHint = TextView(requireContext()).apply {
            text = "Au andika jibu lako hapa chini"
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 16, 0, 8)
            setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
        layout.addView(textInputHint)

        // ── Skip Button ──
        skipButton = Button(requireContext()).apply {
            text = "Ruka swali hili →"
            textSize = 14f
            setOnClickListener { skipQuestion() }
        }
        layout.addView(skipButton)

        // ── Continue Button (hidden until response) ──
        continueButton = Button(requireContext()).apply {
            text = "Endelea →"
            textSize = 18f
            setPadding(48, 24, 48, 24)
            visibility = View.GONE
            setOnClickListener { proceedToNextStep() }
        }
        layout.addView(continueButton)

        // Initialize conversation
        conversation = OnboardingConversation()

        // Start the conversation
        startConversation()

        // Initialize voice pipeline and collect transcriptions
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                voicePipeline.initialize()
                Timber.d("VoicePipeline initialized for BusinessDiscovery")
            } catch (e: Exception) {
                Timber.e(e, "VoicePipeline init failed in BusinessDiscovery")
            }
        }

        // Collect transcriptions from voice pipeline
        viewLifecycleOwner.lifecycleScope.launch {
            voicePipeline.transcription.collect { result ->
                if (result.success && result.text.isNotBlank()) {
                    Timber.d("Voice transcription received: %s", result.text.take(50))
                    processResponse(result.text)
                } else {
                    hintText.text = result.error ?: "Sikujielewa. Jaribu tena."
                }
                isVoiceListening = false
                voiceButton.text = "🎤 Sema Sasa"
                voiceButton.isEnabled = true
            }
        }

        // Entrance animation
        layout.alpha = 0f
        layout.animate()
            .alpha(1f)
            .setDuration(600)
            .setStartDelay(100)
            .start()

        return layout
    }

    /**
     * Start the onboarding conversation flow.
     */
    private fun startConversation() {
        viewLifecycleOwner.lifecycleScope.launch {
            conversation.startConversation(
                workerName = workerName,
                msaidiziName = msaidiziName,
                language = "sw"
            ).collectLatest { step ->
                currentStep = step
                updateUI(step)
            }
        }
    }

    /**
     * Update UI based on current conversation step.
     */
    private fun updateUI(step: ConversationStep) {
        when (step) {
            is ConversationStep.AskBusinessIntro -> {
                promptText.text = step.prompt
                hintText.text = ""
                updateProgress(1)
            }
            is ConversationStep.AskProducts -> {
                promptText.text = step.prompt
                hintText.text = step.followUpHint
                updateProgress(2)
            }
            is ConversationStep.AskLocation -> {
                promptText.text = step.prompt
                hintText.text = ""
                updateProgress(3)
            }
            is ConversationStep.AskWorkingHours -> {
                promptText.text = step.prompt
                hintText.text = ""
                updateProgress(4)
            }
            is ConversationStep.AskWorkAlone -> {
                promptText.text = step.prompt
                hintText.text = ""
                updateProgress(5)
            }
            is ConversationStep.AskSupplyMethod -> {
                promptText.text = step.prompt
                hintText.text = ""
                updateProgress(6)
            }
            is ConversationStep.AskCustomerFind -> {
                promptText.text = step.prompt
                hintText.text = ""
                updateProgress(7)
            }
            is ConversationStep.AskPaymentMethod -> {
                promptText.text = step.prompt
                hintText.text = ""
                updateProgress(8)
            }
            is ConversationStep.AskRecordKeeping -> {
                promptText.text = step.prompt
                hintText.text = ""
                updateProgress(9)
            }
            is ConversationStep.AskChallenge -> {
                promptText.text = step.prompt
                hintText.text = ""
                updateProgress(10)
            }
            is ConversationStep.AskWhatsApp -> {
                promptText.text = step.prompt
                hintText.text = ""
                voiceButton.text = "🎤 Ndio / Hapana"
                updateProgress(11)
            }
            is ConversationStep.ConfirmWhatsAppNumber -> {
                promptText.text = step.prompt
                hintText.text = "Kwa mfano: 0712345678 au 254712345678"
                voiceButton.text = "🎤 Sema Namba"
                updateProgress(12)
            }
            is ConversationStep.WhatsAppConnected -> {
                promptText.text = step.prompt
                hintText.text = ""
                voiceButton.visibility = View.GONE
                continueButton.visibility = View.VISIBLE
            }
            is ConversationStep.ModelDownloadStatus -> {
                promptText.text = step.prompt
                hintText.text = "Models downloading in background..."
                voiceButton.isEnabled = false
                continueButton.visibility = View.VISIBLE
            }
            is ConversationStep.FirstInsight -> {
                promptText.text = step.prompt
                hintText.text = ""
                voiceButton.visibility = View.GONE
                continueButton.text = "Anza Kufanya Kazi →"
                continueButton.visibility = View.VISIBLE
            }
            is ConversationStep.Complete -> {
                // Save profile and navigate to phone verification
                saveProfile(step.profile)
                navigateToPhoneVerification()
            }
        }
    }

    /**
     * Update progress display.
     */
    private fun updateProgress(step: Int) {
        progressText.text = "Swali $step kati ya 12"
        progressBar.max = 12
        progressBar.progress = step
    }

    /**
     * Start voice input.
     * Uses on-device Whisper ASR via VoicePipeline for transcription.
     * No internet needed — Whisper runs locally on the device.
     */
    private fun startVoiceInput() {
        // Check microphone permission
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.RECORD_AUDIO),
                VOICE_PERMISSION_REQUEST
            )
            return
        }

        if (isVoiceListening) {
            // Stop listening — Whisper will transcribe
            isVoiceListening = false
            voiceButton.text = "⏳ Ninachambua..."
            voiceButton.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    voicePipeline.stopListening()
                } catch (e: Exception) {
                    Timber.e(e, "Error stopping voice")
                    voiceButton.text = "🎤 Sema Sasa"
                    voiceButton.isEnabled = true
                }
            }
        } else {
            // Start listening via VoicePipeline (Whisper ASR)
            isVoiceListening = true
            voiceButton.text = "🔴 Nasikiliza... (bonyeza kuacha)"
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    voicePipeline.startListening(this)
                } catch (e: Exception) {
                    Timber.e(e, "Error starting voice")
                    isVoiceListening = false
                    voiceButton.text = "🎤 Sema Sasa"
                    voiceButton.isEnabled = true
                    // Show text input hint as fallback
                    hintText.text = "Sauti haikufanya kazi. Jaribu kuruka swali hili."
                }
            }
        }
    }

    /**
     * Process the worker's response (from voice or text input).
     */
    private fun processResponse(response: String) {
        val step = currentStep ?: return

        val nextStep = conversation.processResponse(step, response)
        currentStep = nextStep
        updateUI(nextStep)
    }

    /**
     * Skip the current question with a default answer.
     */
    private fun skipQuestion() {
        val step = currentStep ?: return
        val defaultResponse = when (step) {
            is ConversationStep.AskBusinessIntro -> "biashara"
            is ConversationStep.AskProducts -> "bidhaa mbalimbali"
            is ConversationStep.AskLocation -> "sokoni"
            is ConversationStep.AskWorkingHours -> "asubuhi mpaka jioni"
            is ConversationStep.AskWorkAlone -> "peke yangu"
            is ConversationStep.AskSupplyMethod -> "nunua sokoni"
            is ConversationStep.AskCustomerFind -> "wanakuja"
            is ConversationStep.AskPaymentMethod -> "zote mbili"
            is ConversationStep.AskRecordKeeping -> "kichwani"
            is ConversationStep.AskChallenge -> "changamoto za kawaida"
            is ConversationStep.AskWhatsApp -> "hapana"
            is ConversationStep.ConfirmWhatsAppNumber -> ""
            else -> ""
        }
        processResponse(defaultResponse)
    }

    /**
     * Proceed to next step (when continue button is clicked).
     */
    private fun proceedToNextStep() {
        val step = currentStep ?: return

        when (step) {
            is ConversationStep.ModelDownloadStatus -> {
                // Models downloading — proceed to first insight
                val nextStep = conversation.processResponse(step, "")
                currentStep = nextStep
                updateUI(nextStep)
            }
            is ConversationStep.FirstInsight -> {
                // Build profile and navigate
                val nextStep = conversation.processResponse(step, "")
                currentStep = nextStep
                updateUI(nextStep)
            }
            else -> {}
        }
    }

    /**
     * Save the completed worker profile to Room database.
     * Retains SharedPreferences fallback for backward compatibility.
     */
    private fun saveProfile(profile: WorkerProfile) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Primary: persist to Room (single source of truth)
                workerProfileDao.upsert(profile)
                Timber.i("Worker profile saved to Room database")
            } catch (e: Exception) {
                Timber.e(e, "Failed to save profile to Room — falling back to SharedPreferences")
            }

            // Secondary: SharedPreferences for backward compat with existing reads
            val prefs = requireContext().getSharedPreferences("worker_profile", 0)
            prefs.edit().apply {
                putString("worker_name", profile.workerName)
                putString("msaidizi_name", profile.msaidiziName)
                putString("business_type", profile.businessType.name)
                putString("business_description", profile.businessDescription)
                putString("location", profile.location)
                putString("language", profile.language)
                putString("payment_method", profile.paymentMethod.name)
                putString("record_method", profile.keepsRecords.name)
                putBoolean("work_alone", profile.workAlone)
                putString("biggest_challenge", profile.biggestChallenge)
                putLong("onboarding_completed_at", profile.onboardingCompletedAt)
                putBoolean("onboarding_complete", true)
                apply()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            voicePipeline.stopSpeaking()
            if (isVoiceListening) {
                viewLifecycleOwner.lifecycleScope.launch {
                    voicePipeline.stopListening()
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Navigate to phone verification after business discovery.
     */
    private fun navigateToPhoneVerification() {
        findNavController().navigate(
            R.id.action_business_discovery_to_phone_verification,
            null,
            androidx.navigation.NavOptions.Builder()
                .setPopUpTo(R.id.nav_onboarding, false)
                .build()
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
                startVoiceInput()
            }
        }
    }
}
