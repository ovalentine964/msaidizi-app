package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion

/**
 * Data-driven configuration for Zulu dialect adapter.
 *
 * Zulu (isiZulu) is a Bantu language spoken by ~12 million people
 * as a first language in South Africa (KwaZulu-Natal, Gauteng).
 */
object ZuluDialectData {

    val config = DialectConfig(
        name = "Zulu",
        languageCode = "zu",
        region = DialectRegion.ZULU,

        markers = mapOf(
            "na" to Regex("\\bna\\b"),
            "futhi" to Regex("\\bfuthi\\b"),
            "kodwa" to Regex("\\bkodwa\\b"),
            "noma" to Regex("\\bnoma\\b"),
            "uma" to Regex("\\buma\\b"),
            "ngoba" to Regex("\\bngoba\\b"),
            "lapho" to Regex("\\blapho\\b"),
            "lokhu" to Regex("\\blokhu\\b"),
            "lowo" to Regex("\\blowo\\b"),
            "namuhla" to Regex("\\bnamuhla\\b"),
            "kusasa" to Regex("\\bkusasa\\b"),
            "izolo" to Regex("\\bizolo\\b"),
            "ngiyafuna" to Regex("\\bngiyafuna\\b"),
            "ngiyabonga" to Regex("\\bngiyabonga\\b"),
            "ngicela" to Regex("\\bngicela\\b"),
            "yebo" to Regex("\\byebo\\b"),
            "cha" to Regex("\\bcha\\b"),
            "sawubona" to Regex("\\bsawubona\\b"),
            "sanibonani" to Regex("\\bsanibonani\\b"),
            "unjani" to Regex("\\bunjani\\b"),
            "umuntu" to Regex("\\bumuntu\\b"),
            "abantu" to Regex("\\babantu\\b"),
            "indlu" to Regex("\\bindlu\\b"),
            "imakethe" to Regex("\\bimakethe\\b"),
            "imali" to Regex("\\bimali\\b"),
            "umsebenzi" to Regex("\\bumsebenzi\\b"),
            "ukudla" to Regex("\\bukudla\\b"),
            "amanzi" to Regex("\\bamanzi\\b"),
            "inyanga" to Regex("\\binyanga\\b"),
            "unyaka" to Regex("\\bunyaka\\b"),
            "usuku" to Regex("\\busuku\\b"),
            "isikhathi" to Regex("\\bisikhathi\\b")
        ),

        pronunciationRegexes = mapOf(
            "bh" to Regex("\\bbh\\b", RegexOption.IGNORE_CASE),
            "dh" to Regex("\\bdh\\b", RegexOption.IGNORE_CASE),
            "gh" to Regex("\\bgh\\b", RegexOption.IGNORE_CASE),
            "mb" to Regex("\\bmb\\b", RegexOption.IGNORE_CASE),
            "nd" to Regex("\\bnd\\b", RegexOption.IGNORE_CASE),
            "ng" to Regex("\\bng\\b", RegexOption.IGNORE_CASE),
            "nj" to Regex("\\bnj\\b", RegexOption.IGNORE_CASE)
        ),

        pronunciationVariations = mapOf(
            "bh" to "b",
            "dh" to "d",
            "gh" to "g",
            "mb" to "mb",
            "nd" to "nd",
            "ng" to "ng",
            "nj" to "nj"
        ),

        dialectMarkerWords = setOf(
            "na", "futhi", "kodwa", "noma", "uma", "ngoba", "lapho", "lokhu",
            "lowo", "namuhla", "kusasa", "izolo", "ngiyafuna", "ngiyabonga",
            "ngicela", "yebo", "cha", "sawubona", "sanibonani", "unjani",
            "umuntu", "abantu", "indlu", "imakethe", "imali", "umsebenzi",
            "ukudla", "amanzi", "inyanga", "unyaka", "usuku", "isikhathi"
        ),

        businessTerms = mapOf(
            "imakethe" to "market",
            "umthengisi" to "seller",
            "umthengi" to "buyer",
            "imali" to "money",
            "umsebenzi" to "work",
            "inzuzo" to "profit",
            "ukulahlekelwa" to "loss",
            "isikweletu" to "debt",
            "ukudla" to "food",
            "umbila" to "corn",
            "ubhatata" to "sweet_potato",
            "ijikijolo" to "spinach",
            "umhluzi" to "soup",
            "inyama" to "meat",
            "inhlanzi" to "fish",
            "ubisi" to "milk",
            "uju" to "honey",
            "ubhontshisi" to "beans",
            "uphuthu" to "pap",
            "itheksi" to "taxi",
            "imoto" to "car",
            "ibhasi" to "bus",
            "ibhayisikili" to "bicycle",
            "imoto_yokuthwala" to "cargo_vehicle",
            "ingubo" to "blanket/cloth",
            "isicoco" to "headring",
            "umceza" to "grass_mat",
            "imbenge" to "basket",
            "rand" to "rand",
            "cent" to "cent",
            "debe" to "debe",
            "isikhwama" to "bag",
            "umama_wemakethe" to "market_mother",
            "ubaba_wemakethe" to "market_father",
            "inkomo" to "cattle",
            "imbuzi" to "goat",
            "imvu" to "sheep",
            "inkukhu" to "chicken",
            "ihhashi" to "horse"
        ),

        dialectToSwahili = mapOf(
            "sawubona" to "habari",
            "sanibonani" to "habari",
            "ngiyabonga" to "asante",
            "ngicela" to "tafadhali",
            "yebo" to "ndiyo",
            "cha" to "hapana",
            "umuntu" to "mtu",
            "abantu" to "watu",
            "indlu" to "nyumba",
            "imakethe" to "soko",
            "imali" to "pesa",
            "umsebenzi" to "kazi",
            "ukudla" to "chakula",
            "amanzi" to "maji",
            "inkomo" to "ng'ombe",
            "imbuzi" to "mbuzi",
            "inkukhu" to "kuku",
            "inzuzo" to "faida",
            "isikweletu" to "deni"
        )
    )
}
