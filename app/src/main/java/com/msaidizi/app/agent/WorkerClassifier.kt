package com.msaidizi.app.agent

import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import timber.log.Timber
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Classifies worker type from initial interactions.
 * Uses STA 442 (Discriminant Analysis) for classification.
 *
 * ## Statistical Foundation
 *
 * ### STA 442 — Applied Multivariate Analysis (Discriminant Analysis)
 * Fisher's Linear Discriminant finds the linear combination of features
 * that best separates two or more classes:
 *   D(x) = w₁x₁ + w₂x₂ + ... + wₙxₙ
 *
 * For K classes, we use Multiple Discriminant Analysis (MDA) which
 * produces (K-1) discriminant functions. Each function maximizes
 * the between-class variance relative to within-class variance:
 *   max w'S_B w / w'S_W w
 *
 * ### Implementation
 * We use a simplified discriminant approach with weighted feature
 * vectors and nearest-centroid classification. This is computationally
 * efficient for on-device use (no matrix inversion needed) while
 * maintaining good classification accuracy from 10-20 transactions.
 *
 * @see BusinessAgent for transaction recording
 * @see AnalysisAgent for statistical analysis
 */
object WorkerClassifier {

    // ═══════════════════════════════════════════════════════════════
    // STA 442 §3.1 — DISCRIMINANT FEATURES: Feature extraction
    // ═══════════════════════════════════════════════════════════════

    /**
     * Feature vector dimensions for discriminant analysis.
     *
     * Each feature captures a different aspect of worker behavior
     * that discriminates between worker types.
     */
    private data class FeatureVector(
        val avgTransactionAmount: Double,       // F1: Price level
        val transactionFrequency: Double,       // F2: Transactions per day
        val categoryDiversity: Double,          // F3: Distinct categories / total txns
        val perishableRatio: Double,            // F4: Perishable items / total items
        val serviceRatio: Double,               // F5: Service transactions / total
        val transportRatio: Double,             // F6: Transport-related / total
        val digitalRatio: Double,               // F7: Digital/mobile money / total
        val agricultureRatio: Double,           // F8: Agricultural items / total
        val manufacturingRatio: Double,         // F9: Manufacturing/artisan / total
        val avgQuantityPerTx: Double,           // F10: Typical quantities
        val expenseToSalesRatio: Double,        // F11: Cost structure
        val peakHourSpread: Double              // F12: How concentrated are transactions
    )

    // ═══════════════════════════════════════════════════════════════
    // STA 442 §3.2 — CLASS CENTROIDS: Learned from domain knowledge
    // ═══════════════════════════════════════════════════════════════

