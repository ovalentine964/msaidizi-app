package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion

/**
 * Data-driven configuration for Xhosa dialect adapter.
 *
 * Xhosa (isiXhosa) is a Bantu language spoken by ~8 million people
 * in South Africa (Eastern Cape, Western Cape).
 */
object XhosaDialectData {

    val config = DialectConfig(
        name = "Xhosa",
        languageCode = "xh",
        region = DialectRegion.XHOSA,

        markers = mapOf(
            "kwaye" to Regex("\\bkwaye\\b"),
            "kodwa" to Regex("\\bkodwa\\b"),
            "okanye" to Regex("\\bokanye\\b"),
            "ukuba" to Regex("\\bukuba\\b"),
            "kuba" to Regex("\\bkuba\\b"),
            "xa" to Regex("\\bxa\\b"),
            "esi" to Regex("\\besi\\b"),
            "eyo" to Regex("\\beyo\\b"),
            "namhlanje" to Regex("\\bnamhlanje\\b"),
            "ngomso" to Regex("\\bngomso\\b"),
            "izolo" to Regex("\\bizolo\\b"),
            "ndifuna" to Regex("\\bndifuna\\b"),
            "enkosi" to Regex("\\benkosi\\b"),
            "ndicela" to Regex("\\bndicela\\b"),
            "ewe" to Regex("\\bewe\\b"),
            "hayi" to Regex("\\bhayi\\b"),
            "molo" to Regex("\\bmolo\\b"),
            "molweni" to Regex("\\bmolweni\\b"),
            "unjani" to Regex("\\bunjani\\b"),
            "umntu" to Regex("\\bumntu\\b"),
            "abantu" to Regex("\\babantu\\b"),
            "indlu" to Regex("\\bindlu\\b"),
            "imarike" to Regex("\\bimarike\\b"),
            "imali" to Regex("\\bimali\\b"),
            "umsebenzi" to Regex("\\bumsebenzi\\b"),
            "ukutya" to Regex("\\bukutya\\b"),
            "amanzi" to Regex("\\bamanzi\\b"),
            "inyanga" to Regex("\\binyanga\\b"),
            "unyaka" to Regex("\\bunyaka\\b"),
            "usuku" to Regex("\\busuku\\b"),
            "ixesha" to Regex("\\bixesha\\b")
        ),

        pronunciationRegexes = mapOf(
            "bh" to Regex("\\bbh\\b", RegexOption.IGNORE_CASE),
            "dh" to Regex("\\bdh\\b", RegexOption.IGNORE_CASE)
        ),

        pronunciationVariations = mapOf(
            "bh" to "b",
            "dh" to "d"
        ),

        dialectMarkerWords = setOf(
            "kwaye", "kodwa", "okanye", "ukuba", "kuba", "xa", "esi", "eyo",
            "namhlanje", "ngomso", "izolo", "ndifuna", "enkosi", "ndicela",
            "ewe", "hayi", "molo", "molweni", "unjani", "umntu", "abantu",
            "indlu", "imarike", "imali", "umsebenzi", "ukutya", "amanzi",
            "inyanga", "unyaka", "usuku", "ixesha"
        ),

        businessTerms = mapOf(
            "imarike" to "market",
            "umthengisi" to "seller",
            "umthengi" to "buyer",
            "imali" to "money",
            "umsebenzi" to "work",
            "inzuzo" to "profit",
            "ilahleko" to "loss",
            "isikweletu" to "debt",
            "ukutya" to "food",
            "umbona" to "corn",
            "ibhatata" to "sweet_potato",
            "ikhowa" to "mushroom",
            "umhluzi" to "soup",
            "inyama" to "meat",
            "intlanzi" to "fish",
            "ubisi" to "milk",
            "utyisi" to "beans",
            "isophi" to "soap",
            "inkomo" to "cattle",
            "ibhokhwe" to "goat",
            "igusha" to "sheep",
            "inkukhu" to "chicken",
            "ihhashi" to "horse",
            "rand" to "rand",
            "isent" to "cent",
            "debe" to "debe",
            "isikhwama" to "bag",
            "umama_wemarike" to "market_mother"
        ),

        dialectToSwahili = mapOf(
            "molo" to "habari",
            "molweni" to "habari",
            "enkosi" to "asante",
            "ndicela" to "tafadhali",
            "ewe" to "ndiyo",
            "hayi" to "hapana",
            "umntu" to "mtu",
            "abantu" to "watu",
            "indlu" to "nyumba",
            "imarike" to "soko",
            "imali" to "pesa",
            "umsebenzi" to "kazi",
            "ukutya" to "chakula",
            "amanzi" to "maji",
            "inkomo" to "ng'ombe",
            "ibhokhwe" to "mbuzi",
            "inkukhu" to "kuku",
            "inzuzo" to "faida",
            "isikweletu" to "deni"
        )
    )
}
