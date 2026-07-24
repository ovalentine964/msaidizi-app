package com.msaidizi.app.superagent.skills

/**
 * Skill Models — definitions for the 6 crystallizable skill types.
 *
 * Each skill type has:
 * - A catalog entry with name, description, trigger, and output templates
 * - i18n support (Swahili and English)
 * - Lifecycle states (proposed → trial → active → retired)
 *
 * ## Skill Catalog
 *
 * ### 📋 Morning Briefing
 * - Trigger: Time-based (worker's morning routine)
 * - Content: Market conditions, weather, daily reminders, price highlights
 * - Output: Short message (≤5 lines), voice option for low-literacy workers
 *
 * ### 📦 Restock Alert
 * - Trigger: Context-based (estimated inventory depletion)
 * - Content: Item name, days remaining, suggested order quantity
 * - Output: Alert with actionable next step
 *
 * ### 💰 Price Check
 * - Trigger: Worker mentions buying a product
 * - Content: Compare prices across known suppliers
 * - Output: Ranked price list with supplier name and last-known price
 *
 * ### 💸 Savings Nudge
 * - Trigger: Context-based (spending exceeds rolling average)
 * - Content: Spending comparison, category breakdown
 * - Output: Supportive nudge (never judgmental)
 *
 * ### 📅 Market Day Preparation
 * - Trigger: Time-based (day before known market days)
 * - Content: Past performance, weather, suggested stock
 * - Output: Actionable checklist
 *
 * ### 📊 Weekly Report
 * - Trigger: Time-based (end of week, typically Sunday evening)
 * - Content: Revenue, top items, trends, savings progress
 * - Output: Simple summary with one actionable insight
 */
object SkillCatalog {

