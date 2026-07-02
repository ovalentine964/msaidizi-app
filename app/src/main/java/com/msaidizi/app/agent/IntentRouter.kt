package com.msaidizi.app.agent

import com.msaidizi.app.core.dialect.ShengDialectAdapter
import com.msaidizi.app.core.model.*
import com.msaidizi.app.core.util.SwahiliParser
import timber.log.Timber

/**
 * Intent Router — classifies user input into intents.
 * This is CODE, not LLM — 0 RAM overhead, instant execution.
 *
 * Uses precompiled regex patterns for Swahili business commands.
 * Handles 90%+ of user input without needing the LLM.
 */
class IntentRouter {

    // ═══════════════════════════════════════════════════════════════
    // SHENG/DIALECT NORMALIZATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Sheng-to-standard Swahili vocabulary mapping.
     * 30% of Kenya's youth speak Sheng — this is critical for intent recognition.
     *
     * Key Sheng business terms:
     * - chapaa/munde = pesa (money)
     * - nikaue = niliuza (I sold)
     * - nika-buy = nilinunua (I bought)
     * - nduthi = pikipiki (motorcycle)
     * - mat = matatu (minibus)
     * - thao = elfu moja (1000)
     * - finje = mia tano (500)
     * - bao = ishirini (20)
     * - jeuri = hamsini (50)
     * - ngiri = elfu (1000)
     */
    private val shengToStandard = mapOf(
        // Verbs — sale/purchase actions
        "nikaue" to "nimeuza", "nikauze" to "nimeuza",
        "nika-buy" to "nimenunua", "nikapurchase" to "nimenunua",
        "nka-log" to "nimenunua", "nalog" to "nimenunua",
        "nka-dispose" to "nimeuza", "nikasell" to "nimeuza",
        "nauza" to "nimeuza", "nimenunua" to "nimenunua",

        // Money amounts — Sheng slang for currency
        "chapaa" to "pesa", "munde" to "pesa",
        "thao" to "1000", "ngiri" to "1000", "thousand" to "1000",
        "finje" to "500", "jeuri" to "50",
        "bao" to "20", "kumi" to "10",
        "jabaa" to "bure", "mwaks" to "0",

        // Business items — common Sheng terms
        "choma" to "nyama", "mutura" to "mutura",
        "smocha" to "smokie", "mayai" to "mayai",
        "rolex" to "chapati", "ndazi" to "mandazi",
        "uji" to "uji",

        // Transport
        "mat" to "matatu", "nduthi" to "pikipiki",
        "ndai" to "gari", "boda" to "boda",

        // Digital
        "mpesa" to "mpesa", "float" to "float",
        "bundles" to "data", "stima" to "stima",

        // Places
        "base" to "nyumba", "ghetto" to "mtaa",
        "kanairo" to "nairobi", "ushago" to "ushago",

        // Descriptors
        "poa" to "nzuri", "fiti" to "nzuri",
        "safi" to "nzuri", "wazi" to "sawa",
        "ndege" to "nzuri", "blaze" to "nzuri",
        "freshi" to "nzuri"
    )

    /**
     * Sheng-specific sale patterns.
     * These match Sheng constructions that standard Swahili patterns miss.
     *
     * Examples:
     * - "nikaue mandazi tano mia tano" (I sold 5 mandazi for 500)
     * - "nikasell nyanya kwa 300" (I sold tomatoes for 300)
     * - "nka-dispose smocha kumi" (I disposed/sold 10 smokies)
     */
    private val shengSalePatterns = listOf(
        // "nikaue mandazi tano mia tano"
        Regex("""(?i)(nikaue|nikauze|nikasell|nka-dispose)\s+(.+?)\s+(\d+)\s*(kwa|sh|ksh)?\s*(\d+(?:\.\d+)?)?"""),
        // "nikaue mandazi 500" (without quantity)
        Regex("""(?i)(nikaue|nikauze|nikasell)\s+(.+?)\s+(\d+(?:\.\d+)?)"""),
        // "chapaa ya mandazi 500" (money from mandazi 500)
        Regex("""(?i)(chapaa|munde|pesa)\s+ya\s+(.+?)\s+(\d+(?:\.\d+)?)"""),
        // "nikapurchase unga 200" (Sheng purchase)
        Regex("""(?i)(nika-buy|nikapurchase|nka-log|nalog)\s+(.+?)\s+(\d+(?:\.\d+)?)"""),
        // "nika-buy unga thao" (I bought flour for 1000)
        Regex("""(?i)(nika-buy|nikapurchase|nka-log)\s+(.+?)\s+(thao|ngiri|finje|bao|jeuri)"""),
        // "nikaue smocha kumi kwa 50" (I sold 10 smokies for 50 each)
        Regex("""(?i)(nikaue|nikauze|nikasell)\s+(.+?)\s+(\d+)\s+kwa\s*(\d+(?:\.\d+)?)""")
    )

    /**
     * Sheng-specific expense patterns.
     * Sheng speakers use different constructions for expenses.
     *
     * Examples:
     * - "nikatoa 200 kwa mafuta" (I spent 200 on fuel)
     * - "nka-float 5000" (I loaded float 5000)
     * - "nikatoa thao kwa rent" (I spent 1000 on rent)
     */
    private val shengExpensePatterns = listOf(
        // "nikatoa 200 kwa mafuta"
        Regex("""(?i)(nikatoa|nkalipa|nikatoa)\s+(\d+(?:\.\d+)?)\s+(kwa|for)\s+(.+)"""),
        // "nka-float 5000"
        Regex("""(?i)(nka-float|nikaweka\s*float)\s+(\d+(?:\.\d+)?)"""),
        // "nikatoa thao kwa rent" (Sheng amount in expense)
        Regex("""(?i)(nikatoa|nkalipa)\s+(thao|ngiri|finje|bao|jeuri)\s+(kwa|for)\s+(.+)"""),
        // "nduthi 300" / "mat 50" (transport expense in Sheng)
        Regex("""(?i)(nduthi|mat|boda)\s+(\d+(?:\.\d+)?)"""),
        // "stima 500" / "bundles 250" (utility expense in Sheng)
        Regex("""(?i)(stima|bundles|data|airtime)\s+(\d+(?:\.\d+)?)""")
    )

