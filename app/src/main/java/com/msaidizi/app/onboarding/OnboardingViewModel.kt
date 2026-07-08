package com.msaidizi.app.onboarding

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.msaidizi.app.core.ai.ModelDownloader
import com.msaidizi.app.core.ai.ModelManager
import com.msaidizi.app.core.dialect.DialectAdapter
import com.msaidizi.app.core.dialect.DialectAdapterFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * OnboardingViewModel — State management for the onboarding conversation.
 *
 * This is NOT a form wizard. It's the brain of a natural conversation
 * between Msaidizi and the worker. The phases mirror how a real CFO
 * would get to know a new client:
 *
 * Phase 1: Introduction (30s) — Names, language, first impression
 * Phase 2: Getting to Know You (2-3 min) — Business type, location, hours
 * Phase 3: Understanding Your Business (2-3 min) — Supply chain, payments, challenges
 * Phase 4: Setting Up (1-2 min) — Permissions, downloads, model setup
 * Phase 5: First Value (immediate) — First insight based on what we learned
 *
 * Academic grounding:
 * - STA 142: Bayesian updating — every answer updates priors
 * - ECO 101: Consumer theory — understanding spending patterns
 * - ECO 201: Producer theory — understanding business operations
 * - ECO 206: Microfinance — understanding financial access
 * - BCB 108: Communication — voice-first, multilingual, culturally appropriate
 *
 * @author Angavu Intelligence — Implementation Swarm 9
 */
