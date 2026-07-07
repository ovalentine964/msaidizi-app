package com.msaidizi.app.onboarding

import com.msaidizi.app.agent.WorkerType
import com.msaidizi.app.agent.WorkerClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber

/**
 * Onboarding Conversation — Msaidizi's first meeting with the worker.
 *
 * This is NOT a form. It's a conversation. Msaidizi gets to know the worker
 * the way a real friend would — asking about their life, their work, their dreams.
 *
 * Valentine's mum doesn't fill out forms. She talks. Msaidizi listens.
 *
 * ## Conversation Phases
 *
 * 1. **Introduction** (~30s) — Msaidizi introduces herself, asks worker's name,
 *    worker names Msaidizi. Creates psychological ownership.
 *
 * 2. **Getting to Know You** (~2-3 min) — Business type, products, location,
 *    working hours. Natural questions, not a checklist.
 *
 * 3. **Understanding Your Business** (~2-3 min) — Supply chain, customers,
 *    payments, record-keeping, challenges. Builds the full picture.
 *
 * 4. **Setting Up** (~1-2 min) — Permissions, model download starts in background.
 *    Worker doesn't wait — conversation continues while models download.
 *
 * 5. **First Value** (immediate) — Msaidizi gives first insight based on what
 *    she learned. "Kwa biashara yako ya mboga, ukinunua asubuhi na kuuza..."
 *
 * ## Academic Foundations
 *
 * ### BCB 108 — Business Communication
 * - 7Cs: Clear, Concise, Concrete, Correct, Coherent, Complete, Courteous
 * - Active listening through follow-up questions
 * - Cultural appropriateness in question framing
 *
 * ### STA 142 — Bayesian Inference
 * - Each answer updates posterior probability of business type
 * - Prior: uniform distribution over worker types
 * - Likelihood: P(answer | business_type)
 * - Posterior: updated classification with confidence
 *
 * ### ECO 201 — Producer Theory
 * - Questions map to production function components:
 *   - Inputs (supply method)
 *   - Process (work alone, location)
 *   - Output (products, customers)
 *   - Cost structure (payment method, records)
 *
 * @see WorkerProfile for the data model this populates
 * @see AgentNamingFragment for the naming UI
 * @see BusinessDiscoveryFragment for the business discovery UI
 */
class OnboardingConversation {

    companion object {
        private const val TAG = "OnboardingConversation"
    }

    // ── Bayesian State (STA 142) ──
    // Prior: uniform distribution over worker types
    private val businessTypePriors = mutableMapOf(
        WorkerType.TRADER to 0.25,
        WorkerType.TRANSPORT to 0.15,
        WorkerType.FARMER to 0.20,
        WorkerType.SERVICE to 0.15,
        WorkerType.MANUFACTURING to 0.10,
        WorkerType.DIGITAL to 0.15
    )

    // Accumulated data from conversation
    private val accumulated = AccumulatedProfileData()

    /**
     * Start the onboarding conversation.
     * Returns a Flow of conversation steps — each step is a prompt
     * from Msaidizi that expects a worker response.
     *
     * @param workerName The worker's name (from AgentNamingFragment)
     * @param msaidiziName What the worker named Msaidizi
     * @param language Worker's preferred language
     * @return Flow of conversation steps
     */
    fun startConversation(
        workerName: String,
        msaidiziName: String,
        language: String = "sw"
    ): Flow<ConversationStep> = flow {
        accumulated.workerName = workerName
        accumulated.msaidiziName = msaidiziName
        accumulated.language = language

        Timber.d(TAG, "Starting onboarding conversation for %s (agent: %s)", workerName, msaidiziName)

        // Phase 2: Getting to Know You
        emit(ConversationStep.AskBusinessIntro(
            prompt = buildPrompt(language,
                "Sasa $workerName, nieleze — unafanya biashara gani?",
                "Now $workerName, tell me — what business do you do?"
            )
        ))
    }

