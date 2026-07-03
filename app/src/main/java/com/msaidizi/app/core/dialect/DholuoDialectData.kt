package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion

/**
 * Data-driven configuration for Dholuo dialect adapter.
 *
 * Dholuo (Luo) is a Nilotic language spoken by ~6 million people
 * around Lake Victoria (Kisumu, Siaya, Homa Bay, Migori counties).
 */
object DholuoDialectData {

    val config = DialectConfig(
        name = "Dholuo",
        languageCode = "luo",
        region = DialectRegion.DHOLUO,

        markers = mapOf(
            "kendo" to Regex("\\bkendo\\b"), "to" to Regex("\\bto\\b"),
            "ka" to Regex("\\bka\\b"), "mano" to Regex("\\bmano\\b"),
            "gi" to Regex("\\bgi\\b"), "nyiso" to Regex("\\bnyiso\\b"),
            "en" to Regex("\\ben\\b"), "ok" to Regex("\\bok\\b"),
            "kata" to Regex("\\bkata\\b"), "chon" to Regex("\\bchon\\b"),
            "nadi" to Regex("\\bnadi\\b"), "inyalo" to Regex("\\binyalo\\b"),
            "kia" to Regex("\\bkia\\b"), "ber" to Regex("\\bber\\b"),
            "maber" to Regex("\\bmaber\\b"), "malo" to Regex("\\bmalo\\b"),
            "yawuoyo" to Regex("\\byawuoyo\\b"), "amos" to Regex("\\bamos\\b"),
            "erokamano" to Regex("\\berokamano\\b"), "wang'" to Regex("\\bwang'\\b"),
            "neno" to Regex("\\bneno\\b"), "kwee" to Regex("\\bkwee\\b"),
            "dhok" to Regex("\\bdhok\\b"), "ogo" to Regex("\\bogo\\b"),
            "wuon" to Regex("\\bwuon\\b"), "min" to Regex("\\bmin\\b"),
            "ja" to Regex("\\bja\\b")
        ),

        pronunciationRegexes = mapOf(
            "dh" to Regex("\\bdh\\b", RegexOption.IGNORE_CASE),
            "ny" to Regex("\\bny\\b", RegexOption.IGNORE_CASE),
            "ng'" to Regex("\\bng'\\b", RegexOption.IGNORE_CASE),
            "th" to Regex("\\bth\\b", RegexOption.IGNORE_CASE),
            "ch" to Regex("\\bch\\b", RegexOption.IGNORE_CASE)
        ),

        pronunciationVariations = mapOf(
            "dh" to "d", "ny" to "n", "ng'" to "ng", "th" to "t", "ch" to "c"
        ),

        dialectMarkerWords = setOf(
            "kendo", "to", "ka", "mano", "gi", "nyiso", "en", "ok", "kata", "chon",
            "nadi", "inyalo", "kia", "ber", "maber", "malo", "yawuoyo", "amos",
            "erokamano", "wang'", "neno", "kwee", "dhok", "ogo", "wuon", "min", "ja"
        ),

        businessTerms = mapOf(
            "chuth" to "sell", "wuoyi" to "sold", "ng'iew" to "buy",
            "ng'iewo" to "bought", "sente" to "money", "peso" to "money",
            "omena" to "silver_cyprinid", "rech" to "fish", "roho" to "tilapia",
            "nyuka" to "shop", "duka" to "shop", "soko" to "market",
            "pi" to "goat", "dhiang'" to "cow", "ng'ombe" to "cow",
            "thur" to "pay", "kel" to "bring", "nyis" to "show",
            "biro" to "come", "dhi" to "go", "neno" to "word/say",
            "chuth" to "sell", "wuoyi" to "sold", "ng'iew" to "buy",
            "ndalo" to "day", "odiechieng'" to "today", "nyoro" to "morning"
        ),

        dialectToSwahili = mapOf(
            "erokamano" to "asante", "maber" to "nzuri", "amos" to "habari",
            "ber" to "nzuri", "malo" to "sawa", "yawuoyo" to "usema",
            "inyalo" to "unaweja", "kata" to "hata", "kendo" to "na",
            "wuon" to "mwenye", "ja" to "mtu", "dhok" to "chakula",
            "ogo" to "mama", "wuon" to "baba", "min" to "nyumba"
        )
    )
}
