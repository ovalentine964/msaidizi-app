package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion

/**
 * Data-driven configuration for Kalenjin dialect adapter.
 *
 * Kalenjin is a Nilotic language cluster spoken by ~5 million people
 * in the Rift Valley (Nandi, Baringo, Uasin Gishu, Elgeyo-Marakwet counties).
 */
object KalenjinDialectData {

    val config = DialectConfig(
        name = "Kalenjin",
        languageCode = "kln",
        region = DialectRegion.KALENJIN,

        markers = mapOf(
            "amit" to Regex("\\bamit\\b"),
            "mamit" to Regex("\\bmamit\\b"),
            "kogo" to Regex("\\bkogo\\b"),
            "kogoich" to Regex("\\bkogoich\\b"),
            "koitoich" to Regex("\\bkoitoich\\b"),
            "mising" to Regex("\\bmising\\b"),
            "chamgei" to Regex("\\bchamgei\\b"),
            "chengo" to Regex("\\bchengo\\b"),
            "kainet" to Regex("\\bkainet\\b"),
            "kongoi" to Regex("\\bkongoi\\b"),
            "murio" to Regex("\\bmurio\\b"),
            "ende" to Regex("\\bende\\b"),
            "amuno" to Regex("\\bamuno\\b"),
            "kipto" to Regex("\\bkipto\\b"),
            "kipsigis" to Regex("\\bkipsigis\\b"),
            "tugen" to Regex("\\btugen\\b"),
            "nandi" to Regex("\\bnandi\\b"),
            "kapkoros" to Regex("\\bkapkoros\\b"),
            "kapchumba" to Regex("\\bkapchumba\\b"),
            "kaptich" to Regex("\\bkaptich\\b"),
            "kapsirwet" to Regex("\\bkapsirwet\\b"),
            "murenik" to Regex("\\bmurenik\\b"),
            "tuiyotich" to Regex("\\btuiyotich\\b"),
            "mursik" to Regex("\\bmursik\\b"),
            "kimiet" to Regex("\\bkimiet\\b"),
            "kabotet" to Regex("\\bkabotet\\b"),
            "ng'atuny" to Regex("\\bng'atuny\\b"),
            "moit" to Regex("\\bmoit\\b")
        ),

        pronunciationRegexes = mapOf(
            "aka" to Regex("\\baka\\b", RegexOption.IGNORE_CASE),
            "oka" to Regex("\\boka\\b", RegexOption.IGNORE_CASE),
            "iga" to Regex("\\biga\\b", RegexOption.IGNORE_CASE),
            "saafi" to Regex("\\bsaafi\\b", RegexOption.IGNORE_CASE),
            "twaa" to Regex("\\btwaa\\b", RegexOption.IGNORE_CASE)
        ),

        pronunciationVariations = mapOf(
            "aka" to "taka",
            "oka" to "toka",
            "iga" to "piga",
            "saafi" to "safi",
            "twaa" to "twa"
        ),

        dialectMarkerWords = setOf(
            "amit", "mamit", "kogo", "kogoich", "koitoich", "mising",
            "chamgei", "chengo", "kainet", "kongoi", "murio", "ende",
            "amuno", "kipto", "kipsigis", "tugen", "nandi",
            "kapkoros", "kapchumba", "kaptich", "kapsirwet",
            "murenik", "tuiyotich", "mursik", "kimiet", "kabotet",
            "ng'atuny", "moit"
        ),

        businessTerms = mapOf(
            "mursik" to "fermented_milk",
            "kimiet" to "traditional_beer",
            "kabotet" to "traditional_brew",
            "kobong'et" to "traditional_greens",
            "kapkolel" to "traditional_soup",
            "moit" to "farm/field",
            "ng'atuny" to "cattle",
            "sigei" to "goat",
            "ruret" to "sheep",
            "kokoich" to "chicken",
            "debe" to "debe",
            "gunia" to "sack",
            "fundo" to "bundle",
            "mfuko" to "bag",
            "mbao" to "twenty_shillings",
            "jeuri" to "fifty_shillings",
            "ngiri" to "thousand_shillings",
            "thao" to "thousand",
            "soko" to "market",
            "duka" to "shop",
            "kibanda" to "stall",
            "boda_boda" to "motorcycle_taxi"
        ),

        dialectToSwahili = mapOf(
            "amit" to "ndiyo",
            "mamit" to "hapana",
            "mising" to "amani",
            "kainet" to "asante",
            "kongoi" to "asante",
            "murio" to "tafadhali",
            "ende" to "nini",
            "kipto" to "mtu",
            "moit" to "shamba",
            "mursik" to "maziwa",
            "ng'atuny" to "ng'ombe"
        )
    )
}
