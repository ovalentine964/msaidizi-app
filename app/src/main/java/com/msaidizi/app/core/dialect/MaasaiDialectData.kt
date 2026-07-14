package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion

/**
 * Data-driven configuration for Maasai dialect adapter.
 *
 * Maasai (Maa) is a Nilotic language spoken by ~1.5 million people
 * in Kajiado, Narok (Kenya) and Arusha, Manyara (Tanzania).
 */
object MaasaiDialectData {

    val config = DialectConfig(
        name = "Maasai",
        languageCode = "mas",
        region = DialectRegion.MAASAI,

        markers = mapOf(
            "sopa" to Regex("\\bsopa\\b"),
            "yeyo" to Regex("\\byeyo\\b"),
            "iko" to Regex("\\biko\\b"),
            "meita" to Regex("\\bmeita\\b"),
            "naikai" to Regex("\\bnaikai\\b"),
            "enaiki" to Regex("\\benaiki\\b"),
            "kioku" to Regex("\\bkioku\\b"),
            "ai" to Regex("\\bai\\b"),
            "aiye" to Regex("\\baiye\\b"),
            "keju" to Regex("\\bkeju\\b"),
            "ashe" to Regex("\\bashe\\b"),
            "oleng" to Regex("\\boleng\\b"),
            "ilkeek" to Regex("\\bilkeek\\b"),
            "enkang'" to Regex("\\benkang'\\b"),
            "enkaji" to Regex("\\benkaji\\b"),
            "olchani" to Regex("\\bolchani\\b"),
            "laibon" to Regex("\\blaibon\\b"),
            "ilmurran" to Regex("\\bilmurran\\b"),
            "inkajijik" to Regex("\\binkajijik\\b"),
            "olotuno" to Regex("\\bolotuno\\b"),
            "enkare" to Regex("\\benkare\\b"),
            "olari" to Regex("\\bolari\\b"),
            "enk'ee" to Regex("\\benk'ee\\b"),
            "ore" to Regex("\\bore\\b"),
            "enk'ositon" to Regex("\\benk'ositon\\b"),
            "entit" to Regex("\\bentit\\b"),
            "ork'oiyotap" to Regex("\\bork'oiyotap\\b"),
            "enkejuk" to Regex("\\benkejuk\\b"),
            "oret" to Regex("\\boret\\b"),
            "enk'ariak" to Regex("\\benk'ariak\\b"),
            "entulelei" to Regex("\\bentulelei\\b"),
            "shuka" to Regex("\\bshuka\\b")
        ),

        pronunciationRegexes = mapOf(
            "aka" to Regex("\\baka\\b", RegexOption.IGNORE_CASE),
            "oka" to Regex("\\boka\\b", RegexOption.IGNORE_CASE),
            "iga" to Regex("\\biga\\b", RegexOption.IGNORE_CASE),
            "saafi" to Regex("\\bsaafi\\b", RegexOption.IGNORE_CASE)
        ),

        pronunciationVariations = mapOf(
            "aka" to "taka",
            "oka" to "toka",
            "iga" to "piga",
            "saafi" to "safi"
        ),

        dialectMarkerWords = setOf(
            "sopa", "yeyo", "iko", "meita", "naikai", "enaiki", "kioku",
            "ai", "aiye", "keju", "ashe", "oleng", "ilkeek", "enkang'",
            "enkaji", "olchani", "laibon", "ilmurran", "inkajijik", "olotuno",
            "enkare", "olari", "enk'ee", "ore", "enk'ositon", "entit",
            "ork'oiyotap", "enkejuk", "oret", "enk'ariak", "entulelei", "shuka"
        ),

        businessTerms = mapOf(
            "enkejuk" to "milk",
            "oret" to "blood",
            "enk'ariak" to "fat",
            "enkashatai" to "butter",
            "olmarei" to "honey",
            "enk'ibishon" to "traditional_beer",
            "enk'ee" to "cattle",
            "ore" to "bull",
            "entit" to "mature_bull",
            "ork'oiyotap" to "calf",
            "enk'ositon" to "heifer",
            "enkejuuk" to "goat",
            "orkejuuk" to "billy_goat",
            "enkeja" to "sheep",
            "enk'arash" to "donkey",
            "entulelei" to "beadwork",
            "shuka" to "blanket",
            "enk'ariwa" to "bracelet",
            "olariatai" to "necklace",
            "debe" to "debe",
            "gunia" to "sack",
            "mbao" to "twenty_shillings",
            "jeuri" to "fifty_shillings",
            "ngiri" to "thousand_shillings",
            "thao" to "thousand",
            "soko" to "market",
            "duka" to "shop",
            "enkang'" to "homestead",
            "enkaji" to "house",
            "olchani" to "chief"
        ),

        dialectToSwahili = mapOf(
            "sopa" to "habari",
            "yeyo" to "nzuri",
            "iko" to "sawa",
            "ai" to "hapana",
            "aiye" to "ndiyo",
            "keju" to "asante",
            "ashe" to "asante",
            "oleng" to "mtu",
            "enkare" to "maji",
            "olari" to "mvua",
            "enk'ee" to "ng'ombe",
            "enkejuk" to "maziwa",
            "enkang'" to "kijiji",
            "enkaji" to "nyumba"
        )
    )
}
