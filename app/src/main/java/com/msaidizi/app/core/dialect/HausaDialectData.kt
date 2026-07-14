package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion

/**
 * Data-driven configuration for Hausa dialect adapter.
 *
 * Hausa is an Afro-Asiatic language spoken by ~80 million people
 * across Nigeria, Niger, Ghana, Cameroon, and Chad.
 */
object HausaDialectData {

    val config = DialectConfig(
        name = "Hausa",
        languageCode = "ha",
        region = DialectRegion.HAUSA,

        markers = mapOf(
            "da" to Regex("\\bda\\b"),
            "kuma" to Regex("\\bkuma\\b"),
            "amma" to Regex("\\bamma\\b"),
            "ko" to Regex("\\bko\\b"),
            "ida" to Regex("\\bida\\b"),
            "saboda" to Regex("\\bsaboda\\b"),
            "lokacin_da" to Regex("\\blokacin_da\\b"),
            "wannan" to Regex("\\bwannan\\b"),
            "wancan" to Regex("\\bwancan\\b"),
            "yau" to Regex("\\byau\\b"),
            "gobe" to Regex("\\bgobe\\b"),
            "jiya" to Regex("\\bjiya\\b"),
            "ina_son" to Regex("\\bina_son\\b"),
            "na_gode" to Regex("\\bna_gode\\b"),
            "don_allah" to Regex("\\bdon_allah\\b"),
            "eh" to Regex("\\beh\\b"),
            "a'a" to Regex("\\ba'a\\b"),
            "sannu" to Regex("\\bsannu\\b"),
            "yaya_kake" to Regex("\\byaya_kake\\b"),
            "yaya_kike" to Regex("\\byaya_kike\\b"),
            "mutum" to Regex("\\bmutum\\b"),
            "mutane" to Regex("\\bmutane\\b"),
            "gida" to Regex("\\bgida\\b"),
            "kasuwa" to Regex("\\bkasuwa\\b"),
            "kudi" to Regex("\\bkudi\\b"),
            "aiki" to Regex("\\baiki\\b"),
            "abinci" to Regex("\\babinci\\b"),
            "ruwa" to Regex("\\bruwa\\b"),
            "wata" to Regex("\\bwata\\b"),
            "shekara" to Regex("\\bshekara\\b"),
            "rana" to Regex("\\brana\\b"),
            "lokaci" to Regex("\\blokaci\\b")
        ),

        pronunciationRegexes = mapOf(
            "ɓ" to Regex("\\bɓ\\b", RegexOption.IGNORE_CASE),
            "ɗ" to Regex("\\bɗ\\b", RegexOption.IGNORE_CASE),
            "t'" to Regex("\\bt'\\b", RegexOption.IGNORE_CASE),
            "k'" to Regex("\\bk'\\b", RegexOption.IGNORE_CASE),
            "aa" to Regex("\\baa\\b", RegexOption.IGNORE_CASE),
            "ee" to Regex("\\bee\\b", RegexOption.IGNORE_CASE),
            "ii" to Regex("\\bii\\b", RegexOption.IGNORE_CASE),
            "oo" to Regex("\\boo\\b", RegexOption.IGNORE_CASE),
            "uu" to Regex("\\buu\\b", RegexOption.IGNORE_CASE),
            "kw" to Regex("\\bkw\\b", RegexOption.IGNORE_CASE),
            "gw" to Regex("\\bgw\\b", RegexOption.IGNORE_CASE)
        ),

        pronunciationVariations = mapOf(
            "ɓ" to "b",
            "ɗ" to "d",
            "t'" to "t",
            "k'" to "k",
            "aa" to "a",
            "ee" to "e",
            "ii" to "i",
            "oo" to "o",
            "uu" to "u",
            "kw" to "kw",
            "gw" to "gw"
        ),

        dialectMarkerWords = setOf(
            "da", "kuma", "amma", "ko", "ida", "saboda", "lokacin_da",
            "wannan", "wancan", "yau", "gobe", "jiya", "ina_son",
            "na_gode", "don_allah", "eh", "a'a", "sannu", "yaya_kake",
            "yaya_kike", "mutum", "mutane", "gida", "kasuwa", "kudi",
            "aiki", "abinci", "ruwa", "wata", "shekara", "rana", "lokaci"
        ),

        businessTerms = mapOf(
            "kasuwa" to "market",
            "mai_kasuwa" to "market_person",
            "dillali" to "broker",
            "mai_sana'a" to "artisan",
            "kudi" to "money",
            "aiki" to "work",
            "riba" to "profit",
            "asara" to "loss",
            "bashin" to "debt",
            "bini" to "credit",
            "abinci" to "food",
            "masara" to "corn",
            "gyada" to "groundnut",
            "wake" to "beans",
            "shinkafa" to "rice",
            "tuwo" to "swallow_food",
            "fura" to "millet_ball",
            "kunu" to "porridge",
            "kilishi" to "dried_meat",
            "suya" to "grilled_meat",
            "daddawa" to "fermented_bean",
            "mai" to "oil",
            "giya" to "beer",
            "ruwa" to "water",
            "turmi" to "cloth",
            "babban_riga" to "large_robe",
            "hula" to "cap",
            "yar_gyada" to "trouser",
            "naira" to "naira",
            "kobo" to "kobo",
            "debe" to "debe",
            "jaka" to "bag",
            "kuru" to "basket",
            "mai_gida" to "landlord",
            "bako" to "customer/guest",
            "abokin_kasuwa" to "market_friend",
            "shanu" to "cattle",
            "akuya" to "goat",
            "tunkiya" to "sheep",
            "kaza" to "chicken",
            "doki" to "horse",
            "jaki" to "donkey"
        ),

        dialectToSwahili = mapOf(
            "sannu" to "habari",
            "na_gode" to "asante",
            "don_allah" to "tafadhali",
            "eh" to "ndiyo",
            "a'a" to "hapana",
            "mutum" to "mtu",
            "mutane" to "watu",
            "gida" to "nyumba",
            "kasuwa" to "soko",
            "kudi" to "pesa",
            "aiki" to "kazi",
            "abinci" to "chakula",
            "ruwa" to "maji",
            "riba" to "faida",
            "asara" to "hasara",
            "bashin" to "deni",
            "shanu" to "ng'ombe",
            "akuya" to "mbuzi",
            "kaza" to "kuku"
        )
    )
}