    /**
     * Process worker's response and return the next conversation step.
     * Each response updates the Bayesian priors (STA 142).
     *
     * @param currentStep Current conversation step
     * @param response What the worker said (transcribed from voice)
     * @return Next conversation step
     */
    fun processResponse(
        currentStep: ConversationStep,
        response: String
    ): ConversationStep {
        Timber.d(TAG, "Processing response for step %s: %s", currentStep::class.simpleName, response.take(50))

        return when (currentStep) {
            // ── Phase 2: Getting to Know You ──

            is ConversationStep.AskBusinessIntro -> {
                // Classify business type and ask follow-up
                val classification = classifyFromResponse(response)
                accumulated.businessDescription = response
                accumulated.businessType = classification.type
                accumulated.classificationConfidence = classification.confidence

                // Ask about specific products
                ConversationStep.AskProducts(
                    prompt = buildPrompt(accumulated.language,
                        "Nzuri! ${getEncouragement(accumulated.language)}. " +
                            "Niuzia nini haswa? Ni vitu gani unauza zaidi?",
                        "Great! ${getEncouragement("en")}. " +
                            "What exactly do you sell? What are your main products?"
                    ),
                    followUpHint = getFollowUpForBusinessType(classification.type, accumulated.language)
                )
            }

            is ConversationStep.AskProducts -> {
                accumulated.products = extractProducts(response)

                // Ask about location
                ConversationStep.AskLocation(
                    prompt = buildPrompt(accumulated.language,
                        "Sawa! Unauzia wapi? Sokoni, barabarani, nyumbani, au unatembea?",
                        "Okay! Where do you sell? At the market, roadside, home, or do you move around?"
                    )
                )
            }

            is ConversationStep.AskLocation -> {
                accumulated.location = extractLocation(response)
                accumulated.marketName = extractMarketName(response)

                // Ask about working hours
                ConversationStep.AskWorkingHours(
                    prompt = buildPrompt(accumulated.language,
                        "Unafanya kazi masaa gani? Unaanza lini, unaisha lini?",
                        "What hours do you work? When do you start, when do you finish?"
                    )
                )
            }

            is ConversationStep.AskWorkingHours -> {
                accumulated.workingHours = parseWorkingHours(response)

                // ── Phase 3: Understanding Your Business ──

                // Ask if they work alone
                ConversationStep.AskWorkAlone(
                    prompt = buildPrompt(accumulated.language,
                        "Unafanya kazi peke yako au una mtu anakusaidia?",
                        "Do you work alone or does someone help you?"
                    )
                )
            }

            is ConversationStep.AskWorkAlone -> {
                accumulated.workAlone = parseWorkAlone(response)

                // Ask about supply method
                ConversationStep.AskSupplyMethod(
                    prompt = buildPrompt(accumulated.language,
                        "Unapata wapi bidhaa zako? Unanunua wapi stock yako?",
                        "Where do you get your products? Where do you buy your stock?"
                    )
                )
            }

            is ConversationStep.AskSupplyMethod -> {
                accumulated.supplyMethod = response

                // Ask about customers
                ConversationStep.AskCustomerFind(
                    prompt = buildPrompt(accumulated.language,
                        "Wateja wako wanakujaje? Wanakutaje kupitia nini?",
                        "How do your customers find you? How do they come to you?"
                    )
                )
            }

            is ConversationStep.AskCustomerFind -> {
                accumulated.customerFindMethod = response

                // Ask about payment method
                ConversationStep.AskPaymentMethod(
                    prompt = buildPrompt(accumulated.language,
                        "Wanalipaje wateja wako? M-Pesa, pesa taslimu, au zote mbili?",
                        "How do your customers pay? M-Pesa, cash, or both?"
                    )
                )
            }

            is ConversationStep.AskPaymentMethod -> {
                accumulated.paymentMethod = parsePaymentMethod(response)

                // Ask about record-keeping
                ConversationStep.AskRecordKeeping(
                    prompt = buildPrompt(accumulated.language,
                        "Je, unafuatilia mauzo yako? Unandika wapi — kwenye daftari, simu, au kichwani?",
                        "Do you track your sales? Where do you write — notebook, phone, or in your head?"
                    )
                )
            }

            is ConversationStep.AskRecordKeeping -> {
                accumulated.keepsRecords = parseRecordMethod(response)

                // Ask about biggest challenge
                ConversationStep.AskChallenge(
                    prompt = buildPrompt(accumulated.language,
                        "Sasa nieleze — changamoto kubwa zaidi ya biashara yako ni ipi?",
                        "Now tell me — what's the biggest challenge in your business?"
                    )
                )
            }

            is ConversationStep.AskChallenge -> {
                accumulated.biggestChallenge = response

                // ── Phase 4: Setting Up ──
                // Models are downloading in background during the conversation
                ConversationStep.ModelDownloadStatus(
                    prompt = buildPrompt(accumulated.language,
                        "Sawa $workerName! Nimejifunza mengi kuhusu biashara yako. " +
                            "Sasa ninajifunza lugha yako... dakika moja tu.",
                        "Great $workerName! I've learned a lot about your business. " +
                            "Now I'm learning your language... just a moment."
                    )
                )
            }

            is ConversationStep.ModelDownloadStatus -> {
                // Models should be downloading — this step waits for them
                // The UI handles the actual download progress
                // This step transitions when download completes
                ConversationStep.FirstInsight(
                    prompt = generateFirstInsight()
                )
            }

            is ConversationStep.FirstInsight -> {
                // Onboarding complete!
                ConversationStep.Complete(
                    profile = buildProfile()
                )
            }

            is ConversationStep.Complete -> currentStep
        }
    }

