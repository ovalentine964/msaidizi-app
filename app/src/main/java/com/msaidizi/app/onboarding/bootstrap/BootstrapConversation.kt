package com.msaidizi.app.onboarding.bootstrap

import com.msaidizi.app.agent.WorkerType
import com.msaidizi.app.onboarding.PaymentType
import com.msaidizi.app.onboarding.RecordMethod
import com.msaidizi.app.onboarding.WorkerProfile
import com.msaidizi.app.onboarding.WorkingHours
import timber.log.Timber

/**
 * Bootstrap Conversation — Msaidizi's first voice conversation with the worker.
 *
 * This is NOT data collection. This is UNDERSTANDING.
 *
 * Like a new friend learning about your life, Msaidizi listens deeply
 * to every response and builds a mental model of who this worker is,
 * what she needs, and how to help her specifically.
 *
 * Each response is analyzed on multiple dimensions:
 * - WHAT she said (the data)
 * - HOW she said it (communication style, sophistication)
 * - WHAT IT REVEALS (business maturity, tech comfort, emotional state)
 * - HOW TO HELP HER (feature priorities, report types, advice style)
 *
 * By the end of 9 turns, Msaidizi doesn't just know Mary sells mboga.
 * She knows Mary is a confident mama mboga who uses M-Pesa, worries about
 * stockouts, wants to save for school fees, and responds best to
 * casual Swahili with a warm, encouraging tone.
 *
 * ## The OpenClaw Naming Pattern
 *
 * Step 2: "Unataka kuniita nani?" — worker names their AI.
 * This creates ownership before the real conversation begins.
 *
 * ## Conversation Flow (9 Turns, ~3-5 min)
 *
 * 1. Greeting → learns name, analyzes communication style
 * 2. Agent Naming → THE OPENCLAW MOMENT, analyzes creativity/culture
 * 3. Business Type → classifies business, analyzes sophistication
 * 4. Products → learns inventory, analyzes business specificity
 * 5. Location → learns market context, analyzes urban/rural
 * 6. Working Hours → learns schedule, analyzes work ethic
 * 7. Customers + Payment → learns market access, analyzes tech comfort
 * 8. Challenges → learns pain points, analyzes emotional state
 * 9. Summary + First Insight → delivers personalized understanding
 *
 * ## Academic Foundations
 *
 * ### PSY 101 — Behavioral Psychology
 * Naming creates ownership (Kahneman's endowment effect).
 * Challenge description reveals emotional state and priorities.
 *
 * ### STA 142 — Bayesian Inference
 * Each answer updates posterior probability of business type
 * AND worker archetype (new, growing, established, struggling).
 *
 * ### ECO 201 — Producer Theory
 * Business description → production function
 * Products → output type
 * Customers → market access
 * Challenges → cost structure constraints
 *
 * ### BCB 108 — Communication
 * Speech patterns reveal education level, cultural context,
 * and preferred communication style.
 *
 * @see WorkerUnderstanding for the derived intelligence model
 * @see BootstrapViewModel for state management
 * @see WorkerProfile for the data model this populates
 */
class BootstrapConversation {

    companion object {
        private const val TAG = "BootstrapConversation"
    }

    // ── Bayesian State (STA 142) ──
    private val businessTypePriors = mutableMapOf(
        WorkerType.TRADER to 0.25,
        WorkerType.TRANSPORT to 0.15,
        WorkerType.FARMER to 0.20,
        WorkerType.SERVICE to 0.15,
        WorkerType.MANUFACTURING to 0.10,
        WorkerType.DIGITAL to 0.15
    )

    // Accumulated raw data from conversation
    private val accumulated = AccumulatedData()

    // Derived understanding — this is what makes it a CONVERSATION, not a form
    val understanding = WorkerUnderstanding()

    // Whether worker responded in English (detected from first response)
    private var language = "sw"

    /**
     * Get the first prompt — the greeting.
     * Msaidizi introduces herself and asks the worker's name.
     */
    fun getGreetingPrompt(): String {
        return "Karibu! Mimi ni Msaidizi wako — nitakusaidia kufuatilia biashara yako.\n\n" +
            "Unaitwa nani?"
    }

    /**
     * Process the worker's response for the current step and return the next prompt.
     *
     * Every response is processed in TWO ways:
     * 1. DATA extraction — what fields to fill
     * 2. UNDERSTANDING analysis — what this reveals about the worker
     */
    fun processResponse(currentStep: BootstrapStep, response: String): BootstrapStep {
        Timber.d(TAG, "Step %s → response: '%s'", currentStep::class.simpleName, response.take(60))

        // Detect language from first substantive response
        if (accumulated.conversationTurns == 0 && response.isNotBlank()) {
            language = detectLanguage(response)
            accumulated.language = language
            understanding.communicationStyle = understanding.communicationStyle.copy(
                primaryLanguage = language
            )
        }
        accumulated.conversationTurns++

        return when (currentStep) {
            is BootstrapStep.Greeting -> handleGreeting(response)
            is BootstrapStep.AskAgentNaming -> handleAgentNaming(response)
            is BootstrapStep.AskBusinessType -> handleBusinessType(response)
            is BootstrapStep.AskProducts -> handleProducts(response)
            is BootstrapStep.AskLocation -> handleLocation(response)
            is BootstrapStep.AskWorkingHours -> handleWorkingHours(response)
            is BootstrapStep.AskCustomersAndPayment -> handleCustomersAndPayment(response)
            is BootstrapStep.AskChallenges -> handleChallenges(response)
            is BootstrapStep.Summary -> handleSummary()
            is BootstrapStep.AskPin -> handlePin(response)
            is BootstrapStep.Complete -> currentStep
        }
    }

    fun getProgress(step: BootstrapStep): Float {
        return when (step) {
            is BootstrapStep.Greeting -> 0f
            is BootstrapStep.AskAgentNaming -> 1f / 8f
            is BootstrapStep.AskBusinessType -> 2f / 8f
            is BootstrapStep.AskProducts -> 3f / 8f
            is BootstrapStep.AskLocation -> 4f / 8f
            is BootstrapStep.AskWorkingHours -> 5f / 8f
            is BootstrapStep.AskCustomersAndPayment -> 6f / 8f
            is BootstrapStep.AskChallenges -> 7f / 8f
            is BootstrapStep.Summary -> 9f / 10f
            is BootstrapStep.AskPin -> 10f / 10f
            is BootstrapStep.Complete -> 1f
        }
    }

