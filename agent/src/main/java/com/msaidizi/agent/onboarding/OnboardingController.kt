package com.msaidizi.agent.onboarding

import com.msaidizi.core.model.BusinessType
import com.msaidizi.core.model.Language
import com.msaidizi.core.model.UserProfileEntity
import com.msaidizi.core.database.UserProfileDao
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OnboardingController — Drives the "Meet Your CFO" conversation.
 *
 * Implements the full onboarding flow from ONBOARDING-SPEC.md:
 * 1. Introduction (voice-first, in worker's language)
 * 2. Personal Connection (names, personality)
 * 3. Business Discovery (type, operations)
 * 4. Operations Deep Dive (contextual per worker type)
 * 5. Financial Situation
 * 6. CFO Assessment Summary
 * 7. First Task (immediate value)
 *
 * The controller is a state machine that:
 * - Generates Msaidizi's messages (bilingual: Swahili + English)
 * - Processes worker responses (voice or tap)
 * - Advances through phases
 * - Collects structured data for the worker profile
 */
@Singleton
class OnboardingController @Inject constructor(
    private val userProfileDao: UserProfileDao,
    private val gson: Gson
) {
    private val _state = MutableStateFlow(OnboardingData())
    val state: StateFlow<OnboardingData> = _state.asStateFlow()

    private val _currentMessage = MutableStateFlow<OnboardingMessage?>(null)
    val currentMessage: StateFlow<OnboardingMessage?> = _currentMessage.asStateFlow()

    /**
     * Start the onboarding conversation.
     */
    fun startOnboarding() {
        Timber.d("OnboardingController: starting 'Meet Your CFO' experience")
        _state.value = OnboardingData()
        advanceToPhase(OnboardingPhase.INTRODUCTION)
    }

    /**
     * Resume onboarding from a saved state (e.g., app killed mid-onboarding).
     */
    fun resumeOnboarding(savedData: OnboardingData) {
        _state.value = savedData
        generateMessageForPhase(savedData.currentPhase)
    }

    /**
     * Process a text/voice response from the worker.
     */
    fun processResponse(response: String) {
        val current = _state.value
        val phase = current.currentPhase

        Timber.d("OnboardingController: processing response in phase %s: %s", phase, response)

        when (phase) {
            OnboardingPhase.INTRODUCTION -> {
                // Worker acknowledged introduction, move to personal connection
                advanceToPhase(OnboardingPhase.PERSONAL_CONNECTION)
            }
            OnboardingPhase.PERSONAL_CONNECTION -> {
                handlePersonalConnection(response)
            }
            OnboardingPhase.ARCHETYPE_SELECTION -> {
                handleArchetypeSelection(response)
            }
            OnboardingPhase.SUBTYPE_REFINEMENT -> {
                handleSubTypeRefinement(response)
            }
            OnboardingPhase.MULTI_ARCHETYPE -> {
                handleMultiArchetype(response)
            }
            OnboardingPhase.BUSINESS_DISCOVERY -> {
                handleBusinessDiscovery(response)
            }
            OnboardingPhase.OPERATIONS_DEEP_DIVE -> {
                handleOperationsDeepDive(response)
            }
            OnboardingPhase.FINANCIAL_SITUATION -> {
                handleFinancialSituation(response)
            }
            OnboardingPhase.CFO_ASSESSMENT -> {
                // Worker acknowledged assessment, move to first task
                advanceToPhase(OnboardingPhase.FIRST_TASK)
            }
            OnboardingPhase.FIRST_TASK -> {
                handleFirstTask(response)
            }
            OnboardingPhase.COMPLETED -> {
                // Already done
            }
        }
    }

    /**
     * Process a choice selection (visual card tap).
     */
    fun processChoice(choiceId: String) {
        val current = _state.value
        val phase = current.currentPhase

        when (phase) {
            OnboardingPhase.ARCHETYPE_SELECTION -> {
                // Archetype selected from 12 cards
                val archetype = com.msaidizi.core.model.ArchetypeType.entries.find { it.name == choiceId }
                if (archetype != null) {
                    _state.value = current.copy(
                        business = current.business.copy(archetype = archetype)
                    )
                    Timber.d("OnboardingController: archetype selected: %s", archetype)
                    advanceToPhase(OnboardingPhase.SUBTYPE_REFINEMENT)
                }
            }
            OnboardingPhase.SUBTYPE_REFINEMENT -> {
                // Sub-type selected within archetype
                val businessType = BusinessType.entries.find { it.name == choiceId }
                if (businessType != null) {
                    _state.value = current.copy(
                        business = current.business.copy(type = businessType)
                    )
                    Timber.d("OnboardingController: sub-type selected: %s", businessType)
                    advanceToPhase(OnboardingPhase.MULTI_ARCHETYPE)
                }
            }
            OnboardingPhase.MULTI_ARCHETYPE -> {
                // "Do you have other businesses?"
                when (choiceId) {
                    "yes" -> {
                        // Go back to archetype selection for secondary
                        advanceToPhase(OnboardingPhase.ARCHETYPE_SELECTION)
                    }
                    "no" -> {
                        advanceToPhase(OnboardingPhase.OPERATIONS_DEEP_DIVE)
                    }
                }
            }
            OnboardingPhase.BUSINESS_DISCOVERY -> {
                // Legacy business type selected
                val businessType = BusinessType.entries.find { it.name == choiceId }
                if (businessType != null) {
                    _state.value = current.copy(
                        business = current.business.copy(type = businessType)
                    )
                    Timber.d("OnboardingController: business type selected: %s", businessType)
                    advanceToPhase(OnboardingPhase.OPERATIONS_DEEP_DIVE)
                }
            }
            OnboardingPhase.PERSONAL_CONNECTION -> {
                // Msaidizi name selected
                _state.value = current.copy(
                    worker = current.worker.copy(msaidiziName = choiceId)
                )
                // Move to archetype selection
                advanceToPhase(OnboardingPhase.ARCHETYPE_SELECTION)
            }
            else -> {
                Timber.d("OnboardingController: choice in unexpected phase: %s", phase)
            }
        }
    }

    /**
     * Complete onboarding and persist the profile.
     */
    suspend fun completeOnboarding() {
        val data = _state.value
        Timber.d("OnboardingController: completing onboarding for %s (%s)",
            data.worker.name, data.business.type)

        // Persist to Room
        val profile = UserProfileEntity(
            id = 1,
            msaidiziName = data.worker.msaidiziName,
            userName = data.worker.name,
            businessProfile = gson.toJson(
                com.msaidizi.core.model.BusinessProfile(
                    businessType = data.business.type ?: BusinessType.OTHER,
                    location = data.business.location,
                    language = data.worker.language
                )
            ),
            isOnboarded = true,
            voiceEnabled = data.preferences.voiceEnabled,
            preferredLanguage = data.worker.language.code,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        userProfileDao.insert(profile)

        // Store full onboarding data in knowledge base for progressive profiling
        // (Flywheel will use this for Loop 1: Vocabulary learning)
        advanceToPhase(OnboardingPhase.COMPLETED)
    }

    /**
     * Get the worker type detected during onboarding.
     */
    fun getDetectedWorkerType(): BusinessType? = _state.value.business.type

    // ═══════════════════════════════════════════════════════════
    //  PRIVATE: Phase Handlers
    // ═══════════════════════════════════════════════════════════

    private fun advanceToPhase(phase: OnboardingPhase) {
        _state.value = _state.value.copy(currentPhase = phase)
        generateMessageForPhase(phase)
    }

    private fun generateMessageForPhase(phase: OnboardingPhase) {
        val message = when (phase) {
            OnboardingPhase.INTRODUCTION -> OnboardingMessage(
                role = MessageRole.MSAIDIZI,
                text = "Hello! I'm Msaidizi — your CFO. I'll help you understand your business, earn more profit, and grow. Let's get to know each other!",
                swahiliText = "Habari! Mimi ni Msaidizi. Mimi ni CFO wako — nitakusaidia kuelewa biashara yako, kupata faida zaidi, na kukua. Hebu tujueane!",
                expectsVoice = true,
                phase = phase
            )
            OnboardingPhase.PERSONAL_CONNECTION -> personalConnectionMessage()
            OnboardingPhase.ARCHETYPE_SELECTION -> archetypeSelectionMessage()
            OnboardingPhase.SUBTYPE_REFINEMENT -> subTypeRefinementMessage()
            OnboardingPhase.MULTI_ARCHETYPE -> multiArchetypeMessage()
            OnboardingPhase.BUSINESS_DISCOVERY -> businessDiscoveryMessage()
            OnboardingPhase.OPERATIONS_DEEP_DIVE -> operationsDeepDiveMessage()
            OnboardingPhase.FINANCIAL_SITUATION -> financialSituationMessage()
            OnboardingPhase.CFO_ASSESSMENT -> cfoAssessmentMessage()
            OnboardingPhase.FIRST_TASK -> firstTaskMessage()
            OnboardingPhase.COMPLETED -> OnboardingMessage(
                role = MessageRole.MSAIDIZI,
                text = "Great! We're all set. I'm ready to help you run your business better. Just talk to me anytime!",
                swahiliText = "Sawa! Tuko tayari. Niko hapa kukusaidia kuboresha biashara yako. Zungumza tu nami wakati wowote!",
                phase = phase
            )
        }
        _currentMessage.value = message
        _state.value.conversationHistory.add(message)
    }

    private fun personalConnectionMessage(): OnboardingMessage {
        val name = _state.value.worker.name
        return if (name.isBlank()) {
            // First ask worker's name
            OnboardingMessage(
                role = MessageRole.MSAIDIZI,
                text = "What's your name?",
                swahiliText = "Unaitwa nani?",
                expectsVoice = true,
                phase = OnboardingPhase.PERSONAL_CONNECTION
            )
        } else {
            // Ask what to call Msaidizi
            OnboardingMessage(
                role = MessageRole.MSAIDIZI,
                text = "Nice to meet you, $name! What would you like to call me?",
                swahiliText = "Nafurahi kukuona, $name! Ungependa uniite?",
                expectsChoice = true,
                choices = listOf(
                    OnboardingChoice("Msaidizi", "Msaidizi", "Msaidizi", "🤝"),
                    OnboardingChoice("Rafiki", "Rafiki (Friend)", "Rafiki", "🤝"),
                    OnboardingChoice("Mshauri", "Mshauri (Advisor)", "Mshauri", "💡"),
                    OnboardingChoice("Boss", "Boss", "Boss", "👔"),
                    OnboardingChoice("custom", "Choose my own name", "Chagua jina langu", "✏️")
                ),
                phase = OnboardingPhase.PERSONAL_CONNECTION
            )
        }
    }

    private fun archetypeSelectionMessage(): OnboardingMessage {
        val choices = com.msaidizi.core.model.ArchetypeType.entries.map { archetype ->
            OnboardingChoice(
                id = archetype.name,
                label = archetype.displayName,
                swahiliLabel = archetype.swahiliName,
                icon = archetype.icon
            )
        }
        return OnboardingMessage(
            role = MessageRole.MSAIDIZI,
            text = "What kind of business do you do? Tap to select:",
            swahiliText = "Biashara yako ni ya aina gani? Bonyeza kuchagua:",
            expectsChoice = true,
            choices = choices,
            phase = OnboardingPhase.ARCHETYPE_SELECTION
        )
    }

    private fun subTypeRefinementMessage(): OnboardingMessage {
        val archetype = _state.value.business.archetype
            ?: return businessDiscoveryMessage()
        val choices = ArchetypeRegistry.getSubTypeChoices(archetype).map { st ->
            OnboardingChoice(
                id = st.id,
                label = st.label,
                swahiliLabel = st.swahiliLabel,
                icon = st.icon
            )
        }
        return OnboardingMessage(
            role = MessageRole.MSAIDIZI,
            text = "What specifically? Tap to select:",
            swahiliText = "Ni ya aina gani hasa? Bonyeza kuchagua:",
            expectsChoice = true,
            choices = choices,
            phase = OnboardingPhase.SUBTYPE_REFINEMENT
        )
    }

    private fun multiArchetypeMessage(): OnboardingMessage {
        return OnboardingMessage(
            role = MessageRole.MSAIDIZI,
            text = "Do you have any other businesses or income sources?",
            swahiliText = "Una biashara nyingine pia?",
            expectsChoice = true,
            choices = listOf(
                OnboardingChoice("yes", "Yes, I have another business", "Ndiyo, nina biashara nyingine", "✅"),
                OnboardingChoice("no", "No, just this one", "Hapana, hii pekee", "❌")
            ),
            phase = OnboardingPhase.MULTI_ARCHETYPE
        )
    }

    private fun businessDiscoveryMessage(): OnboardingMessage {
        return OnboardingMessage(
            role = MessageRole.MSAIDIZI,
            text = "What kind of business do you run? Tap to select:",
            swahiliText = "Biashara yako ni ipi? Bonyeza kuchagua:",
            expectsChoice = true,
            choices = businessTypeChoices(),
            phase = OnboardingPhase.BUSINESS_DISCOVERY
        )
    }

    private fun businessTypeChoices(): List<OnboardingChoice> {
        return listOf(
            OnboardingChoice("MAMA_MBOGA", "Vegetable Vendor", "Mama Mboga", "🥬"),
            OnboardingChoice("BODA_BODA", "Motorcycle Taxi", "Boda Boda", "🏍️"),
            OnboardingChoice("JUA_KALI", "Artisan/Mechanic", "Jua Kali", "🔧"),
            OnboardingChoice("MKULIMA", "Farmer", "Mkulima", "🌾"),
            OnboardingChoice("MAMA_LISHE", "Food Vendor", "Mama Lishe", "🍲"),
            OnboardingChoice("DUKA", "Shop Owner", "Dukawallah", "🏪"),
            OnboardingChoice("FUNDI", "Repair Technician", "Fundi", "🔩"),
            OnboardingChoice("SALON", "Salon Owner", "Mwenye Salon", "💇"),
            OnboardingChoice("M_PESA", "M-Pesa Agent", "M-Pesa", "📱"),
            OnboardingChoice("MJENGO", "Construction Worker", "Mjengo", "🧱"),
            OnboardingChoice("MVUVI", "Fisherman", "Mvuvi", "🐟"),
            OnboardingChoice("OTHER", "Other", "Nyingine", "📋")
        )
    }

    private fun operationsDeepDiveMessage(): OnboardingMessage {
        // Use archetype-based questions from ArchetypeRegistry
        val archetype = _state.value.business.archetype
        if (archetype != null) {
            val questions = ArchetypeRegistry.getOnboardingQuestions(archetype)
            if (questions.isNotEmpty()) {
                val firstQuestion = questions.first()
                return OnboardingMessage(
                    role = MessageRole.MSAIDIZI,
                    text = firstQuestion.english,
                    swahiliText = firstQuestion.swahili,
                    expectsChoice = firstQuestion.choices.isNotEmpty(),
                    choices = firstQuestion.choices.mapIndexed { idx, choice ->
                        OnboardingChoice("q0_$idx", choice, choice, null)
                    },
                    phase = OnboardingPhase.OPERATIONS_DEEP_DIVE
                )
            }
        }
        // Fallback to legacy business type questions
        val businessType = _state.value.business.type ?: BusinessType.OTHER
        return when (businessType) {
            BusinessType.MAMA_MBOGA -> OnboardingMessage(
                role = MessageRole.MSAIDIZI,
                text = "How much do you sell in a typical day? (Just estimate — KES)",
                swahiliText = "Unauza pesa ngapi kwa siku ya kawaida? (Takriban — KES)",
                expectsVoice = true,
                phase = OnboardingPhase.OPERATIONS_DEEP_DIVE
            )
            BusinessType.BODA_BODA -> OnboardingMessage(
                role = MessageRole.MSAIDIZI,
                text = "How many hours do you work per day?",
                swahiliText = "Unafanya kazi masaa ngapi kwa siku?",
                expectsVoice = true,
                phase = OnboardingPhase.OPERATIONS_DEEP_DIVE
            )
            else -> OnboardingMessage(
                role = MessageRole.MSAIDIZI,
                text = "Tell me a bit about how your business works day-to-day.",
                swahiliText = "Niambie kidogo kuhusu biashara yako inavyofanya kazi kila siku.",
                expectsVoice = true,
                phase = OnboardingPhase.OPERATIONS_DEEP_DIVE
            )
        }
    }

    private fun financialSituationMessage(): OnboardingMessage {
        return OnboardingMessage(
            role = MessageRole.MSAIDIZI,
            text = "Do you use M-Pesa for your business?",
            swahiliText = "Unatumia M-Pesa katika biashara yako?",
            expectsChoice = true,
            choices = listOf(
                OnboardingChoice("yes_mpesa", "Yes, M-Pesa", "Ndiyo, M-Pesa", "📱"),
                OnboardingChoice("yes_cash", "Mostly cash", "Pesa taslimu zaidi", "💵"),
                OnboardingChoice("yes_both", "Both M-Pesa and cash", "M-Pesa na pesa taslimu", "📱💵")
            ),
            phase = OnboardingPhase.FINANCIAL_SITUATION
        )
    }

    private fun cfoAssessmentMessage(): OnboardingMessage {
        val data = _state.value
        val businessType = data.business.type?.swahiliName ?: "biashara"
        val name = data.worker.name.ifBlank { "Rafiki" }

        val summary = buildString {
            append("Sawa $name! I understand your $businessType business. Here's how I'll help you:\n\n")
            append("• I'll help you know your real profit every day\n")
            append("• I'll remind you to buy stock when prices are good\n")
            append("• I'll help you save without feeling the pressure\n")
            append("• I'll help you borrow wisely if you need to\n\n")
            append("Let's start!")
        }
        val swahiliSummary = buildString {
            append("Sawa $name! Nimeelewa biashara yako ya $businessType. Hapa kuna vile nitakusaidia:\n\n")
            append("• Nitakusaidia kujua faida yako halisi kila siku\n")
            append("• Nitakukumbusha kununua stock wakati bei ni nzuri\n")
            append("• Nitakusaidia kuweka akiba bila kujihisi\n")
            append("• Nitakusaidia kukopa vizuri ukahitaji\n\n")
            append("Hebu tuanze!")
        }

        return OnboardingMessage(
            role = MessageRole.MSAIDIZI,
            text = summary,
            swahiliText = swahiliSummary,
            expectsVoice = false,
            phase = OnboardingPhase.CFO_ASSESSMENT
        )
    }

    private fun firstTaskMessage(): OnboardingMessage {
        return OnboardingMessage(
            role = MessageRole.MSAIDIZI,
            text = "How much did you earn today? Let's record your first sale!",
            swahiliText = "Leo umepata pesa ngapi? Hebu tuandike mauzo yako ya kwanza!",
            expectsVoice = true,
            phase = OnboardingPhase.FIRST_TASK
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  PRIVATE: Response Handlers
    // ═══════════════════════════════════════════════════════════

    private fun handlePersonalConnection(response: String) {
        val current = _state.value
        if (current.worker.name.isBlank()) {
            // This is the worker's name
            _state.value = current.copy(
                worker = current.worker.copy(name = response.trim())
            )
            generateMessageForPhase(OnboardingPhase.PERSONAL_CONNECTION)
        } else {
            // Msaidizi name or custom name
            _state.value = current.copy(
                worker = current.worker.copy(msaidiziName = response.trim())
            )
            advanceToPhase(OnboardingPhase.ARCHETYPE_SELECTION)
        }
    }

    private fun handleArchetypeSelection(response: String) {
        // Voice response — try to match to an archetype
        val archetype = matchArchetype(response)
        if (archetype != null) {
            _state.value = _state.value.copy(
                business = _state.value.business.copy(archetype = archetype)
            )
            advanceToPhase(OnboardingPhase.SUBTYPE_REFINEMENT)
        } else {
            generateMessageForPhase(OnboardingPhase.ARCHETYPE_SELECTION)
        }
    }

    private fun handleSubTypeRefinement(response: String) {
        val matchedType = matchBusinessType(response)
        if (matchedType != null) {
            _state.value = _state.value.copy(
                business = _state.value.business.copy(type = matchedType)
            )
            advanceToPhase(OnboardingPhase.MULTI_ARCHETYPE)
        } else {
            generateMessageForPhase(OnboardingPhase.SUBTYPE_REFINEMENT)
        }
    }

    private fun handleMultiArchetype(response: String) {
        val lower = response.lowercase()
        when {
            lower.contains("yes") || lower.contains("ndiyo") || lower.contains("nina") -> {
                // Save current archetype as primary, go back for secondary
                advanceToPhase(OnboardingPhase.ARCHETYPE_SELECTION)
            }
            else -> {
                advanceToPhase(OnboardingPhase.OPERATIONS_DEEP_DIVE)
            }
        }
    }

    private fun handleBusinessDiscovery(response: String) {
        // Voice response — try to match to a business type
        val matchedType = matchBusinessType(response)
        if (matchedType != null) {
            _state.value = _state.value.copy(
                business = _state.value.business.copy(type = matchedType)
            )
            advanceToPhase(OnboardingPhase.OPERATIONS_DEEP_DIVE)
        } else {
            // Ask again with visual cards
            generateMessageForPhase(OnboardingPhase.BUSINESS_DISCOVERY)
        }
    }

    private fun handleOperationsDeepDive(response: String) {
        val current = _state.value
        // Store the response and move to financial situation
        _state.value = current.copy(
            business = current.business.copy(description = response.trim())
        )
        advanceToPhase(OnboardingPhase.FINANCIAL_SITUATION)
    }

    private fun handleFinancialSituation(response: String) {
        val current = _state.value
        val usesMpesa = response.contains("mpesa", ignoreCase = true) ||
                response.contains("M-Pesa", ignoreCase = true)
        _state.value = current.copy(
            operations = current.operations.copy(usesMpesa = usesMpesa),
            financial = current.financial.copy(hasMpesa = usesMpesa)
        )
        advanceToPhase(OnboardingPhase.CFO_ASSESSMENT)
    }

    private fun handleFirstTask(response: String) {
        // Parse amount from voice/text response
        val amount = extractAmount(response)
        if (amount != null) {
            _state.value = _state.value.copy(
                operations = _state.value.operations.copy(dailyRevenueEstimate = amount)
            )
        }
        advanceToPhase(OnboardingPhase.COMPLETED)
    }

    // ═══════════════════════════════════════════════════════════
    //  PRIVATE: Helpers
    // ═══════════════════════════════════════════════════════════

    /**
     * Match a voice/text response to an ArchetypeType.
     * Uses keyword matching for common Kenyan business terms.
     */
    private fun matchArchetype(input: String): com.msaidizi.core.model.ArchetypeType? {
        val lower = input.lowercase()
        return when {
            lower.contains("mboga") || lower.contains("vendor") || lower.contains("duka") || lower.contains("machinga") || lower.contains("mitumba") -> com.msaidizi.core.model.ArchetypeType.VENDOR
            lower.contains("lishe") || lower.contains("food") || lower.contains("hotel") || lower.contains("chapati") || lower.contains("chips") -> com.msaidizi.core.model.ArchetypeType.FOOD_SERVICE
            lower.contains("fundi") || lower.contains("artisan") || lower.contains("jua kali") || lower.contains("welder") || lower.contains("carpenter") || lower.contains("tailor") -> com.msaidizi.core.model.ArchetypeType.ARTISAN
            lower.contains("salon") || lower.contains("barber") || lower.contains("hair") || lower.contains("mechanic") || lower.contains("plumber") || lower.contains("repair") -> com.msaidizi.core.model.ArchetypeType.SERVICE_PROVIDER
            lower.contains("boda") || lower.contains("pikipiki") || lower.contains("matatu") || lower.contains("tuk tuk") || lower.contains("taxi") || lower.contains("transport") -> com.msaidizi.core.model.ArchetypeType.TRANSPORT_OPERATOR
            lower.contains("kulima") || lower.contains("farmer") || lower.contains("farming") || lower.contains("crop") || lower.contains("shamba") -> com.msaidizi.core.model.ArchetypeType.CROP_FARMER
            lower.contains("fugaji") || lower.contains("livestock") || lower.contains("cow") || lower.contains("chicken") || lower.contains("goat") || lower.contains("ng'ombe") -> com.msaidizi.core.model.ArchetypeType.LIVESTOCK_KEEPER
            lower.contains("mvuvi") || lower.contains("fish") || lower.contains("samaki") || lower.contains("fishing") -> com.msaidizi.core.model.ArchetypeType.FISHER
            lower.contains("mpesa") || lower.contains("agent") || lower.contains("broker") || lower.contains("dalali") || lower.contains("forex") -> com.msaidizi.core.model.ArchetypeType.AGENT_BROKER
            lower.contains("digital") || lower.contains("online") || lower.contains("mtandaoni") || lower.contains("cyber") || lower.contains("design") -> com.msaidizi.core.model.ArchetypeType.DIGITAL_WORKER
            lower.contains("mjen") || lower.contains("construct") || lower.contains("mjengo") || lower.contains("labor") || lower.contains("kibarua") -> com.msaidizi.core.model.ArchetypeType.CASUAL_LABORER
            lower.contains("event") || lower.contains("dj") || lower.contains("music") || lower.contains("photo") || lower.contains("security") || lower.contains("guard") -> com.msaidizi.core.model.ArchetypeType.COMMUNITY_CARE_WORKER
            else -> null
        }
    }

    /**
     * Match a voice/text response to a BusinessType.
     * Uses keyword matching for common Kenyan business terms.
     */
    private fun matchBusinessType(input: String): BusinessType? {
        val lower = input.lowercase()
        return when {
            lower.contains("mboga") || lower.contains("vegetable") -> BusinessType.MAMA_MBOGA
            lower.contains("boda") || lower.contains("pikipiki") || lower.contains("motorcycle") -> BusinessType.BODA_BODA
            lower.contains("jua kali") || lower.contains("artisan") || lower.contains("mechanic") -> BusinessType.JUA_KALI
            lower.contains("kulima") || lower.contains("farmer") || lower.contains("farming") -> BusinessType.MKULIMA
            lower.contains("lishe") || lower.contains("food") || lower.contains("hotel") -> BusinessType.MAMA_LISHE
            lower.contains("duka") || lower.contains("shop") -> BusinessType.DUKA
            lower.contains("fundi") || lower.contains("repair") -> BusinessType.FUNDI
            lower.contains("salon") || lower.contains("hair") -> BusinessType.SALON
            lower.contains("mpesa") || lower.contains("m-pesa") -> BusinessType.M_PESA
            lower.contains("mjen") || lower.contains("construct") -> BusinessType.MJENGO
            lower.contains("samaki") || lower.contains("fish") || lower.contains("mvuvi") -> BusinessType.MVUVI
            else -> null
        }
    }

    /**
     * Extract a numeric amount from text like "500", "KES 500", "mia tano" (500 in Swahili).
     */
    private fun extractAmount(input: String): Double? {
        // Try to find a number in the text
        val numberRegex = Regex("[0-9]+(?:\\.[0-9]+)?")
        val match = numberRegex.find(input.replace(",", ""))
        return match?.value?.toDoubleOrNull()
    }
}
