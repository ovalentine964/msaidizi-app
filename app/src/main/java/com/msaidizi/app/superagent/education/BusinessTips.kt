package com.msaidizi.app.superagent.education

import timber.log.Timber

/**
 * Business Tips — worker-shared business advice and market wisdom.
 *
 * Delivers contextual business tips based on:
 * - Worker's business type (food vendor, mama mboga, dukawalla, etc.)
 * - Time of day (morning prep, midday rush, evening close)
 * - Recent activity (sales patterns, stock levels)
 * - Market conditions (day of week, season)
 *
 * ## Tip Sources
 * - Community-contributed tips from successful workers
 * - Msaidizi-generated insights from transaction patterns
 * - Classic business wisdom adapted for informal economy
 *
 * ## Delivery
 * Tips are voice-ready, 1-2 sentences, in Swahili.
 * Delivered contextually — not spam, but relevant nudges.
 *
 * ## Categories
 * - **PRICING** — How to price for profit
 * - **STOCK** — Inventory management
 * - **CUSTOMER** — Customer service and retention
 * - **SAVINGS** — Building financial discipline
 * - **MARKET** — Market day strategies
 * - **GROWTH** — Scaling the business
 */
class BusinessTips {

    companion object {
        private const val TAG = "BusinessTips"
    }

    /** All available tips — loaded once */
    private val tips = buildTipsLibrary()

    /**
     * Get a contextual tip based on the worker's situation.
     *
     * @param context The context for tip selection
     * @param language "sw" or "en"
     * @return A relevant business tip
     */
    fun getTip(context: TipContext, language: String = "sw"): BusinessTip {
        // Filter tips by relevance
        val relevantTips = tips.filter { tip ->
            isTipRelevant(tip, context)
        }

        // Pick one (randomized for variety)
        val tip = relevantTips.randomOrNull() ?: tips.random()

        return BusinessTip(
            id = tip.id,
            text = if (language == "sw") tip.textSw else tip.textEn,
            category = tip.category,
            relevance = calculateRelevance(tip, context)
        )
    }

    /**
     * Get tips by category for browsing.
     */
    fun getTipsByCategory(category: String, language: String = "sw"): List<BusinessTip> {
        return tips.filter { it.category == category }.map { tip ->
            BusinessTip(
                id = tip.id,
                text = if (language == "sw") tip.textSw else tip.textEn,
                category = tip.category,
                relevance = 1.0f
            )
        }
    }

    /**
     * Get a morning market tip for the briefing.
     */
    fun getMorningTip(language: String = "sw"): BusinessTip {
        val morningTips = tips.filter { it.timeOfDay == "morning" || it.timeOfDay == "any" }
        val tip = morningTips.randomOrNull() ?: tips.first()

        return BusinessTip(
            id = tip.id,
            text = if (language == "sw") tip.textSw else tip.textEn,
            category = tip.category,
            relevance = 1.0f
        )
    }

    /**
     * Get an evening reflection tip.
     */
    fun getEveningTip(language: String = "sw"): BusinessTip {
        val eveningTips = tips.filter { it.timeOfDay == "evening" || it.timeOfDay == "any" }
        val tip = eveningTips.randomOrNull() ?: tips.first()

        return BusinessTip(
            id = tip.id,
            text = if (language == "sw") tip.textSw else tip.textEn,
            category = tip.category,
            relevance = 1.0f
        )
    }

    // ═══════════════ RELEVANCE SCORING ═══════════════

    private fun isTipRelevant(tip: TipDef, context: TipContext): Boolean {
        // Business type filter
        if (tip.businessTypes.isNotEmpty() && context.businessType !in tip.businessTypes) {
            return false
        }

        // Time of day filter
        if (tip.timeOfDay != "any" && tip.timeOfDay != context.timeOfDay) {
            return false
        }

        return true
    }

    private fun calculateRelevance(tip: TipDef, context: TipContext): Float {
        var score = 0.5f

        // Business type match
        if (context.businessType in tip.businessTypes) score += 0.3f

        // Time of day match
        if (tip.timeOfDay == context.timeOfDay) score += 0.2f

        return score.coerceIn(0f, 1f)
    }

    // ═══════════════ TIPS LIBRARY ═══════════════