    /**
     * Get model download priority order.
     * Whisper first (voice input critical), then Qwen, then TTS.
     */
    fun getModelDownloadPriority(): List<String> = listOf(
        "whisper-tiny-int4",   // ~150MB — voice input is critical
        "qwen-0.5b-q4km",     // ~300MB — reasoning
        "piper-swahili"        // ~50MB — voice output
    )

    // ═══════════════════════════════════════════════════════════════
    // BAYESIAN CLASSIFICATION (STA 142)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Classify business type from worker's response using Bayesian updating.
     *
     * STA 142: P(type | response) ∝ P(response | type) × P(type)
     *
     * We use keyword-based likelihood estimation and update the prior.
     * Each conversation turn refines the posterior.
     */
    private fun classifyFromResponse(response: String): BusinessClassification {
        val lower = response.lowercase()

        // Likelihood: P(keywords | business_type)
        val likelihoods = mutableMapOf<WorkerType, Double>()

        // ECO 201: Production function keywords → business type
        likelihoods[WorkerType.TRADER] = calculateLikelihood(lower, listOf(
            "nauza", "duka", "shop", "sokoni", "market", "mboga",
            "mama mboga", "mitumba", "hawker", "biashara", "nunua",
            "stock", "wholesale", "retail", "mali", "bidhaa", "soko"
        ))

        likelihoods[WorkerType.TRANSPORT] = calculateLikelihood(lower, listOf(
            "boda", "pikipiki", "matatu", "abiria", "taxi", "uber",
            "nduthi", "delivery", "usafiri", "tuk-tuk", "bajaj",
            "mzunguko", "gari", "moto"
        ))

        likelihoods[WorkerType.FARMER] = calculateLikelihood(lower, listOf(
            "shamba", "kulima", "mazao", "mbegu", "mbolea", "mvua",
            "harvest", "mkulima", "mahindi", "ngano", "kahawa", "chai",
            "nyuki", "kuku", "mifugo", "kilimo", "mazao"
        ))

        likelihoods[WorkerType.SERVICE] = calculateLikelihood(lower, listOf(
            "salon", "kunyolewa", "haircut", "fundi", "mechanic",
            "repair", "service", "mteja", "client", "appointment",
            "massage", "dawa", "clinic", "kazi", "huduma"
        ))

        likelihoods[WorkerType.MANUFACTURING] = calculateLikelihood(lower, listOf(
            "tengeneza", "chuma", "mbao", "welding", "furniture",
            "nguo", "ushona", "tailor", "maker", "production",
            "jua kali", "karai", "tofali", "ufundi"
        ))

        likelihoods[WorkerType.DIGITAL] = calculateLikelihood(lower, listOf(
            "mpesa", "float", "airtime", "agent", "online",
            "social media", "instagram", "tiktok", "gig", "phone",
            "simu", "computer", "digital"
        ))

        // Bayesian update: posterior ∝ likelihood × prior
        val posteriors = mutableMapOf<WorkerType, Double>()
        var evidence = 0.0

        for (type in WorkerType.values()) {
            if (type == WorkerType.UNKNOWN) continue
            val prior = businessTypePriors[type] ?: 0.0
            val likelihood = likelihoods[type] ?: 0.0
            posteriors[type] = likelihood * prior
            evidence += posteriors[type]!!
        }

        // Normalize posteriors
        if (evidence > 0) {
            for (type in posteriors.keys) {
                posteriors[type] = posteriors[type]!! / evidence
            }
        }

        // Update priors for next round
        for (type in posteriors.keys) {
            businessTypePriors[type] = posteriors[type]!!
        }

        // Find best classification
        val best = posteriors.maxByOrNull { it.value }
        val confidence = best?.value ?: 0.0

        Timber.d(TAG, "Bayesian update: %s (%.1f%% confidence)", best?.key, confidence * 100)

        return BusinessClassification(
            type = best?.key ?: WorkerType.UNKNOWN,
            confidence = confidence,
            allProbabilities = posteriors
        )
    }

