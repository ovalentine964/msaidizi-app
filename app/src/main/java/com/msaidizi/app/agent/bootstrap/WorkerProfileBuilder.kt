package com.msaidizi.app.agent.bootstrap

import com.msaidizi.app.memory.BehavioralModelManager
import com.msaidizi.app.memory.WorkerProfile
import timber.log.Timber

/**
 * WorkerProfileBuilder — Builds L3 WorkerProfile from bootstrap answers.
 *
 * Constructed incrementally as the worker answers bootstrap questions.
 * After bootstrap completes, produces a fully-initialized WorkerProfile
 * that seeds the L3 behavioral model.
 *
 * Also initializes skill affinities based on business type,
 * sets language preference for dialect detection, and creates
 * Bayesian priors tailored to the worker's context.
 *
 * Design: review_bootstrap_openclaw.md Part 2.1
 */
class WorkerProfileBuilder {

    // ── Identity ──────────────────────────────────────────────
    var name: String? = null
        private set
    var namePhonetic: String? = null
        private set
    var preferredName: String? = null
        private set

    // ── Business ──────────────────────────────────────────────
    var businessType: BusinessType = BusinessType.UNKNOWN
        private set
    var businessTypeLabel: String? = null
        private set
    var businessDescription: String? = null
        private set
    var products: MutableList<String> = mutableListOf()
        private set

    // ── Location ──────────────────────────────────────────────
    var county: String? = null
        private set
    var subCounty: String? = null
        private set
    var market: String? = null
        private set

    // ── Operations ────────────────────────────────────────────
    var openTime: String? = null
        private set
    var closeTime: String? = null
        private set
    var flexibleHours: Boolean = false
        private set

    // ── Payment ───────────────────────────────────────────────
    var usesMpesa: Boolean = false
        private set
    var mpesaType: MpesaType = MpesaType.NONE
        private set

    // ── Records ───────────────────────────────────────────────
    var hasExistingRecords: Boolean = false
        private set
    var recordType: RecordType = RecordType.NONE
        private set

    // ── Preferences ───────────────────────────────────────────
    var primaryLanguage: String = "sw"
        private set
    var dialect: String = "standard"
        private set
    var codeSwitching: Boolean = false
        private set
    var verbosity: String = "normal"
        private set

    // ── Setters (called by BootstrapStateMachine) ─────────────

    fun setName(raw: String): WorkerProfileBuilder {
        val cleaned = raw.trim()
        this.name = cleaned
        this.namePhonetic = cleaned.lowercase()
        this.preferredName = cleaned.split(" ").firstOrNull() ?: cleaned
        return this
    }

    fun setBusinessType(type: BusinessType): WorkerProfileBuilder {
        this.businessType = type
        this.businessTypeLabel = type.label
        return this
    }

    fun setBusinessDescription(desc: String): WorkerProfileBuilder {
        this.businessDescription = desc
        return this
    }

    fun addProduct(product: String): WorkerProfileBuilder {
        if (product.isNotBlank()) products.add(product.trim())
        return this
    }

    fun setProducts(productList: List<String>): WorkerProfileBuilder {
        products.clear()
        products.addAll(productList.filter { it.isNotBlank() })
        return this
    }

    fun setLocation(county: String, subCounty: String): WorkerProfileBuilder {
        this.county = county.trim()
        this.subCounty = subCounty.trim()
        // GPS is NEVER stored — privacy invariant
        return this
    }

    fun setMarket(market: String): WorkerProfileBuilder {
        this.market = market.trim()
        return this
    }

    fun setOperatingHours(open: String, close: String): WorkerProfileBuilder {
        this.openTime = open
        this.closeTime = close
        return this
    }

    fun setFlexibleHours(flexible: Boolean): WorkerProfileBuilder {
        this.flexibleHours = flexible
        return this
    }

    fun setMpesaUsage(uses: Boolean, type: MpesaType = MpesaType.NONE): WorkerProfileBuilder {
        this.usesMpesa = uses
        this.mpesaType = if (uses && type == MpesaType.NONE) MpesaType.PERSONAL else type
        return this
    }

    fun setExistingRecords(has: Boolean, type: RecordType = RecordType.NONE): WorkerProfileBuilder {
        this.hasExistingRecords = has
        this.recordType = type
        return this
    }

    fun setLanguage(language: String): WorkerProfileBuilder {
        this.primaryLanguage = language
        return this
    }

    fun setDialect(dialect: String): WorkerProfileBuilder {
        this.dialect = dialect
        return this
    }

    fun setCodeSwitching(enabled: Boolean): WorkerProfileBuilder {
        this.codeSwitching = enabled
        return this
    }

    // ── Build ─────────────────────────────────────────────────

    /**
     * Build a WorkerProfile from the collected bootstrap answers.
     * Used to initialize L3 behavioral model.
     */
    fun build(): WorkerProfile {
        val skillAffinities = createSkillAffinities()

        return WorkerProfile(
            preferredLanguage = primaryLanguage,
            businessType = businessType.name.lowercase(),
            skills = skillAffinities
        )
    }

