package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion

/**
 * Data-driven configuration for Amharic dialect adapter.
 *
 * Amharic is a Semitic language spoken by ~57 million people in Ethiopia.
 * This adapter handles romanized (transliterated) input from ASR.
 */
object AmharicDialectData {

    val config = DialectConfig(
        name = "Amharic",
        languageCode = "am",
        region = DialectRegion.AMHARIC,

        markers = mapOf(
            "yä" to Regex("\\byä\\b"), "nä" to Regex("\\bnä\\b"),
            "wädä" to Regex("\\bwädä\\b"), "säbbäbä" to Regex("\\bsäbbäbä\\b"),
            "änd" to Regex("\\bänd\\b"), "gäza" to Regex("\\bgäza\\b"),
            "säw" to Regex("\\bsäw\\b"), "säwoc" to Regex("\\bsäwoc\\b"),
            "bet" to Regex("\\bbet\\b"), "shäro" to Regex("\\bshäro\\b"),
            "täffa" to Regex("\\btäffa\\b"), "mäläs" to Regex("\\bmäläs\\b"),
            "selam" to Regex("\\bselam\\b"), "tänässä" to Regex("\\btänässä\\b"),
            "äzbäyähäwür" to Regex("\\bäzbäyähäwür\\b"),
            "dä" to Regex("\\bdä\\b"), "yähon" to Regex("\\byähon\\b"),
            "käfätäri" to Regex("\\bkäfätäri\\b"), "hulä" to Regex("\\bhulä\\b"),
            "ämäsägn" to Regex("\\bämäsägn\\b"), "bärä" to Regex("\\bbärä\\b"),
            "zämän" to Regex("\\bzämän\\b"), "gäbrä" to Regex("\\bgäbrä\\b"),
            "täkäla" to Regex("\\btäkäla\\b"), "säb" to Regex("\\bsäb\\b"),
            "gäbäya" to Regex("\\bgäbäya\\b"), "täjäj" to Regex("\\btäjäj\\b"),
            "äsfä" to Regex("\\bäsfä\\b"), "bärr" to Regex("\\bbärr\\b"),
            "buna" to Regex("\\bbuna\\b"), "jäbäna" to Regex("\\bjäbäna\\b"),
            "säqäl" to Regex("\\bsäqäl\\b"), "käffä" to Regex("\\bkäffä\\b")
        ),

        pronunciationRegexes = mapOf(
            "p'" to Regex("\\bp'\\b", RegexOption.IGNORE_CASE),
            "t'" to Regex("\\bt'\\b", RegexOption.IGNORE_CASE),
            "k'" to Regex("\\bk'\\b", RegexOption.IGNORE_CASE),
            "ch'" to Regex("\\bch'\\b", RegexOption.IGNORE_CASE),
            "ä" to Regex("\\bä\\b", RegexOption.IGNORE_CASE),
            "ë" to Regex("\\bë\\b", RegexOption.IGNORE_CASE),
            "ö" to Regex("\\bö\\b", RegexOption.IGNORE_CASE),
            "ss" to Regex("\\bss\\b", RegexOption.IGNORE_CASE),
            "tt" to Regex("\\btt\\b", RegexOption.IGNORE_CASE),
            "kk" to Regex("\\bkk\\b", RegexOption.IGNORE_CASE)
        ),

        pronunciationVariations = mapOf(
            "p'" to "p", "t'" to "t", "k'" to "k", "ch'" to "ch",
            "ä" to "a", "ë" to "e", "ö" to "o",
            "ss" to "s", "tt" to "t", "kk" to "k"
        ),

        dialectMarkerWords = setOf(
            "yä", "nä", "wädä", "säbbäbä", "änd", "gäza", "säw", "säwoc",
            "bet", "shäro", "täffa", "mäläs", "selam", "tänässä", "äzbäyähäwür",
            "dä", "yähon", "käfätäri", "hulä", "ämäsägn",
            "bärä", "zämän", "gäbrä", "täkäla", "säb", "gäbäya", "täjäj", "äsfä", "bärr",
            "buna", "jäbäna", "säqäl", "käffä"
        ),

        businessTerms = mapOf(
            "buna" to "coffee", "jäbäna" to "coffee_pot", "säqäl" to "roast",
            "käffä" to "coffee_ceremony", "buna_kälu" to "coffee_seller",
            "täff" to "teff_grain", "däbo" to "bread", "injära" to "injera_bread",
            "wot" to "stew", "doro_wot" to "chicken_stew", "misir_wot" to "lentil_stew",
            "shimbra" to "chickpea", "qocho" to "false_banana", "ämbäza" to "corn",
            "bärä" to "money", "täkäla" to "market", "gäbäya" to "market_square",
            "gäbrä" to "work", "akrabi" to "commission", "sänto" to "profit",
            "mäbr" to "debt", "täfäto" to "credit",
            "läm" to "cattle", "ärb" to "goat", "täbot" to "sheep",
            "dämo" to "donkey", "yäfäräs" to "horse",
            "kilo" to "kilogram", "litr" to "liter", "qänto" to "100_kg_sack",
            "birr" to "birr", "santim" to "cent"
        ),

        dialectToSwahili = mapOf(
            "selam" to "amani", "tänässä" to "asante", "säw" to "mtu",
            "säwoc" to "watu", "bet" to "nyumba", "bärä" to "pesa",
            "gäbrä" to "kazi", "täkäla" to "soko", "buna" to "kahawa",
            "injära" to "chapati", "däbo" to "mkate", "wot" to "mchuzi",
            "läm" to "ng'ombe", "ärb" to "mbuzi", "mäbr" to "deni", "sänto" to "faida"
        )
    )
}
