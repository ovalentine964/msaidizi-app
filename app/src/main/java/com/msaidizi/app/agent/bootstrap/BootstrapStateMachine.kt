package com.msaidizi.app.agent.bootstrap

import timber.log.Timber
import java.util.UUID

/**
 * BootstrapStateMachine — 11-step voice conversation state machine.
 *
 * Manages the flow of the bootstrap conversation between Msaidizi
 * and a new worker. Runs through the SuperAgent's cognitive loop
 * (7 phases) — not a separate system.
 *
 * States: IDLE → INTRO → NAME → BUSINESS → LOCATION → HOURS →
 *         PRODUCTS → MPESA → RECORDS → LANGUAGE → CONFIRM → COMPLETE
 *
 * Handles interruptions, retries, frustration detection, and
 * state persistence for app restart recovery.
 *
 * Design: review_bootstrap_openclaw.md Parts 1-3
 */
class BootstrapStateMachine(
    private val workerId: String
) {
    var state: BootstrapState = BootstrapState()
        private set

    private var retryCount: Int = 0
    private val maxRetries: Int = 2
    private var frustrationScore: Double = 0.0

    // ── State Query ───────────────────────────────────────────

    val isComplete: Boolean get() = state.step == BootstrapStep.COMPLETE
    val isActive: Boolean get() = state.step != BootstrapStep.IDLE && state.step != BootstrapStep.COMPLETE
    val currentStep: BootstrapStep get() = state.step
    val profileBuilder: WorkerProfileBuilder get() = state.workerProfile

    // ── Start Bootstrap ───────────────────────────────────────

    /**
     * Start the bootstrap conversation. Returns the agent's introduction.
     */
    fun start(): BootstrapResponse {
        state = state.copy(
            step = BootstrapStep.INTRODUCTION,
            startedAt = System.currentTimeMillis(),
            lastActivityAt = System.currentTimeMillis()
        )

        val intro = buildString {
            append("Habari! Mimi ni Msaidizi — msaidizi wako wa biashara. ")
            append("Nitakusaidia kurekodi mauzo yako, kujua faida yako, ")
            append("na kupanga biashara yako vizuri zaidi. ")
            append("Sasa, hebu tujue kwa undani zaidi. Jina lako nani?")
        }

        advanceTo(BootstrapStep.NAME)

        return BootstrapResponse(
            text = intro,
            step = BootstrapStep.NAME,
            isQuestion = true,
            expectsInput = true
        )
    }

    // ── Process Input ─────────────────────────────────────────

    /**
     * Process a worker's response during bootstrap.
     * Returns the next agent response.
     */
    fun processInput(rawText: String, sttConfidence: Double = 0.8): BootstrapResponse {
        val text = rawText.trim()
        if (text.isBlank()) return handleBlankInput()

        state = state.copy(lastActivityAt = System.currentTimeMillis())
        retryCount = 0 // Reset retry on valid input

        // Check for frustration signals
        if (detectFrustration(text)) {
            frustrationScore += 0.3
            if (frustrationScore > 0.7) {
                return handleFrustration()
            }
        }

        // Check for correction of previous answer
        if (detectCorrection(text)) {
            return handleCorrection(text)
        }

        return when (state.step) {
            BootstrapStep.INTRODUCTION -> start() // Shouldn't happen, re-introduce
            BootstrapStep.NAME -> processName(text, sttConfidence)
            BootstrapStep.BUSINESS_TYPE -> processBusinessType(text)
            BootstrapStep.BUSINESS_DETAIL -> processBusinessDetail(text)
            BootstrapStep.LOCATION -> processLocation(text)
            BootstrapStep.HOURS -> processHours(text)
            BootstrapStep.PRODUCTS -> processProducts(text)
            BootstrapStep.MPESA -> processMpesa(text)
            BootstrapStep.RECORDS -> processRecords(text)
            BootstrapStep.LANGUAGE_CONFIRM -> processLanguageConfirm(text)
            BootstrapStep.CONFIRMATION -> processConfirmation(text)
            BootstrapStep.FIRST_TRANSACTION -> processFirstTransaction(text)
            BootstrapStep.COMPLETE -> BootstrapResponse(
                text = "Tayari! Umefanya vizuri!",
                step = BootstrapStep.COMPLETE,
                isQuestion = false,
                expectsInput = false
            )
        }
    }

    // ── Step Processors ───────────────────────────────────────

    private fun processName(text: String, confidence: Double): BootstrapResponse {
        // Extract name — simple heuristic for voice input
        val name = extractName(text)

        if (name.isNullOrBlank()) {
            return retryOrSkip(
                "Pole, sijaelewa jina lako. Sema tena — jina lako nani?",
                BootstrapStep.NAME
            )
        }

        state.workerProfile.setName(name)
        advanceTo(BootstrapStep.BUSINESS_TYPE)

        // Adaptive: follow up based on business type keywords in the same utterance
        val detectedBusiness = WorkerProfileBuilder.BusinessType.fromInput(text)
        if (detectedBusiness != WorkerProfileBuilder.BusinessType.UNKNOWN) {
            state.workerProfile.setBusinessType(detectedBusiness)
            advanceTo(BootstrapStep.BUSINESS_DETAIL)
            return BootstrapResponse(
                text = "Sawa, ${state.workerProfile.preferredName}! ${detectedBusiness.label} — vizuri! " +
                        getBusinessFollowUp(detectedBusiness),
                step = BootstrapStep.BUSINESS_DETAIL,
                isQuestion = true,
                expectsInput = true
            )
        }

        return BootstrapResponse(
            text = "Sawa, ${state.workerProfile.preferredName}! Biashara yako ni ipi? " +
                    "Kwa mfano — mama mboga, duka, boda-boda, fundi, mama fua...",
            step = BootstrapStep.BUSINESS_TYPE,
            isQuestion = true,
            expectsInput = true
        )
    }

    private fun processBusinessType(text: String): BootstrapResponse {
        val type = WorkerProfileBuilder.BusinessType.fromInput(text)

        if (type == WorkerProfileBuilder.BusinessType.UNKNOWN) {
            // Try to extract a description
            if (text.length > 3) {
                state.workerProfile.setBusinessType(WorkerProfileBuilder.BusinessType.OTHER)
                state.workerProfile.setBusinessDescription(text)
                advanceTo(BootstrapStep.LOCATION)
                return BootstrapResponse(
                    text = "Sawa! $text — poa! Unafanya biashara wapi? Mkoa na kaunti ndogo?",
                    step = BootstrapStep.LOCATION,
                    isQuestion = true,
                    expectsInput = true
                )
            }
            return retryOrSkip(
                "Sema zaidi. Biashara yako inafanya nini hasa?",
                BootstrapStep.BUSINESS_TYPE
            )
        }

        state.workerProfile.setBusinessType(type)

        // Service businesses skip product question
        if (type in listOf(
                WorkerProfileBuilder.BusinessType.BODA_BODA,
                WorkerProfileBuilder.BusinessType.FUNDI,
                WorkerProfileBuilder.BusinessType.MAMA_FUA
            )
        ) {
            advanceTo(BootstrapStep.LOCATION)
            return BootstrapResponse(
                text = "Vizuri! ${type.label} — poa! Unafanya biashara wapi? Mkoa na kaunti ndogo?",
                step = BootstrapStep.LOCATION,
                isQuestion = true,
                expectsInput = true
            )
        }

        advanceTo(BootstrapStep.BUSINESS_DETAIL)
        return BootstrapResponse(
            text = "Vizuri! ${type.label}! ${getBusinessFollowUp(type)}",
            step = BootstrapStep.BUSINESS_DETAIL,
            isQuestion = true,
            expectsInput = true
        )
    }

    private fun processBusinessDetail(text: String): BootstrapResponse {
        // Store the business detail / products mentioned
        val items = extractItems(text)
        items.forEach { state.workerProfile.addProduct(it) }

        advanceTo(BootstrapStep.LOCATION)
        return BootstrapResponse(
            text = "Poa! Sasa — unafanya biashara wapi? Mkoa na kaunti ndogo?",
            step = BootstrapStep.LOCATION,
            isQuestion = true,
            expectsInput = true
        )
    }

    private fun processLocation(text: String): BootstrapResponse {
        val location = extractLocation(text)

        if (location == null) {
            // Accept vague location
            if (text.length > 2) {
                state.workerProfile.setLocation(text, text)
                advanceTo(BootstrapStep.HOURS)
                return BootstrapResponse(
                    text = "Sawa! Unafungua biashara saa ngapi? Unafunga saa ngapi?",
                    step = BootstrapStep.HOURS,
                    isQuestion = true,
                    expectsInput = true
                )
            }
            return retryOrSkip(
                "Hakuna shida. Mji gani? Au soko gani?",
                BootstrapStep.LOCATION
            )
        }

        state.workerProfile.setLocation(location.first, location.second)
        advanceTo(BootstrapStep.HOURS)

        return BootstrapResponse(
            text = "Sawa! ${location.first}, ${location.second} — poa! Unafungua biashara saa ngapi? Unafunga saa ngapi?",
            step = BootstrapStep.HOURS,
            isQuestion = true,
            expectsInput = true
        )
    }

    private fun processHours(text: String): BootstrapResponse {
        val hours = extractHours(text)

        if (hours == null) {
            return retryOrSkip(
                "Sema tena — saa ngapi unafungua, saa ngapi unafunga?",
                BootstrapStep.HOURS
            )
        }

        state.workerProfile.setOperatingHours(hours.first, hours.second)

        // Skip products for service businesses
        if (state.workerProfile.businessType in listOf(
                WorkerProfileBuilder.BusinessType.BODA_BODA,
                WorkerProfileBuilder.BusinessType.FUNDI,
                WorkerProfileBuilder.BusinessType.MAMA_FUA
            )
        ) {
            advanceTo(BootstrapStep.MPESA)
            return BootstrapResponse(
                text = "Sawa! Saa ${hours.first} mpaka ${hours.second}. " +
                        "Je, unatumia M-Pesa kupokea pesa za biashara?",
                step = BootstrapStep.MPESA,
                isQuestion = true,
                expectsInput = true
            )
        }

        advanceTo(BootstrapStep.PRODUCTS)
        return BootstrapResponse(
            text = "Sawa! Saa ${hours.first} mpaka ${hours.second}. " +
                    "Bidhaa zako kuu ni zipi? Unauza nini zaidi?",
            step = BootstrapStep.PRODUCTS,
            isQuestion = true,
            expectsInput = true
        )
    }

    private fun processProducts(text: String): BootstrapResponse {
        val items = extractItems(text)
        items.forEach { state.workerProfile.addProduct(it) }

        if (items.isEmpty() && text.length > 2) {
            // Accept freeform description
            state.workerProfile.addProduct(text)
        }

        advanceTo(BootstrapStep.MPESA)
        return BootstrapResponse(
            text = "Vizuri! Sasa — je, unatumia M-Pesa kupokea pesa za biashara?",
            step = BootstrapStep.MPESA,
            isQuestion = true,
            expectsInput = true
        )
    }

    private fun processMpesa(text: String): BootstrapResponse {
        val lower = text.lowercase()
        val usesMpesa = lower.contains("ndiyo") || lower.contains("yes") ||
                lower.contains("ntumia") || lower.contains("tumia") ||
                lower.contains("mpesa") && !lower.contains("hapana")

        if (usesMpesa) {
            val mpesaType = when {
                lower.contains("till") -> WorkerProfileBuilder.MpesaType.TILL
                lower.contains("paybill") -> WorkerProfileBuilder.MpesaType.PAYBILL
                else -> WorkerProfileBuilder.MpesaType.PERSONAL
            }
            state.workerProfile.setMpesaUsage(true, mpesaType)

            advanceTo(BootstrapStep.RECORDS)
            return BootstrapResponse(
                text = "Vizuri! Utaweza kurekodi mauzo moja kwa moja kutoka M-Pesa. " +
                        "Je, unaandika mauzo yako mahali? Kwenye daftari? Kichwani tu?",
                step = BootstrapStep.RECORDS,
                isQuestion = true,
                expectsInput = true
            )
        }

        state.workerProfile.setMpesaUsage(false)

        advanceTo(BootstrapStep.RECORDS)
        return BootstrapResponse(
            text = "Hakuna shida. Unaweza kurekodi kwa sauti — mimi nitakusikia. " +
                    "Je, unaandika mauzo yako mahali? Kwenye daftari? Kichwani tu?",
            step = BootstrapStep.RECORDS,
            isQuestion = true,
            expectsInput = true
        )
    }

    private fun processRecords(text: String): BootstrapResponse {
        val lower = text.lowercase()
        val recordType = when {
            lower.contains("daftari") || lower.contains("notebook") || lower.contains("andika") ->
                WorkerProfileBuilder.RecordType.NOTEBOOK
            lower.contains("simu") || lower.contains("phone") ->
                WorkerProfileBuilder.RecordType.PHONE
            else -> WorkerProfileBuilder.RecordType.HEAD
        }

        state.workerProfile.setExistingRecords(
            recordType != WorkerProfileBuilder.RecordType.HEAD,
            recordType
        )

        val encouragement = when (recordType) {
            WorkerProfileBuilder.RecordType.HEAD ->
                "Wengi hivyo ndivyo wanafanya. Sasa nitakusaidia kukumbuka kwa wewe."
            WorkerProfileBuilder.RecordType.NOTEBOOK ->
                "Vizuri! Sasa tutaweka kwenye simu pia — rahisi zaidi."
            else ->
                "Poa! Sasa tutaweka kwenye simu pia — rahisi zaidi."
        }

        advanceTo(BootstrapStep.LANGUAGE_CONFIRM)
        return BootstrapResponse(
            text = "$encouragement Naona unapenda Kiswahili cha kawaida. Sawa, tutaongea hivyo!",
            step = BootstrapStep.LANGUAGE_CONFIRM,
            isQuestion = false,
            expectsInput = true,
            autoAdvance = true
        )
    }

    private fun processLanguageConfirm(text: String): BootstrapResponse {
        // Detect dialect from input patterns
        val lower = text.lowercase()
        val dialect = when {
            containsSheng(lower) -> "sheng"
            containsEnglish(lower) -> "mixed"
            else -> "standard"
        }

        state.workerProfile.setDialect(dialect)
        state.workerProfile.setCodeSwitching(dialect == "mixed")

        advanceTo(BootstrapStep.CONFIRMATION)
        return generateConfirmation()
    }

    private fun processConfirmation(text: String): BootstrapResponse {
        val lower = text.lowercase()
        val confirmed = lower.contains("ndiyo") || lower.contains("yes") ||
                lower.contains("sawa") || lower.contains("sahihi") ||
                lower.contains("sawa") || lower.contains("sawasawa") ||
                lower.contains("sawia")

        if (confirmed) {
            advanceTo(BootstrapStep.FIRST_TRANSACTION)
            return BootstrapResponse(
                text = "Amina, sasa tuanze na jambo moja rahisi. Je, umefanya mauzo yoyote leo? " +
                        "Sema tu — kwa mfano: \"Nimeuza nyanya mbili, mia tano.\"",
                step = BootstrapStep.FIRST_TRANSACTION,
                isQuestion = true,
                expectsInput = true
            )
        }

        // Worker wants to correct something
        if (detectCorrection(text)) {
            return handleCorrection(text)
        }

        // Unclear response — re-confirm
        return BootstrapResponse(
            text = "Hakuna shida. Ndiyo au hapana? Kuna kitu unataka kubadilisha?",
            step = BootstrapStep.CONFIRMATION,
            isQuestion = true,
            expectsInput = true
        )
    }

    private fun processFirstTransaction(text: String): BootstrapResponse {
        // This step is handled by the SuperAgent's normal cognitive loop.
        // The state machine just marks completion after the first transaction.
        advanceTo(BootstrapStep.COMPLETE)

        state = state.copy(
            completedAt = System.currentTimeMillis()
        )

        val name = state.workerProfile.preferredName ?: "Rafiki"
        return BootstrapResponse(
            text = "$name, umefanya vizuri! Sasa wewe ni rafiki yangu wa biashara. " +
                    "Nitakutumia muhtasari wa leo baadaye. Tutaongea tena kesho!",
            step = BootstrapStep.COMPLETE,
            isQuestion = false,
            expectsInput = false,
            isComplete = true
        )
    }

    // ── Extraction Helpers ─────────────────────────────────────

    private fun extractName(text: String): String? {
        val lower = text.lowercase()

        // Pattern: "jina langu ni X" / "mimi ni X" / "X" / "naitwa X"
        val patterns = listOf(
            Regex("(?:jina langu ni|mimi ni|naitwa|jina ni|i am|i'm|my name is)\\s+(.+?)(?:\\s*$)", RegexOption.IGNORE_CASE),
            Regex("^(.+?)\\s+ni jina langu", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                val name = match.groupValues[1].trim()
                if (name.length in 2..30 && !name.contains(Regex("\\d{3,}"))) {
                    return name.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                }
            }
        }

        // Fallback: if input is short and looks like a name
        val words = text.trim().split("\\s+".toRegex())
        if (words.size in 1..3 && words.all { it.length in 2..15 && !it.contains(Regex("\\d")) }) {
            // Filter out common non-name words
            val nonNames = setOf("habari", "jina", "langu", "ni", "mimi", "naitwa", "yes", "ndiyo", "sawa")
            val nameWords = words.filter { it.lowercase() !in nonNames }
            if (nameWords.isNotEmpty()) {
                return nameWords.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            }
        }

        return null
    }

    private fun extractLocation(text: String): Pair<String, String>? {
        val lower = text.lowercase()

        // Known counties
        val counties = listOf(
            "nairobi", "mombasa", "kisumu", "nakuru", "eldoret", "thika",
            "malindi", "kitale", "garissa", "nyeri", "machakos", "meru",
            "kilifi", "lamu", "embu", "kajiado", "kiambu", "murang'a",
            "turkana", "bungoma", "kakamega", "vihiga", "siaya", "homabay",
            "migori", "kisii", "nyamira", "kericho", "bomet", "nakuru",
            "narok", "laikipia", "samburu", "isiolo", "marsabit", "wajir",
            "mandera", "taita-taveta", "kwale", "tana river"
        )

        val foundCounty = counties.firstOrNull { lower.contains(it) }

        if (foundCounty != null) {
            // Try to extract sub-county
            val afterCounty = text.substring(text.lowercase().indexOf(foundCounty) + foundCounty.length).trim()
            val subCounty = afterCounty.split(Regex("[,\\s]+"))
                .firstOrNull { it.isNotBlank() && it.lowercase() != foundCounty }
                ?.trim()

            return Pair(
                foundCounty.replaceFirstChar { it.uppercase() },
                subCounty?.replaceFirstChar { it.uppercase() } ?: foundCounty.replaceFirstChar { it.uppercase() }
            )
        }

        // Accept any two-word location
        val words = text.trim().split(Regex("[,\\s]+")).filter { it.isNotBlank() }
        if (words.size >= 2) {
            return Pair(
                words[0].replaceFirstChar { it.uppercase() },
                words[1].replaceFirstChar { it.uppercase() }
            )
        }

        return null
    }

    private fun extractHours(text: String): Pair<String, String>? {
        // Swahili time patterns: "saa kumi" (10), "saa mbili" (2), "saa tatu" (3)
        val swahiliNumbers = mapOf(
            "moja" to 1, "mbili" to 2, "tatu" to 3, "nne" to 4, "tano" to 5,
            "sita" to 6, "saba" to 7, "nane" to 8, "tisa" to 9, "kumi" to 10,
            "kumi na moja" to 11, "kumi na mbili" to 12,
            "moja" to 1, "mbili" to 2, "tatu" to 3, "nne" to 4, "tano" to 5
        )

        // Pattern: "saa X ... saa Y"
        val saaPattern = Regex("saa\\s+(.+?)(?:\\s+(?:mpaka|na|had|to|mpaka)\\s+saa\\s+(.+?))?(?:\\s*$)", RegexOption.IGNORE_CASE)
        val match = saaPattern.find(text.lowercase())

        if (match != null) {
            val openStr = match.groupValues[1].trim()
            val closeStr = match.groupValues.getOrNull(2)?.trim() ?: ""

            val openHour = parseSwahiliTime(openStr)
            val closeHour = if (closeStr.isNotBlank()) parseSwahiliTime(closeStr) else null

            if (openHour != null) {
                val openFormatted = "%02d:00".format(openHour)
                val closeFormatted = if (closeHour != null) "%02d:00".format(closeHour) else "18:00"
                return Pair(openFormatted, closeFormatted)
            }
        }

        // English pattern: "8 to 5" or "8am - 5pm"
        val engPattern = Regex("(\\d{1,2})\\s*(?:am|AM)?\\s*(?:to|-|mpaka|had)\\s*(\\d{1,2})\\s*(?:pm|PM)?", RegexOption.IGNORE_CASE)
        val engMatch = engPattern.find(text)
        if (engMatch != null) {
            val open = engMatch.groupValues[1].toIntOrNull()
            val close = engMatch.groupValues[2].toIntOrNull()
            if (open != null && close != null) {
                return Pair("%02d:00".format(open), "%02d:00".format(if (close < open) close + 12 else close))
            }
        }

        // Single number: "asubuhi mpaka jioni"
        if (text.lowercase().contains("asubuhi") && text.lowercase().contains("jioni")) {
            return Pair("06:00", "18:00")
        }

        return null
    }

    private fun parseSwahiliTime(text: String): Int? {
        val swahiliNumbers = mapOf(
            "moja" to 1, "mbili" to 2, "tatu" to 3, "nne" to 4, "tano" to 5,
            "sita" to 6, "saba" to 7, "nane" to 8, "tisa" to 9, "kumi" to 10,
            "kumi na moja" to 11, "kumi na mbili" to 12
        )

        val lower = text.lowercase().trim()

        // Direct number
        lower.toIntOrNull()?.let { return it }

        // Swahili word
        swahiliNumbers[lower]?.let { return it }

        // Pattern: "kumi na X"
        val kumiNaPattern = Regex("kumi na (\\w+)")
        kumiNaPattern.find(lower)?.let { match ->
            val ones = swahiliNumbers[match.groupValues[1]]
            if (ones != null) return 10 + ones
        }

        return null
    }

    private fun extractItems(text: String): List<String> {
        // Split by common separators: comma, "na" (Swahili "and"), whitespace
        val items = text.split(Regex("(?:,\\s*|\\s+na\\s+|\\s+)", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 1 }
            .filter { it.lowercase() !in setOf("na", "ya", "za", "wa", "ni", "kwa", "the", "and") }
        return items
    }

    // ── Edge Case Handlers ─────────────────────────────────────

    private fun handleBlankInput(): BootstrapResponse {
        retryCount++
        return if (retryCount >= maxRetries) {
            skipCurrentStep()
        } else {
            BootstrapResponse(
                text = "Sikiliza... ${getRetryPrompt()}",
                step = state.step,
                isQuestion = true,
                expectsInput = true
            )
        }
    }

    private fun retryOrSkip(message: String, step: BootstrapStep): BootstrapResponse {
        retryCount++
        return if (retryCount >= maxRetries) {
            skipCurrentStep()
        } else {
            BootstrapResponse(
                text = message,
                step = step,
                isQuestion = true,
                expectsInput = true
            )
        }
    }

    private fun skipCurrentStep(): BootstrapResponse {
        retryCount = 0
        // Apply default for current step and move on
        return when (state.step) {
            BootstrapStep.NAME -> {
                state.workerProfile.setName("Rafiki")
                advanceTo(BootstrapStep.BUSINESS_TYPE)
                BootstrapResponse(
                    text = "Hakuna shida. Sasa — biashara yako ni ipi?",
                    step = BootstrapStep.BUSINESS_TYPE,
                    isQuestion = true,
                    expectsInput = true
                )
            }
            BootstrapStep.BUSINESS_TYPE -> {
                state.workerProfile.setBusinessType(WorkerProfileBuilder.BusinessType.OTHER)
                advanceTo(BootstrapStep.LOCATION)
                BootstrapResponse(
                    text = "Hakuna shida. Sasa — unafanya biashara wapi?",
                    step = BootstrapStep.LOCATION,
                    isQuestion = true,
                    expectsInput = true
                )
            }
            else -> {
                // Skip to next step
                val nextStep = getNextStep(state.step)
                advanceTo(nextStep)
                BootstrapResponse(
                    text = "Hakuna shida. Twende mbele.",
                    step = nextStep,
                    isQuestion = true,
                    expectsInput = true
                )
            }
        }
    }

    private fun handleFrustration(): BootstrapResponse {
        frustrationScore = 0.0 // Reset
        return BootstrapResponse(
            text = "Pole sana. Hakuna presha. Tuko pamoja. Unataka tuendelee baadaye?",
            step = state.step,
            isQuestion = true,
            expectsInput = true,
            isPauseOffer = true
        )
    }

    private fun handleCorrection(text: String): BootstrapResponse {
        // The worker is correcting a previous answer
        // Re-ask the current question
        return BootstrapResponse(
            text = "Sawa! Sema tena — ni ipi si sahihi?",
            step = state.step,
            isQuestion = true,
            expectsInput = true
        )
    }

    // ── Detection Helpers ──────────────────────────────────────

    private fun detectFrustration(text: String): Boolean {
        val lower = text.lowercase()
        val signals = listOf(
            "siwezi", "nimechoka", "inaniuma", "siendi", "acha",
            "can't", "tired", "frustrated", "stop", "quit", "badala",
            "siwezi elewa", "sielewi", "ni ngumu"
        )
        return signals.any { lower.contains(it) }
    }

    private fun detectCorrection(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("siyo") || lower.contains("sio") ||
                lower.contains("happana") || lower.contains("no") ||
                lower.contains("correct") || lower.contains("sahihisha") ||
                lower.contains("badilisha") || lower.contains("nilimaanisha")
    }

    private fun containsSheng(text: String): Boolean {
        val shengWords = listOf("niaje", "sasa", "vipi", "poa", "mbogi", "msee", "ndege", "blaze", "fala")
        return shengWords.any { text.contains(it) }
    }

    private fun containsEnglish(text: String): Boolean {
        val englishWords = listOf("yes", "no", "okay", "sure", "the", "and", "but", "what", "how")
        val words = text.split("\\s+".toRegex())
        val englishCount = words.count { it.lowercase() in englishWords }
        return englishCount > words.size / 2
    }

    // ── Response Builders ──────────────────────────────────────

    private fun generateConfirmation(): BootstrapResponse {
        val name = state.workerProfile.preferredName ?: "Rafiki"
        val summary = state.workerProfile.getConfirmationSummary()

        return BootstrapResponse(
            text = "Sawa $name! Nimekuelewa. Hebu nihakikishe:\n$summary\nHii ni sawa?",
            step = BootstrapStep.CONFIRMATION,
            isQuestion = true,
            expectsInput = true
        )
    }

    private fun getRetryPrompt(): String = when (state.step) {
        BootstrapStep.NAME -> "Jina lako nani?"
        BootstrapStep.BUSINESS_TYPE -> "Biashara yako ni ipi?"
        BootstrapStep.LOCATION -> "Unafanya biashara wapi?"
        BootstrapStep.HOURS -> "Saa ngapi unafungua?"
        BootstrapStep.PRODUCTS -> "Unauza nini?"
        BootstrapStep.MPESA -> "Unatumia M-Pesa?"
        BootstrapStep.RECORDS -> "Unaandika mauzo wapi?"
        else -> "Sema tena taratibu."
    }

    private fun getBusinessFollowUp(type: WorkerProfileBuilder.BusinessType): String = when (type) {
        WorkerProfileBuilder.BusinessType.MAMA_MBOGA ->
            "Mboga za majani? Matunda? Viazi? Nyanya?"
        WorkerProfileBuilder.BusinessType.DUKAWALLAH ->
            "Duka lina bidhaa gani kuu? Ni wholesale au retail?"
        WorkerProfileBuilder.BusinessType.MAMA_LISHE ->
            "Chakula gani? Mama ntilie? Chips? Chapati?"
        WorkerProfileBuilder.BusinessType.CLOTHES_SELLER ->
            "Nguo za aina gani? Mitumba au mpya?"
        else -> "Sema zaidi kuhusu biashara yako."
    }

    private fun getNextStep(current: BootstrapStep): BootstrapStep = when (current) {
        BootstrapStep.INTRODUCTION -> BootstrapStep.NAME
        BootstrapStep.NAME -> BootstrapStep.BUSINESS_TYPE
        BootstrapStep.BUSINESS_TYPE -> BootstrapStep.LOCATION
        BootstrapStep.BUSINESS_DETAIL -> BootstrapStep.LOCATION
        BootstrapStep.LOCATION -> BootstrapStep.HOURS
        BootstrapStep.HOURS -> BootstrapStep.PRODUCTS
        BootstrapStep.PRODUCTS -> BootstrapStep.MPESA
        BootstrapStep.MPESA -> BootstrapStep.RECORDS
        BootstrapStep.RECORDS -> BootstrapStep.LANGUAGE_CONFIRM
        BootstrapStep.LANGUAGE_CONFIRM -> BootstrapStep.CONFIRMATION
        BootstrapStep.CONFIRMATION -> BootstrapStep.FIRST_TRANSACTION
        BootstrapStep.FIRST_TRANSACTION -> BootstrapStep.COMPLETE
        BootstrapStep.COMPLETE -> BootstrapStep.COMPLETE
    }

    private fun advanceTo(step: BootstrapStep) {
        state = state.copy(step = step)
    }

    // ── State Persistence ──────────────────────────────────────

    /**
     * Serialize bootstrap state for persistence (survives app restart).
     */
    fun toPersistableMap(): Map<String, Any?> = mapOf(
        "workerId" to workerId,
        "step" to state.step.name,
        "startedAt" to state.startedAt,
        "lastActivityAt" to state.lastActivityAt,
        "name" to state.workerProfile.name,
        "businessType" to state.workerProfile.businessType.name,
        "county" to state.workerProfile.county,
        "subCounty" to state.workerProfile.subCounty,
        "openTime" to state.workerProfile.openTime,
        "closeTime" to state.workerProfile.closeTime,
        "usesMpesa" to state.workerProfile.usesMpesa,
        "mpesaType" to state.workerProfile.mpesaType.name,
        "hasExistingRecords" to state.workerProfile.hasExistingRecords,
        "dialect" to state.workerProfile.dialect
    )

    companion object {
        /**
         * Restore a BootstrapStateMachine from persisted state.
         */
        fun fromPersistableMap(data: Map<String, Any?>): BootstrapStateMachine {
            val workerId = data["workerId"] as? String ?: UUID.randomUUID().toString()
            val machine = BootstrapStateMachine(workerId)

            val stepName = data["step"] as? String ?: "IDLE"
            val step = try {
                BootstrapStep.valueOf(stepName)
            } catch (e: Exception) {
                BootstrapStep.IDLE
            }

            val builder = WorkerProfileBuilder()
            (data["name"] as? String)?.let { builder.setName(it) }
            (data["businessType"] as? String)?.let {
                try {
                    builder.setBusinessType(WorkerProfileBuilder.BusinessType.valueOf(it))
                } catch (e: Exception) { }
            }
            val county = data["county"] as? String
            val subCounty = data["subCounty"] as? String
            if (county != null && subCounty != null) {
                builder.setLocation(county, subCounty)
            }
            val openTime = data["openTime"] as? String
            val closeTime = data["closeTime"] as? String
            if (openTime != null && closeTime != null) {
                builder.setOperatingHours(openTime, closeTime)
            }
            (data["usesMpesa"] as? Boolean)?.let { uses ->
                val type = try {
                    WorkerProfileBuilder.MpesaType.valueOf(data["mpesaType"] as? String ?: "NONE")
                } catch (e: Exception) { WorkerProfileBuilder.MpesaType.NONE }
                builder.setMpesaUsage(uses, type)
            }
            (data["hasExistingRecords"] as? Boolean)?.let { builder.setExistingRecords(it) }
            (data["dialect"] as? String)?.let { builder.setDialect(it) }

            machine.state = BootstrapState(
                step = step,
                workerProfile = builder,
                startedAt = (data["startedAt"] as? Long) ?: System.currentTimeMillis(),
                lastActivityAt = (data["lastActivityAt"] as? Long) ?: System.currentTimeMillis()
            )

            return machine
        }
    }
}

// ── Data Classes ──────────────────────────────────────────────

data class BootstrapState(
    val step: BootstrapStep = BootstrapStep.IDLE,
    val workerProfile: WorkerProfileBuilder = WorkerProfileBuilder(),
    val startedAt: Long = 0L,
    val lastActivityAt: Long = 0L,
    val completedAt: Long? = null
)

enum class BootstrapStep {
    IDLE,              // Not started
    INTRODUCTION,      // Agent introduces itself
    NAME,              // Ask worker's name
    BUSINESS_TYPE,     // Ask about business type
    BUSINESS_DETAIL,   // Follow-up on business specifics
    LOCATION,          // Ask county + sub-county
    HOURS,             // Ask operating hours
    PRODUCTS,          // Ask main products/services
    MPESA,             // Ask about M-Pesa usage
    RECORDS,           // Ask about existing records
    LANGUAGE_CONFIRM,  // Confirm detected dialect
    CONFIRMATION,      // Summarize and confirm all
    FIRST_TRANSACTION, // Guide first sale recording
    COMPLETE           // Bootstrap done
}

data class BootstrapResponse(
    val text: String,
    val step: BootstrapStep,
    val isQuestion: Boolean,
    val expectsInput: Boolean,
    val isComplete: Boolean = false,
    val isPauseOffer: Boolean = false,
    val autoAdvance: Boolean = false
)
