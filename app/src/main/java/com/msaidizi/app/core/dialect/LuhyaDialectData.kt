package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion

/**
 * Data-driven configuration for Luhya dialect adapter.
 *
 * Luhya (Oluluyia) is a cluster of Bantu languages spoken by ~6 million people
 * in Western Kenya (Kakamega, Bungoma, Busia, Vihiga counties).
 */
object LuhyaDialectData {

    val config = DialectConfig(
        name = "Luhya",
        languageCode = "luy",
        region = DialectRegion.LUHYA,

        markers = mapOf(
            "na" to Regex("\\bna\\b"),
            "ni" to Regex("\\bni\\b"),
            "kha" to Regex("\\bkha\\b"),
            "inga" to Regex("\\binga\\b"),
            "osi" to Regex("\\bosi\\b"),
            "khutsia" to Regex("\\bkhutsia\\b"),
            "mulembe" to Regex("\\bmulembe\\b"),
            "mushiambo" to Regex("\\bmushiambo\\b"),
            "khukhala" to Regex("\\bkhukhala\\b"),
            "khulola" to Regex("\\bkhulola\\b"),
            "khuseva" to Regex("\\bkhuseva\\b"),
            "khukula" to Regex("\\bkhukula\\b"),
            "khukhunda" to Regex("\\bkhukhunda\\b"),
            "simba" to Regex("\\bsimba\\b"),
            "mukulu" to Regex("\\bmukulu\\b"),
            "mumama" to Regex("\\bmumama\\b"),
            "mukhulu" to Regex("\\bmukhulu\\b"),
            "mukasa" to Regex("\\bmukasa\\b"),
            "omukhongo" to Regex("\\bomukhongo\\b"),
            "omusinde" to Regex("\\bomusinde\\b"),
            "omwikale" to Regex("\\bomwikale\\b"),
            "omundu" to Regex("\\bomundu\\b"),
            "abantu" to Regex("\\babantu\\b"),
            "enyumba" to Regex("\\benyumba\\b"),
            "omugunda" to Regex("\\bomugunda\\b"),
            "omukhuyu" to Regex("\\bomukhuyu\\b"),
            "amatsi" to Regex("\\bamatsi\\b"),
            "endekho" to Regex("\\bendekho\\b"),
            "omukhono" to Regex("\\bomukhono\\b"),
            "eshiwi" to Regex("\\beshiwi\\b"),
            "omukhwe" to Regex("\\bomukhwe\\b")
        ),

        pronunciationRegexes = mapOf(
            "aka" to Regex("\\baka\\b", RegexOption.IGNORE_CASE),
            "oka" to Regex("\\boka\\b", RegexOption.IGNORE_CASE),
            "iga" to Regex("\\biga\\b", RegexOption.IGNORE_CASE),
            "saafi" to Regex("\\bsaafi\\b", RegexOption.IGNORE_CASE),
            "twaa" to Regex("\\btwaa\\b", RegexOption.IGNORE_CASE),
            "mboga" to Regex("\\bmboga\\b", RegexOption.IGNORE_CASE),
            "ng'ombe" to Regex("\\bng'ombe\\b", RegexOption.IGNORE_CASE)
        ),

        pronunciationVariations = mapOf(
            "aka" to "taka",
            "oka" to "toka",
            "iga" to "piga",
            "saafi" to "safi",
            "twaa" to "twa",
            "mboga" to "mboga",
            "ng'ombe" to "ng'ombe"
        ),

        dialectMarkerWords = setOf(
            "na", "ni", "kha", "inga", "osi", "khutsia", "mulembe", "mushiambo",
            "khukhala", "khulola", "khuseva", "khukula", "khukhunda",
            "simba", "mukulu", "mumama", "mukhulu", "mukasa", "omukhongo",
            "omusinde", "omwikale", "omundu", "abantu", "enyumba", "omugunda",
            "omukhuyu", "amatsi", "endekho", "omukhono", "eshiwi", "omukhwe"
        ),

        businessTerms = mapOf(
            "omukimo" to "mashed_dish",
            "amakunde" to "beans",
            "omusonga" to "sugarcane",
            "obusuma" to "ugali",
            "omutsatsa" to "traditional_greens",
            "endimi" to "groundnuts",
            "ebinyenya" to "tomatoes",
            "ebibisya" to "pumpkin",
            "amashene" to "mushrooms",
            "omugunda" to "farm",
            "omukhuyu" to "fig_tree",
            "omukhono" to "work",
            "amatsi" to "water",
            "ebibinga" to "bananas",
            "omukhaka" to "avocado",
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
            "boda_boda" to "motorcycle_taxi",
            "esirika" to "market_square",
            "eng'ombe" to "cattle",
            "embuli" to "goat",
            "enkuku" to "chicken"
        ),

        dialectToSwahili = mapOf(
            "mulembe" to "amani",
            "mushiambo" to "habari",
            "omundu" to "mtu",
            "abantu" to "watu",
            "enyumba" to "nyumba",
            "omugunda" to "shamba",
            "omukhono" to "kazi",
            "amatsi" to "maji",
            "eshiwi" to "neno",
            "kha" to "si",
            "osi" to "zote",
            "obusuma" to "ugali",
            "amakunde" to "maharagwe"
        )
    )
}