    /**
     * Class centroids for each worker type.
     * These represent the "average" feature vector for each type.
     *
     * Derived from domain knowledge of Kenya's informal economy:
     * - Mama mboga: low amounts, high frequency, perishable goods
     * - Boda boda: medium amounts, variable frequency, transport category
     * - Farmers: seasonal, agricultural items, high quantities
     * - Hairdressers: service-based, medium amounts, repeat customers
     * - M-Pesa agents: digital transactions, very high frequency
     */
    private val CLASS_CENTROIDS = mapOf(
        WorkerType.TRADER to FeatureVector(
            avgTransactionAmount = 250.0,      // KSh 100-500 per sale
            transactionFrequency = 15.0,       // 10-20 transactions/day
            categoryDiversity = 0.6,           // Moderate variety
            perishableRatio = 0.5,             // Mix of perishable and shelf-stable
            serviceRatio = 0.0,
            transportRatio = 0.0,
            digitalRatio = 0.1,                // Some M-Pesa payments
            agricultureRatio = 0.1,
            manufacturingRatio = 0.0,
            avgQuantityPerTx = 3.0,            // A few items per sale
            expenseToSalesRatio = 0.65,        // ~65% cost of goods
            peakHourSpread = 0.7               // Fairly spread out
        ),
        WorkerType.TRANSPORT to FeatureVector(
            avgTransactionAmount = 150.0,      // KSh 50-300 per trip
            transactionFrequency = 20.0,       // Many short trips
            categoryDiversity = 0.1,           // Very focused
            perishableRatio = 0.0,
            serviceRatio = 0.0,
            transportRatio = 0.9,              // Almost all transport
            digitalRatio = 0.2,
            agricultureRatio = 0.0,
            manufacturingRatio = 0.0,
            avgQuantityPerTx = 1.0,            // 1 passenger per trip
            expenseToSalesRatio = 0.35,        // Fuel is ~35%
            peakHourSpread = 0.5               // Concentrated peak hours
        ),
        WorkerType.FARMER to FeatureVector(
            avgTransactionAmount = 800.0,      // Larger bulk sales
            transactionFrequency = 3.0,        // Low frequency (seasonal)
            categoryDiversity = 0.3,           // Few crop types
            perishableRatio = 0.6,             // Fresh produce
            serviceRatio = 0.0,
            transportRatio = 0.1,
            digitalRatio = 0.05,
            agricultureRatio = 0.8,            // Mostly agricultural
            manufacturingRatio = 0.0,
            avgQuantityPerTx = 20.0,           // Bulk quantities (kg)
            expenseToSalesRatio = 0.45,        // Input costs
            peakHourSpread = 0.3               // Seasonal, not daily
        ),
        WorkerType.SERVICE to FeatureVector(
            avgTransactionAmount = 400.0,      // KSh 100-1000 per service
            transactionFrequency = 8.0,        // 5-12 clients/day
            categoryDiversity = 0.2,           // Focused on service type
            perishableRatio = 0.0,
            serviceRatio = 0.9,                // Almost all services
            transportRatio = 0.0,
            digitalRatio = 0.15,
            agricultureRatio = 0.0,
            manufacturingRatio = 0.1,          // Some parts/materials
            avgQuantityPerTx = 1.0,            // 1 service per transaction
            expenseToSalesRatio = 0.25,        // Low material cost, high labor value
            peakHourSpread = 0.6
        ),
        WorkerType.MANUFACTURING to FeatureVector(
            avgTransactionAmount = 2000.0,     // Higher value items
            transactionFrequency = 3.0,        // Fewer, larger jobs
            categoryDiversity = 0.3,
            perishableRatio = 0.0,
            serviceRatio = 0.3,                // Some labor component
            transportRatio = 0.0,
            digitalRatio = 0.1,
            agricultureRatio = 0.0,
            manufacturingRatio = 0.8,          // Mostly manufactured goods
            avgQuantityPerTx = 1.0,            // Custom items
            expenseToSalesRatio = 0.50,        // Materials + labor
            peakHourSpread = 0.8               // Spread through workday
        ),
        WorkerType.DIGITAL to FeatureVector(
            avgTransactionAmount = 500.0,      // Variable
            transactionFrequency = 30.0,       // Very high frequency
            categoryDiversity = 0.1,           // Focused on digital
            perishableRatio = 0.0,
            serviceRatio = 0.2,
            transportRatio = 0.0,
            digitalRatio = 0.9,                // Almost all digital
            agricultureRatio = 0.0,
            manufacturingRatio = 0.0,
            avgQuantityPerTx = 1.0,
            expenseToSalesRatio = 0.15,        // Low marginal cost
            peakHourSpread = 0.9               // Always "on"
        )
    )

    // ═══════════════════════════════════════════════════════════════
    // STA 442 §3.3 — FEATURE WEIGHTS: Discriminant power
    // ═══════════════════════════════════════════════════════════════

    /**
     * Feature weights for discriminant analysis.
     * Higher weight = more discriminating power.
     *
     * Based on domain analysis of which features best separate
     * Kenya's informal worker types.
     */
    private val FEATURE_WEIGHTS = doubleArrayOf(
        0.08,  // avgTransactionAmount — moderate discriminator
        0.10,  // transactionFrequency — good discriminator
        0.07,  // categoryDiversity
        0.12,  // perishableRatio — strong for farmers/traders vs others
        0.15,  // serviceRatio — strong for services vs goods
        0.15,  // transportRatio — strong for transport workers
        0.12,  // digitalRatio — strong for digital/gig workers
        0.10,  // agricultureRatio — strong for farmers
        0.06,  // manufacturingRatio
        0.03,  // avgQuantityPerTx
        0.01,  // expenseToSalesRatio
        0.01   // peakHourSpread
    )

    // ═══════════════════════════════════════════════════════════════
    // CLASSIFICATION ENGINE
    // ═══════════════════════════════════════════════════════════════