    /**
     * Calculate keyword likelihood for a business type.
     * Returns normalized score based on keyword matches.
     */
    private fun calculateLikelihood(text: String, keywords: List<String>): Double {
        val matches = keywords.count { text.contains(it) }
        return if (keywords.isEmpty()) 0.0 else (matches.toDouble() / keywords.size).coerceIn(0.0, 1.0)
    }

    // ═══════════════════════════════════════════════════════════════
    // RESPONSE PARSING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extract product names from response.
     * Handles Swahili patterns like "nauza mboga na matunda" → ["mboga", "matunda"]
     */
    private fun extractProducts(response: String): List<String> {
        val cleaned = response.lowercase()
            .replace(Regex("nauza|ninauza|ninunua|ninanunua|biashara yangu ni"), "")
            .replace(Regex("\\bna\\b|,|\\."), " ")
            .trim()

        return cleaned.split(Regex("\\s+"))
            .filter { it.length > 2 }
            .distinct()
            .take(10)
    }

    /**
     * Extract location from response.
     * Maps common patterns to standardized locations.
     */
    private fun extractLocation(response: String): String {
        val lower = response.lowercase()
        return when {
            lower.contains("sokoni") || lower.contains("market") -> "market"
            lower.contains("barabarani") || lower.contains("roadside") || lower.contains("njia") -> "roadside"
            lower.contains("nyumbani") || lower.contains("home") || lower.contains("ndani") -> "home"
            lower.contains("natembea") || lower.contains("mobile") || lower.contains("hawker") -> "mobile"
            else -> response.trim()
        }
    }

    /**
     * Extract market name if mentioned.
     */
    private fun extractMarketName(response: String): String {
        // Look for "soko la X" or "market ya X" patterns
        val sokoPattern = Regex("(?i)soko\\s+(?:la\\s+)?(\\w+)")
        sokoPattern.find(response)?.let { return it.groupValues[1] }

        val marketPattern = Regex("(?i)market\\s+(?:ya\\s+)?(\\w+)")
        marketPattern.find(response)?.let { return it.groupValues[1] }

        return ""
    }

    /**
     * Parse working hours from natural language.
     */
    private fun parseWorkingHours(response: String): WorkingHours {
        val lower = response.lowercase()

        // Try to extract times
        val timePattern = Regex("(\\d{1,2})[.:]?\\s*(?:am|pm|asubuhi|mchana|jioni|usiku)?")
        val times = timePattern.findAll(lower).map { it.groupValues[1].toIntOrNull() }.filterNotNull().toList()

        val startHour = times.firstOrNull() ?: 6
        val endHour = if (times.size >= 2) times[1] else 18

        // Detect consistency
        val consistent = !lower.contains("sometimes") && !lower.contains("wakati mwingine")

        return WorkingHours(
            startHour = startHour.coerceIn(0, 23),
            endHour = endHour.coerceIn(0, 23),
            consistent = consistent,
            daysPerWeek = if (lower.contains("kila siku") || lower.contains("every day")) 7 else 6,
            description = response
        )
    }