    private fun buildTipsLibrary(): List<TipDef> {
        return listOf(
            // ═══ PRICING TIPS ═══
            TipDef("price_001", "PRICING", "any", listOf("food", "general"),
                "Bei yako iwe na faida. Gharama + 30% = bei ya kuuza. Usiweke bei ya chini — utapoteza pesa!",
                "Your price must include profit. Cost + 30% = selling price. Don't price too low — you'll lose money!"
            ),
            TipDef("price_002", "PRICING", "morning", listOf("food", "general"),
                "Kabla ya kuuza, jua gharama yako. Kila bidhaa ina bei ya kununua. Ongeza faida ya angalau 20%.",
                "Before selling, know your cost. Every item has a buy price. Add at least 20% profit."
            ),
            TipDef("price_003", "PRICING", "any", listOf("general"),
                "Wateja wanapenda bei nzuri — sio bei ya chini. Weka bei inayouza na kukupa faida.",
                "Customers like fair prices — not cheap prices. Set a price that sells and gives you profit."
            ),
            TipDef("price_004", "PRICING", "any", listOf("food", "mama_mboga"),
                "Bidhaa mpya? Anza bei ya kati. Angalia wateja wananunua vipi. Kisha adjust.",
                "New product? Start with a middle price. Watch how customers buy. Then adjust."
            ),

            // ═══ STOCK TIPS ═══
            TipDef("stock_001", "STOCK", "morning", listOf("food", "mama_mboga", "general"),
                "Kabla ya kufungua duka, angalia stock yako. Bidhaa gani zinakaribia kuisha? Nunua leo!",
                "Before opening, check your stock. Which items are running low? Buy today!"
            ),
            TipDef("stock_002", "STOCK", "any", listOf("food", "mama_mboga"),
                "Usinunue bidhaa nyingi sana — zitaharibika. Nunua kiasi unachoweza kuuza wiki moja.",
                "Don't buy too much — it will spoil. Buy what you can sell in one week."
            ),
            TipDef("stock_003", "STOCK", "evening", listOf("food", "general"),
                "Jioni, hesabu stock yako. Bidhaa gani zilisonga vizuri? Nunua zaidi kesho.",
                "In the evening, count your stock. Which items sold well? Buy more tomorrow."
            ),
            TipDef("stock_004", "STOCK", "any", listOf("general"),
                "Stock ndogo, mauzo mengi = faida. Stock kubwa, mauzo kidogo = hasara. Pata usawa!",
                "Small stock, many sales = profit. Big stock, few sales = loss. Find the balance!"
            ),

            // ═══ CUSTOMER TIPS ═══
            TipDef("customer_001", "CUSTOMER", "any", listOf("food", "mama_mboga", "general"),
                "Mteja anayerudi ni thamani ya biashara yako. Mpende, msikilize, mpe huduma nzuri.",
                "A returning customer is the value of your business. Love them, listen to them, give good service."
            ),
            TipDef("customer_002", "CUSTOMER", "any", listOf("food", "general"),
                "Tabasumu linavutia wateja. Ongea vizuri. Mteja anayefurahia atarudi na rafiki yake.",
                "A smile attracts customers. Speak well. A happy customer will return with a friend."
            ),
            TipDef("customer_003", "CUSTOMER", "any", listOf("general"),
                "Jina lako la biashara ni muhimu. Kuwa na sifa nzuri — wateja watakuja wenyewe.",
                "Your business reputation matters. Have a good name — customers will come on their own."
            ),
            TipDef("customer_004", "CUSTOMER", "evening", listOf("food", "mama_mboga", "general"),
                "Wateja wako wakuu ni nani? Wajue kwa majina. Wape huduma ya kipekee.",
                "Who are your best customers? Know them by name. Give them special service."
            ),

            // ═══ SAVINGS TIPS ═══
            TipDef("savings_001", "SAVINGS", "evening", listOf("general", "food", "mama_mboga"),
                "Kabla ya kulala, weka akiba. Hata KSh 50. Muhimu ni kuanza. Akiba haiozi!",
                "Before sleeping, save something. Even KSh 50. The important thing is to start. Savings never rot!"
            ),
            TipDef("savings_002", "SAVINGS", "any", listOf("general"),
                "Njia ya akiba: weka 10% ya mauzo yako kando. KSh 1,000? Weka KSh 100.",
                "Savings method: set aside 10% of your sales. KSh 1,000? Save KSh 100."
            ),
            TipDef("savings_003", "SAVINGS", "any", listOf("general"),
                "Akiba ya dharura: weka pesa ya siku 30 za gharama zako. Hii ni kinga yako.",
                "Emergency fund: save money for 30 days of expenses. This is your safety net."
            ),
            TipDef("savings_004", "SAVINGS", "morning", listOf("general"),
                "Kabla ya kununua bidhaa, jiulize: Je, nina akiba? Usitumie akiba ya dharura kwa biashara.",
                "Before buying stock, ask: Do I have savings? Don't use emergency funds for business."
            ),

            // ═══ MARKET TIPS ═══
            TipDef("market_001", "MARKET", "morning", listOf("food", "mama_mboga"),
                "Soko la leo? Angalia bei za jirani kabla ya kuweka bei yako. Weka bei ya ushindani.",
                "Today's market? Check neighbor prices before setting yours. Set competitive prices."
            ),
            TipDef("market_002", "MARKET", "any", listOf("food", "mama_mboga"),
                "Siku ya soko kuu = mauzo mengi. Jitayarishe na stock ya kutosha!",
                "Main market day = many sales. Prepare with enough stock!"
            ),
            TipDef("market_003", "MARKET", "morning", listOf("general"),
                "Asubuhi ndio wakati bora wa kuuza. Wateja wana nguvu na pesa. Fungua mapema!",
                "Morning is the best time to sell. Customers have energy and money. Open early!"
            ),
            TipDef("market_004", "MARKET", "any", listOf("general"),
                "Mahali pa biashara ni muhimu. Penye watu wengi, penye ushindani kidogo.",
                "Business location matters. Where there are many people, where there's less competition."
            ),

            // ═══ GROWTH TIPS ═══
            TipDef("growth_001", "GROWTH", "any", listOf("general"),
                "Biashara inakua polepole. Usikate tamaa. Kila siku ya mauzo ni hatua ya mbele.",
                "Business grows slowly. Don't give up. Every sales day is a step forward."
            ),
            TipDef("growth_002", "GROWTH", "any", listOf("general"),
                "Njia ya kukua: ongeza bidhaa mpya. Jaribu. Angalia matokeo. Endelea na ile inayofanya kazi.",
                "Growth method: add new products. Try. Check results. Continue with what works."
            ),
            TipDef("growth_003", "GROWTH", "any", listOf("general"),
                "Jifunze kutoka kwa wengine. Angalia mfanyabiashara mwenye mafanikio. Uliza maswali.",
                "Learn from others. Watch a successful business person. Ask questions."
            ),
            TipDef("growth_004", "GROWTH", "evening", listOf("general"),
                "Jioni, fikiri: Nimefanya nini vizuri leo? Nini niboreshe? Kila siku ni somo.",
                "In the evening, think: What did I do well today? What can I improve? Every day is a lesson."
            ),

            // ═══ GENERAL WISDOM ═══
            TipDef("wisdom_001", "GENERAL", "any", listOf("general"),
                "Biashara ni kama mbegu — inahitaji muda, maji, na jua. Subiri. Itakua!",
                "Business is like a seed — it needs time, water, and sun. Wait. It will grow!"
            ),
            TipDef("wisdom_002", "GENERAL", "any", listOf("general"),
                "Deni ni kama nyoka — ukilibeba unakutaga sumu. Lipa denzi kabla ya kununua zaidi.",
                "Debt is like a snake — carry it and it bites. Pay debts before buying more."
            ),
            TipDef("wisdom_003", "GENERAL", "morning", listOf("general"),
                "Leo ni siku mpya. Fungua duka kwa tabasumu. Wateja wanapenda eneo la furaha!",
                "Today is a new day. Open your shop with a smile. Customers love a happy place!"
            ),
            TipDef("wisdom_004", "GENERAL", "any", listOf("general"),
                "Penye nia pana njia. Ukiamini biashara yako itafanikiwa, itafanikiwa!",
                "Where there's a will, there's a way. If you believe your business will succeed, it will!"
            )
        )
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/**
 * Context for tip selection.
 */
data class TipContext(
    val businessType: String = "general",
    val timeOfDay: String = "any",
    val recentActivity: String = ""
)

/**
 * A business tip ready for delivery.
 */
data class BusinessTip(
    val id: String,
    val text: String,
    val category: String,
    val relevance: Float
)

/**
 * Internal tip definition.
 */
internal data class TipDef(
    val id: String,
    val category: String,
    val timeOfDay: String,
    val businessTypes: List<String>,
    val textSw: String,
    val textEn: String
)
