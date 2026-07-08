package com.msaidizi.app.core.dialect

/**
 * Pre-seeded vocabulary for common Swahili market terms.
 *
 * These are the most frequently spoken terms in East African markets,
 * organized by category. Dialect adapters reference this vocabulary
 * to ensure consistent recognition across all dialects.
 *
 * Source: Field recordings from Nairobi, Migori, Kisumu, Mombasa markets.
 * All terms are in standard Swahili (Kiswahili Sanifu).
 */
object SwahiliMarketVocabulary {

    /**
     * Common food products sold in markets.
     * Map of Swahili term → semantic category tag.
     */
    val FOOD_PRODUCTS = mapOf(
        // Staples
        "mchele" to "staple",           // rice
        "unga" to "staple",             // flour
        "unga_wa_simbi" to "staple",    // maize flour
        "njugu" to "staple",            // groundnuts
        "maharagwe" to "staple",        // beans
        "choroko" to "staple",          // green grams
        "ndengu" to "staple",           // green grams (another name)
        "ngano" to "staple",            // wheat
        "mtama" to "staple",            // millet
        "wimbi" to "staple",            // finger millet
        "ufuta" to "staple",            // sesame

        // Vegetables
        "sukuma_wiki" to "vegetable",   // collard greens
        "spinachi" to "vegetable",      // spinach
        "tomato" to "vegetable",        // tomato
        "vitunguu" to "vegetable",      // onion
        "karoti" to "vegetable",        // carrot
        "kabichi" to "vegetable",       // cabbage
        "hoho" to "vegetable",          // capsicum
        "pilipili" to "vegetable",      // chili
        "nyanya" to "vegetable",        // tomato (alt)
        "mboga" to "vegetable",         // vegetable (generic)
        "kunde" to "vegetable",         // cowpea leaves
        "mrenda" to "vegetable",        // jute mallow
        "osuga" to "vegetable",         // African spider plant
        "managu" to "vegetable",        // nightshade

        // Fruits
        "embe" to "fruit",              // mango
        "ndizi" to "fruit",             // banana
        "nanasi" to "fruit",            // pineapple
        "tikitimaji" to "fruit",        // watermelon
        "chungwa" to "fruit",           // orange
        "limau" to "fruit",             // lemon
        "papai" to "fruit",             // papaya
        "zabibu" to "fruit",            // grape
        "tende" to "fruit",             // dates
        "nazi" to "fruit",              // coconut

        // Proteins
        "nyama" to "protein",           // meat
        "kuku" to "protein",            // chicken
        "samaki" to "protein",          // fish
        "mayai" to "protein",           // eggs
        "maziwa" to "protein",          // milk
        "ng'ombe" to "protein",         // cattle
        "mbuzi" to "protein",           // goat
        "kondoo" to "protein",          // sheep
        "omena" to "protein",           // silver cyprinid (small fish)

        // Beverages
        "chai" to "beverage",           // tea
        "kahawa" to "beverage",         // coffee
        "maji" to "beverage",           // water
        "juisi" to "beverage",          // juice

        // Cooking essentials
        "mafuta" to "cooking",          // oil
        "sukari" to "cooking",          // sugar
        "chumvi" to "cooking",          // salt
        "viungo" to "cooking",          // spices
        "tangawizi" to "cooking",       // ginger
        "iliki" to "cooking",           // cardamom
        "binzari" to "cooking",         // turmeric
    )

    /**
     * Measurement units commonly used in markets.
     */
    val MEASUREMENT_UNITS = mapOf(
        "kilo" to "weight",             // kilogram
        "kilo_mbili" to "weight",       // 2 kg
        "nusu_kilo" to "weight",        // half kg
        "robo" to "weight",             // quarter kg
        "gramu" to "weight",            // grams
        "debe" to "volume",             // tin can (~20kg for maize)
        "debe_ndogo" to "volume",       // small tin
        "gunia" to "volume",            // sack (50-90kg)
        "fundo" to "count",             // bundle/tie
        "mfuko" to "count",             // bag/packet
        "kibaba" to "weight",           // small measure (~1kg)
        "ratili" to "weight",           // pound
        "lita" to "volume",             // liter
        "nusu_lita" to "volume",        // half liter
        "goro" to "volume",             // tin measure for oil
    )