    // === SALE PATTERNS ===
    private val salePatterns = listOf(
        // "Nimeuza mandazi kumi kwa Sh 500"
        Regex("""(?i)(nime?uza|niliuza|nikauza|sold|nauza|nimeuzia)\s+(.+)\s+(kwa|sh|ksh)\s*(\d+(?:\.\d+)?)"""),
        // "Mandazi kumi kwa 500"
        Regex("""(?i)(mandazi|maize|unga|sukari|chai|maziwa|mkate|nyanya|viazi)\s+(\d+)\s*(kwa|sh|ksh)\s*(\d+(?:\.\d+)?)"""),
        // "10 mandazi kwa 500"
        Regex("""(?i)(\d+)\s+(mandazi|maize|unga|sukari|chai|maziwa|mkate)\s+(kwa|sh|ksh)\s*(\d+(?:\.\d+)?)"""),
        // "Nimeuza nyanya" (without explicit price)
        Regex("""(?i)(nime?uza|sold|nauza)\s+(.+?)(?:\s+(?:kwa|sh|ksh)\s*(\d+(?:\.\d+)?))?"""),
        // Simple: "Sale mandazi 500"
        Regex("""(?i)(sale|sold|uza)\s+(.+?)\s+(\d+(?:\.\d+)?)""")
    )

    // === PURCHASE PATTERNS ===
    private val purchasePatterns = listOf(
        // "Nimenunua unga kwa Sh 200"
        Regex("""(?i)(nimenunua|nilinunua|nimenunulia|nimenunua|bought|nimelog)\s+(.+)\s+(kwa|sh|ksh)\s*(\d+(?:\.\d+)?)"""),
        // "Nimenunua unga mbili kwa 200"
        Regex("""(?i)(nimenunua|bought)\s+(.+?)\s+(\w+)\s+(kwa|sh|ksh)\s*(\d+(?:\.\d+)?)"""),
        // Simple: "Buy unga 200"
        Regex("""(?i)(buy|purchase|nunua)\s+(.+?)\s+(\d+(?:\.\d+)?)""")
    )

    // === EXPENSE PATTERNS ===
    private val expensePatterns = listOf(
        // "Nimetumia Sh 100 kwa usafiri"
        Regex("""(?i)(nimetumia|nimelipa|nimetoa|spent|paid)\s+(?:sh|ksh|kwa)?\s*(\d+(?:\.\d+)?)\s+(?:kwa|for)\s+(.+)"""),
        // "Usafiri 100"
        Regex("""(?i)(usafiri|rent|kodi|stima|umeme|majani|data|bundle)\s+(\d+(?:\.\d+)?)"""),
        // "Fuel 300" / "Petrol 500"
        Regex("""(?i)(fuel|petrol|diesel|mafuta\s*ya\s*pikipiki)\s+(\d+(?:\.\d+)?)"""),
        // "Nimetumia 200 kwa mafuta"
        Regex("""(?i)(nimetumia|nimelipa)\s+(\d+(?:\.\d+)?)\s+(kwa|for)\s+(mafuta|fuel|petrol|diesel)"""),
        // "Fertilizer 1500" / "Mbolea 800"
        Regex("""(?i)(fertilizer|mbolea|dawa|pesticide|mbegu|seeds)\s+(\d+(?:\.\d+)?)"""),
        // "Nimenunua mbolea kwa 1500"
        Regex("""(?i)(nimenunua|nimelipa)\s+(mbolea|fertilizer|dawa|mbegu|seeds)\s+(kwa|sh|ksh)?\s*(\d+(?:\.\d+)?)"""),
        // "Float 10000" / "Nimeweka float 5000"
        Regex("""(?i)(float|nimeweka\s*float)\s+(\d+(?:\.\d+)?)"""),
        // "SACCO 200" / "Contribution 500"
        Regex("""(?i)(sacco|contribution|mgao|chip\s*in)\s+(\d+(?:\.\d+)?)"""),
        // "Airtime 100" / "Data bundle 250"
        Regex("""(?i)(airtime|data\s*bundle|bundles?|internet)\s+(\d+(?:\.\d+)?)"""),
        // "Spare parts 2000" / "Parts 1500"
        Regex("""(?i)(spare\s*parts?|parts?|vifaa)\s+(\d+(?:\.\d+)?)"""),
        // "Repair 500" / "Service 800"
        Regex("""(?i)(repair|service|matengenezo)\s+(\d+(?:\.\d+)?)"""),
        // "Advert 500" / "Advertisement 1000" / "Boost 200"
        Regex("""(?i)(advert|advertisement|boost|matangazo|tangazo)\s+(\d+(?:\.\d+)?)"""),
        // "Delivery 200" / "Transport 300"
        Regex("""(?i)(delivery|transport|usafirishaji|kubeba)\s+(\d+(?:\.\d+)?)""")
    )

    // === TRANSPORT-SPECIFIC PATTERNS ===
    private val tripPatterns = listOf(
        // "Nimefanya trip 5" / "Trip 10 leo"
        Regex("""(?i)(nimefanya|nimepiga|nimemaliza)\s+(trip|safari|race|gari)\s+(\d+)"""),
        Regex("""(?i)(trip|safari|race)\s+(\d+)"""),
        // "Nimebeba abiria 20"
        Regex("""(?i)(nimebeba|nimeshusha|nimeweka)\s+(abiria|passenger|wateja)\s+(\d+)"""),
        // "Route: Nairobi - Thika"
        Regex("""(?i)(route|njia|safari)\s*:?\s*(.+?)\s*(-|to|→|hadhi)\s*(.+)"""),
        // "Nimepata 1500 kutoka CBD"
        Regex("""(?i)(nimepata|nimechukua|nimelipwa)\s+(\d+(?:\.\d+)?)\s+(kutoka|from)\s+(.+)"""),
        // "Passenger 300"
        Regex("""(?i)(passenger|abiria|mpanda)\s+(\d+(?:\.\d+)?)""")
    )