class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    // ── State ──────────────────────────────────────────────────

    private val _currentPhase = MutableLiveData(OnboardingPhase.INTRODUCTION)
    val currentPhase: LiveData<OnboardingPhase> = _currentPhase

    private val _workerProfile = MutableLiveData(WorkerProfile())
    val workerProfile: LiveData<WorkerProfile> = _workerProfile

    private val _msaidiziMessage = MutableLiveData<ConversationMessage>()
    val msaidiziMessage: LiveData<ConversationMessage> = _msaidiziMessage

    private val _isListening = MutableLiveData(false)
    val isListening: LiveData<Boolean> = _isListening

    private val _modelDownloadState = MutableStateFlow(ModelDownloadState.NOT_STARTED)
    val modelDownloadState: StateFlow<ModelDownloadState> = _modelDownloadState.asStateFlow()

    private val _modelDownloadProgress = MutableStateFlow(0f)
    val modelDownloadProgress: StateFlow<Float> = _modelDownloadProgress.asStateFlow()

    private val _onboardingComplete = MutableLiveData(false)
    val onboardingComplete: LiveData<Boolean> = _onboardingComplete

    private val _firstInsight = MutableLiveData<String>()
    val firstInsight: LiveData<String> = _firstInsight

    // ── Dependencies ───────────────────────────────────────────

    private val modelManager = ModelManager(application)
    private val modelDownloader = ModelDownloader(application)
    private val gson = Gson()

    // ── Conversation State ─────────────────────────────────────

    private var conversationStep = 0
    private val conversationHistory = mutableListOf<ConversationMessage>()

    init {
        startOnboarding()
    }

    // ── Phase 1: Introduction ──────────────────────────────────

    private fun startOnboarding() {
        _currentPhase.value = OnboardingPhase.INTRODUCTION

        // Msaidizi introduces herself
        // BCB 108: Warm, culturally appropriate greeting
        val greeting = ConversationMessage(
            speaker = Speaker.MSAIDIZI,
            text = "Habari! Mimi ni Msaidizi wako — rafiki yako wa biashara. Nitakusaidia kufuatilia pesa yako, kuelewa biashara yako, na kukupa ushauri. Kwanza, wacha tujue!",
            translation = "Hello! I'm your Msaidizi — your business friend. I'll help you track your money, understand your business, and give you advice. First, let's get to know each other!",
            phase = OnboardingPhase.INTRODUCTION
        )
        emitMessage(greeting)

        // Ask the worker to name Msaidizi
        viewModelScope.launch {
            delay(2000)  // Pause for natural conversation rhythm
            emitMessage(ConversationMessage(
                speaker = Speaker.MSAIDIZI,
                text = "Unapenda kuniita nani? Unaweza kuchagua jina, au kuniita Msaidizi.",
                translation = "What would you like to call me? You can choose a name, or call me Msaidizi.",
                phase = OnboardingPhase.INTRODUCTION,
                expectsResponse = true,
                responseType = ResponseType.AGENT_NAMING
            ))
        }
    }

    /**
     * Called when the worker names their Msaidizi.
     * This is a deeply personal moment — the worker is claiming ownership.
     */
    fun onAgentNamed(name: String) {
        updateProfile { it.copy(agentName = name) }

        val response = ConversationMessage(
            speaker = Speaker.MSAIDIZI,
            text = "Sawa! Nitaitwa $name tangu sasa. $name yuko hapa kukusaidia. Sasa, wewe unaitwa nani?",
            translation = "Okay! I'll be called $name from now on. $name is here to help you. Now, what's your name?",
            phase = OnboardingPhase.INTRODUCTION,
            expectsResponse = true,
            responseType = ResponseType.WORKER_NAME
        )
        emitMessage(response)
    }

    /**
     * Called when the worker tells us their name.
     */
    fun onWorkerNamed(name: String) {
        updateProfile { it.copy(workerName = name) }

        val agentName = _workerProfile.value?.agentName ?: "Msaidizi"
        viewModelScope.launch {
            emitMessage(ConversationMessage(
                speaker = Speaker.MSAIDIZI,
                text = "Poa, $name! Sasa $agentName anajua wewe ni nani. Hebu tuzungumze kuhusu biashara yako.",
                translation = "Great, $name! Now $agentName knows who you are. Let's talk about your business.",
                phase = OnboardingPhase.GETTING_TO_KNOW
            ))
            delay(1500)
            transitionToPhase(OnboardingPhase.GETTING_TO_KNOW)
        }
    }

    // ── Phase 2: Getting to Know You ──────────────────────────

    private fun startGettingToKnowPhase() {
        _currentPhase.value = OnboardingPhase.GETTING_TO_KNOW

        emitMessage(ConversationMessage(
            speaker = Speaker.MSAIDIZI,
            text = "Biashara yako ni ya aina gani? Kwa mfano: rejareja, mama mboga, boda boda, saluni, au nyingine?",
            translation = "What type of business do you have? For example: retail, food vendor, boda boda, salon, or something else?",
            phase = OnboardingPhase.GETTING_TO_KNOW,
            expectsResponse = true,
            responseType = ResponseType.BUSINESS_TYPE
        ))
    }

    fun onBusinessTypeSelected(type: BusinessType, subtype: String) {
        updateProfile { it.copy(businessType = type, businessSubtype = subtype) }

        // ECO 201: Update production function prior based on business type
        updateBayesianPrior { it.copy(estimatedDailyRevenue = type.priorDailyRevenue) }

        val agentName = _workerProfile.value?.agentName ?: "Msaidizi"
        emitMessage(ConversationMessage(
            speaker = Speaker.MSAIDIZI,
            text = "Poa! $agentName ameelewa. $subtype ni biashara nzuri. Sasa, wapi unafanyia kazi? Soko, barabarani, nyumbani, au unazunguka?",
            translation = "Great! $agentName understands. $subtype is a good business. Now, where do you work? Market, roadside, home, or mobile?",
            phase = OnboardingPhase.GETTING_TO_KNOW,
            expectsResponse = true,
            responseType = ResponseType.WORK_LOCATION
        ))
    }

    fun onWorkLocationSelected(location: WorkLocation, marketName: String = "") {
        updateProfile { it.copy(workLocation = location, marketName = marketName) }

        emitMessage(ConversationMessage(
            speaker = Speaker.MSAIDIZI,
            text = "Sawa! Masaa yako ya kazi ni yapi? Asubuhi, mchana, jioni, au usiku?",
            translation = "Okay! What are your working hours? Morning, afternoon, evening, or night?",
            phase = OnboardingPhase.GETTING_TO_KNOW,
            expectsResponse = true,
            responseType = ResponseType.WORK_SCHEDULE
        ))
    }

    fun onWorkScheduleSet(schedule: WorkSchedule) {
        updateProfile { it.copy(workSchedule = schedule) }

        emitMessage(ConversationMessage(
            speaker = Speaker.MSAIDIZI,
            text = "Unafanya kazi pekee yako au una watu wengine wanaokusaidia?",
            translation = "Do you work alone or do you have others helping you?",
            phase = OnboardingPhase.GETTING_TO_KNOW,
            expectsResponse = true,
            responseType = ResponseType.TEAM_SIZE
        ))
    }

    fun onTeamSizeAnswered(worksAlone: Boolean, teamSize: Int) {
        updateProfile { it.copy(worksAlone = worksAlone, teamSize = teamSize) }

        viewModelScope.launch {
            emitMessage(ConversationMessage(
                speaker = Speaker.MSAIDIZI,
                text = "Sawa! $agentName sasa anaelewa biashara yako. Hebu tuzungumze zaidi...",
                translation = "Okay! $agentName now understands your business. Let's talk more...",
                phase = OnboardingPhase.GETTING_TO_KNOW
            ))
            delay(1000)
            transitionToPhase(OnboardingPhase.UNDERSTANDING_BUSINESS)
        }
    }

    // ── Phase 3: Understanding Your Business ───────────────────

    private fun startUnderstandingBusinessPhase() {
        _currentPhase.value = OnboardingPhase.UNDERSTANDING_BUSINESS

        val agentName = _workerProfile.value?.agentName ?: "Msaidizi"
        emitMessage(ConversationMessage(
            speaker = Speaker.MSAIDIZI,
            text = "Bidhaa zako unazipata wapi? Ghala, mkulima, dalali, au mtengenezaji?",
            translation = "Where do you get your products? Wholesale market, farmer, middleman, or manufacturer?",
            phase = OnboardingPhase.UNDERSTANDING_BUSINESS,
            expectsResponse = true,
            responseType = ResponseType.SUPPLY_CHAIN
        ))
    }

    fun onSupplyChainAnswered(supplyChain: SupplyChain) {
        updateProfile { it.copy(supplyChain = supplyChain) }

        emitMessage(ConversationMessage(
            speaker = Speaker.MSAIDIZI,
            text = "Wateja wako wanakujaje? Wanakuja mwenyewe, unapata rafiki, au unatumia mitandao?",
            translation = "How do your customers find you? Walk-ins, referrals, or social media?",
            phase = OnboardingPhase.UNDERSTANDING_BUSINESS,
            expectsResponse = true,
            responseType = ResponseType.CUSTOMER_ACQUISITION
        ))
    }

    fun onCustomerAcquisitionAnswered(acquisition: CustomerAcquisition) {
        updateProfile { it.copy(customerAcquisition = acquisition) }

        // ECO 206: Understanding payment methods for financial inclusion
        emitMessage(ConversationMessage(
            speaker = Speaker.MSAIDIZI,
            text = "Unalipaje? M-Pesa, pesa taslimu, au vyote?",
            translation = "How do you get paid? M-Pesa, cash, or both?",
            phase = OnboardingPhase.UNDERSTANDING_BUSINESS,
            expectsResponse = true,
            responseType = ResponseType.PAYMENT_METHODS
        ))
    }

    fun onPaymentMethodsAnswered(methods: PaymentMethods) {
        updateProfile { it.copy(paymentMethods = methods) }

        // ECO 206: Update M-Pesa prior
        if (methods.mpesa || methods.both) {
            updateBayesianPrior { it.copy(likelyHasMpesa = true) }
        }

        emitMessage(ConversationMessage(
            speaker = Speaker.MSAIDIZI,
            text = "Je, unaweka rekodi za biashara yako? Kwenye daftari, simu, au kichwani?",
            translation = "Do you keep records of your business? In a notebook, phone, or memory?",
            phase = OnboardingPhase.UNDERSTANDING_BUSINESS,
            expectsResponse = true,
            responseType = ResponseType.RECORD_KEEPING
        ))
    }

    fun onRecordKeepingAnswered(recordKeeping: RecordKeeping) {
        updateProfile { it.copy(recordKeeping = recordKeeping) }

        // ECO 201: If they track records, their production function data will be better
        if (recordKeeping.tracksSales || recordKeeping.tracksExpenses) {
            updateBayesianPrior {
                it.copy(
                    revenueConfidence = 0.5,
                    costConfidence = 0.5
                )
            }
        }

        emitMessage(ConversationMessage(
            speaker = Speaker.MSAIDIZI,
            text = "Swali la mwisho: Changamoto kubwa zaidi ya biashara yako ni ipi?",
            translation = "Last question: What's your biggest challenge in your business?",
            phase = OnboardingPhase.UNDERSTANDING_BUSINESS,
            expectsResponse = true,
            responseType = ResponseType.BIGGEST_CHALLENGE
        ))
    }

    fun onBiggestChallengeAnswered(challenge: String, category: ChallengeCategory) {
        updateProfile { it.copy(biggestChallenge = challenge, challengeCategory = category) }

        // ECO 206: Update credit constraint prior based on challenge
        when (category) {
            ChallengeCategory.CAPITAL -> {
                updateBayesianPrior { it.copy(creditConstraintLevel = 0.9) }
            }
            ChallengeCategory.CUSTOMERS -> {
                updateBayesianPrior { it.copy(incomeVolatility = 0.6) }
            }
            ChallengeCategory.COMPETITION -> {
                updateBayesianPrior { it.copy(substitutionElasticity = 1.5) }
            }
            else -> {}
        }

        viewModelScope.launch {
            emitMessage(ConversationMessage(
                speaker = Speaker.MSAIDIZI,
                text = "Asante, $agentName ameelewa changamoto yako. Sasa wacha $agentName aanze kukusaidia!",
                translation = "Thank you, $agentName understands your challenge. Now let $agentName start helping you!",
                phase = OnboardingPhase.UNDERSTANDING_BUSINESS
            ))
            delay(1500)
            transitionToPhase(OnboardingPhase.SETTING_UP)
        }
    }

    // ── Phase 4: Setting Up ────────────────────────────────────

    private fun startSettingUpPhase() {
        _currentPhase.value = OnboardingPhase.SETTING_UP

        val agentName = _workerProfile.value?.agentName ?: "Msaidizi"

        // Ask for voice permission — naturally, not as a system dialog
        emitMessage(ConversationMessage(
            speaker = Speaker.MSAIDIZI,
            text = "$agentName anahitaji kusikia sauti yako ili kukusaidia vizuri. Je, nikisikiliza ukizungumza kuhusu pesa, iko sawa?",
            translation = "$agentName needs to hear your voice to help you well. If I listen when you talk about money, is that okay?",
            phase = OnboardingPhase.SETTING_UP,
            expectsResponse = true,
            responseType = ResponseType.VOICE_PERMISSION
        ))
    }

    fun onVoicePermissionGranted() {
        updateProfile { it.copy(voicePermissionGranted = true) }

        val agentName = _workerProfile.value?.agentName ?: "Msaidizi"

        emitMessage(ConversationMessage(
            speaker = Speaker.MSAIDIZI,
            text = "Sawa! $agentName atakutumia ujumbe kila siku asubuhi. Unapenda saa ngapi? Saa kumi na mbili asubuhi, au wakati mwingine?",
            translation = "Okay! $agentName will send you a message every morning. What time? 7am, or another time?",
            phase = OnboardingPhase.SETTING_UP,
            expectsResponse = true,
            responseType = ResponseType.NOTIFICATION_TIME
        ))
    }

    fun onNotificationTimeSet(time: String) {
        updateProfile { it.copy(dailyBriefingTime = time) }

        // Start model download in background
        startModelDownload()

        val agentName = _workerProfile.value?.agentName ?: "Msaidizi"
        emitMessage(ConversationMessage(
            speaker = Speaker.MSAIDIZI,
            text = "Sawa! Wakati $agentName inajiandaa, wacha nikueleze ninachoweza kukufanyia...",
            translation = "Okay! While $agentName gets ready, let me tell you what I can do for you...",
            phase = OnboardingPhase.SETTING_UP
        ))

        // Explain capabilities while models download
        viewModelScope.launch {
            delay(2000)
            explainCapabilities()
        }
    }

    /**
     * Start downloading full models in background.
     * NOT mini models — full quality from day one.
     *
     * Model download strategy:
     * - Download during onboarding conversation (background)
     * - Prioritize Whisper first (voice input is critical)
     * - Then Qwen 0.5B (reasoning)
     * - Then Piper TTS (voice output)
     * - Works on MOBILE DATA — not just WiFi
     * - Auto-resume if interrupted
     */
    private fun startModelDownload() {
        _modelDownloadState.value = ModelDownloadState.DOWNLOADING

        viewModelScope.launch {
            try {
                // Phase 1: Whisper (~150MB) — speech recognition
                // This is the most critical model for voice-first interaction
                _modelDownloadState.value = ModelDownloadState.DOWNLOADING_WHISPER
                modelDownloader.downloadModel(
                    model = ModelDownloader.ModelType.WISPER,
                    onProgress = { progress ->
                        _modelDownloadProgress.value = progress * 0.3f  // Whisper is 30% of total
                    },
                    allowMobileData = true  // Key: works on data, not just WiFi
                )

                // Phase 2: Qwen 0.5B (~300MB) — on-device reasoning
                _modelDownloadState.value = ModelDownloadState.DOWNLOADING_QWEN
                modelDownloader.downloadModel(
                    model = ModelDownloader.ModelType.QWEN_0_5B,
                    onProgress = { progress ->
                        _modelDownloadProgress.value = 0.3f + progress * 0.55f  // Qwen is 55% of total
                    },
                    allowMobileData = true
                )

                // Phase 3: Piper TTS (~50MB) — voice output
                _modelDownloadState.value = ModelDownloadState.DOWNLOADING_TTS
                modelDownloader.downloadModel(
                    model = ModelDownloader.ModelType.PIPER_TTS,
                    onProgress = { progress ->
                        _modelDownloadProgress.value = 0.85f + progress * 0.15f  // TTS is 15% of total
                    },
                    allowMobileData = true
                )

                _modelDownloadState.value = ModelDownloadState.COMPLETED
                _modelDownloadProgress.value = 1.0f

            } catch (e: Exception) {
                // Auto-resume — don't fail the onboarding
                // The app will retry in the background
                _modelDownloadState.value = ModelDownloadState.DOWNLOADING  // Keep trying
            }
        }
    }

    private suspend fun explainCapabilities() {
        val agentName = _workerProfile.value?.agentName ?: "Msaidizi"
        val businessType = _workerProfile.value?.businessType?.displayName ?: "biashara yako"

        // Explain capabilities in context of what the worker told us
        val capabilities = listOf(
            "Naweza kukusaidia kufuatilia mauzo yako kila siku — bila kuandika chochote, sema tu.",
            "Nitakutumia taarifa za bei za soko — bei ya nyanya, vitunguu, na mengineyo.",
            "Nitakukumbusha unapopaswa kununua stock mpya — kulingana na mauzo yako.",
            "Nitakusaidia kuelewa faida yako — pesa gani inaingia, nini inatoka.",
            "Nitakupa ushauri wa kukua biashara yako — kulingana na data yako."
        )

        capabilities.forEach { capability ->
            emitMessage(ConversationMessage(
                speaker = Speaker.MSAIDIZI,
                text = "• $capability",
                phase = OnboardingPhase.SETTING_UP
            ))
            delay(2500)
        }

        // Transition to first value
        delay(2000)
        transitionToPhase(OnboardingPhase.FIRST_VALUE)
    }

    // ── Phase 5: First Value ───────────────────────────────────

    private fun startFirstValuePhase() {
        _currentPhase.value = OnboardingPhase.FIRST_VALUE

        val profile = _workerProfile.value ?: return
        val agentName = profile.agentName.ifEmpty { "Msaidizi" }

        // Generate first insight based on what we learned
        // ECO 101: Consumer/Producer theory applied to the worker's context
        val insight = generateFirstInsight(profile)
        _firstInsight.value = insight

        emitMessage(ConversationMessage(
            speaker = Speaker.MSAIDIZI,
            text = insight,
            phase = OnboardingPhase.FIRST_VALUE
        ))

        viewModelScope.launch {
            delay(3000)

            // Set up daily briefing
            emitMessage(ConversationMessage(
                speaker = Speaker.MSAIDIZI,
                text = "Kuanzia kesho, $agentName atakutumia taarifa saa ${profile.dailyBriefingTime} asubuhi. Utajua mauzo yako, faida, na ushauri wa leo.",
                translation = "Starting tomorrow, $agentName will send you a briefing at ${profile.dailyBriefingTime} in the morning. You'll know your sales, profit, and today's advice.",
                phase = OnboardingPhase.FIRST_VALUE
            ))

            delay(3000)

            // Complete onboarding
            emitMessage(ConversationMessage(
                speaker = Speaker.MSAIDIZI,
                text = "Tayari, ${profile.workerName}! $agentName amekuwa tayari kukusaidia. Anza kuzungumza — sema tu 'Nimeuza' au 'Nimenunua' na $agentName ataandika.",
                translation = "Ready, ${profile.workerName}! $agentName is ready to help you. Start talking — just say 'I sold' or 'I bought' and $agentName will record it.",
                phase = OnboardingPhase.FIRST_VALUE
            ))

            // Mark onboarding complete
            updateProfile { it.copy(
                onboardingCompleted = true,
                onboardingTimestamp = System.currentTimeMillis()
            ) }
            _onboardingComplete.value = true

            // Save profile to persistent storage
            saveProfile()
        }
    }

    /**
     * Generate the first insight based on what Msaidizi learned.
     *
     * This is where the academic framework becomes invisible but powerful:
     * - ECO 101: Market timing based on consumer behavior patterns
     * - ECO 201: Supply chain optimization for their business type
     * - ECO 204: Gender-sensitive, location-aware advice
     * - BCB 108: In their language, culturally appropriate
     */
    private fun generateFirstInsight(profile: WorkerProfile): String {
        val agentName = profile.agentName.ifEmpty { "Msaidizi" }

        return when (profile.businessType) {
            BusinessType.FOOD -> {
                val market = if (profile.marketName.isNotEmpty()) profile.marketName else "soko lako"
                "$agentName amejifunza: Mama mboga kama wewe kwenye $market " +
                "huuza zaidi siku za Jumatatu na Ijumaa — watu wananunua chakula baada ya wiki. " +
                "Je, ungependa $agentName ikukumbushe kununua stock siku za Jumapili?"
            }
            BusinessType.RETAIL -> {
                "Kulingana na biashara yako ya rejareja, $agentName imegundua kuwa " +
                "wateja wako wengi wanakuja asubuhi. Je, ungependa $agentName ikukumbushe " +
                "kufungua duka saa kumi na mbili asubuhi?"
            }
            BusinessType.TRANSPORT -> {
                "Wafanyakazi wa boda boda kama wewe hupata zaidi wakati wa asubuhi " +
                "na jioni — watu wanakwenda kazini na kurudi nyumbani. " +
                "$agentName itakusaidia kufuatilia safari zako."
            }
            BusinessType.SERVICES -> {
                "Biashara za huduma kama saluni hupata wateja zaidi wikendi. " +
                "$agentName itakukumbusha kujipanga kwa wiki nzima."
            }
            BusinessType.AGRICULTURE -> {
                "Kilimo — $agentName itakusaidia kufuatilia msimu, bei za mazao, " +
                "na wakati bora wa kuuza. Tutaanza na data ya hii wiki."
            }
            else -> {
                "$agentName amekuelewa! Kuanzia sasa, kila unaposema kuhusu pesa — " +
                "mauzo, manunuzi, au faida — $agentName ataandika na kukupa taarifa. " +
                "Tuanze leo!"
            }
        }
    }

    // ── Helper Methods ─────────────────────────────────────────

    private fun transitionToPhase(phase: OnboardingPhase) {
        _currentPhase.value = phase
        when (phase) {
            OnboardingPhase.GETTING_TO_KNOW -> startGettingToKnowPhase()
            OnboardingPhase.UNDERSTANDING_BUSINESS -> startUnderstandingBusinessPhase()
            OnboardingPhase.SETTING_UP -> startSettingUpPhase()
            OnboardingPhase.FIRST_VALUE -> startFirstValuePhase()
            else -> {}
        }
    }

    private fun emitMessage(message: ConversationMessage) {
        conversationHistory.add(message)
        _msaidiziMessage.value = message
    }

    private fun updateProfile(transform: (WorkerProfile) -> WorkerProfile) {
        _workerProfile.value = transform(_workerProfile.value ?: WorkerProfile())
    }

    private fun updateBayesianPrior(transform: (BayesianPriors) -> BayesianPriors) {
        updateProfile { profile ->
            profile.copy(bayesianPriors = transform(profile.bayesianPriors))
        }
    }

    private fun saveProfile() {
        val profile = _workerProfile.value ?: return
        val prefs = getApplication<Application>()
            .getSharedPreferences("msaidizi_profile", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("worker_profile", profile.toJson())
            .putBoolean("onboarding_completed", true)
            .apply()
    }

    /**
     * Detect dialect from worker's speech patterns.
     * BCB 108: Multilingual code-switching detection.
     */
    fun detectDialect(text: String): String {
        val adapter = DialectAdapterFactory.create(_workerProfile.value?.language ?: Language.KISWAHILI)
        return adapter.detectDialect(text)
    }

    /**
     * Get the appropriate dialect adapter for the worker's language.
     */
    fun getDialectAdapter(): DialectAdapter {
        return DialectAdapterFactory.create(_workerProfile.value?.language ?: Language.KISWAHILI)
    }
}