    /**
     * Parse whether worker works alone.
     */
    private fun parseWorkAlone(response: String): Boolean {
        val lower = response.lowercase()
        return !(lower.contains("na mtu") || lower.contains("na mke") || lower.contains("na mume") ||
            lower.contains("na kijana") || lower.contains("na msaidizi") || lower.contains("together") ||
            lower.contains("team") || lower.contains("watu"))
    }

    /**
     * Parse payment method from response.
     */
    private fun parsePaymentMethod(response: String): PaymentType {
        val lower = response.lowercase()
        return when {
            lower.contains("mpesa") && (lower.contains("cash") || lower.contains("pesa") || lower.contains("zote")) -> PaymentType.BOTH
            lower.contains("mpesa") || lower.contains("mobile money") -> PaymentType.MOBILE
            lower.contains("bank") || lower.contains("benki") -> PaymentType.BANK
            else -> PaymentType.CASH
        }
    }

    /**
     * Parse record-keeping method from response.
     */
    private fun parseRecordMethod(response: String): RecordMethod {
        val lower = response.lowercase()
        return when {
            lower.contains("daftari") || lower.contains("notebook") || lower.contains("andika") || lower.contains("paper") -> RecordMethod.NOTEBOOK
            lower.contains("simu") || lower.contains("phone") || lower.contains("app") -> RecordMethod.PHONE
            lower.contains("kitabu") || lower.contains("bookkeep") || lower.contains("account") -> RecordMethod.BOOKKEEPING
            else -> RecordMethod.MEMORY
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // FIRST INSIGHT GENERATION (Phase 5: First Value)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate the first insight based on what Msaidizi learned.
     * This is Msaidizi's "aha moment" — immediate value from onboarding.
     *
     * ECO 201: Producer theory — basic cost/revenue insight
     * ECO 206: Microfinance — savings/payment advice
     */
    private fun generateFirstInsight(): String {
        val name = accumulated.workerName
        val agentName = accumulated.msaidiziName

        return when (accumulated.businessType) {
            WorkerType.TRADER -> {
                val products = accumulated.products.take(2).joinToString(" na ")
                buildPrompt(accumulated.language,
                    "$name, nimekuelewa! Biashara yako ya $products iko vizuri.\n\n" +
                        "Ushauri wangu wa kwanza: Kuanzia leo, $agentName atakusaidia " +
                        "kufuatilia kila mauzo. Hii itakuonyesha:\n" +
                        "• Bidhaa gani inauza zaidi\n" +
                        "• Faida yako ya kila siku\n" +
                        "• Lini unapaswa kununua stock zaidi\n\n" +
                        "Tuanze? Niuzia leo nini?",
                    "$name, I understand you! Your $products business looks good.\n\n" +
                        "My first advice: From today, $agentName will help you " +
                        "track every sale. This will show you:\n" +
                        "• Which products sell best\n" +
                        "• Your daily profit\n" +
                        "• When to restock\n\n" +
                        "Let's start? What did you sell today?"
                )
            }
            WorkerType.TRANSPORT -> buildPrompt(accumulated.language,
                "$name, nimekuelewa! Biashara yako ya usafiri iko vizuri.\n\n" +
                    "Ushauri wangu wa kwanza: $agentName atakusaidia kufuatilia " +
                    "kila safari na gharama za mafuta. Hii itakuonyesha:\n" +
                    "• Mapato yako ya kila siku\n" +
                    "• Gharama za mafuta\n" +
                    "• Saa ngapi unapata zaidi\n\n" +
                    "Tuanze? Leo umefanya safari ngapi?",
                "$name, I understand you! Your transport business looks good.\n\n" +
                    "My first advice: $agentName will help you track " +
                    "every trip and fuel costs. This will show you:\n" +
                    "• Your daily earnings\n" +
                    "• Fuel expenses\n" +
                    "• Best earning hours\n\n" +
                    "Let's start? How many trips today?"
            )
            WorkerType.FARMER -> buildPrompt(accumulated.language,
                "$name, nimekuelewa! Kilimo chako kina uwezo mkubwa.\n\n" +
                    "Ushauri wangu wa kwanza: $agentName atakusaidia kufuatilia " +
                    "gharama za mbegu na mbolea dhidi ya mauzo. Hii itakuonyesha:\n" +
                    "• Faida yako ya msimu\n" +
                    "• Mazao gani yanafaa zaidi\n" +
                    "• Lini uuze bei nzuri\n\n" +
                    "Tuanze? Leo umepanda au kuuza nini?",
                "$name, I understand you! Your farming has great potential.\n\n" +
                    "My first advice: $agentName will help you track " +
                    "seed and fertilizer costs against sales. This will show you:\n" +
                    "• Seasonal profit\n" +
                    "• Best crops to grow\n" +
                    "• When to sell for best prices\n\n" +
                    "Let's start? What did you plant or sell today?"
            )
            else -> buildPrompt(accumulated.language,
                "$name, nimekuelewa! Biashara yako iko vizuri.\n\n" +
                    "Ushauri wangu wa kwanza: $agentName atakusaidia kufuatilia " +
                    "kila mauzo na gharama. Hii itakuonyesha:\n" +
                    "• Mapato yako ya kila siku\n" +
                    "• Faida yako halisi\n" +
                    "• Jinsi ya kukua biashara yako\n\n" +
                    "Tuanze? Leo umefanya mauzo gapi?",
                "$name, I understand you! Your business looks good.\n\n" +
                    "My first advice: $agentName will help you track " +
                    "every sale and expense. This will show you:\n" +
                    "• Your daily income\n" +
                    "• Your real profit\n" +
                    "• How to grow your business\n\n" +
                    "Let's start? What sales did you make today?"
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PROFILE BUILDING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build the final WorkerProfile from accumulated conversation data.
     */
    private fun buildProfile(): WorkerProfile {
        return WorkerProfile(
            msaidiziName = accumulated.msaidiziName,
            workerName = accumulated.workerName,
            businessType = accumulated.businessType ?: WorkerType.UNKNOWN,
            businessDescription = accumulated.businessDescription,
            products = accumulated.products,
            location = accumulated.location,
            marketName = accumulated.marketName,
            workingHours = accumulated.workingHours,
            workAlone = accumulated.workAlone,
            supplyMethod = accumulated.supplyMethod,
            customerFindMethod = accumulated.customerFindMethod,
            paymentMethod = accumulated.paymentMethod,
            keepsRecords = accumulated.keepsRecords,
            biggestChallenge = accumulated.biggestChallenge,
            language = accumulated.language,
            dialect = accumulated.dialect,
            classificationConfidence = accumulated.classificationConfidence,
            conversationTurns = accumulated.conversationTurns,
            onboardingCompletedAt = System.currentTimeMillis()
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build bilingual prompt — Swahili primary, English secondary.
     * BCB 108: Clear, culturally appropriate communication.
     */
    private fun buildPrompt(language: String, swahili: String, english: String): String {
        return if (language.startsWith("sw")) swahili
        else if (language.startsWith("en")) english
        else "$swahili\n\n$english"
    }

    /**
     * Get encouragement based on language.
     */
    private fun getEncouragement(language: String): String {
        return if (language.startsWith("sw")) "Nzuri sana" else "That's great"
    }

    /**
     * Get follow-up hint based on business type.
     * ECO 201: Different business types have different key products.
     */
    private fun getFollowUpForBusinessType(type: WorkerType?, language: String): String {
        val hint = when (type) {
            WorkerType.TRADER -> if (language.startsWith("sw"))
                "Kwa mfano: mboga, matunda, nguo, vifaa..."
            else "For example: vegetables, clothes, goods..."
            WorkerType.TRANSPORT -> if (language.startsWith("sw"))
                "Kwa mfano: abiria, mzigo, delivery..."
            else "For example: passengers, cargo, delivery..."
            WorkerType.FARMER -> if (language.startsWith("sw"))
                "Kwa mfano: mahindi, maharagwe, nyanya, kuku..."
            else "For example: maize, beans, tomatoes, chicken..."
            else -> ""
        }
        return hint
    }
}

// ═══════════════════════════════════════════════════════════════
// CONVERSATION STEPS (sealed class)
// ═══════════════════════════════════════════════════════════════

/**
 * Sealed class representing each step in the onboarding conversation.
 *
 * The conversation flows through 5 phases:
 * 1. Introduction (handled by AgentNamingFragment)
 * 2. Getting to Know You: AskBusinessIntro → AskProducts → AskLocation → AskWorkingHours
 * 3. Understanding Your Business: AskWorkAlone → AskSupplyMethod → AskCustomerFind → AskPaymentMethod → AskRecordKeeping → AskChallenge
 * 4. Setting Up: ModelDownloadStatus
 * 5. First Value: FirstInsight → Complete
 */
sealed class ConversationStep {

    // ── Phase 2: Getting to Know You ──

    /** Ask about business type */
    data class AskBusinessIntro(val prompt: String) : ConversationStep()

    /** Ask about specific products/services */
    data class AskProducts(val prompt: String, val followUpHint: String = "") : ConversationStep()

    /** Ask about work location */
    data class AskLocation(val prompt: String) : ConversationStep()

    /** Ask about working hours */
    data class AskWorkingHours(val prompt: String) : ConversationStep()

    // ── Phase 3: Understanding Your Business ──

    /** Ask if they work alone */
    data class AskWorkAlone(val prompt: String) : ConversationStep()

    /** Ask about supply/procurement method */
    data class AskSupplyMethod(val prompt: String) : ConversationStep()

    /** Ask how customers find them */
    data class AskCustomerFind(val prompt: String) : ConversationStep()

    /** Ask about payment method */
    data class AskPaymentMethod(val prompt: String) : ConversationStep()

    /** Ask about record-keeping */
    data class AskRecordKeeping(val prompt: String) : ConversationStep()

    /** Ask about biggest challenge */
    data class AskChallenge(val prompt: String) : ConversationStep()

    // ── Phase 4: Setting Up ──

    /** Model download status — conversation continues while models download */
    data class ModelDownloadStatus(val prompt: String) : ConversationStep()

    // ── Phase 5: First Value ──

    /** First insight based on what we learned */
    data class FirstInsight(val prompt: String) : ConversationStep()

    /** Onboarding complete — return the profile */
    data class Complete(val profile: WorkerProfile) : ConversationStep()
}

// ═══════════════════════════════════════════════════════════════
// SUPPORTING DATA CLASSES
// ═══════════════════════════════════════════════════════════════

/**
 * Business classification result from Bayesian inference.
 */
data class BusinessClassification(
    val type: WorkerType,
    val confidence: Double,
    val allProbabilities: Map<WorkerType, Double>
)

/**
 * Mutable container for data accumulated during the onboarding conversation.
 * Passed between steps so each step can add its findings.
 */
class AccumulatedProfileData {
    var workerName: String = ""
    var msaidiziName: String = "Msaidizi"
    var businessDescription: String = ""
    var businessType: WorkerType? = null
    var products: List<String> = emptyList()
    var location: String = ""
    var marketName: String = ""
    var workingHours: WorkingHours = WorkingHours()
    var workAlone: Boolean = true
    var supplyMethod: String = ""
    var customerFindMethod: String = ""
    var paymentMethod: PaymentType = PaymentType.BOTH
    var keepsRecords: RecordMethod = RecordMethod.MEMORY
    var biggestChallenge: String = ""
    var language: String = "sw"
    var dialect: String = "STANDARD"
    var classificationConfidence: Double = 0.0
    var conversationTurns: Int = 0
}