    // === FARMING-SPECIFIC PATTERNS ===
    private val farmingPatterns = listOf(
        // "Nimevuna mahindi 50kg"
        Regex("""(?i)(nimevuna|nimekuna|nimekata|nimetafuna)\s+(.+)\s+(\d+)\s*(kg|kilo|gunia|mifuko)?"""),
        // "Nimepanda mahindi ekari 2"
        Regex("""(?i)(nimepanda|nimetega|nimeotesha)\s+(.+)\s+(ekari|acre|hectare)\s*(\d+)?"""),
        // "Harvest 200kg"
        Regex("""(?i)(harvest|vunja|kuvuna)\s+(\d+)\s*(kg|kilo)?"""),
        // "Nimenunua mbegu kwa 800"
        Regex("""(?i)(nimenunua|nimelog)\s+(mbegu|seeds|mbolea|fertilizer|dawa)\s+(kwa|sh|ksh)?\s*(\d+(?:\.\d+)?)"""),
        // "Nimepanda mahindi" (without quantity)
        Regex("""(?i)(nimepanda|nimetega|nimeotesha)\s+(.+?)(?:\s+(ekari|acre))?"""),
        // "Crop: maize" / "Mazao: mahindi"
        Regex("""(?i)(crop|mazao|mmea)\s*:?\s*(.+)""")
    )

    // === DIGITAL/GIG-SPECIFIC PATTERNS ===
    private val digitalPatterns = listOf(
        // "Nimefanya transaction 50"
        Regex("""(?i)(nimefanya|nimemaliza)\s+(transaction|tx|shughuli)\s+(\d+)"""),
        // "Commission 1200"
        Regex("""(?i)(commission|komisheni|fee|charge)\s+(\d+(?:\.\d+)?)"""),
        // "Nimepata commission 500"
        Regex("""(?i)(nimepata|nimelipwa)\s+(commission|komisheni)\s+(\d+(?:\.\d+)?)"""),
        // "Deposits 20000" / "Withdrawals 15000"
        Regex("""(?i)(deposit|withdrawal|send|receive|withdraw)\s+(\d+(?:\.\d+)?)"""),
        // "Float balance 50000"
        Regex("""(?i)(float\s*balance|salio\s*la\s*float)\s+(\d+(?:\.\d+)?)"""),
        // "Nimeuza airtime 200"
        Regex("""(?i)(nimeuza|nimetuma)\s+(airtime|bundle|data)\s+(\d+(?:\.\d+)?)?"""),
        // "Nimepost TikTok" / "Nimepost Instagram"
        Regex("""(?i)(nimepost|nimeweka|nimeshare)\s+(tiktok|instagram|facebook|whatsapp)"""),
        // "Orders 10" / "Nimepata order 5"
        Regex("""(?i)(order|oda)\s+(\d+)"""),
        // "Ad spend 500"
        Regex("""(?i)(ad\s*spend|matangazo|boost|promotion)\s+(\d+(?:\.\d+)?)""")
    )

    // === GIVING/TITHING PATTERNS ===
    private val givingRecordPatterns = listOf(
        // "Nilitoa sadaka KSh 200"
        Regex("""(?i)(nilitoa|nimetoa|nimechangia|nimeketoa|nikatoa)\s+(sadaka|zaka|zaka\s+ya\s+kumi|sadaqah|misaada|mchango)\s*(?:ksh|sh|kwa)?\s*(\d+(?:\.\d+)?)"""),
        // "Sadaka 200" / "Zaka 500"
        Regex("""(?i)(sadaka|zaka\s+ya\s+kumi|zaka|sadaqah|misaada|mchango)\s+(?:ksh|sh|kwa)?\s*(\d+(?:\.\d+)?)"""),
        // "Nilitoa 200 kanisani"
        Regex("""(?i)(nilitoa|nimetoa|nimechangia)\s+(?:ksh|sh)?\s*(\d+(?:\.\d+)?)\s+(kanisani|msikitini|kwa)"""),
        // "Toa sadaka 200" / "Give offering 200"
        Regex("""(?i)(toa|give|changia)\s+(sadaka|zaka|sadaqah|misaada|mchango)\s+(?:ksh|sh|kwa)?\s*(\d+(?:\.\d+)?)"""),
        // "Nimeketoa sadaka 200" (Sheng-inflected)
        Regex("""(?i)(nimeketoa|nikadonate)\s+(sadaka|zaka|sadaqah|mchango)\s*(\d+(?:\.\d+)?)""")
    )

    private val givingQueryPatterns = listOf(
        Regex("""(?i)(ripoti|report|summary|jumla)\s+(ya\s+)?(sadaka|zaka|kutoa|giving)"""),
        Regex("""(?i)(sadaka|zaka|kutoa|giving)\s+(ya\s+)?(leo|today|wiki|week|mwezi|month)"""),
        Regex("""(?i)(nime|nimetoa)\s+(kiasi\s+gani|ngapi)\s+(sadaka|zaka)"""),
        Regex("""(?i)(how\s+much|ngapi)\s+(have\s+i\s+)?(given|donated|tithe)"""),
        Regex("""(?i)(historia|history|rekodi)\s+(ya\s+)?(sadaka|zaka|kutoa)"""),
        Regex("""(?i)(abundance|mapato|income)\s+(baada|after)\s+(ya\s+)?(kutoa|giving)""")
    )

    private val givingGoalPatterns = listOf(
        Regex("""(?i)(lengo|goal|target)\s+(la\s+)?(kutoa|sadaka|zaka|giving)"""),
        Regex("""(?i)(weka|set|badilisha)\s+(lengo|goal)\s+(la\s+)?(kutoa|sadaka|zaka)"""),
        Regex("""(?i)(nataka\s+kutoa|want\s+to\s+give)\s+(ksh|sh)?\s*(\d+(?:\.\d+)?)"""),
        Regex("""(?i)(lengo\s+la\s+sadaka|giving\s+goal)\s+(\d+(?:\.\d+)?)""")
    )

