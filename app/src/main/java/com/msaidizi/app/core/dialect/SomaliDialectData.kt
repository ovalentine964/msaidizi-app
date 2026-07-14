package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion

/**
 * Data-driven configuration for Somali dialect adapter.
 *
 * Somali is an Afro-Asiatic language spoken by ~22 million people
 * in Somalia, Djibouti, Somali Region (Ethiopia), and NE Kenya.
 */
object SomaliDialectData {

    val config = DialectConfig(
        name = "Somali",
        languageCode = "so",
        region = DialectRegion.SOMALI,

        markers = mapOf(
            "waa" to Regex("\\bwaa\\b"),
            "oo" to Regex("\\boo\\b"),
            "la" to Regex("\\bla\\b"),
            "ka" to Regex("\\bka\\b"),
            "ku" to Regex("\\bku\\b"),
            "iyo" to Regex("\\biyo\\b"),
            "ama" to Regex("\\bama\\b"),
            "laakiin" to Regex("\\blaakiin\\b"),
            "haddii" to Regex("\\bhaddii\\b"),
            "maxaa" to Regex("\\bmaxaa\\b"),
            "xagee" to Regex("\\bxagee\\b"),
            "goorma" to Regex("\\bgoorma\\b"),
            "sida" to Regex("\\bsida\\b"),
            "ma" to Regex("\\bma\\b"),
            "haye" to Regex("\\bhaye\\b"),
            "mahadsanid" to Regex("\\bmahadsanid\\b"),
            "fadlan" to Regex("\\bfadlan\\b"),
            "waan" to Regex("\\bwaan\\b"),
            "wuu" to Regex("\\bwuu\\b"),
            "way" to Regex("\\bway\\b"),
            "waxaa" to Regex("\\bwaxaa\\b"),
            "qof" to Regex("\\bqof\\b"),
            "dad" to Regex("\\bdad\\b"),
            "guri" to Regex("\\bguri\\b"),
            "suuq" to Regex("\\bsuuq\\b"),
            "biyo" to Regex("\\bbiyo\\b"),
            "caano" to Regex("\\bcaano\\b"),
            "hilib" to Regex("\\bhilib\\b"),
            "bariis" to Regex("\\bbariis\\b"),
            "burr" to Regex("\\bburr\\b"),
            "shaah" to Regex("\\bshaah\\b"),
            "sonkor" to Regex("\\bsonkor\\b"),
            "saliid" to Regex("\\bsaliid\\b"),
            "dhar" to Regex("\\bdhar\\b"),
            "lacag" to Regex("\\blacag\\b"),
            "ganacsi" to Regex("\\bganacsi\\b")
        ),

        pronunciationRegexes = mapOf(
            "aka" to Regex("\\baka\\b", RegexOption.IGNORE_CASE),
            "oka" to Regex("\\boka\\b", RegexOption.IGNORE_CASE),
            "saafi" to Regex("\\bsaafi\\b", RegexOption.IGNORE_CASE)
        ),

        pronunciationVariations = mapOf(
            "aka" to "taka",
            "oka" to "toka",
            "saafi" to "safi"
        ),

        dialectMarkerWords = setOf(
            "waa", "oo", "iyo", "ama", "laakiin", "haddii", "maxaa",
            "xagee", "goorma", "sida", "haye", "mahadsanid", "fadlan",
            "waan", "wuu", "way", "waxaa", "qof", "dad", "guri",
            "suuq", "biyo", "caano", "hilib", "bariis", "burr",
            "shaah", "sonkor", "saliid", "dhar", "lacag", "ganacsi",
            "geel", "arig", "lo", "idah", "faras", "dameer"
        ),

        businessTerms = mapOf(
            "caano" to "milk",
            "hilib" to "meat",
            "subag" to "butter/ghee",
            "lagh" to "camel_milk",
            "suqaar" to "dried_meat",
            "muqmad" to "preserved_meat",
            "bariis" to "rice",
            "burr" to "bread",
            "shaah" to "tea",
            "sonkor" to "sugar",
            "saliid" to "oil",
            "khudaar" to "vegetables",
            "khudaar_qalalan" to "dried_vegetables",
            "malab" to "honey",
            "qamadi" to "wheat",
            "bur_salid" to "flour",
            "geel" to "camel",
            "arig" to "goat",
            "lo'" to "cattle",
            "idah" to "sheep",
            "faras" to "horse",
            "dameer" to "donkey",
            "ganacsi" to "business",
            "lacag" to "money",
            "suuq" to "market",
            "dukaan" to "shop",
            "dhar" to "cloth",
            "alwaax" to "wood",
            "bir" to "iron",
            "dahab" to "gold",
            "kiilo" to "kilogram",
            "mitir" to "meter",
            "debe" to "debe",
            "gunia" to "sack",
            "shilin" to "shilling",
            "doolar" to "dollar",
            "mbao" to "twenty_shillings",
            "jeuri" to "fifty_shillings",
            "ngiri" to "thousand_shillings"
        ),

        dialectToSwahili = mapOf(
            "waa" to "ni",
            "iyo" to "na",
            "laakiin" to "lakini",
            "haddii" to "kama",
            "mahadsanid" to "asante",
            "fadlan" to "tafadhali",
            "qof" to "mtu",
            "dad" to "watu",
            "guri" to "nyumba",
            "suuq" to "soko",
            "biyo" to "maji",
            "caano" to "maziwa",
            "hilib" to "nyama",
            "lacag" to "pesa",
            "ganacsi" to "biashara",
            "shaah" to "chai",
            "sonkor" to "sukari",
            "saliid" to "mafuta",
            "geel" to "ngamia",
            "arig" to "mbuzi",
            "lo'" to "ng'ombe"
        )
    )
}