    /**
     * All available skill types with their templates.
     */
    val SKILL_TYPES = listOf(
        SkillTemplate(
            type = SkillType.MORNING_BRIEFING,
            nameSw = "Muhtasari wa Asubuhi",
            nameEn = "Morning Briefing",
            descriptionSw = "Nitakutumia muhtasari wa soko kila asubuhi kabla ya saa 12.",
            descriptionEn = "I'll send you a market summary every morning before noon.",
            triggerType = "time",
            triggerDescription = "Kila asubuhi",
            actionTemplate = """
                1. Angalia bei za soko za bidhaa zako
                2. Linganisha na bei za jana
                3. Angalia hali ya hewa
                4. Andika muhtasari mfupi
            """.trimIndent(),
            outputTemplateSw = "Bei za soko tarehe {date}: {items}",
            outputTemplateEn = "Market prices for {date}: {items",
            icon = "📋"
        ),
        SkillTemplate(
            type = SkillType.RESTOCK_ALERT,
            nameSw = "Tahadhari ya Stock",
            nameEn = "Restock Alert",
            descriptionSw = "Nitakuonyesha bidhaa zinazokaribia kuisha kabla ya kuisha.",
            descriptionEn = "I'll warn you about items running low before they run out.",
            triggerType = "context",
            triggerDescription = "Stock inapungua",
            actionTemplate = """
                1. Kwa kila bidhaa: hesabu siku zilizobaki
                2. Siku zilizobaki = stock / wastani wa mauzo ya siku
                3. Kama siku < 3: tuma tahadhari
            """.trimIndent(),
            outputTemplateSw = "{item} inaweza kuisha baada ya siku {days}. Nunua sasa!",
            outputTemplateEn = "{item} may run out in {days} days. Buy now!",
            icon = "📦"
        ),
        SkillTemplate(
            type = SkillType.PRICE_CHECK,
            nameSw = "Kuangalia Bei",
            nameEn = "Price Check",
            descriptionSw = "Nitalinganisha bei za wasambazaji unapotaka kununua.",
            descriptionEn = "I'll compare supplier prices when you're ready to buy.",
            triggerType = "context",
            triggerDescription = "Unapotaja kununua",
            actionTemplate = """
                1. Tafuta bei za wasambazaji wako
                2. Linganisha bei za soko
                3. Ongeza gharama za usafiri
                4. Pendekeza bei bora
            """.trimIndent(),
            outputTemplateSw = "Bei za {item}: {supplier1} KSh {price1}, {supplier2} KSh {price2}",
            outputTemplateEn = "{item} prices: {supplier1} KSh {price1}, {supplier2} KSh {price2}",
            icon = "💰"
        ),
        SkillTemplate(
            type = SkillType.SAVINGS_NUDGE,
            nameSw = "Kukumbusha Akiba",
            nameEn = "Savings Nudge",
            descriptionSw = "Nitakukumbusha unapotumia zaidi ya kawaida.",
            descriptionEn = "I'll nudge you when spending exceeds your usual pattern.",
            triggerType = "context",
            triggerDescription = "Matumizi yanapokuwa juu",
            actionTemplate = """
                1. Linganisha matumizi ya wiki hii na wiki 4 zilizopita
                2. Kama matumizi ni 30%+ juu: tuma nudge
                3. Onyesha kategoria inayoongoza
            """.trimIndent(),
            outputTemplateSw = "Umefaidi KSh {amount} zaidi ya kawaida wiki hii. Kategoria kubwa: {category}.",
            outputTemplateEn = "You've spent KSh {amount} more than usual this week. Top category: {category}.",
            icon = "💸"
        ),
        SkillTemplate(
            type = SkillType.MARKET_DAY_PREP,
            nameSw = "Maandalizi ya Siku ya Soko",
            nameEn = "Market Day Preparation",
            descriptionSw = "Nitakutayarisha jioni kabla ya siku ya soko.",
            descriptionEn = "I'll prepare you the evening before market days.",
            triggerType = "time",
            triggerDescription = "Jioni kabla ya soko",
            actionTemplate = """
                1. Angalia mauzo ya siku kama hii zilizopita
                2. Angalia hali ya hewa
                3. Pendekeza kiasi cha stock
                4. Andika orodha ya vitu
            """.trimIndent(),
            outputTemplateSw = "Kesho ni soko. Mauzo ya mwisho: {lastSales}. Hali ya hewa: {weather}.",
            outputTemplateEn = "Tomorrow is market day. Last sales: {lastSales}. Weather: {weather}.",
            icon = "📅"
        ),
        SkillTemplate(
            type = SkillType.WEEKLY_REPORT,
            nameSw = "Ripoti ya Wiki",
            nameEn = "Weekly Report",
            descriptionSw = "Nitakutumia ripoti ya biashara kila Jumapili jioni.",
            descriptionEn = "I'll send you a business report every Sunday evening.",
            triggerType = "time",
            triggerDescription = "Jumapili jioni",
            actionTemplate = """
                1. Hesabu mauzo ya wiki
                2. Tafuta bidhaa bora
                3. Linganisha na wiki iliyopita
                4. Angalia maendeleo ya akiba
                5. Toa fundisho moja
            """.trimIndent(),
            outputTemplateSw = "Ripoti ya wiki: mauzo {sales}, faida KSh {profit}. Bidhaa bora: {topItem}.",
            outputTemplateEn = "Weekly report: {sales} sales, profit KSh {profit}. Top item: {topItem}.",
            icon = "📊"
        )
    )

    /**
     * Get a skill template by type.
     */
    fun getTemplate(type: SkillType): SkillTemplate? {
        return SKILL_TYPES.find { it.type == type }
    }

    /**
     * Get skill name in the worker's language.
     */
    fun getName(type: SkillType, language: String = "sw"): String {
        val template = getTemplate(type) ?: return type.name
        return if (language == "sw") template.nameSw else template.nameEn
    }

    /**
     * Get skill description in the worker's language.
     */
    fun getDescription(type: SkillType, language: String = "sw"): String {
        val template = getTemplate(type) ?: return ""
        return if (language == "sw") template.descriptionSw else template.descriptionEn
    }
}

/**
 * Skill template with i18n support.
 */
data class SkillTemplate(
    val type: SkillType,
    val nameSw: String,
    val nameEn: String,
    val descriptionSw: String,
    val descriptionEn: String,
    val triggerType: String, // "time", "context", "request"
    val triggerDescription: String,
    val actionTemplate: String,
    val outputTemplateSw: String,
    val outputTemplateEn: String,
    val icon: String
)

/**
 * A skill proposal in the worker's language.
 */
data class LocalizedProposal(
    val proposalId: String,
    val name: String,
    val description: String,
    val trigger: String,
    val confidence: Float,
    val language: String
)