    // === GOAL PLANNING PATTERNS ===
    /**
     * Goal creation patterns — "Lengo langu ni kununua friji"
     * "Nataka kusave KSh 50,000 kwa shule"
     */
    private val goalCreatePatterns = listOf(
        // "Lengo langu ni kununua friji"
        Regex("""(?i)(lengo\s+langu|nataka|ninataka|nina\s*hitaji)\s+(?:ni\s+)?(ku|kusave|kununua|kulipa|kupanua)\s+(.+)"""),
        // "Ninataka kusave KSh 50,000"
        Regex("""(?i)(nataka|ninataka)\s+(kusave|kununua|kulipa|kupanua)\s+(.+?)\s+(?:ksh|sh)?\s*(\d+(?:,\d{3})*(?:\.\d+)?)"""),
        // "Weka lengo la kununua friji"
        Regex("""(?i)(weka|unda|fanya)\s+lengo\s+(la|la\s+ku)(.+)"""),
        // "Lengo la kuuza KSh 10,000 kwa siku"
        Regex("""(?i)(lengo|goal)\s+(la|ni)\s+(kuuza|kupata|kufikia)\s+(?:ksh|sh)?\s*(\d+(?:,\d{3})*(?:\.\d+)?)"""),
        // "Nataka kununua friji mpya"
        Regex("""(?i)(nataka|ninataka|nina\s*hitaji)\s+(kununua|kusave|kulipa|kupanua)\s+(.+?)(?:\s+(?:ksh|sh)?\s*(\d+(?:,\d{3})*(?:\.\d+)?)?)?$"""),
        // Sheng: "Nataka kuprocure friji"
        Regex("""(?i)(nika-buy|niprocure|nikaget)\s+(.+?)\s+(?:ksh|sh)?\s*(\d+(?:,\d{3})*(?:\.\d+)?)?""")
    )

    /**
     * Goal progress patterns — "Nimefikia 50% ya lengo"
     */
    private val goalProgressPatterns = listOf(
        // "Nimefikia 50% ya lengo"
        Regex("""(?i)(nimefikia|nimeweka|nimekuwa)\s+(\d+)%?\s*(ya\s+lengo)?"""),
        // "Nimeweka 2000 leo"
        Regex("""(?i)(nimeweka|nimehifadhi|nimesave)\s+(?:ksh\s*)?(\d+)\s*(leo|wiki\s+hii)?"""),
        // "Progress ya lengo ni 60%"
        Regex("""(?i)(lengo\s+limefikia|progress\s+ni)\s+(\d+)%"""),
        // "Nimenunua friji" (goal complete)
        Regex("""(?i)(nimenunua|nimepata|nimefanikiwa)\s+(.+?)(?:\s+lengo\s+limeisha)?$""")
    )

    /**
     * Goal report patterns — "Ripoti ya malengo"
     */
    private val goalReportPatterns = listOf(
        // "Ripoti ya malengo"
        Regex("""(?i)(ripoti|report|hali|summary)\s+ya\s+malengo"""),
        // "Malengo yangu"
        Regex("""(?i)(malengo\s+yangu|goals?\s+zangu|malengo)"""),
        // "Nina malengo gani"
        Regex("""(?i)(nina\s+malengo|what\s+are\s+my\s+goals?)"""),
        // "Goal report"
        Regex("""(?i)(goal\s+report|report\s+ya\s+goals?)""")
    )

    /**
     * Time-to-goal forecast patterns — "Muda wa kufikia lengo"
     */
    private val goalTimeForecastPatterns = listOf(
        // "Muda wa kufikia lengo"
        Regex("""(?i)(muda|wakati)\s+wa\s+kufikia\s+lengo"""),
        // "Lengo litafikiwa lini"
        Regex("""(?i)(lengo\s+litafikiwa|goal\s+will\s+be\s+reached)\s+(lini|wakati\s+gani)"""),
        // "Nitafikia lengo lini"
        Regex("""(?i)(nitafikia|nita\s*fikia)\s+lengo\s+(lini|wakati\s+gani)"""),
        // "When will I reach my goal"
        Regex("""(?i)(when\s+will|how\s+long)\s+(to\s+)?(reach|achieve|complete)\s+(my\s+)?goal""")
    )

    /**
     * Goal adjustment patterns — "Badilisha lengo"
     */
    private val goalAdjustPatterns = listOf(
        // "Badilisha lengo"
        Regex("""(?i)(badilisha|change|adjust|sogeza)\s+lengo"""),
        // "Ongeza lengo hadi KSh 100,000"
        Regex("""(?i)(ongeza|punguza|badilisha)\s+lengo\s+(hadi|kuwa)\s+(?:ksh|sh)?\s*(\d+(?:,\d{3})*(?:\.\d+)?)"""),
        // "Extend deadline ya lengo"
        Regex("""(?i)(extend|sogeza|ongeza)\s+(tarehe|deadline|muda)\s+(ya\s+)?(lengo|mwisho)""")
    )

    /**
     * Goal encouragement patterns — "Nisaidie na lengo"
     */
    private val goalEncouragementPatterns = listOf(
        // "Nisaidie na lengo"
        Regex("""(?i)(nisaidie|encourage|motivation|saidia)\s+(na\s+)?lengo"""),
        // "Sijisikii kufikia lengo"
        Regex("""(?i)(sijisikii|nimechoka|nimemotivation|nikatae\s+tamaa)\s+(kufikia|na)\s+lengo"""),
        // "Niko\s+nje\s+ya\s+mpango"
        Regex("""(?i)(niko\s+nje|off\s+track|siko\s+sawa)\s+(ya\s+)?(mpango|lengo)""")
    )

    // === SERVICE-SPECIFIC PATTERNS ===
    private val servicePatterns = listOf(
        // "Nimenyolewa mteja 5" / "Clients 8"
        Regex("""(?i)(nimenyolewa|nimeshonwa|nimeshonewa|nimefanyiwa)\s+(mteja|client|wateja)\s+(\d+)"""),
        Regex("""(?i)(client|mteja|wateja)\s+(\d+)"""),
        // "Nimefundi gari 3"
        Regex("""(?i)(nimefundi|nimefanyia|nimeshonewa)\s+(gari|simu|phone|nguo|nywele)\s+(\d+)"""),
        // "Service ya gari 2000"
        Regex("""(?i)(service\s*ya|kazi\s*ya)\s+(gari|simu|phone|nywele|nguo)\s+(\d+(?:\.\d+)?)"""),
        // "Nimepata 1500 kutoka kwa mteja"
        Regex("""(?i)(nimepata|nimelipwa)\s+(\d+(?:\.\d+)?)\s+(kutoka|from)\s+(kwa\s+mteja|client)""")
    )

