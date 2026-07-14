package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion

/**
 * Data-driven configuration for Igbo dialect adapter.
 *
 * Igbo is a Niger-Congo language spoken by ~45 million people
 * in southeastern Nigeria.
 */
object IgboDialectData {

    val config = DialectConfig(
        name = "Igbo",
        languageCode = "ig",
        region = DialectRegion.IGBO,

        markers = mapOf(
            "na" to Regex("\\bna\\b"),
            "bu" to Regex("\\bbu\\b"),
            "ga" to Regex("\\bga\\b"),
            "nke" to Regex("\\bnke\\b"),
            "ma" to Regex("\\bma\\b"),
            "o_buru" to Regex("\\bo_buru\\b"),
            "n'ihi_na" to Regex("\\bn'ihi_na\\b"),
            "otu_o_no" to Regex("\\botu_o_no\\b"),
            "ihunanya" to Regex("\\bihunanya\\b"),
            "ndewo" to Regex("\\bndewo\\b"),
            "kedu" to Regex("\\bkedu\\b"),
            "daalu" to Regex("\\bdaalu\\b"),
            "biko" to Regex("\\bbiko\\b"),
            "o_di_mma" to Regex("\\bo_di_mma\\b"),
            "ehee" to Regex("\\behee\\b"),
            "mba" to Regex("\\bmba\\b"),
            "mmadu" to Regex("\\bmmadu\\b"),
            "ndi" to Regex("\\bndi\\b"),
            "ulo" to Regex("\\bulo\\b"),
            "ahia" to Regex("\\bahia\\b"),
            "ego" to Regex("\\bego\\b"),
            "oru" to Regex("\\boru\\b"),
            "nri" to Regex("\\bnri\\b"),
            "mmiri" to Regex("\\bmmiri\\b"),
            "onwu" to Regex("\\bonwu\\b"),
            "afo" to Regex("\\bafo\\b"),
            "ubochi" to Regex("\\bubochi\\b"),
            "oge" to Regex("\\boge\\b")
        ),

        pronunciationRegexes = mapOf(
            "o" to Regex("\\bo\\b", RegexOption.IGNORE_CASE),
            "u" to Regex("\\bu\\b", RegexOption.IGNORE_CASE),
            "i" to Regex("\\bi\\b", RegexOption.IGNORE_CASE),
            "e" to Regex("\\be\\b", RegexOption.IGNORE_CASE)
        ),

        pronunciationVariations = mapOf(
            "o" to "o",
            "u" to "u",
            "i" to "i",
            "e" to "e"
        ),

        dialectMarkerWords = setOf(
            "na", "bu", "ga", "nke", "ma", "o_buru", "n'ihi_na", "otu_o_no",
            "ihunanya", "ndewo", "kedu", "daalu", "biko", "o_di_mma",
            "ehee", "mba", "mmadu", "ndi", "ulo", "ahia", "ego",
            "oru", "nri", "mmiri", "onwu", "afo", "ubochi", "oge"
        ),

        businessTerms = mapOf(
            "ahia" to "market",
            "onye_ahia" to "trader/customer",
            "onye_oru" to "worker",
            "ego" to "money",
            "oru" to "work",
            "uzo" to "way/method",
            "ogu" to "competition",
            "ji" to "yam",
            "akpu" to "cocoyam",
            "akara" to "bean_cake",
            "oha" to "oil_bean",
            "mkpuru_osisti" to "palm_kernel",
            "mma" to "oil/palm_oil",
            "osisti" to "palm_tree",
            "azu" to "fish",
            "anu" to "meat",
            "ede" to "cocoyam",
            "ofe_owerri" to "owerri_soup",
            "akwukwo" to "paper/cloth",
            "ugwu" to "title/honor",
            "ukwu" to "foot/measure",
            "naira" to "naira",
            "kobo" to "kobo",
            "debe" to "debe",
            "gunia" to "sack",
            "nne_ahia" to "market_mother",
            "nna_ahia" to "market_father",
            "onye_nchikwa" to "administrator",
            "ehi" to "cattle",
            "ewu" to "goat",
            "aturu" to "sheep",
            "okuko" to "chicken"
        ),

        dialectToSwahili = mapOf(
            "ndewo" to "habari",
            "kedu" to "habari",
            "daalu" to "asante",
            "biko" to "tafadhali",
            "ehee" to "ndiyo",
            "mba" to "hapana",
            "mmadu" to "mtu",
            "ndi" to "watu",
            "ulo" to "nyumba",
            "ahia" to "soko",
            "ego" to "pesa",
            "oru" to "kazi",
            "nri" to "chakula",
            "mmiri" to "maji",
            "ji" to "viazi",
            "ehi" to "ng'ombe",
            "ewu" to "mbuzi",
            "okuko" to "kuku"
        )
    )
}
