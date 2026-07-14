package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion

/**
 * Data-driven configuration for Yoruba dialect adapter.
 *
 * Yoruba is a Niger-Congo language spoken by ~45 million people
 * in Nigeria, Benin, and Togo.
 */
object YorubaDialectData {

    val config = DialectConfig(
        name = "Yoruba",
        languageCode = "yo",
        region = DialectRegion.YORUBA,

        markers = mapOf(
            "ni" to Regex("\\bni\\b"),
            "si" to Regex("\\bsi\\b"),
            "ati" to Regex("\\bati\\b"),
            "tabi" to Regex("\\btabi\\b"),
            "sugbon" to Regex("\\bsugbon\\b"),
            "bi" to Regex("\\bbi\\b"),
            "nitori" to Regex("\\bnitori\\b"),
            "nigbati" to Regex("\\bnigbati\\b"),
            "beeni" to Regex("\\bbeeni\\b"),
            "rara" to Regex("\\brara\\b"),
            "jowo" to Regex("\\bjowo\\b"),
            "e_se" to Regex("\\be_se\\b"),
            "e_ku_ojo" to Regex("\\be_ku_ojo\\b"),
            "e_ku_owo" to Regex("\\be_ku_owo\\b"),
            "a_dupe" to Regex("\\ba_dupe\\b"),
            "eniyan" to Regex("\\beniyan\\b"),
            "awon_eniyan" to Regex("\\bawon_eniyan\\b"),
            "ile" to Regex("\\bile\\b"),
            "oja" to Regex("\\boja\\b"),
            "owo" to Regex("\\bowo\\b"),
            "ise" to Regex("\\bise\\b"),
            "ounje" to Regex("\\bounje\\b"),
            "omi" to Regex("\\bomi\\b"),
            "osu" to Regex("\\bosu\\b"),
            "odun" to Regex("\\bodun\\b"),
            "ojo" to Regex("\\bojo\\b"),
            "igba" to Regex("\\bigba\\b")
        ),

        pronunciationRegexes = mapOf(
            "kp" to Regex("\\bkp\\b", RegexOption.IGNORE_CASE),
            "gb" to Regex("\\bgb\\b", RegexOption.IGNORE_CASE),
            "ati" to Regex("\\bati\\b", RegexOption.IGNORE_CASE),
            "ni" to Regex("\\bni\\b", RegexOption.IGNORE_CASE)
        ),

        pronunciationVariations = mapOf(
            "kp" to "kp",
            "gb" to "gb",
            "ati" to "ati",
            "ni" to "ni"
        ),

        dialectMarkerWords = setOf(
            "ni", "si", "ati", "tabi", "sugbon", "bi", "nitori", "nigbati",
            "beeni", "rara", "jowo", "e_se", "e_ku_ojo", "e_ku_owo", "a_dupe",
            "eniyan", "awon_eniyan", "ile", "oja", "owo", "ise", "ounje",
            "omi", "osu", "odun", "ojo", "igba"
        ),

        businessTerms = mapOf(
            "oja" to "market",
            "alagbere" to "broker",
            "alaja" to "trader",
            "ojagoods" to "goods",
            "owo" to "money",
            "ise" to "work",
            "oju_ogbon" to "business_strategy",
            "iyan" to "pounded_yam",
            "eba" to "eba_cassava",
            "egusi" to "melon_seed",
            "iru" to "locust_bean",
            "ogiri" to "fermented_locust_bean",
            "dawadawa" to "locust_bean_seasoning",
            "ogede" to "banana",
            "ose" to "soap",
            "ata" to "pepper",
            "tatashe" to "bell_pepper",
            "rodo" to "hot_pepper",
            "gbongon" to "palm_fruit",
            "epo" to "palm_oil",
            "ori" to "shea_butter",
            "iyu" to "cassava",
            "agbado" to "corn",
            "ewa" to "beans",
            "ayara" to "groundnut",
            "aso" to "cloth",
            "kente" to "woven_cloth",
            "adire" to "tie-dye",
            "fila" to "cap",
            "buba" to "top/blouse",
            "iro" to "wrapper",
            "gele" to "headwrap",
            "naira" to "naira",
            "kobo" to "kobo",
            "owo_ile" to "house_money",
            "debe" to "debe",
            "gunia" to "sack",
            "iyaleja" to "market_mother",
            "babalawo" to "traditional_healer",
            "alaga" to "chairperson",
            "onigun" to "stall_owner",
            "alade" to "crown_owner",
            "malu" to "cattle",
            "ewure" to "goat",
            "agutan" to "sheep",
            "adie" to "chicken"
        ),

        dialectToSwahili = mapOf(
            "beeni" to "ndiyo",
            "rara" to "hapana",
            "jowo" to "tafadhali",
            "e_se" to "asante",
            "eniyan" to "mtu",
            "awon_eniyan" to "watu",
            "ile" to "nyumba",
            "oja" to "soko",
            "owo" to "pesa",
            "ise" to "kazi",
            "ounje" to "chakula",
            "omi" to "maji",
            "malu" to "ng'ombe",
            "ewure" to "mbuzi",
            "adie" to "kuku"
        )
    )
}
