package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion

/**
 * Data-driven configuration for Kikuyu dialect adapter.
 *
 * Kikuyu (Gĩkũyũ) is a Bantu language spoken by ~8 million people
 * in Central Kenya (Nyeri, Murang'a, Kiambu, Kirinyaga counties).
 */
object KikuyuDialectData {

    val config = DialectConfig(
        name = "Kikuyu",
        languageCode = "ki",
        region = DialectRegion.KIKUYU,

        markers = mapOf(
            "na" to Regex("\\bna\\b"),
            "ni" to Regex("\\bni\\b"),
            "no" to Regex("\\bno\\b"),
            "ungi" to Regex("\\bungi\\b"),
            "nake" to Regex("\\bnake\\b"),
            "nayo" to Regex("\\bnayo\\b"),
            "nio" to Regex("\\bnio\\b"),
            "tuika" to Regex("\\btuika\\b"),
            "ri" to Regex("\\bri\\b"),
            "ngi" to Regex("\\bngi\\b"),
            "muno" to Regex("\\bmuno\\b"),
            "hingo" to Regex("\\bhingo\\b"),
            "kana" to Regex("\\bkana\\b"),
            "tiga" to Regex("\\btiga\\b"),
            "ngu" to Regex("\\bngu\\b"),
            "ndu" to Regex("\\bndu\\b"),
            "nindu" to Regex("\\bnindu\\b"),
            "mundu" to Regex("\\bmundu\\b"),
            "andu" to Regex("\\bandu\\b"),
            "nyumba" to Regex("\\bnyumba\\b"),
            "mutuuri" to Regex("\\bmutuuri\\b"),
            "kihi" to Regex("\\bkihi\\b"),
            "ng'ombe" to Regex("\\bng'ombe\\b"),
            "mburi" to Regex("\\bmburi\\b"),
            "mugunda" to Regex("\\bmugunda\\b"),
            "muti" to Regex("\\bmuti\\b"),
            "njeri" to Regex("\\bnjeri\\b"),
            "gika" to Regex("\\bgika\\b"),
            "muthuri" to Regex("\\bmuthuri\\b"),
            "wira" to Regex("\\bwira\\b"),
            "mondeki" to Regex("\\bmondeki\\b"),
            "nduma" to Regex("\\bnduma\\b"),
            "mukimo" to Regex("\\bmukimo\\b"),
            "githeri" to Regex("\\bgitheri\\b"),
            "irio" to Regex("\\birio\\b"),
            "mutura" to Regex("\\bmutura\\b"),
            "njahi" to Regex("\\bnjahi\\b"),
            "kirima" to Regex("\\bkirima\\b"),
            "mukoru" to Regex("\\bmukoru\\b"),
            "njohi" to Regex("\\bnjohi\\b"),
            "thabiti" to Regex("\\bthabiti\\b")
        ),

        pronunciationRegexes = mapOf(
            "aka" to Regex("\\baka\\b", RegexOption.IGNORE_CASE),
            "apata" to Regex("\\bapata\\b", RegexOption.IGNORE_CASE),
            "oka" to Regex("\\boka\\b", RegexOption.IGNORE_CASE),
            "iga" to Regex("\\biga\\b", RegexOption.IGNORE_CASE),
            "saafi" to Regex("\\bsaafi\\b", RegexOption.IGNORE_CASE),
            "twaa" to Regex("\\btwaa\\b", RegexOption.IGNORE_CASE),
            "zaa" to Regex("\\bzaa\\b", RegexOption.IGNORE_CASE),
            "lali" to Regex("\\blali\\b", RegexOption.IGNORE_CASE),
            "leta" to Regex("\\bleta\\b", RegexOption.IGNORE_CASE),
            "mboga" to Regex("\\bmboga\\b", RegexOption.IGNORE_CASE),
            "ng'ombe" to Regex("\\bng'ombe\\b", RegexOption.IGNORE_CASE),
            "samahani" to Regex("\\bsamahani\\b", RegexOption.IGNORE_CASE),
            "sawa" to Regex("\\bsawa\\b", RegexOption.IGNORE_CASE)
        ),

        pronunciationVariations = mapOf(
            "aka" to "taka",
            "apata" to "tapata",
            "oka" to "toka",
            "iga" to "piga",
            "saafi" to "safi",
            "twaa" to "twa",
            "zaa" to "za",
            "lali" to "rali",
            "leta" to "leta",
            "mboga" to "mboga",
            "ng'ombe" to "ng'ombe",
            "samahani" to "samahani",
            "sawa" to "sawa"
        ),

        dialectMarkerWords = setOf(
            "na", "ni", "no", "ungi", "nake", "nayo", "nio", "tuika", "ri", "ngi",
            "muno", "hingo", "kana", "tiga", "ngu", "ndu", "nindu",
            "mundu", "andu", "nyumba", "mutuuri", "kihi", "ng'ombe", "mburi",
            "mugunda", "muti", "njeri", "gika", "muthuri", "wira", "mondeki",
            "nduma", "mukimo", "githeri", "irio", "mutura", "njahi", "kirima",
            "mukoru", "njohi", "thabiti"
        ),

        businessTerms = mapOf(
            "nduma" to "arrowroot",
            "mukimo" to "mashed_green_maize",
            "githeri" to "bean_mixture",
            "irio" to "traditional_dishes",
            "njahi" to "caterpillar_delicacy",
            "kirima" to "sweet_potato",
            "mukoru" to "traditional_beer",
            "njohi" to "traditional_beer",
            "mutura" to "tripe",
            "kama" to "council_hall",
            "mondeki" to "seller",
            "muthuri" to "trader",
            "debe" to "debe",
            "gunia" to "sack",
            "fundo" to "bundle",
            "mfuko" to "bag",
            "kibaba" to "small_measure",
            "ratili" to "pound",
            "mbao" to "twenty_shillings",
            "jeuri" to "fifty_shillings",
            "kibabu" to "fifty_shillings",
            "ngiri" to "thousand_shillings",
            "thao" to "thousand",
            "finje" to "five_hundred_shillings",
            "soko" to "market",
            "duka" to "shop",
            "kibanda" to "stall",
            "mama_mboga" to "vegetable_seller",
            "boda_boda" to "motorcycle_taxi",
            "mkokoteni" to "hand_cart",
            "ng'ombe" to "cattle",
            "mburi" to "goat",
            "kondoo" to "sheep",
            "kuku" to "chicken",
            "mugunda" to "farm"
        ),

        dialectToSwahili = mapOf(
            "ni" to "ni",
            "mundu" to "mtu",
            "andu" to "watu",
            "nyumba" to "nyumba",
            "mugunda" to "shamba",
            "wira" to "kazi",
            "muti" to "mti",
            "gika" to "soko",
            "nduma" to "kiazi",
            "irio" to "chakula",
            "njohi" to "pombe",
            "tiga" to "acha",
            "muno" to "sana",
            "ri" to "wakati"
        )
    )
}