    // === QUERY PATTERNS ===
    private val balancePatterns = listOf(
        Regex("""(?i)(salio|balance|pesa|how much|ngapi|nina|niko)"""),
        Regex("""(?i)(nina\s+pesa|pesa\s+yangu|how\s+much\s+do\s+i\s+have)""")
    )

    private val profitPatterns = listOf(
        Regex("""(?i)(faida|profit|loss|hasara|margin|mapato)"""),
        Regex("""(?i)(how\s+much\s+(did\s+i|have\s+i)\s+(made|earned|profit))""")
    )

    private val stockPatterns = listOf(
        Regex("""(?i)(stock|inventory|baki|remaining|imebaki|bado|gani)"""),
        Regex("""(?i)(how\s+much\s+(stock|left|remaining))""")
    )

    // Transport-specific queries
    private val tripQueryPatterns = listOf(
        Regex("""(?i)(trips?|safari|gari|race)\s+(leo|today|jana|yesterday)"""),
        Regex("""(?i)(nimefanya\s+trips?|how\s+many\s+trips?)"""),
        Regex("""(?i)(fare|nauli|bei\s*ya\s*usafiri)"""),
        Regex("""(?i)(fuel\s*cost|gharama\s*ya\s*mafuta)"""),
        Regex("""(?i)(earnings\s*per\s*hour|mapato\s*ya\s*saa)""")
    )

    // Farming-specific queries
    private val harvestQueryPatterns = listOf(
        Regex("""(?i)(harvest|vuna|vunja|mazao)"""),
        Regex("""(?i)(crop|mazao|mmea|shamba)"""),
        Regex("""(?i)(planting\s*season|wakati\s*wa\s*kupanda)"""),
        Regex("""(?i)(yields|mavuno|production)""")
    )

    // Digital/gig queries
    private val digitalQueryPatterns = listOf(
        Regex("""(?i)(commission|komisheni)\s+(leo|today|wiki|week)"""),
        Regex("""(?i)(transactions?|shughuli)\s+(leo|today|jumla)"""),
        Regex("""(?i)(float|salio)"""),
        Regex("""(?i)(ads?\s*spend|matangazo|ROI|return)""")
    )

    // === ADVICE PATTERNS ===
    private val advicePatterns = listOf(
        Regex("""(?i)(nishauri|advise|nifanye|what\s+should|saidia|msaidizi|help|usaidizi)"""),
        Regex("""(?i)(nifanye\s+nini|what\s+can\s+i|how\s+can\s+i)""")
    )

    // === SUMMARY PATTERNS ===
    private val dailySummaryPatterns = listOf(
        Regex("""(?i)(report|summary|jumla|jumlisho)\s+(ya\s+)?leo"""),
        Regex("""(?i)(today|leo)\s+(report|summary|sales|mauzo)"""),
        Regex("""(?i)(daily\s+report|report\s+ya\s+leo)""")
    )

    private val weeklySummaryPatterns = listOf(
        Regex("""(?i)(report|summary)\s+(ya\s+)?wiki"""),
        Regex("""(?i)(weekly|wiki)\s+(report|summary)""")
    )

    // === HELP / GREETING PATTERNS ===
    private val helpPatterns = listOf(
        Regex("""(?i)(help|saidia|msaidizi|nini|unaweza|what\s+can)"""),
        Regex("""(?i)(jinsi|how\s+to|how\s+do)""")
    )

    private val greetingPatterns = listOf(
        Regex("""(?i)^(habari|hi|hello|hey|sasa|niaje|vipi|mambo|salama)"""),
        Regex("""(?i)^(good\s+(morning|afternoon|evening)|siku\s+njema)""")
    )

    // === CORRECTION PATTERNS ===
    private val correctionPatterns = listOf(
        Regex("""(?i)(hapana|siyo|si|wrong|incorrect|badilisha|rekebisha|change|correct)"""),
        Regex("""(?i)(I\s+meant|alisema|kusema)""")
    )

