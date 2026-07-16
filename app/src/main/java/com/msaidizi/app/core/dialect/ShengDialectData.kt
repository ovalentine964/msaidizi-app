package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion

/**
 * Data-driven configuration for Sheng dialect adapter.
 *
 * Sheng is a Swahili-based cant/slang spoken by ~20 million people
 * in Nairobi and other Kenyan cities. It blends Swahili, English,
 * Dholuo, Kikuyu, and other Kenyan languages.
 */
object ShengDialectData {

    val config = DialectConfig(
        name = "Sheng",
        languageCode = "sheng",
        region = DialectRegion.SHENG,

        markers = mapOf(
            "sasa" to Regex("\\bsasa\\b"),
            "poa" to Regex("\\bpoa\\b"),
            "fiti" to Regex("\\bfiti\\b"),
            "aje" to Regex("\\baje\\b"),
            "niaje" to Regex("\\bniaje\\b"),
            "mambo" to Regex("\\bmambo\\b"),
            "vipi" to Regex("\\bvipi\\b"),
            "wazi" to Regex("\\bwazi\\b"),
            "safi" to Regex("\\bsafi\\b"),
            "freshi" to Regex("\\bfreshi\\b"),
            "ndege" to Regex("\\bndege\\b"),
            "blaze" to Regex("\\bblaze\\b"),
            "mbogi" to Regex("\\bmbogi\\b"),
            "sonko" to Regex("\\bsonko\\b"),
            "mheshimiwa" to Regex("\\bmheshimiwa\\b"),
            "chapaa" to Regex("\\bchapaa\\b"),
            "munde" to Regex("\\bmunde\\b"),
            "pesa" to Regex("\\bpesa\\b"),
            "bao" to Regex("\\bbao\\b"),
            "ngiri" to Regex("\\bngiri\\b"),
            "thao" to Regex("\\bthao\\b"),
            "finje" to Regex("\\bfinje\\b"),
            "jeuri" to Regex("\\bjeuri\\b"),
            "kumi" to Regex("\\bkumi\\b"),
            "jabaa" to Regex("\\bjabaa\\b"),
            "mwaks" to Regex("\\bmwaks\\b"),
            "kanyang'u" to Regex("\\bkanyang'u\\b"),
            "kudevaa" to Regex("\\bkudevaa\\b"),
            "kukasirika" to Regex("\\bkukasirika\\b"),
            "kuja" to Regex("\\bkuja\\b"),
            "kupiga" to Regex("\\bkupiga\\b"),
            "kuchapa" to Regex("\\bkuchapa\\b"),
            "kublaze" to Regex("\\bkublaze\\b"),
            "kutoka" to Regex("\\bkutoka\\b"),
            "kukula" to Regex("\\bkukula\\b"),
            "kunywa" to Regex("\\bkunywa\\b"),
            "kuvuta" to Regex("\\bkuvuta\\b"),
            "kusoma" to Regex("\\bkusoma\\b"),
            "kuskizaa" to Regex("\\bkuskizaa\\b"),
            "kuwatch" to Regex("\\bkuwatch\\b"),
            "msee" to Regex("\\bmsee\\b"),
            "msichana" to Regex("\\bmsichana\\b"),
            "kijana" to Regex("\\bkijana\\b"),
            "mubaba" to Regex("\\bmubaba\\b"),
            "mumama" to Regex("\\bmumama\\b"),
            "dame" to Regex("\\bdame\\b"),
            "boyi" to Regex("\\bboyi\\b"),
            "kamuti" to Regex("\\bkamuti\\b"),
            "ndai" to Regex("\\bndai\\b"),
            "mzae" to Regex("\\bmzae\\b"),
            "base" to Regex("\\bbase\\b"),
            "ghetto" to Regex("\\bghetto\\b"),
            "mtaa" to Regex("\\bmtaa\\b"),
            "kanairo" to Regex("\\bkanairo\\b"),
            "ushago" to Regex("\\bushago\\b"),
            "mat" to Regex("\\bmat\\b"),
            "nduthi" to Regex("\\bnduthi\\b"),
            "gari" to Regex("\\bgari\\b")
        ),

        pronunciationRegexes = mapOf(
            "chapaa" to Regex("\\bchapaa\\b", RegexOption.IGNORE_CASE),
            "munde" to Regex("\\bmunde\\b", RegexOption.IGNORE_CASE),
            "ndege" to Regex("\\bndege\\b", RegexOption.IGNORE_CASE),
            "mbogi" to Regex("\\bmbogi\\b", RegexOption.IGNORE_CASE),
            "sonko" to Regex("\\bsonko\\b", RegexOption.IGNORE_CASE),
            "kuchapa" to Regex("\\bkuchapa\\b", RegexOption.IGNORE_CASE),
            "kublaze" to Regex("\\bkublaze\\b", RegexOption.IGNORE_CASE),
            "kuwatch" to Regex("\\bkuwatch\\b", RegexOption.IGNORE_CASE),
            "nkt" to Regex("\\bnkt\\b", RegexOption.IGNORE_CASE),
            "wah" to Regex("\\bwah\\b", RegexOption.IGNORE_CASE)
        ),

        pronunciationVariations = mapOf(
            "chapaa" to "pesa",
            "munde" to "pesa",
            "ndege" to "nzuri",
            "mbogi" to "kundi",
            "sonko" to "tajiri",
            "kuchapa" to "kufanya_kazi",
            "kublaze" to "kupumzika",
            "kuwatch" to "kutazama",
            "nkt" to "nkt",
            "wah" to "wah"
        ),

        dialectMarkerWords = setOf(
            "sasa", "poa", "fiti", "aje", "niaje", "mambo", "vipi", "wazi", "safi", "freshi",
            "ndege", "blaze", "mbogi", "sonko", "mheshimiwa",
            "chapaa", "munde", "pesa", "bao", "ngiri", "thao", "finje", "jeuri", "kumi", "jabaa",
            "mwaks", "kanyang'u", "kudevaa", "kukasirika",
            "kuja", "kuja_kuja", "kupiga", "kuchapa", "kublaze", "kutoka", "kukula", "kunywa",
            "kuvuta", "kusoma", "kuskizaa", "kuwatch",
            "msee", "msichana", "kijana", "mubaba", "mumama", "dame", "boyi", "mbogi", "kamuti",
            "ndai", "mzae",
            "base", "ghetto", "mtaa", "kanairo", "ushago",
            "mat", "nduthi", "gari", "ndai"
        ),

        businessTerms = mapOf(
            "chapaa" to "money", "munde" to "money", "bao" to "twenty_shillings",
            "jeuri" to "fifty_shillings", "kumi" to "ten_shillings",
            "ngiri" to "thousand_shillings", "thao" to "thousand",
            "finje" to "five_hundred_shillings", "jabaa" to "free", "mwaks" to "no_money",
            "kanyang'u" to "broke", "kudevaa" to "broke",
            "choma" to "roasted_meat", "mutura" to "blood_sausage",
            "smocha" to "smokie_chapati", "mayai_boiled" to "boiled_egg",
            "rolex" to "chapati_egg_wrap", "kachumbari" to "tomato_onion_salad",
            "ndazi" to "mandazi_donut", "uji" to "porridge", "chai" to "tea",
            "mtura" to "street_sausage", "samosa" to "samosa",
            "mat" to "matatu", "nduthi" to "motorcycle", "ndai" to "car",
            "gari" to "car", "boda" to "motorcycle_taxi",
            "mpesa" to "mobile_money", "kutoa" to "withdraw", "kutuma" to "send",
            "kudeposit" to "deposit", "stima" to "electricity",
            "data" to "internet_data", "bundles" to "internet_bundles",
            "base" to "home", "ghetto" to "neighborhood", "ploti" to "plot/house",
            "single_room" to "single_room", "bedsitter" to "bedsitter",
            "duka" to "shop", "kibanda" to "stall", "soko" to "market",
            "mama_mboga" to "vegetable_seller", "mzae" to "shopkeeper"
        ),

        dialectToSwahili = mapOf(
            "sasa" to "habari", "poa" to "nzuri", "fiti" to "nzuri",
            "niaje" to "habari", "wazi" to "sawa", "safi" to "nzuri",
            "chapaa" to "pesa", "munde" to "pesa",
            "msee" to "mtu", "dame" to "msichana", "mbogi" to "kundi",
            "base" to "nyumba", "ghetto" to "mtaa", "kanairo" to "nairobi",
            "mat" to "matatu", "nduthi" to "pikipiki",
            "choma" to "nyama_choma", "jabaa" to "bure",
            "kuchapa" to "kufanya_kazi"
        ),

        // ────────────── Code-Switching Construction Patterns ──────────────

        /**
         * Sheng code-switching constructions: English verb stems with Swahili prefixes.
         * These are the hallmark of Sheng — speakers conjugate English verbs
         * using Swahili grammar: ku-deposit, nime-buy, na-think.
         * Map of Sheng construction → standard Swahili translation.
         */
        codeSwitchConstructions = mapOf(
            // ku- (infinitive) + English verb
            "kudeposit" to "kudepositi", "kuwithdraw" to "kutoa",
            "kutransfer" to "kutuma", "kucheck" to "kuangalia",
            "kusend" to "kutuma", "kureceive" to "kupokea",
            "kubuy" to "kununua", "kusell" to "kuuza",
            "kupay" to "kulipa", "kucancel" to "kufuta",
            "kuconfirm" to "kuthibitisha", "kudelete" to "kufuta",
            "kuregister" to "kujiandikisha", "kulogin" to "kuingia",
            "kuupdate" to "kusasisha", "kudownload" to "kupakua",
            "kuupload" to "kupakia", "kushare" to "kushiriki",
            "kuprint" to "kuchapisha", "kucall" to "kupiga_simu",
            "kumessage" to "kutuma_ujumbe", "kupost" to "kuchapisha",
            "kureport" to "kuripoti", "kubook" to "kupanga",
            "kuload" to "kupakia", "kubalance" to "kusawazisha",
            "kuprofit" to "kupata_faida",
            // nime- (perfective) + English verb
            "nimedo" to "nimemaliza", "nimesee" to "nimeona",
            "nimebuy" to "nimenunua", "nimesell" to "nimeuza",
            "nimepay" to "nimelipa", "nimesend" to "nimetuma",
            "nimecheck" to "nimeangalia", "nimefinish" to "nimemaliza",
            "nimeconfirm" to "nimethibitisha", "nimecancel" to "nimefuta",
            "nimebook" to "nimepanga", "nimeload" to "nimepakia",
            // na- (present) + English verb
            "nathink" to "nafikiri", "nado" to "nafanya",
            "nasee" to "naona", "nabuy" to "nanunua",
            "nasell" to "nauza", "napay" to "nalipa",
            "nacheck" to "naangalia", "nasend" to "natuma",
            // ame- (3rd person perfective) + English verb
            "amebuy" to "amenunua", "amesell" to "ameuza",
            "amepay" to "amelipa", "amesend" to "ametuma",
            "amecheck" to "ameangalia", "amefinish" to "amemaliza",
            "ameconfirm" to "amethibitisha",
            // tuta- (future) + English verb
            "tutabuy" to "tutanunua", "tutasell" to "tutauza",
            "tutasend" to "tutatuma", "tutacheck" to "tutaangalia",
            "tutapay" to "tutalipa"
        ),

        /**
         * English nouns commonly used with Swahili possessive suffixes in Sheng.
         * e.g., "account yangu", "receipt zangu"
         * Map of English noun → its Swahili class (for agreement)
         */
        englishNounSwahiliClass = mapOf(
            "account" to "n-class",     // account yangu → n-class
            "receipt" to "n-class",     // receipt yangu
            "balance" to "n-class",     // balance yangu
            "profit" to "n-class",      // profit yangu
            "stock" to "n-class",       // stock yangu
            "order" to "n-class",       // order yangu
            "payment" to "n-class",     // payment yangu
            "phone" to "ki-class",      // phone changu
            "number" to "n-class",       // number yangu
            "message" to "n-class",     // message yangu
            "photo" to "n-class",       // photo yangu
            "video" to "n-class",       // video yangu
            "error" to "n-class",       // error yangu
            "problem" to "n-class"      // problem yangu
        ),

        /**
         * Common Sheng sentence frames that indicate code-switching.
         * When these patterns appear, the following English words are
         * part of the Sheng code-switch, not standard English.
         */
        codeSwitchFrames = listOf(
            // Subject + na- + English verb
            Regex("\\b(na|nina|tuna|wana)\\s+[a-z]+(?:ing|ed|t)\\b"),
            // nime + English verb
            Regex("\\bnime[a-z]{3,}\\b"),
            // ku + English verb
            Regex("\\bku[a-z]{4,}\\b"),
            // English noun + possessive
            Regex("\\b(account|receipt|balance|stock|order|payment)\\s+(ya|za|yangu|zangu|yake|zake)\\b"),
            // Swahili frame + English word + Swahili continuation
            Regex("\\b(nime|na|tuta|ame)\\s+[a-z]+\\s+(pesa|mboga|soko|duka)\\b"),
            // "ni" + English adjective
            Regex("\\bni\\s+(soft|hard|cool|fire|heavy|sharp|smooth)\\b"),
            // "iko" + English adjective
            Regex("\\b(iko|iko)\\s+(poa|fiti|soft|cool|sharp)\\b")
        )
    )
}