    fun getStepNumber(step: BootstrapStep): Int {
        return when (step) {
            is BootstrapStep.Greeting -> 1
            is BootstrapStep.AskAgentNaming -> 2
            is BootstrapStep.AskBusinessType -> 3
            is BootstrapStep.AskProducts -> 4
            is BootstrapStep.AskLocation -> 5
            is BootstrapStep.AskWorkingHours -> 6
            is BootstrapStep.AskCustomersAndPayment -> 7
            is BootstrapStep.AskChallenges -> 8
            is BootstrapStep.Summary -> 9
            is BootstrapStep.AskPin -> 10
            is BootstrapStep.Complete -> 10
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // STEP HANDLERS — Each handler does TWO things:
    //   1. Extract data (what she said)
    //   2. Analyze understanding (what it reveals)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Step 1: Worker tells us their name.
     *
     * DATA: extracts worker name
     * UNDERSTANDING: analyzes communication style from how they introduce themselves
     *   - "Naitwa Mary" → standard Swahili, moderate comfort
     *   - "Mary" → confident, direct, possibly tech-savvy
     *   - "Mimi ni Mary Wanjiku" → formal, provides full name
     *   - "Wacha nikuambie..." → casual, storytelling style
     */
    private fun handleGreeting(response: String): BootstrapStep {
        accumulated.workerName = extractName(response)

        // ANALYZE: Communication style from self-introduction
        analyzeIntroductionStyle(response)

        val name = accumulated.workerName
        val prompt = if (language == "sw") {
            "$name, nzuri sana!\n\n" +
                "Sasa — unataka kuniita nani? Jina lolote unalopenda.\n" +
                "Kwa mfano: Rafiki, Mshauri, Biashara Yangu, au jina lako mwenyewe."
        } else {
            "$name, great!\n\n" +
                "Now — what do you want to call me? Any name you like.\n" +
                "For example: Rafiki, Mshauri, Biashara Yangu, or your own name."
        }

        return BootstrapStep.AskAgentNaming(prompt = prompt)
    }

    /**
     * Step 2: Worker names their Msaidizi. THE OPENCLAW MOMENT.
     *
     * DATA: extracts agent name
     * UNDERSTANDING: analyzes the CHOICE to understand the relationship the worker wants
     *   - "Rafiki" (friend) → wants warmth, personal connection
     *   - "Mshauri" (advisor) → wants professional guidance
     *   - "Biashara Yangu" (my business) → ownership-focused, business-minded
     *   - "Mwalimu" (teacher) → wants to learn, growth mindset
     *   - Creative/funny name → playful personality, likely younger
     *   - Skips naming → prefers simplicity, doesn't want complexity
     */
    private fun handleAgentNaming(response: String): BootstrapStep {
        val agentName = extractAgentName(response)
        accumulated.msaidiziName = agentName

        // ANALYZE: What the naming choice reveals about relationship expectations
        analyzeNamingChoice(response, agentName)

        Timber.d(TAG, "Agent named: '%s' by %s", agentName, accumulated.workerName)

        val delight = if (language == "sw") "$agentName! Napenda jina hilo."
        else "$agentName! I love that name."

        val prompt = if (language == "sw") {
            "$delight\n\nSasa $agentName anataka kukujua zaidi — unafanya biashara gani?"
        } else {
            "$delight\n\nNow $agentName wants to know more — what business do you do?"
        }

        return BootstrapStep.AskBusinessType(prompt = prompt)
    }

    /**
     * Step 3: Worker describes their business.
     *
     * DATA: Bayesian classification, business description
     * UNDERSTANDING: analyzes business sophistication and specificity
     *   - "Nauza mboga" → simple, direct (beginner)
     *   - "Mama mboga, nauza nyanya, sukuma, vitunguu sokoni" → specific, experienced
     *   - "Biashara yangu ni dukani" → uses formal language (educated)
     *   - "Hii kazi yangu..." → sees it as work, not business (mindset signal)
     *   - Mentions "mpango" or "kukua" → growth mindset
     *   - Mentions employees/team → established business
     */
    private fun handleBusinessType(response: String): BootstrapStep {
        val classification = classifyFromResponse(response)
        accumulated.businessDescription = response
        accumulated.businessType = classification.type
        accumulated.classificationConfidence = classification.confidence

        // ANALYZE: Business sophistication and worker archetype
        analyzeBusinessDescription(response, classification.type)

        val prompt = if (language == "sw") {
            "${getEncouragement()}! Niuzia nini haswa? Vitu gani unauza zaidi?"
        } else {
            "${getEncouragement()}! What exactly do you sell? What are your main products?"
        }

        return BootstrapStep.AskProducts(prompt = prompt, hint = getFollowUpHint(classification.type))
    }

    /**
     * Step 4: Worker lists their products/services.
     *
     * DATA: product list
     * UNDERSTANDING: analyzes business specificity and inventory management needs
     *   - Single product → simple operation, focus on volume
     *   - Many products → complex inventory, needs stock tracking
     *   - Specific names ("Nyanya ya Kibiti") → experienced, knows suppliers
     *   - Generic ("mboga tu") → may need help identifying product mix
     *   - Perishable items → needs waste tracking, timing insights
     *   - High-value items → needs margin tracking
     */
    private fun handleProducts(response: String): BootstrapStep {
        accumulated.products = extractProducts(response)

        // ANALYZE: What products reveal about business complexity
        analyzeProductMix(response, accumulated.products)

        val prompt = if (language == "sw") {
            "Sawa! Unauzia wapi? Sokoni, barabarani, nyumbani, au unatembea?"
        } else {
            "Okay! Where do you sell? At the market, roadside, home, or do you move around?"
        }

        return BootstrapStep.AskLocation(prompt = prompt)
    }

    /**
     * Step 5: Worker tells us where they work.
     *
     * DATA: location type, market name
     * UNDERSTANDING: analyzes market context and business environment
     *   - Named market → established location, regular customer base
     *   - Roadside → higher visibility, but weather-dependent
     *   - Home → lower overhead, but limited walk-in traffic
     *   - Mobile → highest effort, but flexible pricing
     *   - Urban area → more competition, more customers, M-Pesa common
     *   - Rural area → less competition, less foot traffic, cash-heavy
     */
    private fun handleLocation(response: String): BootstrapStep {
        accumulated.location = extractLocation(response)
        accumulated.marketName = extractMarketName(response)

        // ANALYZE: Market context
        analyzeLocationContext(response, accumulated.location)

        val prompt = if (language == "sw") {
            "Unafanya kazi masaa gani? Unaanza lini, unaisha lini?"
        } else {
            "What hours do you work? When do you start, when do you finish?"
        }

        return BootstrapStep.AskWorkingHours(prompt = prompt)
    }

    /**
     * Step 6: Worker tells us their working hours.
     *
     * DATA: working hours, schedule
     * UNDERSTANDING: analyzes work patterns and time management
     *   - Long hours (6am-8pm) → hard worker, may need efficiency tips
     *   - Short hours → part-time, may have other income sources
     *   - "Kila siku" → dedicated, full-time
     *   - "Wakati mwingine" → inconsistent, may need schedule optimization
     *   - Peak hours awareness → already thinks about timing (sophisticated)
     *   - Early morning → likely fresh produce (perishable)
     */
    private fun handleWorkingHours(response: String): BootstrapStep {
        accumulated.workingHours = parseWorkingHours(response)

        // ANALYZE: Work patterns
        analyzeWorkPatterns(response, accumulated.workingHours)

        val prompt = if (language == "sw") {
            "Sasa nieleze — wateja wako wanakujaje? Wanakutaje kupitia nini? Na wanalipaje — M-Pesa, pesa taslimu, au zote mbili?"
        } else {
            "How do your customers find you? And how do they pay — M-Pesa, cash, or both?"
        }

        return BootstrapStep.AskCustomersAndPayment(prompt = prompt)
    }

    /**
     * Step 7: Worker tells us about customers and payment.
     *
     * DATA: customer method, payment method
     * UNDERSTANDING: analyzes tech comfort and market access
     *   - M-Pesa only → tech-comfortable, digital trail exists
     *   - Cash only → traditional, may need M-Pesa guidance
     *   - Both → adaptable, most common
     *   - Walk-in customers → location-dependent, predictable
     *   - Phone/WhatsApp orders → tech-savvy, delivery capability
     *   - Regulars mentioned → customer relationship management potential
     *   - "Wanakuja mwenyewe" → passive customer acquisition
     */
    private fun handleCustomersAndPayment(response: String): BootstrapStep {
        accumulated.customerFindMethod = extractCustomerMethod(response)
        accumulated.paymentMethod = parsePaymentMethod(response)

        // ANALYZE: Tech comfort and market sophistication
        analyzeCustomerAndPaymentPatterns(response)

        val prompt = if (language == "sw") {
            "Sasa — changamoto kubwa zaidi ya biashara yako ni ipi? Ni kitu gani kinakusumbua zaidi?"
        } else {
            "Now — what's the biggest challenge in your business? What bothers you most?"
        }

        return BootstrapStep.AskChallenges(prompt = prompt)
    }

    /**
     * Step 8: Worker tells us their biggest challenge.
     *
     * DATA: challenge description
     * UNDERSTANDING: analyzes pain points, emotional state, and help priorities
     *   - "Stock haifiki" → supply chain problem → restock alerts
     *   - "Bei ni ngumu" → pricing challenge → market price insights
     *   - "Wateja wachache" → customer acquisition → marketing tips
     *   - "Pesa inaisha" → cash flow crisis → savings guidance, urgent
     *   - "Sijui faida" → doesn't know profit → daily P&L report, critical
     *   - "Maviboko" → external shock → resilience planning
     *   - Emotional language → stress, needs encouragement
     *   - Practical language → problem-solver, wants actionable advice
     */
    /**
     * Summary is display-only. When user taps continue, ask for PIN.
     */
    private fun handleSummary(): BootstrapStep {
        val agentName = accumulated.msaidiziName
        val prompt = if (language == "sw") {
            "Sasa, weka PIN yako ya tarakimu 4 kubiriya data yako ya biashara.\n\n" +
                "Sema tarakimu nne, kwa mfano: 'moja mbili tatu nne'"
        } else {
            "Now, set a 4-digit PIN to protect your business data.\n\n" +
                "Say four digits, for example: 'one two three four'"
        }
        return BootstrapStep.AskPin(prompt = prompt)
    }

    /**
     * Process PIN input from voice.
     * Expects 4 digits spoken in Swahili or English.
     */
    private fun handlePin(response: String): BootstrapStep {
        // Extract digits from voice input
        val digits = extractDigits(response)

        if (digits.length < 4) {
            // Not enough digits — ask again
            val prompt = if (language == "sw") {
                "PIN inahitaji tarakimu 4. Jaribu tena. Sema tarakimu nne."
            } else {
                "PIN needs 4 digits. Try again. Say four digits."
            }
            return BootstrapStep.AskPin(prompt = prompt)
        }

        // PIN is valid — save it and complete onboarding
        val pin = digits.take(4)
        accumulated.pin = pin

        // Generate completion message
        val agentName = accumulated.msaidiziName
        val workerName = accumulated.workerName

        val readyMessage = if (language == "sw") {
            "Pongezi $workerName! $agentName amekujua. Sasa $agentName ni msaidizi wako wa biashara.\n\n" +
                "Anza kwa kusema chochote kuhusu biashara yako — mauzo, manunuzi, au changamoto. " +
                "$agentName atasikiliza na kukusaidia."
        } else {
            "Congratulations $workerName! $agentName knows you now. $agentName is your business assistant.\n\n" +
                "Start by saying anything about your business — sales, purchases, or challenges. " +
                "$agentName will listen and help."
        }

        val profile = buildProfile()
        return BootstrapStep.Complete(
            profile = profile,
            readyMessage = readyMessage,
            agentName = agentName
        )
    }

    /**
     * Extract digits from spoken text.
     * Handles Swahili numbers (moja, mbili, tatu...) and English (one, two, three...)
     */
    private fun extractDigits(text: String): String {
        val swahiliDigits = mapOf(
            "moja" to "1", "mbili" to "2", "tatu" to "3", "nne" to "4",
            "tano" to "5", "sita" to "6", "saba" to "7", "nane" to "8", "tisa" to "9", "sifuri" to "0"
        )
        val englishDigits = mapOf(
            "one" to "1", "two" to "2", "three" to "3", "four" to "4",
            "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "zero" to "0"
        )
        val allDigits = swahiliDigits + englishDigits

        var result = ""
        val words = text.lowercase().split(Regex("\\s+"))
        for (word in words) {
            // Direct digit match
            if (word in allDigits) {
                result += allDigits[word]
            }
            // Numeric digit (e.g., "1", "2")
            else if (word.length == 1 && word[0].isDigit()) {
                result += word
            }
            // Multiple digits together (e.g., "1234")
            else if (word.all { it.isDigit() }) {
                result += word
            }
        }
        return result
    }

    private fun handleChallenges(response: String): BootstrapStep {
        accumulated.biggestChallenge = response

        // ANALYZE: Pain points, emotional state, help priorities
        analyzeChallengeDescription(response)

        // Now derive the final understanding — what Msaidizi will actually DO
        deriveFinalUnderstanding()

        val summary = generateSummary()
        val insight = generateFirstInsight()
        val agentName = accumulated.msaidiziName
        val workerName = accumulated.workerName

        val prompt = if (language == "sw") {
            "Nimekuelewa, $workerName!\n\n" +
                "Hapa kile $agentName amejifunza:\n\n" +
                "$summary\n\n" +
                "───\n\n" +
                "$insight\n\n" +
                "Tayari kuanza! Sema chochote kuhusu biashara yako — $agentName atakusikiliza."
        } else {
            "I understand you, $workerName!\n\n" +
                "Here's what $agentName learned:\n\n" +
                "$summary\n\n" +
                "───\n\n" +
                "$insight\n\n" +
                "Ready to start! Say anything about your business — $agentName will listen."
        }

        return BootstrapStep.Summary(prompt = prompt)
    }

    // ═══════════════════════════════════════════════════════════════
    // UNDERSTANDING ANALYSIS — The brain behind the conversation
    //
    // Each function analyzes a response on MULTIPLE dimensions:
    // - What the worker SAID (data)
    // - HOW they said it (style, sophistication)
    // - What it REVEALS (needs, capabilities, mindset)
    // - How to HELP them (features, reports, advice style)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Analyze how the worker introduces themselves.
     *
     * The WAY someone says their name reveals:
     * - Communication comfort level
     * - Formality preference
     * - Cultural context
     * - Tech comfort (short = phone-typing habit, long = storytelling)
     */
    private fun analyzeIntroductionStyle(response: String) {
        val lower = response.lowercase().trim()
        val wordCount = lower.split("\\s+".toRegex()).size

        // Formality detection
        val formality = when {
            lower.contains("mimi ni") || lower.contains("jina langu") -> Formality.FORMAL
            lower.contains("naitwa") -> Formality.STANDARD
            wordCount <= 2 -> Formality.CASUAL // Just the name — direct, confident
            lower.contains("habari") || lower.contains("shikamoo") -> Formality.FORMAL
            else -> Formality.STANDARD
        }

        // Communication comfort
        val comfort = when {
            wordCount >= 5 -> CommunicationComfort.VERBOSE // Storyteller, comfortable
            wordCount >= 3 -> CommunicationComfort.MODERATE
            wordCount <= 1 -> CommunicationComfort.MINIMAL // May be shy or just direct
            else -> CommunicationComfort.MODERATE
        }

        understanding.communicationStyle = understanding.communicationStyle.copy(
            formality = formality,
            verbosity = comfort,
            primaryLanguage = language
        )

        Timber.d(TAG, "Introduction analysis: formality=%s, verbosity=%s", formality, comfort)
    }

    /**
     * Analyze the naming choice — what the worker wants from this relationship.
     *
     * The name someone gives their AI reveals their expectations:
     * - "Rafiki" (friend) → wants warmth, personal connection, encouragement
     * - "Mshauri" (advisor) → wants professional advice, data-driven
     * - "Biashara Yangu" (my business) → ownership-focused, results-oriented
     * - "Mwalimu" (teacher) → wants to learn, growth mindset
     * - "Mama" / "Dada" → family-like trust, comfort
     * - Creative/funny → playful, younger, likely Sheng speaker
     * - Skips → pragmatic, doesn't want fluff, just help
     */
    private fun analyzeNamingChoice(response: String, agentName: String) {
        val lower = response.lowercase().trim()
        val nameLower = agentName.lowercase()

        // Relationship expectation
        val relationship = when {
            nameLower.contains("rafiki") || nameLower.contains("friend") -> RelationshipType.FRIEND
            nameLower.contains("mshauri") || nameLower.contains("advisor") || nameLower.contains("mwalimu") -> RelationshipType.ADVISOR
            nameLower.contains("biashara") || nameLower.contains("business") -> RelationshipType.BUSINESS_PARTNER
            nameLower.contains("mama") || nameLower.contains("dada") || nameLower.contains("mzee") -> RelationshipType.FAMILY
            lower in listOf("hapana", "msaidizi", "siwezi", "sijui") -> RelationshipType.PRAGMATIC
            agentName.length > 12 || agentName.contains(" ") -> RelationshipType.CREATIVE
            else -> RelationshipType.FRIEND
        }

        // Communication style implications
        val tone = when (relationship) {
            RelationshipType.FRIEND -> Tone.WARM_CASUAL
            RelationshipType.ADVISOR -> Tone.PROFESSIONAL
            RelationshipType.BUSINESS_PARTNER -> Tone.RESULTS_FOCUSED
            RelationshipType.FAMILY -> Tone.NURTURING
            RelationshipType.PRAGMATIC -> Tone.DIRECT
            RelationshipType.CREATIVE -> Tone.PLAYFUL
        }

        // Sheng likelihood from naming creativity
        val shengLikelihood = when {
            relationship == RelationshipType.CREATIVE -> ShengLikelihood.HIGH
            relationship == RelationshipType.FRIEND && language == "sw" -> ShengLikelihood.MODERATE
            language == "en" -> ShengLikelihood.LOW
            else -> ShengLikelihood.MODERATE
        }

        understanding.relationshipType = relationship
        understanding.communicationStyle = understanding.communicationStyle.copy(
            preferredTone = tone,
            shengLikelihood = shengLikelihood
        )

        Timber.d(TAG, "Naming analysis: relationship=%s, tone=%s, sheng=%s", relationship, tone, shengLikelihood)
    }

    /**
     * Analyze the business description for sophistication and mindset.
     *
     * WHAT the worker says about their business reveals:
     * - Business maturity (new vs established)
     * - Sophistication level (simple vs complex operations)
     * - Growth mindset (stuck vs aspiring)
     * - Self-identity (hustler vs business owner vs worker)
     */
    private fun analyzeBusinessDescription(response: String, businessType: WorkerType?) {
        val lower = response.lowercase()

        // Business specificity (how well they know their business)
        val specificity = when {
            lower.split("\\s+".toRegex()).size >= 8 -> BusinessSpecificity.DETAILED
            lower.split("\\s+".toRegex()).size >= 4 -> BusinessSpecificity.MODERATE
            else -> BusinessSpecificity.VAGUE
        }

        // Business maturity signals
        val maturity = when {
            lower.contains("mpango") || lower.contains("kukua") || lower.contains("grow") -> BusinessMaturity.GROWING
            lower.contains("mpya") || lower.contains("new") || lower.contains("anza") -> BusinessMaturity.NEW
            lower.contains("miaka") || lower.contains("years") || lower.contains("tangu") -> BusinessMaturity.ESTABLISHED
            lower.contains("changamoto") || lower.contains("problem") || lower.contains("ngumu") -> BusinessMaturity.STRUGGLING
            else -> BusinessMaturity.STABLE
        }

        // Self-identity: how does she see herself?
        val identity = when {
            lower.contains("mama mboga") || lower.contains("mama") -> WorkerIdentity.MAMA
            lower.contains("fundi") || lower.contains("fundii") -> WorkerIdentity.FUNDI
            lower.contains("biashara") || lower.contains("business") -> WorkerIdentity.BUSINESS_OWNER
            lower.contains("kazi") || lower.contains("work") -> WorkerIdentity.WORKER
            lower.contains("hustle") || lower.contains("hustler") -> WorkerIdentity.HUSTLER
            else -> WorkerIdentity.UNDEFINED
        }

        understanding.businessSophistication = understanding.businessSophistication.copy(
            specificity = specificity,
            maturity = maturity,
            selfIdentity = identity
        )

        // Infer help priorities from maturity
        when (maturity) {
            BusinessMaturity.NEW -> {
                understanding.helpPriority = understanding.helpPriority.copy(
                    primary = HelpFocus.TRACK_SALES,
                    secondary = HelpFocus.UNDERSTAND_PROFIT
                )
            }
            BusinessMaturity.GROWING -> {
                understanding.helpPriority = understanding.helpPriority.copy(
                    primary = HelpFocus.MANAGE_STOCK,
                    secondary = HelpFocus.GROW_BUSINESS
                )
            }
            BusinessMaturity.STRUGGLING -> {
                understanding.helpPriority = understanding.helpPriority.copy(
                    primary = HelpFocus.UNDERSTAND_PROFIT,
                    secondary = HelpFocus.MANAGE_CASH
                )
            }
            BusinessMaturity.ESTABLISHED -> {
                understanding.helpPriority = understanding.helpPriority.copy(
                    primary = HelpFocus.OPTIMIZE_OPERATIONS,
                    secondary = HelpFocus.GROW_BUSINESS
                )
            }
            else -> {}
        }

        Timber.d(TAG, "Business analysis: specificity=%s, maturity=%s, identity=%s", specificity, maturity, identity)
    }

    /**
     * Analyze product mix for business complexity and help needs.
     *
     * Products reveal:
     * - Inventory complexity (1 item vs 20)
     * - Perishability (fresh produce vs durable goods)
     * - Price range (high-value tracking needed?)
     * - Seasonality (predictable vs variable demand)
     */
    private fun analyzeProductMix(response: String, products: List<String>) {
        val lower = response.lowercase()

        // Perishable detection
        val perishableKeywords = listOf(
            "mboga", "matunda", "nyanya", "sukuma", "vitunguu", "nyama", "samaki",
            "maziwa", "mayai", "mkate", "chipsi", "mchuzi", "ugali", "chapo"
        )
        val isPerishable = perishableKeywords.any { lower.contains(it) }

        // High-value detection
        val highValueKeywords = listOf(
            "simu", "phone", "nguo", "shoes", "viatu", "furniture", "meza", "viti",
            "kompyuta", "laptop", "televisheni", "radio", "betri", "solar"
        )
        val isHighValue = highValueKeywords.any { lower.contains(it) }

        // Service vs product
        val serviceKeywords = listOf(
            "kunyolewa", "kusuka", "massage", "kupiga picha", "kufundisha", "kutengeneza",
            "kupaka rangi", "kushona", "kukata nywele", "salon", "barber"
        )
        val isService = serviceKeywords.any { lower.contains(it) }

        understanding.businessSophistication = understanding.businessSophistication.copy(
            productCount = products.size,
            isPerishable = isPerishable,
            isHighValue = isHighValue,
            isService = isService
        )

        // Adjust help priorities based on product type
        if (isPerishable) {
            understanding.helpPriority = understanding.helpPriority.copy(
                featurePriorities = understanding.helpPriority.featurePriorities + "waste_tracking" + "daily_sales"
            )
        }
        if (isHighValue) {
            understanding.helpPriority = understanding.helpPriority.copy(
                featurePriorities = understanding.helpPriority.featurePriorities + "margin_tracking" + "theft_alerts"
            )
        }
        if (products.size >= 5) {
            understanding.helpPriority = understanding.helpPriority.copy(
                featurePriorities = understanding.helpPriority.featurePriorities + "inventory_management"
            )
        }

        Timber.d(TAG, "Product analysis: count=%d, perishable=%s, highValue=%s, service=%s",
            products.size, isPerishable, isHighValue, isService)
    }

    /**
     * Analyze location for market context and infrastructure.
     *
     * Location reveals:
     * - Market type (formal market vs informal)
     * - Competition level (busy market vs solo roadside)
     * - Infrastructure access (electricity, M-Pesa coverage)
     * - Customer type (shoppers vs passersby)
     */
    private fun analyzeLocationContext(response: String, locationType: String) {
        val lower = response.lowercase()

        // Urban vs rural signals
        val urbanKeywords = listOf("nairobi", "mombasa", "kisumu", "nakuru", "eldoret", "thika", "town", "cbd", "downtown")
        val ruralKeywords = listOf("shamba", "kijiji", "village", "nyumbani", "home", "rural", "mbali")
        val isUrban = urbanKeywords.any { lower.contains(it) }
        val isRural = ruralKeywords.any { lower.contains(it) }

        // Named market = established location
        val hasNamedLocation = accumulated.marketName.isNotBlank()

        understanding.marketContext = understanding.marketContext.copy(
            locationType = locationType,
            isUrban = isUrban || (!isRural && locationType == "market"),
            isRural = isRural,
            hasNamedLocation = hasNamedLocation,
            isMobile = locationType == "mobile"
        )

        // Mobile workers need different features
        if (locationType == "mobile") {
            understanding.helpPriority = understanding.helpPriority.copy(
                featurePriorities = understanding.helpPriority.featurePriorities + "route_optimization" + "customer_location_tracking"
            )
        }

        Timber.d(TAG, "Location analysis: type=%s, urban=%s, rural=%s, named=%s",
            locationType, isUrban, isRural, hasNamedLocation)
    }

    /**
     * Analyze work patterns for time management insights.
     *
     * Working hours reveal:
     * - Work ethic (long hours = dedicated)
     * - Time awareness (knows peak hours = sophisticated)
     * - Schedule flexibility (consistent vs variable)
     * - Life balance (overwork risk)
     */
    private fun analyzeWorkPatterns(response: String, hours: WorkingHours) {
        val lower = response.lowercase()
        val totalHours = if (hours.endHour > hours.startHour) {
            hours.endHour - hours.startHour
        } else {
            (24 - hours.startHour) + hours.endHour
        }

        // Work intensity
        val intensity = when {
            totalHours >= 14 -> WorkIntensity.EXTREME // 5am-7pm or similar
            totalHours >= 10 -> WorkIntensity.HIGH
            totalHours >= 6 -> WorkIntensity.NORMAL
            else -> WorkIntensity.LOW
        }

        // Peak hour awareness (sophistication signal)
        val peakAware = lower.contains("peak") || lower.contains("msongamano") ||
            lower.contains("asubuhi") && lower.contains("mchana") ||
            lower.contains("morning") && lower.contains("afternoon")

        understanding.workPatterns = understanding.workPatterns.copy(
            totalHours = totalHours,
            intensity = intensity,
            isConsistent = hours.consistent,
            peakHourAwareness = peakAware,
            daysPerWeek = hours.daysPerWeek
        )

        // Long hours → efficiency tips
        if (intensity == WorkIntensity.EXTREME) {
            understanding.helpPriority = understanding.helpPriority.copy(
                featurePriorities = understanding.helpPriority.featurePriorities + "efficiency_tips" + "rest_reminders"
            )
        }

        Timber.d(TAG, "Work pattern analysis: %dh/day, intensity=%s, peakAware=%s",
            totalHours, intensity, peakAware)
    }

    /**
     * Analyze customer and payment patterns for tech comfort and market access.
     *
     * Customer/payment reveals:
     * - Tech comfort (M-Pesa = digital-ready)
     * - Market sophistication (regulars = relationship builder)
     * - Growth potential (referral-based = word-of-mouth works)
     * - Infrastructure needs (WhatsApp = can receive digital reports)
     */
    private fun analyzeCustomerAndPaymentPatterns(response: String) {
        val lower = response.lowercase()

        // Tech comfort from payment method
        val techComfort = when (accumulated.paymentMethod) {
            PaymentType.MOBILE -> TechComfort.HIGH
            PaymentType.BOTH -> TechComfort.MODERATE
            PaymentType.CASH -> TechComfort.LOW
            PaymentType.BANK -> TechComfort.HIGH
        }

        // WhatsApp detection
        val usesWhatsApp = lower.contains("whatsapp") || lower.contains("wa") || lower.contains("status")

        // Regulars detection (relationship builder)
        val hasRegulars = lower.contains("regular") || lower.contains("mteja wangu") ||
            lower.contains("wateja wangu") || lower.contains("marafiki") ||
            lower.contains("wanakuja") || lower.contains("wanarudi")

        // Customer acquisition sophistication
        val acquisition = when {
            lower.contains("whatsapp") || lower.contains("instagram") || lower.contains("social") -> CustomerAcquisition.DIGITAL
            lower.contains("delivery") || lower.contains("natuma") -> CustomerAcquisition.DELIVERY
            lower.contains("marafiki") || lower.contains("referral") || lower.contains("word") -> CustomerAcquisition.REFERRAL
            lower.contains("wanakuja") || lower.contains("walk") -> CustomerAcquisition.PASSIVE
            else -> CustomerAcquisition.PASSIVE
        }

        understanding.techProfile = understanding.techProfile.copy(
            comfortLevel = techComfort,
            usesWhatsApp = usesWhatsApp,
            usesMPesa = accumulated.paymentMethod == PaymentType.MOBILE || accumulated.paymentMethod == PaymentType.BOTH,
            hasDigitalPresence = acquisition == CustomerAcquisition.DIGITAL
        )

        understanding.customerProfile = understanding.customerProfile.copy(
            hasRegulars = hasRegulars,
            acquisitionMethod = acquisition,
            paymentMethod = accumulated.paymentMethod
        )

        // WhatsApp users can receive digital reports
        if (usesWhatsApp) {
            understanding.helpPriority = understanding.helpPriority.copy(
                reportDelivery = ReportDeliveryMethod.WHATSAPP
            )
        } else if (techComfort == TechComfort.LOW) {
            understanding.helpPriority = understanding.helpPriority.copy(
                reportDelivery = ReportDeliveryMethod.IN_APP
            )
        }

        Timber.d(TAG, "Customer analysis: tech=%s, whatsapp=%s, regulars=%s, acquisition=%s",
            techComfort, usesWhatsApp, hasRegulars, acquisition)
    }

    /**
     * Analyze challenge description for pain points, emotional state, and priorities.
     *
     * This is the MOST revealing response. The way someone describes their
     * problems tells you:
     * - What to prioritize (the first thing mentioned = biggest pain)
     * - Emotional state (stressed? calm? frustrated?)
     * - Problem-solving ability (vague vs specific)
     * - What kind of help they need (data? advice? encouragement?)
     */
    private fun analyzeChallengeDescription(response: String) {
        val lower = response.lowercase()
        val wordCount = lower.split("\\s+".toRegex()).size

        // Pain point classification
        val painPoints = mutableListOf<PainPoint>()

        // Stock/inventory problems
        if (lower.contains("stock") || lower.contains("bidhaa") || lower.contains("hakuna") ||
            lower.contains("isha") || lower.contains("stockout") || lower.contains("haifiki")) {
            painPoints.add(PainPoint.STOCKOUT)
        }

        // Pricing/margin problems
        if (lower.contains("bei") || lower.contains("price") || lower.contains("gharama") ||
            lower.contains("cost") || lower.contains("faida") || lower.contains("profit") ||
            lower.contains("hasara") || lower.contains("loss")) {
            painPoints.add(PainPoint.PRICING)
        }

        // Customer problems
        if (lower.contains("wateja") || lower.contains("customer") || lower.contains("hawana") ||
            lower.contains("wachache") || lower.contains("few") || lower.contains("competition") ||
            lower.contains("mpinzani") || lower.contains("ushindani")) {
            painPoints.add(PainPoint.CUSTOMER_SHORTAGE)
        }

        // Cash flow problems
        if (lower.contains("pesa") || lower.contains("money") || lower.contains("cash") ||
            lower.contains("inaisha") || lower.contains("hela") || lower.contains("mkopo") ||
            lower.contains("loan") || lower.contains("deni") || lower.contains("debt")) {
            painPoints.add(PainPoint.CASH_FLOW)
        }

        // Knowledge problems
        if (lower.contains("sijui") || lower.contains("don't know") || lower.contains("sielevi") ||
            lower.contains("confus") || lower.contains("ngumu") || lower.contains("difficult") ||
            lower.contains("kujua") || lower.contains("kuelewa")) {
            painPoints.add(PainPoint.KNOWLEDGE_GAP)
        }

        // External shocks
        if (lower.contains("mvua") || lower.contains("rain") || lower.contains("jua") ||
            lower.contains("sun") || lower.contains("polisi") || lower.contains("kanjo") ||
            lower.contains("county") || lower.contains("viboko") || lower.contains("mara")) {
            painPoints.add(PainPoint.EXTERNAL_SHOCK)
        }

        if (painPoints.isEmpty()) {
            painPoints.add(PainPoint.GENERAL)
        }

        // Emotional state from language intensity
        val stressIndicators = listOf("sana", "sana sana", "very", "really", "biggest", "kubwa",
            "mbaya", "bad", "ngumu", "hard", "inaniumiza", "hurts", "nateseka", "suffering")
        val stressLevel = stressIndicators.count { lower.contains(it) }

        val emotionalState = when {
            stressLevel >= 3 -> EmotionalState.HIGH_STRESS
            stressLevel >= 1 -> EmotionalState.MODERATE_STRESS
            wordCount >= 15 -> EmotionalState.REFLECTIVE // Long answer = thoughtful
            wordCount <= 3 -> EmotionalState.RESIGNED // Short answer = accepted it
            else -> EmotionalState.CALM
        }

        // Problem-solving sophistication
        val problemSolving = when {
            lower.contains("because") || lower.contains("kwa sababu") || lower.contains("maana") -> ProblemSolving.ANALYTICAL
            lower.contains("try") || lower.contains("jaribu") || lower.contains("nimejaribu") -> ProblemSolving.ACTIVE
            lower.contains("always") || lower.contains("daima") || lower.contains("kila wakati") -> ProblemSolving.PATTERN_AWARE
            wordCount <= 5 -> ProblemSolving.VAGUE
            else -> ProblemSolving.DESCRIPTIVE
        }

        understanding.painPoints = painPoints
        understanding.emotionalState = emotionalState
        understanding.problemSolvingStyle = problemSolving

        // Adjust help priorities based on biggest pain point
        when (painPoints.firstOrNull()) {
            PainPoint.STOCKOUT -> {
                understanding.helpPriority = understanding.helpPriority.copy(
                    primary = HelpFocus.MANAGE_STOCK,
                    immediateAction = "send_low_stock_alerts"
                )
            }
            PainPoint.PRICING -> {
                understanding.helpPriority = understanding.helpPriority.copy(
                    primary = HelpFocus.UNDERSTAND_PROFIT,
                    immediateAction = "send_daily_profit_report"
                )
            }
            PainPoint.CUSTOMER_SHORTAGE -> {
                understanding.helpPriority = understanding.helpPriority.copy(
                    primary = HelpFocus.GROW_BUSINESS,
                    immediateAction = "send_customer_insights"
                )
            }
            PainPoint.CASH_FLOW -> {
                understanding.helpPriority = understanding.helpPriority.copy(
                    primary = HelpFocus.MANAGE_CASH,
                    immediateAction = "send_savings_tips"
                )
            }
            PainPoint.KNOWLEDGE_GAP -> {
                understanding.helpPriority = understanding.helpPriority.copy(
                    primary = HelpFocus.TRACK_SALES,
                    immediateAction = "start_with_simple_tracking"
                )
            }
            else -> {}
        }

        // Stressed workers need encouragement first, data second
        if (emotionalState == EmotionalState.HIGH_STRESS) {
            understanding.communicationStyle = understanding.communicationStyle.copy(
                preferredTone = Tone.ENCOURAGING
            )
        }

        Timber.d(TAG, "Challenge analysis: painPoints=%s, emotional=%s, problemSolving=%s",
            painPoints, emotionalState, problemSolving)
    }

    /**
     * Final synthesis — combine all understanding into actionable intelligence.
     * This runs AFTER all responses are collected.
     */
    private fun deriveFinalUnderstanding() {
        // Determine worker archetype
        understanding.archetype = when {
            understanding.businessSophistication.maturity == BusinessMaturity.NEW &&
                understanding.techProfile.comfortLevel == TechComfort.LOW -> WorkerArchetype.NEW_TRADITIONAL

            understanding.businessSophistication.maturity == BusinessMaturity.NEW &&
                understanding.techProfile.comfortLevel >= TechComfort.MODERATE -> WorkerArchetype.NEW_DIGITAL

            understanding.businessSophistication.maturity == BusinessMaturity.GROWING &&
                understanding.businessSophistication.specificity == BusinessSpecificity.DETAILED -> WorkerArchetype.GROWING_SOPHISTICATED

            understanding.businessSophistication.maturity == BusinessMaturity.GROWING -> WorkerArchetype.GROWING_BASIC

            understanding.businessSophistication.maturity == BusinessMaturity.STRUGGLING &&
                understanding.emotionalState == EmotionalState.HIGH_STRESS -> WorkerArchetype.STRUGGLING_STRESSED

            understanding.businessSophistication.maturity == BusinessMaturity.STRUGGLING -> WorkerArchetype.STRUGGLING_RESILIENT

            understanding.businessSophistication.maturity == BusinessMaturity.ESTABLISHED -> WorkerArchetype.ESTABLISHED

            else -> WorkerArchetype.UNKNOWN
        }

        // Determine report type
        understanding.helpPriority = understanding.helpPriority.copy(
            reportType = when (understanding.archetype) {
                WorkerArchetype.NEW_TRADITIONAL -> ReportType.SIMPLE_DAILY
                WorkerArchetype.NEW_DIGITAL -> ReportType.INTERACTIVE
                WorkerArchetype.GROWING_SOPHISTICATED -> ReportType.DETAILED_WEEKLY
                WorkerArchetype.GROWING_BASIC -> ReportType.DAILY_WITH_TIPS
                WorkerArchetype.STRUGGLING_STRESSED -> ReportType.ENCOURAGING_DAILY
                WorkerArchetype.STRUGGLING_RESILIENT -> ReportType.ACTIONABLE_DAILY
                WorkerArchetype.ESTABLISHED -> ReportType.COMPREHENSIVE
                WorkerArchetype.UNKNOWN -> ReportType.SIMPLE_DAILY
            }
        )

        // Determine greeting style
        understanding.greetingStyle = when (understanding.relationshipType) {
            RelationshipType.FRIEND -> "Habari ${accumulated.workerName}! ${accumulated.msaidiziName} hapa."
            RelationshipType.ADVISOR -> "Habari ${accumulated.workerName}. Ripoti yako ya leo:"
            RelationshipType.BUSINESS_PARTNER -> "${accumulated.workerName}, hapa kile kilichotokea leo:"
            RelationshipType.FAMILY -> "Habari ${accumulated.workerName}! Leo imekuwaje?"
            RelationshipType.PRAGMATIC -> "Ripoti ya leo:"
            RelationshipType.CREATIVE -> "Heeey ${accumulated.workerName}! ${accumulated.msaidiziName} ana update! 🎉"
        }

        Timber.i(TAG, "Final understanding: archetype=%s, primaryHelp=%s, reportType=%s, tone=%s",
            understanding.archetype, understanding.helpPriority.primary,
            understanding.helpPriority.reportType, understanding.communicationStyle.preferredTone)
    }

    // ═══════════════════════════════════════════════════════════════
    // BAYESIAN CLASSIFICATION (STA 142)
    // ═══════════════════════════════════════════════════════════════

    private fun classifyFromResponse(response: String): ClassificationResult {
        val lower = response.lowercase()
        val likelihoods = mutableMapOf<WorkerType, Double>()

        likelihoods[WorkerType.TRADER] = calculateLikelihood(lower, listOf(
            "nauza", "duka", "shop", "sokoni", "market", "mboga", "mama mboga",
            "mitumba", "hawker", "biashara", "nunua", "stock", "wholesale",
            "retail", "mali", "bidhaa", "soko", "ninauza", "ninunua"
        ))
        likelihoods[WorkerType.TRANSPORT] = calculateLikelihood(lower, listOf(
            "boda", "pikipiki", "matatu", "abiria", "taxi", "uber", "nduthi",
            "delivery", "usafiri", "tuk-tuk", "bajaj", "mzunguko", "gari", "moto"
        ))
        likelihoods[WorkerType.FARMER] = calculateLikelihood(lower, listOf(
            "shamba", "kulima", "mazao", "mbegu", "mbolea", "mvua", "harvest",
            "mkulima", "mahindi", "ngano", "kahawa", "chai", "nyuki", "kuku", "mifugo"
        ))
        likelihoods[WorkerType.SERVICE] = calculateLikelihood(lower, listOf(
            "salon", "kunyolewa", "haircut", "fundi", "mechanic", "repair",
            "service", "mteja", "client", "massage", "dawa", "clinic", "huduma"
        ))
        likelihoods[WorkerType.MANUFACTURING] = calculateLikelihood(lower, listOf(
            "tengeneza", "chuma", "mbao", "welding", "furniture", "nguo",
            "ushona", "tailor", "maker", "jua kali", "karai", "tofali", "ufundi"
        ))
        likelihoods[WorkerType.DIGITAL] = calculateLikelihood(lower, listOf(
            "mpesa", "float", "airtime", "agent", "online", "social media",
            "instagram", "tiktok", "gig", "simu", "computer", "digital"
        ))

        val posteriors = mutableMapOf<WorkerType, Double>()
        var evidence = 0.0
        for (type in WorkerType.values()) {
            if (type == WorkerType.UNKNOWN) continue
            posteriors[type] = (likelihoods[type] ?: 0.0) * (businessTypePriors[type] ?: 0.0)
            evidence += posteriors[type]!!
        }
        if (evidence > 0) {
            for (type in posteriors.keys) posteriors[type] = posteriors[type]!! / evidence
        }
        for (type in posteriors.keys) businessTypePriors[type] = posteriors[type]!!

        val best = posteriors.maxByOrNull { it.value }
        return ClassificationResult(best?.key ?: WorkerType.UNKNOWN, best?.value ?: 0.0)
    }

    private fun calculateLikelihood(text: String, keywords: List<String>): Double {
        val matches = keywords.count { text.contains(it) }
        return if (keywords.isEmpty()) 0.0 else (matches.toDouble() / keywords.size).coerceIn(0.0, 1.0)
    }

    // ═══════════════════════════════════════════════════════════════
    // NATURAL LANGUAGE EXTRACTION
    // ═══════════════════════════════════════════════════════════════

    private fun extractName(response: String): String {
        val cleaned = response.trim()
        if (cleaned.isBlank()) return "Rafiki"
        Regex("(?i)mimi\\s+ni\\s+(\\S+)").find(cleaned)?.let { return it.groupValues[1].replaceFirstChar { c -> c.uppercase() } }
        Regex("(?i)jina\\s+langu\\s+(?:ni\\s+)?(\\S+)").find(cleaned)?.let { return it.groupValues[1].replaceFirstChar { c -> c.uppercase() } }
        Regex("(?i)naitwa\\s+(\\S+)").find(cleaned)?.let { return it.groupValues[1].replaceFirstChar { c -> c.uppercase() } }
        Regex("(?i)(?:i am|my name is)\\s+(\\S+)").find(cleaned)?.let { return it.groupValues[1].replaceFirstChar { c -> c.uppercase() } }
        return cleaned.split("\\s+".toRegex()).first().replaceFirstChar { it.uppercase() }
    }

    private fun extractAgentName(response: String): String {
        val cleaned = response.trim()
        if (cleaned.isBlank()) return "Msaidizi"
        val lower = cleaned.lowercase()
        if (lower in listOf("hapana", "no", "si", "msaidizi", "la", "aah", "siwezi", "sijui")) return "Msaidizi"
        Regex("(?i)(?:uniite|niite)\\s+(.+)").find(cleaned)?.let { return it.groupValues[1].trim().split("\\s+".toRegex()).joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } } }
        Regex("(?i)jina\\s+(?:la(?:ke)?\\s+)?ni\\s+(.+)").find(cleaned)?.let { return it.groupValues[1].trim().split("\\s+".toRegex()).joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } } }
        return cleaned.split("\\s+".toRegex()).joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun extractProducts(response: String): List<String> {
        val cleaned = response.lowercase()
            .replace(Regex("nauza|ninauza|ninunua|ninanunua|biashara yangu ni|nauzia"), "")
            .replace(Regex("\\bna\\b|,|\\."), " ").trim()
        return cleaned.split(Regex("\\s+")).filter { it.length > 2 }.distinct().take(10)
    }

    private fun extractLocation(response: String): String {
        val lower = response.lowercase()
        return when {
            lower.contains("sokoni") || lower.contains("market") -> "market"
            lower.contains("barabarani") || lower.contains("roadside") -> "roadside"
            lower.contains("nyumbani") || lower.contains("home") -> "home"
            lower.contains("natembea") || lower.contains("mobile") || lower.contains("hawker") -> "mobile"
            else -> response.trim()
        }
    }

    private fun extractMarketName(response: String): String {
        Regex("(?i)soko\\s+(?:la\\s+)?(\\w+)").find(response)?.let { return it.groupValues[1] }
        Regex("(?i)market\\s+(?:ya\\s+)?(\\w+)").find(response)?.let { return it.groupValues[1] }
        return ""
    }

    private fun extractCustomerMethod(response: String): String {
        val lower = response.lowercase()
        return when {
            lower.contains("wanakuja") || lower.contains("come") -> "walk-in"
            lower.contains("simu") || lower.contains("phone") -> "phone"
            lower.contains("whatsapp") || lower.contains("social") -> "social media"
            lower.contains("delivery") || lower.contains("natuma") -> "delivery"
            lower.contains("marafiki") || lower.contains("referral") -> "referral"
            else -> response.trim().take(100)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PARSING
    // ═══════════════════════════════════════════════════════════════

    private fun parseWorkingHours(response: String): WorkingHours {
        val lower = response.lowercase()
        val timePattern = Regex("(\\d{1,2})[.:]?\\s*(?:am|pm|asubuhi|mchana|jioni|usiku)?")
        val times = timePattern.findAll(lower).map { it.groupValues[1].toIntOrNull() }.filterNotNull().toList()
        val startHour = times.firstOrNull() ?: 6
        val endHour = if (times.size >= 2) times[1] else 18
        return WorkingHours(
            startHour = startHour.coerceIn(0, 23), endHour = endHour.coerceIn(0, 23),
            consistent = !lower.contains("wakati mwingine") && !lower.contains("sometimes"),
            daysPerWeek = if (lower.contains("kila siku") || lower.contains("every day")) 7 else 6,
            description = response
        )
    }

    private fun parsePaymentMethod(response: String): PaymentType {
        val lower = response.lowercase()
        return when {
            lower.contains("mpesa") && (lower.contains("cash") || lower.contains("pesa") || lower.contains("zote")) -> PaymentType.BOTH
            lower.contains("mpesa") || lower.contains("mobile") -> PaymentType.MOBILE
            lower.contains("bank") || lower.contains("benki") -> PaymentType.BANK
            lower.contains("zote") || lower.contains("both") -> PaymentType.BOTH
            else -> PaymentType.CASH
        }
    }

    private fun detectLanguage(response: String): String {
        val lower = response.lowercase()
        val sw = listOf("mimi", "ni", "na", "ya", "kwa", "sana", "nzuri", "sawa", "habari", "naitwa", "jina")
        val en = listOf("i", "am", "my", "name", "the", "and", "is", "good", "hello", "what")
        return if (sw.count { lower.contains(it) } > en.count { lower.contains(it) }) "sw" else "en"
    }

    // ═══════════════════════════════════════════════════════════════
    // SUMMARY & INSIGHT GENERATION — Uses UNDERSTANDING, not just data
    // ═══════════════════════════════════════════════════════════════

    private fun generateSummary(): String {
        val parts = mutableListOf<String>()
        val name = accumulated.workerName
        val agentName = accumulated.msaidiziName

        if (language == "sw") {
            parts.add("• Jina lako: $name")
            parts.add("• Biashara: ${accumulated.businessDescription.take(60)}")
            if (accumulated.products.isNotEmpty()) parts.add("• Bidhaa: ${accumulated.products.take(3).joinToString(", ")}")
            parts.add("• Mahali: ${accumulated.location}")
            parts.add("• Masaa: ${accumulated.workingHours.description.take(40)}")
            if (accumulated.biggestChallenge.isNotBlank()) parts.add("• Changamoto: ${accumulated.biggestChallenge.take(60)}")

            // Understanding-based additions
            if (understanding.techProfile.usesWhatsApp) parts.add("• Mawasiliano: WhatsApp ✓")
            if (understanding.techProfile.usesMPesa) parts.add("• Malipo: M-Pesa ✓")
            if (understanding.customerProfile.hasRegulars) parts.add("• Wateja wa kudumu ✓")
        } else {
            parts.add("• Your name: $name")
            parts.add("• Business: ${accumulated.businessDescription.take(60)}")
            if (accumulated.products.isNotEmpty()) parts.add("• Products: ${accumulated.products.take(3).joinToString(", ")}")
            parts.add("• Location: ${accumulated.location}")
            parts.add("• Hours: ${accumulated.workingHours.description.take(40)}")
            if (accumulated.biggestChallenge.isNotBlank()) parts.add("• Challenge: ${accumulated.biggestChallenge.take(60)}")
        }

        return parts.joinToString("\n")
    }

    /**
     * Generate the first insight — powered by UNDERSTANDING, not just data.
     *
     * This isn't generic advice. It's specific to THIS worker's situation,
     * based on what we UNDERSTOOD from the conversation.
     */
    private fun generateFirstInsight(): String {
        val agentName = accumulated.msaidiziName
        val name = accumulated.workerName
        val u = understanding

        // Start with the primary help focus
        val primaryHelp = when (u.helpPriority.primary) {
            HelpFocus.TRACK_SALES -> if (language == "sw") {
                "Kuanzia leo, $agentName atakusaidia kufuatilia kila mauzo — utajua faida yako ya kila siku."
            } else {
                "From today, $agentName will track every sale — you'll know your daily profit."
            }

            HelpFocus.MANAGE_STOCK -> if (language == "sw") {
                "$agentName atakufuatilia stock yako — atakuambia bidhaa gani inaisha na lini unapaswa kununua zaidi."
            } else {
                "$agentName will track your stock — tell you what's running low and when to restock."
            }

            HelpFocus.UNDERSTAND_PROFIT -> if (language == "sw") {
                "Sijui faida yako? $agentName atakokokotoa kila siku — utajua pesa unayopata na unayopoteza."
            } else {
                "Don't know your profit? $agentName will calculate it daily — you'll know what you earn and lose."
            }

            HelpFocus.MANAGE_CASH -> if (language == "sw") {
                "$agentName atakusaidia kufuatilia pesa inayoingia na kutoka — utajua wapi pesa yako inaenda."
            } else {
                "$agentName will track money in and out — you'll know where your money goes."
            }

            HelpFocus.GROW_BUSINESS -> if (language == "sw") {
                "$agentName atakusaidia kuona fursa za kukua — bidhaa gani uuze zaidi, na wakati gani."
            } else {
                "$agentName will help you spot growth opportunities — what to sell more, and when."
            }

            HelpFocus.OPTIMIZE_OPERATIONS -> if (language == "sw") {
                "$agentName atakusaidia kuboresha biashara yako — masaa bora, bei nzuri, na wateja wazuri."
            } else {
                "$agentName will help optimize your business — best hours, best prices, best customers."
            }
        }

        // Add emotional context if stressed
        val emotionalSupport = if (u.emotionalState == EmotionalState.HIGH_STRESS) {
            if (language == "sw") {
                "\n\n$name, usijali — changamoto zote zina suluhisho. $agentName atakuwa nawe kila siku."
            } else {
                "\n\n$name, don't worry — every challenge has a solution. $agentName will be with you every day."
            }
        } else ""

        // Add tech-specific guidance
        val techGuidance = if (u.techProfile.usesWhatsApp && u.helpPriority.reportDelivery == ReportDeliveryMethod.WHATSAPP) {
            if (language == "sw") {
                "\n\n$agentName atakutumia ripoti kupitia WhatsApp kila siku."
            } else {
                "\n\n$agentName will send you reports via WhatsApp every day."
            }
        } else ""

        return primaryHelp + emotionalSupport + techGuidance
    }

    private fun getEncouragement(): String {
        return if (language == "sw") "Nzuri sana" else "That's great"
    }

    private fun getFollowUpHint(type: WorkerType): String {
        return when (type) {
            WorkerType.TRADER -> if (language == "sw") "Kwa mfano: mboga, matunda, nguo..." else "For example: vegetables, clothes..."
            WorkerType.TRANSPORT -> if (language == "sw") "Kwa mfano: abiria, mzigo, delivery..." else "For example: passengers, cargo..."
            WorkerType.FARMER -> if (language == "sw") "Kwa mfano: mahindi, maharagwe, nyanya..." else "For example: maize, beans, tomatoes..."
            WorkerType.SERVICE -> if (language == "sw") "Kwa mfano: kusuka nywele, kukata nywele..." else "For example: braiding, haircut..."
            WorkerType.MANUFACTURING -> if (language == "sw") "Kwa mfano: meza, viti, nguo, tofali..." else "For example: tables, chairs, bricks..."
            WorkerType.DIGITAL -> if (language == "sw") "Kwa mfano: M-Pesa float, airtime, simu..." else "For example: M-Pesa float, airtime..."
            WorkerType.UNKNOWN -> ""
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PROFILE BUILDING
    // ═══════════════════════════════════════════════════════════════

    fun buildProfile(): WorkerProfile {
        return WorkerProfile(
            msaidiziName = accumulated.msaidiziName,
            workerName = accumulated.workerName,
            businessType = accumulated.businessType ?: WorkerType.UNKNOWN,
            businessDescription = accumulated.businessDescription,
            products = accumulated.products,
            location = accumulated.location,
            marketName = accumulated.marketName,
            workingHours = accumulated.workingHours,
            workAlone = true,
            supplyMethod = "",
            customerFindMethod = accumulated.customerFindMethod,
            paymentMethod = accumulated.paymentMethod,
            keepsRecords = RecordMethod.MEMORY,
            biggestChallenge = accumulated.biggestChallenge,
            usesWhatsApp = understanding.techProfile.usesWhatsApp,
            whatsappPhone = "",
            language = accumulated.language,
            dialect = "STANDARD",
            classificationConfidence = accumulated.classificationConfidence,
            conversationTurns = accumulated.conversationTurns,
            onboardingCompletedAt = System.currentTimeMillis()
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// BOOTSTRAP STEPS (sealed class)
// ═══════════════════════════════════════════════════════════════

sealed class BootstrapStep {
    data class Greeting(val prompt: String) : BootstrapStep()
    data class AskAgentNaming(val prompt: String) : BootstrapStep()
    data class AskBusinessType(val prompt: String) : BootstrapStep()
    data class AskProducts(val prompt: String, val hint: String = "") : BootstrapStep()
    data class AskLocation(val prompt: String) : BootstrapStep()
    data class AskWorkingHours(val prompt: String) : BootstrapStep()
    data class AskCustomersAndPayment(val prompt: String) : BootstrapStep()
    data class AskChallenges(val prompt: String) : BootstrapStep()
    data class Summary(val prompt: String) : BootstrapStep()
    data class AskPin(val prompt: String) : BootstrapStep()
    data class Complete(val profile: WorkerProfile, val readyMessage: String, val agentName: String = "Msaidizi") : BootstrapStep()
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

data class ClassificationResult(val type: WorkerType, val confidence: Double)

class AccumulatedData {
    var workerName: String = "Rafiki"
    var businessDescription: String = ""
    var businessType: WorkerType? = null
    var classificationConfidence: Double = 0.0
    var products: List<String> = emptyList()
    var location: String = ""
    var marketName: String = ""
    var workingHours: WorkingHours = WorkingHours()
    var customerFindMethod: String = ""
    var paymentMethod: PaymentType = PaymentType.BOTH
    var biggestChallenge: String = ""
    var msaidiziName: String = "Msaidizi"
    var language: String = "sw"
    var conversationTurns: Int = 0
    var pin: String = ""
}
