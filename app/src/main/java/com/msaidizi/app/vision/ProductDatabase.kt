package com.msaidizi.app.vision

/**
 * Kenyan Produce Database — Catalog of recognizable products.
 *
 * Maps MobileNetV3 class indices to product metadata.
 * Prices reflect typical market prices in Nairobi/Kenya (2024-2025).
 *
 * The classifier model is fine-tuned on these 10 product classes:
 *   0: nyanya (tomato)
 *   1: vitunguu (onion)
 *   2: sukuma (kale/collard greens)
 *   3: mboga (mixed greens/spinach)
 *   4: viazi (potato)
 *   5: ndizi (banana)
 *   6: embe (mango)
 *   7: parachichi (avocado)
 *   8: limau (lime)
 *   9: pilipili (chili)
 *
 * Price data sourced from Kenya's National Farmers Information Service (NAFIS)
 * and typical open-air market prices.
 */
object ProductDatabase {

    /**
     * Master product catalog — indexed by class index.
     */
    val products: List<ProductEntry> = listOf(
        ProductEntry(
            classIndex = 0,
            swahiliName = "nyanya",
            englishName = "tomato",
            category = "produce",
            defaultPriceKSh = 50.0,
            unit = "kg",
            aliases = listOf("nyanya", "tomato", "matomato")
        ),
        ProductEntry(
            classIndex = 1,
            swahiliName = "vitunguu",
            englishName = "onion",
            category = "produce",
            defaultPriceKSh = 80.0,
            unit = "kg",
            aliases = listOf("vitunguu", "onion", "vitunguu vya kupikia")
        ),
        ProductEntry(
            classIndex = 2,
            swahiliName = "sukuma",
            englishName = "kale",
            category = "greens",
            defaultPriceKSh = 20.0,
            unit = "bunch",
            aliases = listOf("sukuma", "sukuma wiki", "kale", "collard")
        ),
        ProductEntry(
            classIndex = 3,
            swahiliName = "mboga",
            englishName = "greens",
            category = "greens",
            defaultPriceKSh = 25.0,
            unit = "bunch",
            aliases = listOf("mboga", "mboga za kijani", "spinach", "greens")
        ),
        ProductEntry(
            classIndex = 4,
            swahiliName = "viazi",
            englishName = "potato",
            category = "produce",
            defaultPriceKSh = 60.0,
            unit = "kg",
            aliases = listOf("viazi", "potato", "viazi vya kawaida")
        ),
        ProductEntry(
            classIndex = 5,
            swahiliName = "ndizi",
            englishName = "banana",
            category = "fruit",
            defaultPriceKSh = 20.0,
            unit = "pieces",
            aliases = listOf("ndizi", "banana", "ndizi za kupika")
        ),
        ProductEntry(
            classIndex = 6,
            swahiliName = "embe",
            englishName = "mango",
            category = "fruit",
            defaultPriceKSh = 30.0,
            unit = "pieces",
            aliases = listOf("embe", "mango", "embe yangu")
        ),
        ProductEntry(
            classIndex = 7,
            swahiliName = "parachichi",
            englishName = "avocado",
            category = "fruit",
            defaultPriceKSh = 40.0,
            unit = "pieces",
            aliases = listOf("parachichi", "avocado", "pear")
        ),
        ProductEntry(
            classIndex = 8,
            swahiliName = "limau",
            englishName = "lime",
            category = "fruit",
            defaultPriceKSh = 10.0,
            unit = "pieces",
            aliases = listOf("limau", "lime", "ndimu", "lemon")
        ),
        ProductEntry(
            classIndex = 9,
            swahiliName = "pilipili",
            englishName = "chili",
            category = "produce",
            defaultPriceKSh = 15.0,
            unit = "pieces",
            aliases = listOf("pilipili", "chili", "pilipili hoho", "pilipili kali")
        )
    )

    /** Total number of classes the model recognizes */
    const val NUM_CLASSES = 10

    /** Model input image dimensions (MobileNetV3-Small) */
    const val INPUT_WIDTH = 224
    const val INPUT_HEIGHT = 224
    const val INPUT_CHANNELS = 3

    /**
     * Look up product by class index.
     */
    fun getByIndex(index: Int): ProductEntry? =
        products.getOrNull(index)

    /**
     * Look up product by Swahili name (case-insensitive, with aliases).
     */
    fun getBySwahiliName(name: String): ProductEntry? {
        val lower = name.lowercase().trim()
        return products.find { entry ->
            entry.swahiliName == lower || entry.aliases.any { it.equals(lower, ignoreCase = true) }
        }
    }

    /**
     * Look up product by English name.
     */
    fun getByEnglishName(name: String): ProductEntry? {
        val lower = name.lowercase().trim()
        return products.find { entry ->
            entry.englishName.equals(lower, ignoreCase = true) ||
            entry.aliases.any { it.equals(lower, ignoreCase = true) }
        }
    }

    /**
     * Find best matching product from free-text input.
     * Tries exact match first, then substring match.
     */
    fun findBestMatch(text: String): ProductEntry? {
        val lower = text.lowercase().trim()
        // Exact match on Swahili or English name
        getBySwahiliName(lower)?.let { return it }
        getByEnglishName(lower)?.let { return it }

        // Substring match on aliases
        return products.find { entry ->
            entry.aliases.any { alias -> lower.contains(alias) || alias.contains(lower) }
        }
    }

    /**
     * Get the class index for a Swahili product name.
     * Returns -1 if not found.
     */
    fun getClassIndex(swahiliName: String): Int =
        getBySwahiliName(swahiliName)?.classIndex ?: -1

    /**
     * Get all Swahili product names (for voice prompts).
     */
    fun allSwahiliNames(): List<String> = products.map { it.swahiliName }

    /**
     * Update seasonal price factor for a product.
     * Used by Soko Pulse market data integration.
     */
    fun updateSeasonalFactor(classIndex: Int, factor: Double) {
        // In production this would update via DAO.
        // For now, products are val — seasonal factors come from the market data layer.
    }
}