    /**
     * Currency and price terms.
     */
    val CURRENCY_TERMS = mapOf(
        "pesa" to "money",              // money
        "shilingi" to "currency",       // shilling
        "mbao" to "slang_20",           // KSh 20
        "jeuri" to "slang_50",          // KSh 50
        "thao" to "slang_1000",         // KSh 1000
        "ngiri" to "slang_1000",        // KSh 1000
        "finje" to "slang_500",         // KSh 500
        "nane" to "slang_80",           // KSh 80
        "mia" to "hundred",             // 100
        "elfu" to "thousand",           // 1000
        "deni" to "debt",               // debt
        "mkopo" to "credit",            // loan
        "faida" to "profit",            // profit
        "hasara" to "loss",             // loss
        "bei" to "price",               // price
        "punguzo" to "discount",        // discount
    )

    /**
     * Business action terms.
     */
    val BUSINESS_ACTIONS = mapOf(
        "kununua" to "buy",             // buy
        "kuuza" to "sell",              // sell
        "kutoa" to "withdraw",          // withdraw
        "kutuma" to "send",             // send
        "kulipa" to "pay",              // pay
        "kupokea" to "receive",         // receive
        "kurekodi" to "record",         // record
        "kuhesabu" to "count",          // count
        "kubadilisha" to "exchange",    // change/exchange
        "kukopesha" to "lend",          // lend
        "kukodisha" to "rent",          // rent
        "kufunga" to "close",           // close (shop)
        "kufungua" to "open",           // open (shop)
    )

    /**
     * Market location and infrastructure terms.
     */
    val MARKET_LOCATIONS = mapOf(
        "soko" to "market",             // market
        "duka" to "shop",               // shop
        "kibanda" to "stall",           // stall
        "sokoni" to "at_market",        // at the market
        "dukani" to "at_shop",          // at the shop
        "mama_mboga" to "vendor",       // vegetable seller
        "mzee_wa_duka" to "shopkeeper", // shop owner
        "boda_boda" to "transport",     // motorcycle taxi
        "mkokoteni" to "transport",     // hand cart
        "matatu" to "transport",        // minibus
        "mpesa" to "mobile_money",      // M-Pesa
    )

    /**
     * Time-related business terms.
     */
    val TIME_TERMS = mapOf(
        "asubuhi" to "morning",         // morning
        "mchana" to "afternoon",        // afternoon
        "jioni" to "evening",           // evening
        "usiku" to "night",             // night
        "leo" to "today",               // today
        "jana" to "yesterday",          // yesterday
        "kesho" to "tomorrow",          // tomorrow
        "wiki" to "week",               // week
        "mwezi" to "month",             // month
        "mwaka" to "year",              // year
    )

    /**
     * All terms combined for quick lookup.
     */
    val ALL_TERMS: Map<String, String> by lazy {
        FOOD_PRODUCTS + MEASUREMENT_UNITS + CURRENCY_TERMS +
        BUSINESS_ACTIONS + MARKET_LOCATIONS + TIME_TERMS
    }

    /**
     * Check if a word is a known Swahili market term.
     */
    fun isMarketTerm(word: String): Boolean {
        return ALL_TERMS.containsKey(word.lowercase().trim())
    }

    /**
     * Get the category of a market term.
     */
    fun getCategory(word: String): String? {
        return ALL_TERMS[word.lowercase().trim()]
    }

    /**
     * Get all terms in a specific category.
     */
    fun getTermsByCategory(category: String): List<String> {
        return ALL_TERMS.filter { it.value == category }.keys.toList()
    }
}