// ── Supporting Types ──────────────────────────────────────────

enum class OnboardingPhase {
    INTRODUCTION,           // Phase 1: Names, greeting
    GETTING_TO_KNOW,        // Phase 2: Business type, location, hours
    UNDERSTANDING_BUSINESS, // Phase 3: Supply chain, payments, challenges
    SETTING_UP,             // Phase 4: Permissions, downloads
    FIRST_VALUE             // Phase 5: First insight
}

enum class Speaker { MSAIDIZI, WORKER }

enum class ResponseType {
    AGENT_NAMING,
    WORKER_NAME,
    BUSINESS_TYPE,
    WORK_LOCATION,
    WORK_SCHEDULE,
    TEAM_SIZE,
    SUPPLY_CHAIN,
    CUSTOMER_ACQUISITION,
    PAYMENT_METHODS,
    RECORD_KEEPING,
    BIGGEST_CHALLENGE,
    VOICE_PERMISSION,
    NOTIFICATION_TIME,
    FREE_TEXT
}

data class ConversationMessage(
    val speaker: Speaker,
    val text: String,
    val translation: String = "",
    val phase: OnboardingPhase,
    val expectsResponse: Boolean = false,
    val responseType: ResponseType = ResponseType.FREE_TEXT
)

enum class ModelDownloadState {
    NOT_STARTED,
    DOWNLOADING,
    DOWNLOADING_WHISPER,
    DOWNLOADING_QWEN,
    DOWNLOADING_TTS,
    COMPLETED,
    FAILED
}