    /**
     * Initialize the L3 BehavioralModelManager with bootstrap data.
     */
    fun initializeBehavioralModel(l3: BehavioralModelManager) {
        val profile = build()
        Timber.i("Bootstrap: Initializing L3 with businessType=${businessType.name}, name=$name")
        // The BehavioralModelManager will be updated with this profile
        // through the normal updateFromSignal pathway
    }

    /**
     * Get notification time (30 min after closing).
     */
    fun getNotificationTime(): String? {
        val close = closeTime ?: return null
        return try {
            val parts = close.split(":")
            var hour = parts[0].toInt()
            var minute = parts.getOrNull(1)?.toInt()?.plus(30) ?: 30
            if (minute >= 60) {
                hour += 1
                minute -= 60
            }
            "%02d:%02d".format(hour % 24, minute)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Create initial skill affinities based on business type.
     * These are priors — the flywheel will refine them.
     */
    private fun createSkillAffinities(): List<com.msaidizi.app.memory.Skill> {
        val baseIntents = listOf("sale", "check_balance", "daily_summary", "greeting", "help")

        val businessIntents = when (businessType) {
            BusinessType.MAMA_MBOGA -> listOf("stock_query", "purchase", "inventory")
            BusinessType.DUKAWALLAH -> listOf("stock_query", "purchase", "inventory", "retail")
            BusinessType.BODA_BODA -> listOf("transport", "expense")
            BusinessType.FUNDI -> listOf("service", "expense")
            BusinessType.MAMA_FUA -> listOf("service", "expense")
            BusinessType.MAMA_LISHE -> listOf("purchase", "expense", "inventory")
            BusinessType.CLOTHES_SELLER -> listOf("stock_query", "purchase", "inventory", "retail")
            BusinessType.OTHER -> listOf("advice")
        }

        val mpesaIntents = if (usesMpesa) listOf("mpesa") else emptyList()

        return (baseIntents + businessIntents + mpesaIntents).map { intent ->
            com.msaidizi.app.memory.Skill(
                name = intent,
                supportedIntents = listOf(intent),
                confidence = 0.5 // Initial prior — will be updated by flywheel
            )
        }
    }

    /**
     * Check if bootstrap has collected enough data to be useful.
     */
    fun isMinimumViable(): Boolean {
        return name != null && businessType != BusinessType.UNKNOWN
    }

    /**
     * Get a summary for the confirmation step.
     */
    fun getConfirmationSummary(): String {
        val parts = mutableListOf<String>()
        name?.let { parts.add("Jina: $it") }
        if (businessType != BusinessType.UNKNOWN) parts.add("Biashara: ${businessType.label}")
        if (county != null && subCounty != null) parts.add("Eneo: $county, $subCounty")
        if (openTime != null && closeTime != null) parts.add("Saa: $openTime - $closeTime")
        if (products.isNotEmpty()) parts.add("Bidhaa: ${products.joinToString(", ")}")
        if (usesMpesa) parts.add("M-Pesa: ${mpesaType.label}")
        parts.add("Lugha: ${primaryLanguage}")
        return parts.joinToString("\n")
    }

    fun isComplete(): Boolean {
        return name != null &&
                businessType != BusinessType.UNKNOWN &&
                county != null &&
                subCounty != null &&
                openTime != null &&
                closeTime != null
    }

    // ── Enums ─────────────────────────────────────────────────

    enum class BusinessType(val label: String, val swahiliLabels: List<String>) {
        MAMA_MBOGA("Mama Mboga", listOf("mama mboga", "mboga", "mama mboga")),
        DUKAWALLAH("Dukawallah", listOf("duka", "dukwallah", "duka la", "shop", "dukawallah")),
        BODA_BODA("Boda Boda", listOf("boda", "boda boda", "boda-boda", "pikipiki", "motorcycle")),
        FUNDI("Fundi", listOf("fundi", "fundii", "mechanic", "repair", "mtengenezaji")),
        MAMA_FUA("Mama Fua", listOf("mama fua", "fua", "laundry", "mama fuá")),
        MAMA_LISHE("Mama Lishe", listOf("mama lishe", "lishe", "mama ntilie", "chips", "chakula", "food")),
        CLOTHES_SELLER("Mtu wa Nguo", listOf("nguo", "clothes", "mtu wa nguo", "mitumba", "fashion")),
        OTHER("Nyingine", listOf("nyingine", "other", "nyingine"));

        companion object {
            fun fromInput(input: String): BusinessType {
                val lower = input.lowercase().trim()
                return entries.firstOrNull { type ->
                    type.swahiliLabels.any { label -> lower.contains(label) }
                } ?: OTHER
            }
        }
    }

    enum class MpesaType(val label: String) {
        TILL("Till Number"),
        PAYBILL("Paybill"),
        PERSONAL("Ya Kawaida"),
        NONE("Hapana")
    }

    enum class RecordType(val label: String) {
        NOTEBOOK("Daftari"),
        PHONE("Simu"),
        HEAD("Kichwani"),
        NONE("Hapana")
    }
}