    /**
     * Classify the intent of user input.
     * Returns IntentResult with type, confidence, and extracted data.
     */
    fun classify(text: String): IntentResult {
        val cleaned = text.trim()
        if (cleaned.isBlank()) {
            return IntentResult(IntentType.UNKNOWN, 0.0)
        }

        // ═══ STEP 0: Sheng/Dialect Normalization ═══
        // 30% of Kenya's youth speak Sheng. Normalize before pattern matching.
        // We keep the original text for Sheng-specific patterns.
        val normalized = normalizeSheng(cleaned)
        val hasSheng = normalized != cleaned.lowercase().trim()

        // ═══ STEP 1: Try standard patterns on normalized text ═══
        // Try sale patterns first (most common)
        for (pattern in salePatterns) {
            val match = pattern.find(cleaned)
            if (match != null) {
                val data = extractSaleData(cleaned, match)
                if (data != null) {
                    return IntentResult(
                        intent = IntentType.SALE,
                        confidence = 0.95,
                        extractedData = mapOf(
                            "item" to data.item,
                            "quantity" to data.quantity.toString(),
                            "amount" to data.amount.toString(),
                            "unitPrice" to data.unitPrice.toString()
                        )
                    )
                }
            }
        }

        // Try purchase patterns
        for (pattern in purchasePatterns) {
            val match = pattern.find(cleaned)
            if (match != null) {
                val data = extractPurchaseData(cleaned, match)
                if (data != null) {
                    return IntentResult(
                        intent = IntentType.PURCHASE,
                        confidence = 0.95,
                        extractedData = mapOf(
                            "item" to data.item,
                            "quantity" to data.quantity.toString(),
                            "amount" to data.amount.toString(),
                            "unitPrice" to data.unitPrice.toString()
                        )
                    )
                }
            }
        }

        // Try expense patterns
        for (pattern in expensePatterns) {
            val match = pattern.find(cleaned)
            if (match != null) {
                val data = extractExpenseData(cleaned, match)
                if (data != null) {
                    return IntentResult(
                        intent = IntentType.EXPENSE,
                        confidence = 0.90,
                        extractedData = data
                    )
                }
            }
        }

        // === TRANSPORT-SPECIFIC PATTERNS ===
        for (pattern in tripPatterns) {
            val match = pattern.find(cleaned)
            if (match != null) {
                val groups = match.groupValues
                return when {
                    // Trip count: "Nimefanya trip 5"
                    groups.size >= 3 && groups[2].toIntOrNull() != null -> IntentResult(
                        intent = IntentType.TRANSPORT_TRIP,
                        confidence = 0.90,
                        extractedData = mapOf(
                            "tripCount" to groups[2],
                            "item" to "trip"
                        )
                    )
                    // Passenger fare: "Passenger 300"
                    groups.size >= 3 && groups[2].toDoubleOrNull() != null -> IntentResult(
                        intent = IntentType.TRANSPORT_TRIP,
                        confidence = 0.85,
                        extractedData = mapOf(
                            "item" to "passenger",
                            "amount" to groups[2]
                        )
                    )
                    else -> null
                } ?: continue
            }
        }

        // === FARMING-SPECIFIC PATTERNS ===
        for (pattern in farmingPatterns) {
            val match = pattern.find(cleaned)
            if (match != null) {
                val groups = match.groupValues
                return when {
                    // Harvest with quantity: "Nimevuna mahindi 50kg"
                    groups.size >= 4 && groups[3].toIntOrNull() != null -> IntentResult(
                        intent = IntentType.FARMING_ACTIVITY,
                        confidence = 0.90,
                        extractedData = mapOf(
                            "item" to groups[2].trim(),
                            "quantity" to groups[3],
                            "activity" to "harvest"
                        )
                    )
                    // Planting with area: "Nimepanda mahindi ekari 2"
                    groups.size >= 4 && groups[3].toIntOrNull() != null -> IntentResult(
                        intent = IntentType.FARMING_ACTIVITY,
                        confidence = 0.90,
                        extractedData = mapOf(
                            "item" to groups[2].trim(),
                            "area" to groups[3],
                            "activity" to "plant"
                        )
                    )
                    // Input purchase: "Nimenunua mbegu kwa 800"
                    groups.size >= 5 && groups[4].toDoubleOrNull() != null -> IntentResult(
                        intent = IntentType.FARMING_INPUT,
                        confidence = 0.90,
                        extractedData = mapOf(
                            "item" to groups[2].trim(),
                            "amount" to groups[4]
                        )
                    )
                    // Simple planting: "Nimepanda mahindi"
                    groups.size >= 3 -> IntentResult(
                        intent = IntentType.FARMING_ACTIVITY,
                        confidence = 0.85,
                        extractedData = mapOf(
                            "item" to groups[2].trim(),
                            "activity" to "plant"
                        )
                    )
                    else -> null
                } ?: continue
            }
        }

        // === DIGITAL/GIG-SPECIFIC PATTERNS ===
        for (pattern in digitalPatterns) {
            val match = pattern.find(cleaned)
            if (match != null) {
                val groups = match.groupValues
                return when {
                    // Transaction count: "Nimefanya transaction 50"
                    groups.size >= 3 && groups[2].toIntOrNull() != null -> IntentResult(
                        intent = IntentType.DIGITAL_TRANSACTION,
                        confidence = 0.85,
                        extractedData = mapOf(
                            "transactionCount" to groups[2],
                            "item" to "transaction"
                        )
                    )
                    // Commission/amount: "Commission 1200"
                    groups.size >= 3 && groups[2].toDoubleOrNull() != null -> IntentResult(
                        intent = IntentType.DIGITAL_COMMISSION,
                        confidence = 0.90,
                        extractedData = mapOf(
                            "item" to groups[1].lowercase(),
                            "amount" to groups[2]
                        )
                    )
                    // Deposit/withdrawal: "Deposits 20000"
                    groups.size >= 3 && groups[2].toDoubleOrNull() != null -> IntentResult(
                        intent = IntentType.DIGITAL_TRANSACTION,
                        confidence = 0.85,
                        extractedData = mapOf(
                            "item" to groups[1].lowercase(),
                            "amount" to groups[2]
                        )
                    )
                    // Social media post: "Nimepost TikTok"
                    groups.size >= 2 -> IntentResult(
                        intent = IntentType.DIGITAL_TRANSACTION,
                        confidence = 0.80,
                        extractedData = mapOf(
                            "item" to "post",
                            "platform" to groups.last().lowercase()
                        )
                    )
                    else -> null
                } ?: continue
            }
        }

        // === SERVICE-SPECIFIC PATTERNS ===
        for (pattern in servicePatterns) {
            val match = pattern.find(cleaned)
            if (match != null) {
                val groups = match.groupValues
                return when {
                    // Client count: "Clients 8"
                    groups.size >= 3 && groups[2].toIntOrNull() != null -> IntentResult(
                        intent = IntentType.SERVICE_CLIENT,
                        confidence = 0.85,
                        extractedData = mapOf(
                            "clientCount" to groups[2],
                            "item" to "client"
                        )
                    )
                    // Service with amount: "Service ya gari 2000"
                    groups.size >= 4 && groups[3].toDoubleOrNull() != null -> IntentResult(
                        intent = IntentType.SERVICE_JOB,
                        confidence = 0.90,
                        extractedData = mapOf(
                            "item" to groups[2].trim(),
                            "amount" to groups[3]
                        )
                    )
                    // Payment from client: "Nimepata 1500 kutoka kwa mteja"
                    groups.size >= 4 && groups[2].toDoubleOrNull() != null -> IntentResult(
                        intent = IntentType.SERVICE_JOB,
                        confidence = 0.90,
                        extractedData = mapOf(
                            "item" to "service",
                            "amount" to groups[2]
                        )
                    )
                    else -> null
                } ?: continue
            }
        }

        // === GIVING/TITHING PATTERNS ===
        // Check giving record patterns ("Nilitoa sadaka KSh 200")
        for (pattern in givingRecordPatterns) {
            val match = pattern.find(cleaned)
            if (match != null) {
                val groups = match.groupValues
                val amount = groups.lastOrNull { it.toDoubleOrNull() != null } ?: continue
                // Determine giving type keyword
                val givingKeyword = groups.getOrNull(2)?.lowercase() ?: "sadaka"
                val type = when {
                    givingKeyword.contains("zaka ya kumi") || givingKeyword.contains("tithe") -> "TITHE"
                    givingKeyword.contains("zaka") || givingKeyword.contains("zakat") -> "ZAKAT"
                    givingKeyword.contains("sadaqah") || givingKeyword.contains("sadaqa") -> "SADAQAH"
                    givingKeyword.contains("sadaka") -> "OFFERING"
                    givingKeyword.contains("misaada") || givingKeyword.contains("mchango") -> "CHARITY"
                    else -> "OFFERING"
                }
                return IntentResult(
                    intent = IntentType.GIVING_RECORD,
                    confidence = 0.95,
                    extractedData = mapOf(
                        "amount" to amount,
                        "givingType" to type,
                        "rawText" to cleaned
                    )
                )
            }
        }

        // Check giving query patterns ("Ripoti ya sadaka")
        if (givingQueryPatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.GIVING_QUERY,
                confidence = 0.90,
                extractedData = mapOf("rawText" to cleaned)
            )
        }

