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
 * ## The OpenClaw Philosophy
 *
 * Like OpenClaw's bootstrap, the agent introduces ITSELF first.
 * It's not a form asking for data — it's a conversation between
 * two people getting to know each other.
 *
 * ## Conversation Flow (10 Turns, ~3-5 min)
 *
 * 1. **Agent introduces itself** — "Mimi ni Msaidizi — CFO wako wa biashara..."
 *    → Learns worker name, analyzes communication style
 *
 * 2. **Agent responds with personality** — Uses name warmly, offers naming
 *    → "Mama Amina! Sawa. Sasa unaweza kuniita jina lolote..."
 *    → Learns what relationship the worker wants
 *
 * 3. **Ask about business — ADAPTIVELY** — Not generic, responds to specifics
 *    → "Mboga gani haswa? Nyanya? Vitunguu? Kwa sababu soko la nyanya..."
 *    → Learns business type, classifies with Bayesian inference
 *
 * 4-8. **Adaptive conversation** — Each response shows understanding in real-time
 *    → Follows up on specifics mentioned
 *    → Demonstrates knowledge of their market
 *
 * 9. **Summary with SPECIFIC insights** — Not generic, shows real understanding
 *    → "Ukizungumza nyanya 3 kwa siku, na bei ya soko ni KSh 50..."
 *    → Uses WorkerUnderstanding.toHumanSummary() for personalized output
 *
 * 10. **PIN setup** — Voice-based, simple
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

    /** Public accessor for PIN (set during voice onboarding) */
    val pin: String get() = accumulated.pin

    // Derived understanding — this is what makes it a CONVERSATION, not a form
    val understanding = WorkerUnderstanding()

    // Whether worker responded in English (detected from first response)
    private var language = "sw"

    /**
     * Get the first prompt — the agent introduces ITSELF first.
     *
     * Turn 1: Agent introduces itself, then asks the worker's name.
     * This follows the OpenClaw pattern: the agent speaks first.
     */
    fun getGreetingPrompt(): String {
        return "Mimi ni Msaidizi — CFO wako wa biashara. Niko hapa kukusaidia " +
            "kufuatilia pesa yako, kuelewa biashara yako, na kukupa ushauri.\n\n" +
            "Sasa, nieleze — jina lako ni nani?"
    }

    /**
     * Process the worker's response for the current step and return the next prompt.
     *
     * Every response is processed in TWO ways:
     * 1. DATA extraction — what fields to fill
     * 2. UNDERSTANDING analysis — what this reveals about the worker
     *
     * Every prompt is ADAPTIVE — it references what the worker already said,
     * demonstrating understanding in real-time.
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
            is BootstrapStep.AskAgentNaming -> 1f / 10f
            is BootstrapStep.AskBusinessType -> 2f / 10f
            is BootstrapStep.AskProducts -> 3f / 10f
            is BootstrapStep.AskLocation -> 4f / 10f
            is BootstrapStep.AskWorkingHours -> 5f / 10f
            is BootstrapStep.AskCustomersAndPayment -> 6f / 10f
            is BootstrapStep.AskChallenges -> 7f / 10f
            is BootstrapStep.Summary -> 8f / 10f
            is BootstrapStep.AskPin -> 9f / 10f
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
    // STEP HANDLERS — Adaptive, personality-driven conversation
    // ═══════════════════════════════════════════════════════════════

    /**
     * Turn 1: Worker tells us their name.
     *
     * Agent responds with WARMTH and personality — not a flat "OK".
     * Shows the worker this is a real conversation, not a form.
     *
     * ANALYZE: Communication style from self-introduction
     *   - "Naitwa Mary" → standard Swahili, moderate comfort
     *   - "Mary" → confident, direct, possibly tech-savvy
     *   - "Mimi ni Mary Wanjiku" → formal, provides full name
     */
    private fun handleGreeting(response: String): BootstrapStep {
        accumulated.workerName = extractName(response)
        analyzeIntroductionStyle(response)

        val name = accumulated.workerName
        understanding.businessContext = "Worker name: $name"

        // Turn 2: Agent responds with personality, offers naming
        val prompt = if (language == "sw") {
            "Mama $name! Sawa. Sasa unaweza kuniita jina lolote unalotaka — " +
                "au kuniaita Msaidizi. Jina lako lina nguvu!\n\n" +
                "Ungependa uniite nani?"
        } else {
            "Mama $name! Great. Now you can call me any name you want — " +
                "or just call me Msaidizi. Your name has strength!\n\n" +
                "What would you like to call me?"
        }

        return BootstrapStep.AskAgentNaming(prompt = prompt)
    }

    /**
     * Turn 2: Worker names their Msaidizi. THE OPENCLAW MOMENT.
     *
     * Agent responds with DELIGHT — not just "OK". Shows the name
     * has emotional significance.
     *
     * ANALYZE: What the naming choice reveals about relationship expectations
     *   - "Rafiki" (friend) → wants warmth, personal connection
     *   - "Mshauri" (advisor) → wants professional guidance
     *   - Creative/funny → playful personality
     */
    private fun handleAgentNaming(response: String): BootstrapStep {
        val agentName = extractAgentName(response)
        accumulated.msaidiziName = agentName
        understanding.agentName = agentName
        analyzeNamingChoice(response, agentName)

        Timber.d(TAG, "Agent named: '%s' by %s", agentName, accumulated.workerName)

        // Agent responds with genuine delight
        val delight = if (language == "sw") "$agentName! Napenda jina hilo sana."
        else "$agentName! I love that name."

        val name = accumulated.workerName
        val prompt = if (language == "sw") {
            "$delight $name, sasa $agentName anataka kukujua zaidi — unafanya biashara gani? " +
                "Elezea kwa undani."
        } else {
            "$delight $name, now $agentName wants to know more — what business do you do? " +
                "Describe it in detail."
        }

        return BootstrapStep.AskBusinessType(prompt = prompt)
    }

    /**
     * Turn 3: Worker describes their business — ADAPTIVELY.
     *
     * Instead of a generic "Great!", the agent responds to SPECIFIC details.
     * If they say "mboga", ask follow-up: "Mboga gani haswa? Nyanya? Vitunguu?"
     * This shows the agent is LISTENING, not just collecting data.
     *
     * ANALYZE: Business sophistication, maturity, self-identity
     */
    private fun handleBusinessType(response: String): BootstrapStep {
        val classification = classifyFromResponse(response)
        accumulated.businessDescription = response
        accumulated.businessType = classification.type
        accumulated.classificationConfidence = classification.confidence

        // ANALYZE: Business sophistication
        analyzeBusinessDescription(response, classification.type)

        // Build ADAPTIVE response based on what they said
        val lower = response.lowercase()
        val adaptivePrefix = buildAdaptiveBusinessResponse(lower, classification.type)

        val prompt = if (language == "sw") {
            "$adaptivePrefix\n\n" +
                "Niuzia nini haswa? Vitu gani unauza zaidi?"
        } else {
            "$adaptivePrefix\n\n" +
                "What exactly do you sell? What are your main products?"
        }

        return BootstrapStep.AskProducts(
            prompt = prompt,
            hint = getFollowUpHint(classification.type)
        )
    }

    /**
     * Build an adaptive response to business description.
     * References SPECIFIC things the worker mentioned.
     */
    private fun buildAdaptiveBusinessResponse(lower: String, type: WorkerType?): String {
        val name = accumulated.workerName

        // Extract specific mentions for follow-up
        val mentionedItems = extractSpecificItems(lower)
        if (mentionedItems.isNotEmpty()) {
            understanding.mentionedProducts = mentionedItems
        }

        return when {
            // Trader mentions specific products
            type == WorkerType.TRADER && mentionedItems.isNotEmpty() -> {
                val items = mentionedItems.take(2).joinToString(" na ")
                if (language == "sw") {
                    "$name, $items — nzuri sana! Bidhaa hizi zina soko kubwa."
                } else {
                    "$name, $items — great! These products have a big market."
                }
            }
            // Trader mentions mboga — follow up specifically
            type == WorkerType.TRADER && lower.contains("mboga") -> {
                if (language == "sw") {
                    "Mboga! Soko la mboga linaabadilika sana. $name, " +
                        "mboga gani haswa? Nyanya? Sukuma? Vitunguu?"
                } else {
                    "Vegetables! The vegetable market changes a lot. $name, " +
                        "which vegetables exactly? Tomatoes? Kale? Onions?"
                }
            }
            // Transport
            type == WorkerType.TRANSPORT -> {
                if (language == "sw") {
                    "$name, biashara ya usafiri — ni kazi ngumu lakini ina faida nzuri!"
                } else {
                    "$name, transport business — hard work but good returns!"
                }
            }
            // Farmer
            type == WorkerType.FARMER -> {
                if (language == "sw") {
                    "$name, kilimo — msingi wa uchumi! Mazao yako yanategemea mvua?"
                } else {
                    "$name, farming — the backbone of the economy! Do your crops depend on rain?"
                }
            }
            // Service
            type == WorkerType.SERVICE -> {
                if (language == "sw") {
                    "$name, huduma — biashara inayotegemea ujuzi wako!"
                } else {
                    "$name, services — a business that depends on your skills!"
                }
            }
            // Manufacturing
            type == WorkerType.MANUFACTURING -> {
                if (language == "sw") {
                    "$name, utengenezaji — unatengeneza mwenyewe? Hiyo ni ujuzi mkubwa!"
                } else {
                    "$name, manufacturing — you make things yourself? That's real skill!"
                }
            }
            // Digital
            type == WorkerType.DIGITAL -> {
                if (language == "sw") {
                    "$name, biashara ya kidijitali — wakati ujao uko hapa!"
                } else {
                    "$name, digital business — the future is here!"
                }
            }
            // Generic
            else -> {
                if (language == "sw") {
                    "${getEncouragement()}! Biashara yako inaonekana nzuri."
                } else {
                    "${getEncouragement()}! Your business looks good."
                }
            }
        }
    }

    /**
     * Extract specific items/products mentioned in the response.
     */
    private fun extractSpecificItems(lower: String): List<String> {
        val productKeywords = listOf(
            "nyanya", "sukuma", "vitunguu", "mboga", "matunda", "ndizi", "embe",
            "machungwa", "maembe", "nanasi", "mihogo", "viazi", "maharagwe",
            "mahindi", "ngano", "mchele", "unga", "mafuta", "chumvi",
            "nguo", "viatu", "simu", "betri", "mavazi", "kofia",
            "mandazi", "chapati", "maandazi", "chipsi", "nyama", "samaki",
            "maziwa", "mayai", "mkate", "kahawa", "chai", "juice"
        )
        return productKeywords.filter { lower.contains(it) }
            .map { it.replaceFirstChar { c -> c.uppercase() } }
    }

    /**
     * Turn 4: Products — adaptive to what they listed.
     */
    private fun handleProducts(response: String): BootstrapStep {
        accumulated.products = extractProducts(response)
        understanding.mentionedProducts = accumulated.products

        // ANALYZE: Product mix
        analyzeProductMix(response, accumulated.products)

        // Adaptive: reference their products
        val productRef = if (accumulated.products.size >= 3) {
            accumulated.products.take(2).joinToString(" na ") + " na mengine"
        } else if (accumulated.products.isNotEmpty()) {
            accumulated.products.joinToString(" na ")
        } else {
            ""
        }

        val prompt = if (language == "sw") {
            if (productRef.isNotEmpty()) {
                "$productRef — bidhaa nzuri! Unauzia wapi? Sokoni, barabarani, nyumbani, au unatembea?"
            } else {
                "Sawa! Unauzia wapi? Sokoni, barabarani, nyumbani, au unatembea?"
            }
        } else {
            if (productRef.isNotEmpty()) {
                "$productRef — good products! Where do you sell? At the market, roadside, home, or mobile?"
            } else {
                "Okay! Where do you sell? At the market, roadside, home, or mobile?"
            }
        }

        return BootstrapStep.AskLocation(prompt = prompt)
    }

    /**
     * Turn 5: Location — adaptive to market vs roadside vs home.
     */
    private fun handleLocation(response: String): BootstrapStep {
        accumulated.location = extractLocation(response)
        accumulated.marketName = extractMarketName(response)
        understanding.mentionedLocation = accumulated.location

        // ANALYZE: Market context
        analyzeLocationContext(response, accumulated.location)

        // Adaptive: respond differently for market vs roadside vs home
        val locationResponse = buildAdaptiveLocationResponse()

        val prompt = if (language == "sw") {
            "$locationResponse\n\nUnafanya kazi masaa gani? Unaanza lini, unaisha lini?"
        } else {
            "$locationResponse\n\nWhat hours do you work? When do you start, when do you finish?"
        }

        return BootstrapStep.AskWorkingHours(prompt = prompt)
    }

    private fun buildAdaptiveLocationResponse(): String {
        val loc = accumulated.location
        val market = accumulated.marketName

        return when {
            loc == "market" && market.isNotBlank() -> {
                if (language == "sw") "Soko la $market — mahali pazuri! Una wateja wengi huko?"
                else "Market $market — great location! Do you have many customers there?"
            }
            loc == "market" -> {
                if (language == "sw") "Sokoni — mahali pazuri pa biashara!"
                else "At the market — great place for business!"
            }
            loc == "roadside" -> {
                if (language == "sw") "Barabarani — unaona wateja wengi kupita!"
                else "Roadside — you see many customers passing!"
            }
            loc == "home" -> {
                if (language == "sw") "Nyumbani — gharama ndogo, wateja wanajua kukupata!"
                else "At home — low costs, customers know where to find you!"
            }
            loc == "mobile" -> {
                if (language == "sw") "Unatembea — kazi ngumu lakini una uhuru!"
                else "You're mobile — hard work but you have freedom!"
            }
            else -> {
                if (language == "sw") "Sawa!"
                else "Okay!"
            }
        }
    }

    /**
     * Turn 6: Working hours — adaptive to work intensity.
     */
    private fun handleWorkingHours(response: String): BootstrapStep {
        accumulated.workingHours = parseWorkingHours(response)

        // ANALYZE: Work patterns
        analyzeWorkPatterns(response, accumulated.workingHours)

        // Adaptive: comment on their hours
        val hoursComment = buildHoursComment()

        val prompt = if (language == "sw") {
            "$hoursComment\n\nSasa nieleze — wateja wako wanakujaje? " +
                "Wanalipaje — M-Pesa, pesa taslimu, au zote mbili?"
        } else {
            "$hoursComment\n\nHow do your customers find you? " +
                "How do they pay — M-Pesa, cash, or both?"
        }

        return BootstrapStep.AskCustomersAndPayment(prompt = prompt)
    }

    private fun buildHoursComment(): String {
        val hours = accumulated.workingHours
        val total = if (hours.endHour > hours.startHour) hours.endHour - hours.startHour
        else (24 - hours.startHour) + hours.endHour

        return when {
            total >= 14 -> if (language == "sw") "Masaa mengi! $total saa — ni kazi ngumu."
            else "Long hours! $total hours — that's hard work."
            total >= 10 -> if (language == "sw") "Masaa mazuri — $total saa za kazi."
            else "Good hours — $total hours of work."
            else -> if (language == "sw") "Sawa."
            else "Okay."
        }
    }

    /**
     * Turn 7: Customers and payment — adaptive to tech comfort.
     */
    private fun handleCustomersAndPayment(response: String): BootstrapStep {
        accumulated.customerFindMethod = extractCustomerMethod(response)
        accumulated.paymentMethod = parsePaymentMethod(response)

        // ANALYZE: Tech comfort
        analyzeCustomerAndPaymentPatterns(response)

        // Adaptive: respond based on payment method
        val paymentComment = buildPaymentComment()

        val prompt = if (language == "sw") {
            "$paymentComment\n\nSasa — changamoto kubwa zaidi ya biashara yako ni ipi? " +
                "Ni kitu gani kinakusumbua zaidi?"
        } else {
            "$paymentComment\n\nNow — what's the biggest challenge in your business? " +
                "What bothers you most?"
        }

        return BootstrapStep.AskChallenges(prompt = prompt)
    }

    private fun buildPaymentComment(): String {
        return when (accumulated.paymentMethod) {
            PaymentType.MOBILE -> if (language == "sw") "M-Pesa — nzuri! Rahisi na salama."
            else "M-Pesa — great! Easy and safe."
            PaymentType.BOTH -> if (language == "sw") "M-Pesa na pesa taslimu — una uhuru wa kuchagua!"
            else "M-Pesa and cash — you have the freedom to choose!"
            PaymentType.BANK -> if (language == "sw") "Benki — una mfumo mzuri wa malipo!"
            else "Bank — you have a good payment system!"
            PaymentType.CASH -> if (language == "sw") "Pesa taslimu — rahisi na ya moja kwa moja."
            else "Cash — simple and direct."
        }
    }

    /**
     * Turn 8: Challenges — the most revealing response.
     * Adaptive to their specific pain points.
     */
    private fun handleChallenges(response: String): BootstrapStep {
        accumulated.biggestChallenge = response

        // ANALYZE: Pain points, emotional state, priorities
        analyzeChallengeDescription(response)

        // Build adaptive response to their challenge
        val challengeResponse = buildAdaptiveChallengeResponse()

        // Derive the final understanding
        deriveFinalUnderstanding()

        // Generate summary using WorkerUnderstanding
        val summary = understanding.toHumanSummary(
            accumulated.workerName, accumulated.msaidiziName, language
        )

        // Generate SPECIFIC insight — not generic
        val insight = understanding.generateSpecificInsight(
            accumulated.workerName, accumulated.msaidiziName, language
        )

        val agentName = accumulated.msaidiziName
        val workerName = accumulated.workerName

        val prompt = if (language == "sw") {
            "$challengeResponse\n\n" +
                "Nimekuelewa, $workerName!\n\n" +
                "$summary\n\n" +
                "───\n\n" +
                "$insight\n\n" +
                "Tayari kuanza! Sema chochote kuhusu biashara yako — $agentName atakusikiliza."
        } else {
            "$challengeResponse\n\n" +
                "I understand you, $workerName!\n\n" +
                "$summary\n\n" +
                "───\n\n" +
                "$insight\n\n" +
                "Ready to start! Say anything about your business — $agentName will listen."
        }

        return BootstrapStep.Summary(prompt = prompt)
    }

    /**
     * Build adaptive response to their challenge.
     * Shows the agent UNDERSTANDS their specific problem.
     */
    private fun buildAdaptiveChallengeResponse(): String {
        val lower = accumulated.biggestChallenge.lowercase()
        val name = accumulated.workerName
        val agentName = accumulated.msaidiziName

        // Stock problems
        if (lower.contains("stock") || lower.contains("isha") || lower.contains("hakuna")) {
            return if (language == "sw") {
                "$name, nimesikia — stock inaisha. Hii ni changamoto kubwa! " +
                    "$agentName atakusaidia kufuatilia stock yako na kukuarifu kabla haijaisha."
            } else {
                "$name, I hear you — stock runs out. This is a big challenge! " +
                    "$agentName will track your stock and alert you before it runs out."
            }
        }

        // Pricing/margin problems
        if (lower.contains("bei") || lower.contains("faida") || lower.contains("hasara")) {
            return if (language == "sw") {
                "$name, bei na faida — ndio msingi wa biashara! " +
                    "$agentName atakokokotea faida yako ya kila siku ili ujue unapata kiasi gani."
            } else {
                "$name, prices and profit — that's the foundation of business! " +
                    "$agentName will calculate your daily profit so you know exactly what you earn."
            }
        }

        // Customer problems
        if (lower.contains("wateja") || lower.contains("hawana") || lower.contains("wachache")) {
            return if (language == "sw") {
                "$name, wateja wachache — $agentName atakusaidia kuelewa ni wakati gani " +
                    "wateja wengi wanakuja na bidhaa gani wanapenda zaidi."
            } else {
                "$name, few customers — $agentName will help you understand when " +
                    "most customers come and which products they prefer."
            }
        }

        // Cash flow
        if (lower.contains("pesa") || lower.contains("inaisha") || lower.contains("mkopo") || lower.contains("deni")) {
            return if (language == "sw") {
                "$name, pesa inaisha — $agentName atakusaidia kufuatilia kila shilingi " +
                    "inayoingia na kutoka. Utajua wapi pesa yako inaenda."
            } else {
                "$name, money runs out — $agentName will track every shilling " +
                    "in and out. You'll know where your money goes."
            }
        }

        // Knowledge gap
        if (lower.contains("sijui") || lower.contains("ngumu") || lower.contains("kuelewa")) {
            return if (language == "sw") {
                "$name, kujua ni ngumu — lakini $agentName iko hapa kukusaidia. " +
                    "Tutafanya pamoja, hatua kwa hatua."
            } else {
                "$name, not knowing is hard — but $agentName is here to help. " +
                    "We'll do it together, step by step."
            }
        }

        // Generic response
        return if (language == "sw") {
            "$name, nimesikia changamoto yako. $agentName atakusaidia."
        } else {
            "$name, I hear your challenge. $agentName will help."
        }
    }

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
     */
    private fun handlePin(response: String): BootstrapStep {
        val digits = extractDigits(response)

        if (digits.length < 4) {
            val prompt = if (language == "sw") {
                "PIN inahitaji tarakimu 4. Jaribu tena. Sema tarakimu nne."
            } else {
                "PIN needs 4 digits. Try again. Say four digits."
            }
            return BootstrapStep.AskPin(prompt = prompt)
        }

        val pin = digits.take(4)
        accumulated.pin = pin

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

    // ═══════════════════════════════════════════════════════════════
    // UNDERSTANDING ANALYSIS
    // ═══════════════════════════════════════════════════════════════

    private fun analyzeIntroductionStyle(response: String) {
        val lower = response.lowercase().trim()
        val wordCount = lower.split("\\s+".toRegex()).size

        val formality = when {
            lower.contains("mimi ni") || lower.contains("jina langu") -> Formality.FORMAL
            lower.contains("naitwa") -> Formality.STANDARD
            wordCount <= 2 -> Formality.CASUAL
            lower.contains("habari") || lower.contains("shikamoo") -> Formality.FORMAL
            else -> Formality.STANDARD
        }

        val comfort = when {
            wordCount >= 5 -> CommunicationComfort.VERBOSE
            wordCount >= 3 -> CommunicationComfort.MODERATE
            wordCount <= 1 -> CommunicationComfort.MINIMAL
            else -> CommunicationComfort.MODERATE
        }

        understanding.communicationStyle = understanding.communicationStyle.copy(
            formality = formality,
            verbosity = comfort,
            primaryLanguage = language
        )
    }

    private fun analyzeNamingChoice(response: String, agentName: String) {
        val lower = response.lowercase().trim()
        val nameLower = agentName.lowercase()

        val relationship = when {
            nameLower.contains("rafiki") || nameLower.contains("friend") -> RelationshipType.FRIEND
            nameLower.contains("mshauri") || nameLower.contains("advisor") || nameLower.contains("mwalimu") -> RelationshipType.ADVISOR
            nameLower.contains("biashara") || nameLower.contains("business") -> RelationshipType.BUSINESS_PARTNER
            nameLower.contains("mama") || nameLower.contains("dada") || nameLower.contains("mzee") -> RelationshipType.FAMILY
            lower in listOf("hapana", "msaidizi", "siwezi", "sijui") -> RelationshipType.PRAGMATIC
            agentName.length > 12 || agentName.contains(" ") -> RelationshipType.CREATIVE
            else -> RelationshipType.FRIEND
        }

        val tone = when (relationship) {
            RelationshipType.FRIEND -> Tone.WARM_CASUAL
            RelationshipType.ADVISOR -> Tone.PROFESSIONAL
            RelationshipType.BUSINESS_PARTNER -> Tone.RESULTS_FOCUSED
            RelationshipType.FAMILY -> Tone.NURTURING
            RelationshipType.PRAGMATIC -> Tone.DIRECT
            RelationshipType.CREATIVE -> Tone.PLAYFUL
        }

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
    }

    private fun analyzeBusinessDescription(response: String, businessType: WorkerType?) {
        val lower = response.lowercase()
        val wordCount = lower.split("\\s+".toRegex()).size

        val specificity = when {
            wordCount >= 8 -> BusinessSpecificity.DETAILED
            wordCount >= 4 -> BusinessSpecificity.MODERATE
            else -> BusinessSpecificity.VAGUE
        }

        val maturity = when {
            lower.contains("mpango") || lower.contains("kukua") || lower.contains("grow") -> BusinessMaturity.GROWING
            lower.contains("mpya") || lower.contains("new") || lower.contains("anza") -> BusinessMaturity.NEW
            lower.contains("miaka") || lower.contains("years") || lower.contains("tangu") -> BusinessMaturity.ESTABLISHED
            lower.contains("changamoto") || lower.contains("problem") || lower.contains("ngumu") -> BusinessMaturity.STRUGGLING
            else -> BusinessMaturity.STABLE
        }

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

        // Store business context for summary
        understanding.businessContext = response.take(100)

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
    }

    private fun analyzeProductMix(response: String, products: List<String>) {
        val lower = response.lowercase()

        val perishableKeywords = listOf(
            "mboga", "matunda", "nyanya", "sukuma", "vitunguu", "nyama", "samaki",
            "maziwa", "mayai", "mkate", "chipsi", "mchuzi", "ugali", "chapo"
        )
        val isPerishable = perishableKeywords.any { lower.contains(it) }

        val highValueKeywords = listOf(
            "simu", "phone", "nguo", "shoes", "viatu", "furniture", "meza", "viti",
            "kompyuta", "laptop", "televisheni", "radio", "betri", "solar"
        )
        val isHighValue = highValueKeywords.any { lower.contains(it) }

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

        // Extract prices if mentioned
        val pricePattern = Regex("(?:ksh|kes|shilingi|bob)?\\s*(\\d+)\\s*(?:ksh|kes|bob)?")
        val prices = pricePattern.findAll(lower).map { "KSh ${it.groupValues[1]}" }.toList()
        if (prices.isNotEmpty()) {
            understanding.mentionedPrices = prices
        }

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
    }

    private fun analyzeLocationContext(response: String, locationType: String) {
        val lower = response.lowercase()

        val urbanKeywords = listOf("nairobi", "mombasa", "kisumu", "nakuru", "eldoret", "thika", "town", "cbd", "downtown")
        val ruralKeywords = listOf("shamba", "kijiji", "village", "nyumbani", "home", "rural", "mbali")
        val isUrban = urbanKeywords.any { lower.contains(it) }
        val isRural = ruralKeywords.any { lower.contains(it) }

        val hasNamedLocation = accumulated.marketName.isNotBlank()

        understanding.marketContext = understanding.marketContext.copy(
            locationType = locationType,
            isUrban = isUrban || (!isRural && locationType == "market"),
            isRural = isRural,
            hasNamedLocation = hasNamedLocation,
            isMobile = locationType == "mobile"
        )

        if (locationType == "mobile") {
            understanding.helpPriority = understanding.helpPriority.copy(
                featurePriorities = understanding.helpPriority.featurePriorities + "route_optimization" + "customer_location_tracking"
            )
        }
    }

    private fun analyzeWorkPatterns(response: String, hours: WorkingHours) {
        val lower = response.lowercase()
        val totalHours = if (hours.endHour > hours.startHour) hours.endHour - hours.startHour
        else (24 - hours.startHour) + hours.endHour

        val intensity = when {
            totalHours >= 14 -> WorkIntensity.EXTREME
            totalHours >= 10 -> WorkIntensity.HIGH
            totalHours >= 6 -> WorkIntensity.NORMAL
            else -> WorkIntensity.LOW
        }

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

        if (intensity == WorkIntensity.EXTREME) {
            understanding.helpPriority = understanding.helpPriority.copy(
                featurePriorities = understanding.helpPriority.featurePriorities + "efficiency_tips" + "rest_reminders"
            )
        }
    }

    private fun analyzeCustomerAndPaymentPatterns(response: String) {
        val lower = response.lowercase()

        val techComfort = when (accumulated.paymentMethod) {
            PaymentType.MOBILE -> TechComfort.HIGH
            PaymentType.BOTH -> TechComfort.MODERATE
            PaymentType.CASH -> TechComfort.LOW
            PaymentType.BANK -> TechComfort.HIGH
        }

        val usesWhatsApp = lower.contains("whatsapp") || lower.contains("wa") || lower.contains("status")

        val hasRegulars = lower.contains("regular") || lower.contains("mteja wangu") ||
            lower.contains("wateja wangu") || lower.contains("marafiki") ||
            lower.contains("wanakuja") || lower.contains("wanarudi")

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

        if (usesWhatsApp) {
            understanding.helpPriority = understanding.helpPriority.copy(
                reportDelivery = ReportDeliveryMethod.WHATSAPP
            )
        } else if (techComfort == TechComfort.LOW) {
            understanding.helpPriority = understanding.helpPriority.copy(
                reportDelivery = ReportDeliveryMethod.IN_APP
            )
        }
    }

    private fun analyzeChallengeDescription(response: String) {
        val lower = response.lowercase()
        val wordCount = lower.split("\\s+".toRegex()).size

        val painPoints = mutableListOf<PainPoint>()

        if (lower.contains("stock") || lower.contains("bidhaa") || lower.contains("hakuna") ||
            lower.contains("isha") || lower.contains("stockout") || lower.contains("haifiki")) {
            painPoints.add(PainPoint.STOCKOUT)
        }
        if (lower.contains("bei") || lower.contains("price") || lower.contains("gharama") ||
            lower.contains("cost") || lower.contains("faida") || lower.contains("profit") ||
            lower.contains("hasara") || lower.contains("loss")) {
            painPoints.add(PainPoint.PRICING)
        }
        if (lower.contains("wateja") || lower.contains("customer") || lower.contains("hawana") ||
            lower.contains("wachache") || lower.contains("few") || lower.contains("competition") ||
            lower.contains("mpinzani") || lower.contains("ushindani")) {
            painPoints.add(PainPoint.CUSTOMER_SHORTAGE)
        }
        if (lower.contains("pesa") || lower.contains("money") || lower.contains("cash") ||
            lower.contains("inaisha") || lower.contains("hela") || lower.contains("mkopo") ||
            lower.contains("loan") || lower.contains("deni") || lower.contains("debt")) {
            painPoints.add(PainPoint.CASH_FLOW)
        }
        if (lower.contains("sijui") || lower.contains("don't know") || lower.contains("sielevi") ||
            lower.contains("confus") || lower.contains("ngumu") || lower.contains("difficult") ||
            lower.contains("kujua") || lower.contains("kuelewa")) {
            painPoints.add(PainPoint.KNOWLEDGE_GAP)
        }
        if (lower.contains("mvua") || lower.contains("rain") || lower.contains("jua") ||
            lower.contains("sun") || lower.contains("polisi") || lower.contains("kanjo") ||
            lower.contains("county") || lower.contains("viboko") || lower.contains("mara")) {
            painPoints.add(PainPoint.EXTERNAL_SHOCK)
        }

        if (painPoints.isEmpty()) {
            painPoints.add(PainPoint.GENERAL)
        }

        val stressIndicators = listOf("sana", "sana sana", "very", "really", "biggest", "kubwa",
            "mbaya", "bad", "ngumu", "hard", "inaniumiza", "hurts", "nateseka", "suffering")
        val stressLevel = stressIndicators.count { lower.contains(it) }

        val emotionalState = when {
            stressLevel >= 3 -> EmotionalState.HIGH_STRESS
            stressLevel >= 1 -> EmotionalState.MODERATE_STRESS
            wordCount >= 15 -> EmotionalState.REFLECTIVE
            wordCount <= 3 -> EmotionalState.RESIGNED
            else -> EmotionalState.CALM
        }

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

        if (emotionalState == EmotionalState.HIGH_STRESS) {
            understanding.communicationStyle = understanding.communicationStyle.copy(
                preferredTone = Tone.ENCOURAGING
            )
        }
    }

    private fun deriveFinalUnderstanding() {
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

        understanding.greetingStyle = when (understanding.relationshipType) {
            RelationshipType.FRIEND -> "Habari ${accumulated.workerName}! ${accumulated.msaidiziName} hapa."
            RelationshipType.ADVISOR -> "Habari ${accumulated.workerName}. Ripoti yako ya leo:"
            RelationshipType.BUSINESS_PARTNER -> "${accumulated.workerName}, hapa kile kilichotokea leo:"
            RelationshipType.FAMILY -> "Habari ${accumulated.workerName}! Leo imekuwaje?"
            RelationshipType.PRAGMATIC -> "Ripoti ya leo:"
            RelationshipType.CREATIVE -> "Heeey ${accumulated.workerName}! ${accumulated.msaidiziName} ana update! "
        }
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
    // PIN & DIGIT EXTRACTION
    // ═══════════════════════════════════════════════════════════════

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
            if (word in allDigits) {
                result += allDigits[word]
            } else if (word.length == 1 && word[0].isDigit()) {
                result += word
            } else if (word.all { it.isDigit() }) {
                result += word
            }
        }
        return result
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
