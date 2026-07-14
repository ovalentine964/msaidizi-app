package com.msaidizi.app.onboarding

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.msaidizi.app.R
import com.msaidizi.app.data.api.MsaidiziApi
import com.msaidizi.app.databinding.ActivityOnboardingBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Onboarding Activity — Msaidizi meets Valentine's mum for the first time.
 *
 * This is NOT a technical setup screen. It's a conversation.
 * Msaidizi introduces herself, gets to know the worker, and lets the
 * worker name her — creating psychological ownership.
 *
 * ## Flow (5 Phases)
 *
 * 1. **Karibu!** (~30s) — Introduction, warm welcome
 * 2. **Naming** (~1 min) — Worker names Msaidizi, Msaidizi learns worker's name
 * 3. **Business Discovery** (~3-4 min) — Voice conversation to understand the business
 * 4. **Model Setup** (~2-3 min) — Models download in background while conversation continues
 * 5. **First Value** (immediate) — First insight based on what Msaidizi learned
 *
 * ## Academic Foundations
 *
 * ### BCB 108 — Communication
 * - Voice-first: matches how informal workers communicate
 * - Natural conversation, not forms
 * - Culturally appropriate: Swahili first, English second
 *
 * ### PSY 101 — Behavioral Psychology
 * - Naming creates ownership (Kahneman's endowment effect)
 * - Personalization drives engagement
 * - First value creates "aha moment"
 *
 * ### STA 142 — Bayesian Inference
 * - Each answer updates business type classification
 * - Confidence increases with each conversation turn
 *
 * @see OnboardingConversation for the conversation logic
 * @see WorkerProfile for the data model
 * @see ModelDownloadManager for model downloads on mobile data
 */
class OnboardingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var navController: NavController

    val onboardingData = OnboardingSessionData()
    lateinit var api: MsaidiziApi
        private set

    /** Model download manager — downloads full models on mobile data */
    lateinit var modelDownloadManager: ModelDownloadManager
        private set

    /** Worker profile being built during onboarding */
    var workerProfile: WorkerProfile = WorkerProfile()

    val whatsappStep: WhatsAppConnectionStep by lazy {
        ViewModelProvider(this, WhatsAppStepFactory(application, api, onboardingData)).get(WhatsAppConnectionStep::class.java)
    }

    /** Unified phone verification step (SMS + WhatsApp) */
    val phoneVerificationStep: PhoneVerificationStep by lazy {
        ViewModelProvider(this, PhoneVerificationStepFactory(application, api, onboardingData)).get(PhoneVerificationStep::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize API
        api = Retrofit.Builder()
            .baseUrl(getString(R.string.api_base_url))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MsaidiziApi::class.java)

        // Initialize model download manager
        modelDownloadManager = ModelDownloadManager(applicationContext)

        // Start model downloads immediately (background during onboarding)
        modelDownloadManager.startDownloads(lifecycleScope)

        // Set up navigation
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_onboarding) as NavHostFragment
        navController = navHostFragment.navController

        // Update progress indicator on navigation changes
        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateProgress(destination.id)
        }

        // Observe model download state
        observeModelDownloads()
    }

    /**
     * Update progress indicator based on current navigation destination.
     */
    private fun updateProgress(destinationId: Int) {
        // Updated 6-step onboarding flow:
        // 1. Karibu! (Introduction)
        // 2. Naming (AgentNaming)
        // 3. Business Discovery (BusinessDiscovery)
        // 4. Phone Verification (PhoneVerification)
        // 5. Personality (Personality)
        // 6. Ready! (FirstUse)
        val step = when (destinationId) {
            R.id.introductionFragment -> 1
            R.id.agentNamingFragment -> 2
            R.id.businessDiscoveryFragment -> 3
            R.id.phoneVerificationFragment -> 4
            R.id.personalityFragment -> 5
            R.id.firstUseFragment -> 6
            else -> 0
        }
        binding.progressOnboarding.progress = step
        binding.progressOnboarding.max = 6

        // Update step indicator text
        val stepText = when (step) {
            1 -> "Karibu! Welcome"
            2 -> "Name Your Helper"
            3 -> "Getting to Know You"
            4 -> "Verify Phone"
            5 -> "Preferences"
            6 -> "Ready!"
            else -> ""
        }
        binding.textViewStepIndicator.text = stepText
    }

    /**
     * Observe model download state and update UI.
     * Shows natural progress messages, not technical download stats.
     */
    private fun observeModelDownloads() {
        lifecycleScope.launch {
            modelDownloadManager.statusMessage.collectLatest { message ->
                if (message.isNotEmpty()) {
                    // Update any visible status text
                    // The message is in Swahili: "Ninajifunza lugha yako..."
                }
            }
        }

        lifecycleScope.launch {
            modelDownloadManager.allReady.collectLatest { ready ->
                if (ready) {
                    // All models downloaded — Msaidizi is ready
                    // This will be picked up by ModelSetupFragment
                }
            }
        }
    }

    /**
     * Get the current model download progress for display.
     * Returns a map of model ID to progress (0.0-1.0).
     */
    fun getModelProgress(): Map<String, Float> {
        return modelDownloadManager.modelProgress.value
    }

    /**
     * Get overall download progress (0.0-1.0).
     */
    fun getOverallDownloadProgress(): Float {
        return modelDownloadManager.getOverallProgress()
    }

    /**
     * Check if all models are ready.
     */
    fun areModelsReady(): Boolean {
        return modelDownloadManager.allReady.value
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    override fun onDestroy() {
        super.onDestroy()
        modelDownloadManager.destroy()
    }
}

class WhatsAppStepFactory(
    private val application: android.app.Application,
    private val api: MsaidiziApi,
    private val onboardingData: OnboardingSessionData
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WhatsAppConnectionStep::class.java)) {
            return WhatsAppConnectionStep(application, api, onboardingData) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

class PhoneVerificationStepFactory(
    private val application: android.app.Application,
    private val api: MsaidiziApi,
    private val onboardingData: OnboardingSessionData
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PhoneVerificationStep::class.java)) {
            return PhoneVerificationStep(application, api, onboardingData) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