        // Check giving goal patterns ("Lengo la kutoa")
        for (pattern in givingGoalPatterns) {
            val match = pattern.find(cleaned)
            if (match != null) {
                val groups = match.groupValues
                val amount = groups.lastOrNull { it.toDoubleOrNull() != null } ?: "0"
                return IntentResult(
                    intent = IntentType.GIVING_GOAL,
                    confidence = 0.90,
                    extractedData = mapOf(
                        "amount" to amount,
                        "rawText" to cleaned
                    )
                )
            }
        }

        // Try query patterns (use normalized text for Sheng support)
        if (profitPatterns.any { it.containsMatchIn(normalized) }) {
            return IntentResult(
                intent = IntentType.PROFIT_QUERY,
                confidence = 0.90
            )
        }

        if (balancePatterns.any { it.containsMatchIn(normalized) }) {
            return IntentResult(
                intent = IntentType.CHECK_BALANCE,
                confidence = 0.90
            )
        }

        if (stockPatterns.any { it.containsMatchIn(normalized) }) {
            return IntentResult(
                intent = IntentType.STOCK_QUERY,
                confidence = 0.85,
                extractedData = mapOf("item" to (SwahiliParser.extractItemName(normalized) ?: ""))
            )
        }

        // Transport-specific queries
        if (tripQueryPatterns.any { it.containsMatchIn(normalized) }) {
            return IntentResult(
                intent = IntentType.TRANSPORT_TRIP,
                confidence = 0.80,
                extractedData = mapOf("queryType" to "trip_info")
            )
        }

        // Farming-specific queries
        if (harvestQueryPatterns.any { it.containsMatchIn(normalized) }) {
            return IntentResult(
                intent = IntentType.FARMING_ACTIVITY,
                confidence = 0.80,
                extractedData = mapOf("queryType" to "harvest_info")
            )
        }

        // Digital/gig queries
        if (digitalQueryPatterns.any { it.containsMatchIn(normalized) }) {
            return IntentResult(
                intent = IntentType.DIGITAL_COMMISSION,
                confidence = 0.80,
                extractedData = mapOf("queryType" to "digital_info")
            )
        }

        // Try summary patterns
        if (dailySummaryPatterns.any { it.containsMatchIn(normalized) }) {
            return IntentResult(
                intent = IntentType.DAILY_SUMMARY,
                confidence = 0.90
            )
        }

        if (weeklySummaryPatterns.any { it.containsMatchIn(normalized) }) {
            return IntentResult(
                intent = IntentType.WEEKLY_SUMMARY,
                confidence = 0.85
            )
        }

        // Try advice patterns
        if (advicePatterns.any { it.containsMatchIn(normalized) }) {
            return IntentResult(
                intent = IntentType.ASK_ADVICE,
                confidence = 0.85,
                needsLLM = true
            )
        }