    /**
     * Classify worker type from a list of transactions.
     *
     * **STA 442 §3.1 (Nearest Centroid Classification):**
     * Assign the observation to the class whose centroid is closest
     * in weighted feature space:
     *   class(x) = argmin_k ||W ⊙ (x - μ_k)||
     *
     * where W is the weight vector, ⊙ is element-wise multiplication,
     * and μ_k is the centroid for class k.
     *
     * **Minimum data requirement:** 5 transactions for basic classification,
     * 10-20 for reliable classification. With < 5 transactions, returns
     * UNKNOWN with low confidence.
     *
     * @param transactions List of recorded transactions
     * @return WorkerClassification with type, confidence, and feature breakdown
     */
    fun classify(transactions: List<Transaction>): WorkerClassification {
        if (transactions.size < 5) {
            Timber.d("Insufficient transactions for classification: %d", transactions.size)
            return WorkerClassification(
                type = WorkerType.UNKNOWN,
                confidence = 0.0,
                transactionsAnalyzed = transactions.size,
                featureBreakdown = emptyMap()
            )
        }

        // Extract feature vector from transactions
        val features = extractFeatures(transactions)
        val featureArray = featureToArray(features)

        // Calculate weighted distance to each class centroid
        val distances = CLASS_CENTROIDS.map { (workerType, centroid) ->
            val centroidArray = featureToArray(centroid)
            val distance = weightedEuclideanDistance(featureArray, centroidArray, FEATURE_WEIGHTS)
            workerType to distance
        }

        // Sort by distance (nearest first)
        val sorted = distances.sortedBy { it.second }

        // Calculate confidence using softmax of negative distances
        // Confidence = exp(-d_k) / Σ exp(-d_i)
        val minDist = sorted.first().second
        val expScores = sorted.map { (type, dist) ->
            type to Math.exp(-(dist - minDist))  // Shift for numerical stability
        }
        val totalExp = expScores.sumOf { it.second }
        val confidenceScores = expScores.map { (type, score) ->
            type to score / totalExp
        }

        val bestType = confidenceScores.first().first
        val confidence = confidenceScores.first().second

        Timber.d(
            "Worker classification: %s (%.1f%% confidence, %d transactions)",
            bestType, confidence * 100, transactions.size
        )

        return WorkerClassification(
            type = bestType,
            confidence = confidence,
            transactionsAnalyzed = transactions.size,
            featureBreakdown = confidenceScores.toMap(),
            features = features
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // FEATURE EXTRACTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extract feature vector from transaction list.
     *
     * Each feature is normalized to [0, 1] range for comparable distances.
     */
    private fun extractFeatures(transactions: List<Transaction>): FeatureVector {
        val n = transactions.size.toDouble()

        // F1: Average transaction amount (normalized)
        val avgAmount = transactions.map { it.totalAmount }.average()
        val normalizedAmount = (avgAmount / 5000.0).coerceIn(0.0, 1.0)

        // F2: Transaction frequency (transactions per day)
        val timeSpanDays = if (transactions.size >= 2) {
            val first = transactions.minOf { it.createdAt }
            val last = transactions.maxOf { it.createdAt }
            ((last - first) / 86400.0).coerceAtLeast(1.0)
        } else 1.0
        val frequency = (transactions.size / timeSpanDays / 50.0).coerceIn(0.0, 1.0)

        // F3: Category diversity (distinct categories / total)
        val categories = transactions.map { it.category }.distinct().size
        val diversity = (categories / 10.0).coerceIn(0.0, 1.0)

        // F4: Perishable ratio
        val perishableCats = setOf("produce", "protein", "prepared_food")
        val perishableCount = transactions.count { it.category in perishableCats }
        val perishableRatio = perishableCount / n

        // F5: Service ratio (transactions with service-related categories)
        val serviceCats = setOf("service", "beauty", "repair", "laundry", "salon")
        val serviceCount = transactions.count {
            it.category in serviceCats || it.type == TransactionType.EXPENSE && it.item.contains("service")
        }
        val serviceRatio = serviceCount / n

        // F6: Transport ratio
        val transportItems = setOf("usafiri", "fare", "trip", "delivery", "pikipiki", "matatu", "boda")
        val transportCount = transactions.count {
            it.category == "transport" || transportItems.any { kw -> it.item.contains(kw, ignoreCase = true) }
        }
        val transportRatio = transportCount / n

        // F7: Digital ratio (M-Pesa, float, airtime)
        val digitalItems = setOf("mpesa", "m-pesa", "float", "airtime", "data", "bundle", "commission")
        val digitalCount = transactions.count {
            it.paymentMethod == "mpesa" ||
            digitalItems.any { kw -> it.item.contains(kw, ignoreCase = true) }
        }
        val digitalRatio = digitalCount / n

        // F8: Agriculture ratio
        val agriItems = setOf("mbegu", "fertilizer", "mbolea", "dawa", "shamba", "mazao",
            "mahindi", "maharagwe", "viazi", "nyanya", "sukuma", "embe", "ndizi")
        val agriCount = transactions.count {
            it.category == "agriculture" ||
            agriItems.any { kw -> it.item.contains(kw, ignoreCase = true) }
        }
        val agriRatio = agriCount / n

        // F9: Manufacturing ratio
        val mfgItems = setOf("chuma", "mbao", "furniture", "meza", "kiti", "bed", "welding",
            "fabric", "nguo", "ushona", "brick", "tofali")
        val mfgCount = transactions.count {
            it.category == "manufacturing" ||
            mfgItems.any { kw -> it.item.contains(kw, ignoreCase = true) }
        }
        val mfgRatio = mfgCount / n

        // F10: Average quantity per transaction
        val avgQty = transactions.map { it.quantity }.average()
        val normalizedQty = (avgQty / 50.0).coerceIn(0.0, 1.0)

        // F11: Expense to sales ratio
        val sales = transactions.filter { it.type == TransactionType.SALE }.sumOf { it.totalAmount }
        val expenses = transactions.filter {
            it.type == TransactionType.EXPENSE || it.type == TransactionType.PURCHASE
        }.sumOf { it.totalAmount }
        val expenseRatio = if (sales > 0) (expenses / sales).coerceIn(0.0, 1.0) else 0.5

        // F12: Peak hour spread (how concentrated are transactions)
        // Low spread = concentrated peaks (transport), high = spread out (always-on)
        val hourBuckets = transactions.groupBy { (it.createdAt % 86400) / 3600 }
        val maxBucketPct = hourBuckets.values.maxOfOrNull { it.size / n } ?: 0.0
        val spread = 1.0 - maxBucketPct  // Inverse of concentration

        return FeatureVector(
            avgTransactionAmount = normalizedAmount,
            transactionFrequency = frequency,
            categoryDiversity = diversity,
            perishableRatio = perishableRatio,
            serviceRatio = serviceRatio,
            transportRatio = transportRatio,
            digitalRatio = digitalRatio,
            agricultureRatio = agriRatio,
            manufacturingRatio = mfgRatio,
            avgQuantityPerTx = normalizedQty,
            expenseToSalesRatio = expenseRatio,
            peakHourSpread = spread
        )
    }

    /**
     * Convert feature vector to double array for distance calculations.
     */
    private fun featureToArray(f: FeatureVector): DoubleArray = doubleArrayOf(
        f.avgTransactionAmount,
        f.transactionFrequency,
        f.categoryDiversity,
        f.perishableRatio,
        f.serviceRatio,
        f.transportRatio,
        f.digitalRatio,
        f.agricultureRatio,
        f.manufacturingRatio,
        f.avgQuantityPerTx,
        f.expenseToSalesRatio,
        f.peakHourSpread
    )

    /**
     * **STA 442 §3.1:** Weighted Euclidean distance.
     *   d(x, μ) = √(Σ wᵢ(xᵢ - μᵢ)²)
     */
    private fun weightedEuclideanDistance(
        x: DoubleArray,
        mu: DoubleArray,
        weights: DoubleArray
    ): Double {
        var sum = 0.0
        for (i in x.indices) {
            val diff = x[i] - mu[i]
            sum += weights[i] * diff * diff
        }
        return sqrt(sum)
    }

    // ═══════════════════════════════════════════════════════════════
    // VOCABULARY-BASED CLASSIFICATION (Fast path)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Quick classification from vocabulary/keywords alone.
     * Used during onboarding before enough transactions exist.
     *
     * @param spokenWords Words from initial voice interactions
     * @return Worker type hint with confidence
     */
    fun classifyFromVocabulary(spokenWords: List<String>): WorkerClassification {
        val text = spokenWords.joinToString(" ").lowercase()

        val scores = mutableMapOf<WorkerType, Double>()

        // TRADER vocabulary
        val traderWords = listOf("duka", "shop", "biashara", "nauza", "nunua", "stock",
            "mama mboga", "mboga", "sokoni", "market", "supplier", "wholesale")
        scores[WorkerType.TRADER] = traderWords.count { text.contains(it) }.toDouble()

        // TRANSPORT vocabulary
        val transportWords = listOf("boda", "pikipiki", "matatu", "abiria", "stage", "route",
            "fare", "usafiri", "passenger", "trip", "delivery", "nduthi")
        scores[WorkerType.TRANSPORT] = transportWords.count { text.contains(it) }.toDouble()

        // FARMER vocabulary
        val farmerWords = listOf("shamba", "kulima", "mazao", "mbegu", "mbolea", "mvua",
            "harvest", "vunja", "ekari", "crop", "kulima", "mkulima", "mahindi", "ngano")
        scores[WorkerType.FARMER] = farmerWords.count { text.contains(it) }.toDouble()

        // SERVICE vocabulary
        val serviceWords = listOf("kunyolewa", "salon", "haircut", "fundisha", "fundi",
            "mechanic", "repair", "service", "kazi", "mteja", "client", "appointment")
        scores[WorkerType.SERVICE] = serviceWords.count { text.contains(it) }.toDouble()

        // MANUFACTURING vocabulary
        val mfgWords = listOf("tengeneza", "fundi", "chuma", "mbao", "welding", "furniture",
            "nguo", "ushona", "tailor", "maker", "production")
        scores[WorkerType.MANUFACTURING] = mfgWords.count { text.contains(it) }.toDouble()

        // DIGITAL vocabulary
        val digitalWords = listOf("mpesa", "float", "airtime", "commission", "agent",
            "online", "social media", "instagram", "tiktok", "delivery", "gig")
        scores[WorkerType.DIGITAL] = digitalWords.count { text.contains(it) }.toDouble()

        val best = scores.maxByOrNull { it.value }
        val total = scores.values.sum()

        return if (best != null && best.value > 0 && total > 0) {
            WorkerClassification(
                type = best.key,
                confidence = best.value / total,
                transactionsAnalyzed = 0,
                featureBreakdown = scores.mapValues { it.value / total },
                source = ClassificationSource.VOCABULARY
            )
        } else {
            WorkerClassification(
                type = WorkerType.UNKNOWN,
                confidence = 0.0,
                transactionsAnalyzed = 0,
                featureBreakdown = emptyMap(),
                source = ClassificationSource.VOCABULARY
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

/**
 * Worker types in Kenya's informal economy.
 * Maps to the 6 major sectors across 25 worker subtypes.
 */
enum class WorkerType {
    /** Market traders, mama mboga, dukawallah, hawkers, mitumba sellers */
    TRADER,

    /** Boda boda, matatu, tuk-tuk, taxi/ride-hail drivers */
    TRANSPORT,

    /** Smallholder farmers, fish traders, food processors */
    FARMER,

    /** Hairdressers, barbers, mechanics, tailors, laundry workers */
    SERVICE,

    /** Jua kali workshops, furniture makers, brick makers */
    MANUFACTURING,

    /** M-Pesa agents, phone repair, social media sellers, content creators */
    DIGITAL,

    /** Not enough data to classify */
    UNKNOWN
}

/**
 * Classification source — where the classification came from.
 */
enum class ClassificationSource {
    /** Classification from transaction data (most reliable) */
    TRANSACTIONS,

    /** Classification from vocabulary/keywords (quick, less reliable) */
    VOCABULARY,

    /** Self-declared by user during onboarding */
    SELF_DECLARED
}

/**
 * Result of worker type classification.
 *
 * @param type The classified worker type
 * @param confidence Confidence score (0.0-1.0)
 * @param transactionsAnalyzed Number of transactions used
 * @param featureBreakdown Confidence scores per type
 * @param features Extracted feature vector (for debugging)
 * @param source How the classification was made
 */
data class WorkerClassification(
    val type: WorkerType,
    val confidence: Double,
    val transactionsAnalyzed: Int,
    val featureBreakdown: Map<WorkerType, Double>,
    val source: ClassificationSource = ClassificationSource.TRANSACTIONS
)