        // Try greeting patterns
        if (greetingPatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.GREETING,
                confidence = 0.95
            )
        }

        // Try help patterns
        if (helpPatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.HELP,
                confidence = 0.80
            )
        }

        // Try correction patterns
        if (correctionPatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.CORRECTION,
                confidence = 0.80,
                needsLLM = true
            )
        }

        // ═══ STEP 2: Try Sheng-specific patterns on original text ═══
        // If standard patterns failed and text contains Sheng markers,
        // try Sheng-specific patterns.
        if (hasSheng) {
            val shengResult = tryShengPatterns(cleaned)
            if (shengResult != null) {
                Timber.d("Sheng pattern matched: %s (%.2f)", shengResult.intent, shengResult.confidence)
                return shengResult
            }
        }

        // ═══ STEP 3: Try normalized text with query patterns ═══
        // Use normalized text for query patterns (greetings, help, etc.)
        val queryResult = tryQueryPatterns(normalized)
        if (queryResult.intent != IntentType.UNKNOWN) {
            return queryResult
        }

        // Fallback: unknown intent
        return IntentResult(
            intent = IntentType.UNKNOWN,
            confidence = 0.0,
            needsLLM = true
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // SHENG NORMALIZATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Normalize Sheng text to standard Swahili.
     * Replaces Sheng vocabulary with standard equivalents so that
     * the existing Swahili regex patterns can match.
     *
     * Example:
     * "nikaue mandazi tano kwa thao" → "nimeuza mandazi tano kwa 1000"
     * "nikapurchase unga finje" → "nimenunua unga 500"
     */
    private fun normalizeSheng(text: String): String {
        var normalized = text.lowercase().trim()

        // Replace Sheng words with standard equivalents
        for ((sheng, standard) in shengToStandard) {
            normalized = normalized.replace(sheng, standard)
        }

        return normalized
    }

    // ═══════════════════════════════════════════════════════════════
    // SHENG-SPECIFIC PATTERN MATCHING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Try Sheng-specific patterns for sale/purchase/expense.
     * These handle constructions unique to Sheng that standard
     * Swahili patterns can't match.
     *
     * @param text Original (un-normalized) Sheng text
     * @return IntentResult if matched, null otherwise
     */
    private fun tryShengPatterns(text: String): IntentResult? {
        // Sheng sale patterns
        for (pattern in shengSalePatterns) {
            val match = pattern.find(text) ?: continue
            val groups = match.groupValues

            // Determine if this is a sale or purchase based on verb
            val verb = groups[1].lowercase()
            val isPurchase = verb.contains("buy") || verb.contains("purchase") ||
                verb.contains("log")

            val item = groups[2].trim()
            if (item.isBlank()) continue

            // Extract amount — could be a Sheng number word or digit
            val amountStr = groups.lastOrNull { it.toDoubleOrNull() != null }
                ?: resolveShengAmount(groups)
                ?: continue

            val amount = amountStr.toDoubleOrNull() ?: continue

            return IntentResult(
                intent = if (isPurchase) IntentType.PURCHASE else IntentType.SALE,
                confidence = 0.85,
                extractedData = mapOf(
                    "item" to item,
                    "quantity" to (groups.getOrNull(3)?.toDoubleOrNull()?.toString() ?: "1"),
                    "amount" to amount.toString(),
                    "unitPrice" to amount.toString()
                )
            )
        }

        // Sheng expense patterns
        for (pattern in shengExpensePatterns) {
            val match = pattern.find(text) ?: continue
            val groups = match.groupValues

            // Resolve Sheng amount words
            val amountStr = groups.getOrNull(2)?.toDoubleOrNull()?.toString()
                ?: resolveShengAmount(groups)
                ?: continue

            val amount = amountStr.toDoubleOrNull() ?: continue
            val category = groups.getOrNull(4)?.ifBlank { null }
                ?: groups.getOrNull(1)?.lowercase()
                ?: "other"

            return IntentResult(
                intent = IntentType.EXPENSE,
                confidence = 0.80,
                extractedData = mapOf(
                    "category" to category,
                    "amount" to amount.toString()
                )
            )
        }

        return null
    }

    /**
     * Resolve Sheng amount words to numeric values.
     * Handles constructions like "thao" (1000), "finje" (500), "bao" (20).
     */
    private fun resolveShengAmount(groups: List<String>): String? {
        for (group in groups) {
            val lower = group.lowercase().trim()
            when (lower) {
                "thao", "ngiri" -> return "1000"
                "finje" -> return "500"
                "jeuri" -> return "50"
                "bao" -> return "20"
                "kumi" -> return "10"
            }
        }
        return null
    }

    /**
     * Try query patterns (balance, profit, stock, summary, advice, greeting).
     * This is the final fallback after standard and Sheng patterns.
     */
    private fun tryQueryPatterns(text: String): IntentResult {
        // Profit query
        if (profitPatterns.any { it.containsMatchIn(text) }) {
            return IntentResult(intent = IntentType.PROFIT_QUERY, confidence = 0.90)
        }

        // Balance query
        if (balancePatterns.any { it.containsMatchIn(text) }) {
            return IntentResult(intent = IntentType.CHECK_BALANCE, confidence = 0.90)
        }

        // Stock query
        if (stockPatterns.any { it.containsMatchIn(text) }) {
            return IntentResult(
                intent = IntentType.STOCK_QUERY,
                confidence = 0.85,
                extractedData = mapOf("item" to (SwahiliParser.extractItemName(text) ?: ""))
            )
        }

        // Transport queries
        if (tripQueryPatterns.any { it.containsMatchIn(text) }) {
            return IntentResult(
                intent = IntentType.TRANSPORT_TRIP,
                confidence = 0.80,
                extractedData = mapOf("queryType" to "trip_info")
            )
        }

        // Farming queries
        if (harvestQueryPatterns.any { it.containsMatchIn(text) }) {
            return IntentResult(
                intent = IntentType.FARMING_ACTIVITY,
                confidence = 0.80,
                extractedData = mapOf("queryType" to "harvest_info")
            )
        }

        // Digital queries
        if (digitalQueryPatterns.any { it.containsMatchIn(text) }) {
            return IntentResult(
                intent = IntentType.DIGITAL_COMMISSION,
                confidence = 0.80,
                extractedData = mapOf("queryType" to "digital_info")
            )
        }

        // Daily summary
        if (dailySummaryPatterns.any { it.containsMatchIn(text) }) {
            return IntentResult(intent = IntentType.DAILY_SUMMARY, confidence = 0.90)
        }

        // Weekly summary
        if (weeklySummaryPatterns.any { it.containsMatchIn(text) }) {
            return IntentResult(intent = IntentType.WEEKLY_SUMMARY, confidence = 0.85)
        }

        // Advice
        if (advicePatterns.any { it.containsMatchIn(text) }) {
            return IntentResult(intent = IntentType.ASK_ADVICE, confidence = 0.85, needsLLM = true)
        }

        // Greeting
        if (greetingPatterns.any { it.containsMatchIn(text) }) {
            return IntentResult(intent = IntentType.GREETING, confidence = 0.95)
        }

        // Help
        if (helpPatterns.any { it.containsMatchIn(text) }) {
            return IntentResult(intent = IntentType.HELP, confidence = 0.80)
        }

        // Correction
        if (correctionPatterns.any { it.containsMatchIn(text) }) {
            return IntentResult(intent = IntentType.CORRECTION, confidence = 0.80, needsLLM = true)
        }

        // Unknown
        return IntentResult(intent = IntentType.UNKNOWN, confidence = 0.0, needsLLM = true)
    }

    /**
     * Extract sale data from the matched text.
     */
    private fun extractSaleData(text: String, match: MatchResult): SaleData? {
        val item = SwahiliParser.extractItemName(text) ?: return null
        val quantity = SwahiliParser.extractQuantity(text)
        val amount = SwahiliParser.extractPrice(text) ?: return null

        return SaleData(
            item = item,
            quantity = quantity,
            amount = amount
        )
    }

    /**
     * Extract purchase data from the matched text.
     */
    private fun extractPurchaseData(text: String, match: MatchResult): PurchaseData? {
        val item = SwahiliParser.extractItemName(text) ?: return null
        val quantity = SwahiliParser.extractQuantity(text)
        val amount = SwahiliParser.extractPrice(text) ?: return null

        return PurchaseData(
            item = item,
            quantity = quantity,
            amount = amount
        )
    }

    /**
     * Extract expense data from the matched text.
     */
    private fun extractExpenseData(text: String, match: MatchResult): Map<String, String>? {
        val amount = SwahiliParser.extractPrice(text) ?: return null
        val category = SwahiliParser.extractItemName(text) ?: "other"

        return mapOf(
            "category" to category,
            "amount" to amount.toString()
        )
    }
}
