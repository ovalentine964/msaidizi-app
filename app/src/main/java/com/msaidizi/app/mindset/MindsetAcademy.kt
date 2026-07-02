package com.msaidizi.app.mindset

import com.msaidizi.app.core.database.MindsetLessonDao
import com.msaidizi.app.core.model.MindsetLessonEntity
import timber.log.Timber

/**
 * Mindset Academy — delivers daily voice lessons on wealth mindset,
 * financial literacy, and personal development.
 *
 * Based on key principles from:
 * - "The Magic of Thinking Big" by David Schwartz
 * - "Think and Grow Rich" by Napoleon Hill
 * - "The Richest Man in Babylon" by George Clason
 * - "Atomic Habits" by James Clear
 * - Financial literacy fundamentals for East African context
 *
 * Categories: Habits, Goals, Financial Literacy, Mindset, Giving
 * All lessons in Swahili first, English fallback.
 * Delivered via voice (2-3 minutes each).
 *
 * @param lessonDao Room DAO for lesson persistence
 */
class MindsetAcademy(
    private val lessonDao: MindsetLessonDao
) {
    companion object {
        private const val TAG = "MindsetAcademy"
        const val TOTAL_LESSONS = 50

        // Category names
        val CATEGORIES = listOf("HABITS", "GOALS", "FINANCIAL_LITERACY", "MINDSET", "GIVING")
        val CATEGORIES_SW = mapOf(
            "HABITS" to "Tabia",
            "GOALS" to "Malengo",
            "FINANCIAL_LITERACY" to "Elimu ya Fedha",
            "MINDSET" to "Mawazo",
            "GIVING" to "Kutoa"
        )
    }

    /**
     * Seed the database with all 50 lessons if not already present.
     * Call once on first launch or database creation.
     */
    suspend fun seedLessons() {
        val existingCount = lessonDao.getTotalCount()
        if (existingCount >= TOTAL_LESSONS) {
            Timber.d(TAG, "Lessons already seeded (%d)", existingCount)
            return
        }

        Timber.d(TAG, "Seeding %d mindset lessons", TOTAL_LESSONS)
        lessonDao.insertAll(buildLessonLibrary())
    }

    /**
     * Get the next lesson to deliver to the user.
     * Prioritizes undelivered lessons in order.
     *
     * @param category Optional category filter
     * @return The next lesson, or null if all delivered
     */
    suspend fun getNextLesson(category: String? = null): MindsetLessonEntity? {
        return if (category != null) {
            lessonDao.getNextIncompleteByCategory(category)
        } else {
            lessonDao.getNextUndeliveredLesson()
        }
    }

    /**
     * Deliver a lesson — marks it as delivered and returns voice-ready content.
     *
     * @param lessonId The lesson to deliver
     * @param language "sw" or "en"
     * @return VoiceLesson ready for TTS playback
     */
    suspend fun deliverLesson(lessonId: String, language: String = "sw"): VoiceLesson? {
        val lesson = lessonDao.getLesson(lessonId) ?: return null
        lessonDao.markDelivered(lessonId)

        val title = if (language == "sw") lesson.titleSw else lesson.titleEn
        val content = if (language == "sw") lesson.contentSw else lesson.contentEn

        Timber.d(TAG, "Delivering lesson: %s (%s)", lessonId, title)

        return VoiceLesson(
            lessonId = lesson.lessonId,
            title = title,
            content = content,
            category = lesson.category,
            sourceBook = lesson.sourceBook,
            durationSeconds = lesson.durationSeconds,
            language = language
        )
    }

    /**
     * Mark a lesson as completed (user listened to the end).
     */
    suspend fun completeLesson(lessonId: String) {
        lessonDao.markCompleted(lessonId)
        Timber.d(TAG, "Lesson completed: %s", lessonId)
    }

    /**
     * Get progress summary for the user.
     */
    suspend fun getProgress(): AcademyProgress {
        val completed = lessonDao.getCompletedCount()
        val total = lessonDao.getTotalCount()

        val categoryProgress = CATEGORIES.associateWith { cat ->
            val catCompleted = lessonDao.getCompletedCountByCategory(cat)
            val catTotal = lessonDao.getTotalCountByCategory(cat)
            CategoryProgress(
                category = cat,
                nameSw = CATEGORIES_SW[cat] ?: cat,
                completed = catCompleted,
                total = catTotal
            )
        }

        return AcademyProgress(
            completedLessons = completed,
            totalLessons = total.coerceAtLeast(TOTAL_LESSONS),
            progressPercent = if (total > 0) (completed * 100 / total) else 0,
            categoryProgress = categoryProgress
        )
    }

    /**
     * Get daily lesson prompt for voice delivery.
     * Called during morning briefing or first app open of the day.
     */
    suspend fun getDailyLessonPrompt(language: String = "sw"): String? {
        val lesson = getNextLesson() ?: return null

        return if (language == "sw") {
            "📖 Somo la leo: ${lesson.titleSw}. Kutoka kitabu '${lesson.sourceBook}'. " +
            "Sikiliza dakika ${lesson.durationSeconds / 60}."
        } else {
            "📖 Today's lesson: ${lesson.titleEn}. From '${lesson.sourceBook}'. " +
            "Listen for ${lesson.durationSeconds / 60} minutes."
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LESSON LIBRARY — 50+ lessons
    // ═══════════════════════════════════════════════════════════════

    private fun buildLessonLibrary(): List<MindsetLessonEntity> {
        var order = 0
        return mutableListOf<MindsetLessonEntity>().apply {
            // ═══ HABITS (10 lessons) ═══
            add(lesson(order++, "HABITS",
                "Njia ya Mafanikio ni Tabia", "The Path to Success is Habits",
                "Karibu kwenye somo la kwanza la tabia. Mwandishi James Clear anasema: " +
                "Mafanikio si tukio moja, ni matokeo ya tabia ndogo kila siku. " +
                "Kama mfanyabiashara, kurekodi mauzo yako kila siku ni tabia ya kwanza. " +
                "Usiache hata siku moja. Siku 21 mfululizo na tabia inakuwa sehemu yako. " +
                "Leo, rekodi mauzo yako yote — hata yale madogo. Hii ndio njia ya mafanikio.",
                "Atomic Habits"))

            add(lesson(order++, "HABITS",
                "Uwekezaji wa Tabia", "Habit Stacking",
                "James Clear anatuambia:unganisha tabia mpya na ile unayoifanya tayari. " +
                "Mfano: Ukimaliza kuuza, angalio salio lako mara moja. " +
                "Hii ni 'habit stacking' — kuweka tabia juu ya tabia. " +
                "Kila unapofungua Msaidizi, rekodi mauzo, angalia salio, kisha angalia stock. " +
                "Tabia tatu, dakika moja. Baada ya mwezi, utaona mabadiliko makubwa.",
                "Atomic Habits"))

            add(lesson(order++, "HABITS",
                "Mfumo wa Pointi Nne", "The Four Laws of Behavior Change",
                "James Clear anasema kuna njia nne za kubadilisha tabia: " +
                "1. Ionekane — weka app karibu na wewe. " +
                "2. Ivutie — fungua app ukiwa na wateja. " +
                "3. Irahisi — sema tu 'Nimeuza kwa 500'. " +
                "4. Iradhishe — angalia faida yako ikiongezeka. " +
                "Msaidizi imetengenezwa kwa njia hizi zote. Tumia kila siku!",
                "Atomic Habits"))

            add(lesson(order++, "HABITS",
                "Asilimia Moja Kila Siku", "1% Better Every Day",
                "James Clear anasema: ukiboresha asilimia moja kila siku, " +
                "baada ya mwaka mmoja utakuwa mara 37 bora zaidi. " +
                "Hii ndio nguvu ya tabia ndogo. Kurekodi mauzo yako leo? " +
                "Hii ni asilimia moja yako ya leo. Kesho, angalia faida yako — " +
                "asilimia moja nyingine. Mwisho wa mwaka, utakuwa tajiri wa data!",
                "Atomic Habits"))

            add(lesson(order++, "HABITS",
                "Sio Kukamilika, ni Kupanda", "Not Perfection, But Direction",
                "Mwandishi anasema: usijaribu kukamilika, jaribu kupanda. " +
                "Ukiweka mauzo 10 leo na 8 kesho — usikate tamaa! " +
                "Muhimu ni mwelekeo, si ukamilifu. Angalia juma lote: " +
                "je, una mauzo zaidi wiki hii kuliko iliyopita? " +
                "Hii ndio maendeleo ya kweli. Endelea kupanda!",
                "Atomic Habits"))

            add(lesson(order++, "HABITS",
                "Utambulisho wako", "Your Identity",
                "James Clear anasema: mabadiliko ya kweli yanatoka ndani. " +
                "Usiseme 'Ninataka kuuza zaidi'. Sema 'Mimi ni mfanyabiashara'. " +
                "Mfanyabiashara anarekodi mauzo yake kila siku. " +
                "Mfanyabiashara anajua faida yake. Mfanyabiashara anapanga kesho. " +
                "Sema leo: 'Mimi ni mfanyabiashara' — na fanya hivyo!",
                "Atomic Habits"))

            add(lesson(order++, "HABITS",
                "Mazingira yako", "Your Environment",
                "Mazingira yako yanakutengeneza. James Clear anasema: " +
                "weka vitu vinavyokusaidia mbele, na vishawishi mbali. " +
                "Weka simu yako na Msaidizi mahali pa kazi. " +
                "Fungua app asubuhi kabla ya kufungua duka. " +
                "Mazingira mazuri = tabia nzuri = biashara bora!",
                "Atomic Habits"))

            add(lesson(order++, "HABITS",
                "Dakika Mbili", "The Two-Minute Rule",
                "James Clear anasema: anza na kitu kinachochukua dakika mbili tu. " +
                "Kurekodi mauzo? Dakika mbili. Kuangalia salio? Dakika mbili. " +
                "Kuangalia stock? Dakika mbili. " +
                "Ukishazoea dakika mbili, utaongeza muda polepole. " +
                "Anza leo — dakika mbili za Msaidizi tu!",
                "Atomic Habits"))

            add(lesson(order++, "HABITS",
                "Mnyororo wa Siku", "The Chain Effect",
                "Jerry Seinfeld alifanikiwa kwa 'don't break the chain'. " +
                "Anza siku moja, kisha siku ya pili, ya tatu... " +
                "Kila siku ya mfululizo ni kiungo kwenye mnyororo wako. " +
                "Msaidizi inakuhesabia mfululizo wako. " +
                "Siku 7 = wiki moja. Siku 30 = mwezi. Siku 90 = mabadiliko!",
                "Atomic Habits"))

            add(lesson(order++, "HABITS",
                "Mwenzi wa Kujihesabia", "Accountability Partner",
                "James Clear anasema: mwenzi mzuri anakuweka na uwajibikaji. " +
                "Msaidizi ni mwenzi wako wa kila siku. " +
                "Inakukumbusha, inakuhesabia, inakutia moyo. " +
                "Lakini pia, ongea na mwingine — mwenye duka jirani, rafiki yako. " +
                "Msaidizi wa kweli ni yule anayekuhimiza kila siku!",
                "Atomic Habits"))

            // ═══ GOALS (10 lessons) ═══
            add(lesson(order++, "GOALS",
                "Lengo la Kwanza", "Your First Goal",
                "Napoleon Hill anasema: mafanikio yanaanza na lengo thabiti. " +
                "Je, unataka kupata faida ya KSh ngapi wiki hii? " +
                "Weka leno lako sasa. Mfano: 'Nataka kupata KSh 5,000 wiki hii'. " +
                "Andika leno lako. Sema kwa Msaidizi: 'Lengo langu ni KSh 5,000'. " +
                "Lengo lisiloandikwa ni ndoto. Lengo lililoandikwa ni mpango!",
                "Think and Grow Rich"))

            add(lesson(order++, "GOALS",
                "Njia ya Pesa", "The Money Mindset",
                "Napoleon Hill anasema: wazo lako la kwanza la pesa ni muhimu. " +
                "Kama unafikiri 'pesa ni ngumu', utapata ugumu. " +
                "Kama unafikiri 'pesa inakuja kwa kazi nzuri', utafanikiwa. " +
                "Biashara yako ndio njia yako ya pesa. " +
                "Kila mauzo ni hatua. Kila faida ni ushindi. Anza kufikiri hivi!",
                "Think and Grow Rich"))

            add(lesson(order++, "GOALS",
                "Uamuzi wa Haraka", "Decision Making",
                "Hill anasema: watu waliofanikiwa wana uamuzi wa haraka, " +
                "na hubadilika polepole. Wale ambao hawajafanikiwa wana uamuzi wa polepole, " +
                "na hubadilika haraka. " +
                "Kama mteja anauliza bei, jibu haraka. Kama bei ya soko imebadilika, badilika haraka. " +
                "Biashara inahitaji uamuzi — usiogope kufanya maamuzi!",
                "Think and Grow Rich"))

            add(lesson(order++, "GOALS",
                "Ndoto ya Biashara", "Business Vision",
                "Hill anasema: kila mafanikio makubwa yalianza na ndoto. " +
                "Ndoto yako ni nini? Duka kubwa? Msururu wa maduka? " +
                "Anza na ndoto, kisha weka malengo, kisha fanya kazi. " +
                "Msaidizi inakusaidia kufuatilia hatua zako. " +
                "Kila siku ya mauzo ni hatua kuelekea ndoto yako!",
                "Think and Grow Rich"))

            add(lesson(order++, "GOALS",
                "Mpango wa Wiki", "Weekly Planning",
                "Kila Jumapili, weka mpango wa wiki. " +
                "1. Lengo la mauzo ni KSh ngapi? " +
                "2. Bidhaa gani zinahitajika? " +
                "3. Bei gani nitatoa? " +
                "4. Wateja gani natarajia? " +
                "Weka mpango huu kwenye Msaidizi. Kila siku, angalia maendeleo yako. " +
                "Mpango bila hatua ni ndoto. Hatua bila mpango ni fujo!",
                "Think and Grow Rich"))

            add(lesson(order++, "GOALS",
                "Malengo ya Mwezi", "Monthly Goals",
                "Napoleon Hill anasema: weka malengo ya mwezi. " +
                "Mfano: Mwezi huu, nataka kupata faida ya KSh 20,000. " +
                "Hii inamaanisha KSh 650 kwa siku. " +
                "Angalia mauzo yako ya kila siku. Je, unafikia KSh 650? " +
                "Kama hapana, ongeza bei, ongeza wateja, au ongeza bidhaa. " +
                "Msaidizi itakuonyesha maendeleo yako!",
                "Think and Grow Rich"))

            add(lesson(order++, "GOALS",
                "Kuongeza Kipato", "Increasing Income",
                "Njia tatu za kuongeza kipato: " +
                "1. Ongeza bei — angalia bei za soko, weka bei nzuri. " +
                "2. Ongeza wauzaji — ongeza bidhaa au huduma mpya. " +
                "3. Ongeza muda — fungua mapema, funga baadaye. " +
                "Anza na moja. Wiki hii, jaribu njia moja. " +
                "Angalia matokeo kwenye Msaidizi. Nenda na ile inayofanya kazi!",
                "Think and Grow Rich"))

            add(lesson(order++, "GOALS",
                "Kumbuka Kwa Nini", "Remember Your Why",
                "Hill anasema: wakati ni vigumu, kumbuka kwa nini ulianza. " +
                "Kwa nini una biashara hii? Familia yako? Ndoto yako? " +
                "Weka picha ya familia yako mahali pa kazi. " +
                "Kila unapofungua Msaidizi, kumbuka: hii ni kwa ajili yao. " +
                "Faida yako leo ni mbegu ya kesho yao!",
                "Think and Grow Rich"))

            add(lesson(order++, "GOALS",
                "Shukrani na Malengo", "Gratitude and Goals",
                "Kabla ya kulala, fikiri mambo matatu uliyofanikiwa leo. " +
                "Hata kama ni mauzo mawili tu. Shukrani inakuweka na nguvu. " +
                "Kisha, fikiri kesho: nataka kufanya nini? " +
                "Shukrani ya jana + mpango wa leo = mafanikio ya kesho. " +
                "Msaidizi inakumbuka mauzo yako — angalia kila siku!",
                "Think and Grow Rich"))

            add(lesson(order++, "GOALS",
                "Kuweka Lengo la Akiba", "Setting Savings Goals",
                "George Clason anasema: weka akiba kwanza, kisha tumia. " +
                "Lengo la akiba: 10% ya mauzo yako ya siku. " +
                "KSh 1,000 ya mauzo? Weka KSh 100 kando. " +
                "Wiki moja = KSh 700. Mwezi = KSh 3,000. " +
                "Anza leo — hata KSh 50 inatosha. Muhimu ni kuanza!",
                "The Richest Man in Babylon"))

            // ═══ FINANCIAL LITERACY (10 lessons) ═══
            add(lesson(order++, "FINANCIAL_LITERACY",
                "Faida vs Mauzo", "Profit vs Revenue",
                "George Clason anasema: tafauti kati ya mtu tajiri na masikini ni maarifa. " +
                "Mauzo ni pesa unazopata. Faida ni pesa inayobaki. " +
                "KSh 10,000 za mauzo, KSh 8,000 za manunuzi = KSh 2,000 za faida. " +
                "Msaidizi inakuhesabia hii kila siku. " +
                "Sema: 'Nataka faida, si mauzo tu!'",
                "The Richest Man in Babylon"))

            add(lesson(order++, "FINANCIAL_LITERACY",
                "Gharama za Siku", "Daily Costs",
                "Kila siku una gharama: kodi, usafiri, chakula. " +
                "Hizi ni 'gharama za biashara'. Zinapunguza faida yako. " +
                "Mfano: mauzo KSh 5,000, gharama KSh 3,000 = faida KSh 2,000. " +
                "Rekodi gharama zako zote kwenye Msaidizi. " +
                "Ukijua gharama zako, unaweza kupunguza na kuongeza faida!",
                "The Richest Man in Babylon"))

            add(lesson(order++, "FINANCIAL_LITERACY",
                "Bei ya Soko", "Market Price",
                "George Clason anasema: bei bora ni ile inayouza. " +
                "Usiweke bei ya juu sana — wateja wataenda pengine. " +
                "Usiweke bei ya chini sana — utapoteza faida. " +
                "Angalia bei za jirani. Angalia bei za soko. " +
                "Weka bei ya kati — faida + ushindani. " +
                "Msaidizi inaweza kukusaidia kufuatilia bei zako!",
                "The Richest Man in Babylon"))

            add(lesson(order++, "FINANCIAL_LITERACY",
                "Akiba ya Dharura", "Emergency Savings",
                "Clason anasema: weka akiba ya dharura kwanza. " +
                "Lengo: akiba ya siku 30 za gharama zako. " +
                "Kama gharama zako ni KSh 500 kwa siku, weka KSh 15,000. " +
                "Hii itakusaidia wakati wa dharura — ugonjwa, msiba, etc. " +
                "Anza na KSh 100 kwa wiki. Baada ya mwaka, utakuwa na KSh 5,200!",
                "The Richest Man in Babylon"))

            add(lesson(order++, "FINANCIAL_LITERACY",
                "Denzi na Biashara", "Debt and Business",
                "Clason anasema: denzi ni adui ya utajiri. " +
                "Kama una denzi, lipa kwanza. Kabla ya kununua bidhaa mpya, " +
                "lipa denzi la zamani. " +
                "Denzi linakula faida yako. KSh 1,000 za riba kwa mwezi = KSh 12,000 kwa mwaka. " +
                "Lipa denzi, kisha weka akiba. Hii ndio njia ya utajiri!",
                "The Richest Man in Babylon"))

            add(lesson(order++, "FINANCIAL_LITERACY",
                "Pesa Inayofanya Kazi", "Money That Works",
                "Clason anasema: pesa inapaswa kufanya kazi, si kulala tu. " +
                "Akiba yako iweke mahali inapata faida. " +
                "Mali ya kuuza, vifaa vya biashara, elimu — hizi ni njia za pesa kufanya kazi. " +
                "Usiweke pesa chini ya godoro. Weka kwenye biashara yako — " +
                "ongeza bidhaa, ongeza vifaa. Pesa ifanye kazi!",
                "The Richest Man in Babylon"))

            add(lesson(order++, "FINANCIAL_LITERACY",
                "Mzunguko wa Pesa", "Cash Flow",
                "Pesa inaingia (mauzo) na inatoka (manunuzi, gharama). " +
                "Mzunguko mzuri: zaidi ya inayoingia kuliko inayotoka. " +
                "Angalia Msaidizi: mauzo yako dhidi ya manunuzi. " +
                "Kama manunuzi ni zaidi, kuna tatizo. " +
                "Suluhisho: ongeza bei, punguza gharama, au ongeza wauzaji. " +
                "Mzunguko mzuri = biashara hai!",
                "The Richest Man in Babylon"))

            add(lesson(order++, "FINANCIAL_LITERACY",
                "Kodi na Biashara", "Tax and Business",
                "Kama biashara yako inakua, fikiria usajili. " +
                "Kodi si adui — ni sehemu ya biashara rasmi. " +
                "Biashara rasmi = mikopo, usaidizi wa serikali, heshima. " +
                "Anza na kufuatilia mauzo yako kwa usahihi — Msaidizi inakusaidia. " +
                "Baadaye, utakuwa tayari kwa usajili na kodi. Hatua kwa hatua!",
                "Financial Literacy"))

            add(lesson(order++, "FINANCIAL_LITERACY",
                "Bei ya Juu, Bei ya Chini", "Pricing Strategy",
                "George Clason anasema: bei bora ni ile inayouza na kukupa faida. " +
                "Njia ya bei: " +
                "1. Gharama + Faida = Bei ya kuuza. " +
                "2. Angalia bei za soko. " +
                "3. Wateja wanalipia nini? " +
                "Jaribu bei tofauti. Angalia matokeo kwenye Msaidizi. " +
                "Bei nzuri = mauzo mengi + faida nzuri!",
                "The Richest Man in Babylon"))

            add(lesson(order++, "FINANCIAL_LITERACY",
                "Kufuatilia Kila Shilingi", "Track Every Shilling",
                "Clason anasema: mtu anayejua pesa yake anakwendapi ni tajiri. " +
                "Rekodi kila mauzo. Rekodi kila manunuzi. Rekodi kila gharama. " +
                "Msaidizi inakusaidia kufuatilia kila shilingi. " +
                "Kila siku, angalia salio lako. " +
                "Ukijua pesa yako anakwendapi, unaweza kuiweka vizuri!",
                "The Richest Man in Babylon"))

            // ═══ MINDSET (10 lessons) ═══
            add(lesson(order++, "MINDSET",
                "Fikiri Kwa Ukubwa", "Think Big",
                "David Schwartz anasema: ukubwa wa mafanikio yako ni ukubwa wa mawazo yako. " +
                "Usifikiri 'Duka langu ni dogo'. Fikiri 'Duka langu linakua'. " +
                "Mama mboga anayefikiri atakuwa na msururu wa maduka — atafanikiwa. " +
                "Mama mboga anayefikiri duka lake ni dogo — atabaki hivyo. " +
                "Fikiri kwa ukubwa. Biashara yako inaweza kukua!",
                "The Magic of Thinking Big"))

            add(lesson(order++, "MINDSET",
                "Ujasiri wa Kuuza", "Confidence in Selling",
                "Schwartz anasema: ujasiri unaambukiza. " +
                "Ukiamini bidhaa yako ni nzuri, mteja ataamini. " +
                "Jifunze kuuza kwa ujasuri. Sema bei yako kwa sauti thabiti. " +
                "Usiogope kukataliwa — hata wauzaji bora wanakataliwa. " +
                "Kila 'hapana' ni hatua ya karibu na 'ndio'. Endelea!",
                "The Magic of Thinking Big"))

            add(lesson(order++, "MINDSET",
                "Kushindwa ni Fundisho", "Failure is a Lesson",
                "Schwartz anasema: watu waliokuwa na mafanikio makubwa walishindwa mara nyingi. " +
                "Edison alijaribu mara 1000 kabla ya balbu. " +
                "Leo mauzo yako ni sifuri? Sio kushindwa — ni fundisho. " +
                "Kesho jaribu tena. Kila jaribio linakufundisha kitu. " +
                "Msaidizi inakumbuka matokeo yako — angalia mwelekeo, si siku moja!",
                "The Magic of Thinking Big"))

            add(lesson(order++, "MINDSET",
                "Watu ni Muhimu", "People Matter",
                "Schwartz anasema: mafanikio ya biashara ni mafanikio ya watu. " +
                "Wateja wako ni watu — watendee vizuri. " +
                "Jirani yako wa biashara ni rafiki — usiwe adui. " +
                "Msaidizi wako wa kazi ni mshirika — mwamini. " +
                "Uhusiano mzuri = biashara nzuri. Tendewa watu vizuri!",
                "The Magic of Thinking Big"))

            add(lesson(order++, "MINDSET",
                "Usiogope Mabadiliko", "Embrace Change",
                "Schwartz anasema: wale wanaoogopa mabadiliko wanabaki nyuma. " +
                "Soko linabadilika. Bei zinabadilika. Wateja wanabadilika. " +
                "Badilika nao! Jaribu bidhaa mpya. Jaribu bei mpya. " +
                "Jaribu njia mpya za kuuza. Mabadiliko ni fursa, si tishio. " +
                "Msaidizi inakusaidia kuona mabadiliko — tumia data yako!",
                "The Magic of Thinking Big"))

            add(lesson(order++, "MINDSET",
                "Kuwa na Nidhamu", "Self-Discipline",
                "Schwartz anasema: nidhamu ni nguvu ya mafanikio. " +
                "Fungua duka wakati. Funga duka wakati. " +
                "Rekodi mauzo wakati. Angalia salio wakati. " +
                "Nidhamu ya kila siku = mafanikio ya kila mwaka. " +
                "Msaidizi inakukumbusha — fanya sehemu yako. Nidhamu!",
                "The Magic of Thinking Big"))

            add(lesson(order++, "MINDSET",
                "Fikiri Kama Tajiri", "Think Like the Wealthy",
                "Schwartz anasema: maskini anafikiri 'Nitapata wapi?' Tajiri anafikiri 'Nitafanyaje?' " +
                "Badilisha mawazo yako. Badilisha maswali yako. " +
                "Badala ya 'Bei gani?' — 'Faida gani?' " +
                "Badala ya 'Nitanunua lini?' — 'Nitaongezaje mauzo?' " +
                "Mawazo ya tajiri = biashara ya tajiri!",
                "The Magic of Thinking Big"))

            add(lesson(order++, "MINDSET",
                "Umuhimu wa Kujiamini", "The Power of Self-Belief",
                "Schwartz anasema: jiamini, na wengine watakuamini. " +
                "Wewe ni mfanyabiashara. Wewe ni kiongozi. Wewe ni shujaa. " +
                "Kila siku unarekodi mauzo, unajua faida yako, unapanga kesho. " +
                "Hii si biashara ndogo — hii ndio biashara yako! " +
                "Jiamini. Biashara yako inaweza kukua!",
                "The Magic of Thinking Big"))

            add(lesson(order++, "MINDSET",
                "Kupanga ni Kufanikiwa", "Planning is Succeeding",
                "Schwartz anasema: kila dakika ya kupanga inaokoa dakika kumi za kazi. " +
                "Kila Jumapili, panga wiki yako. " +
                "Ninataka kuuza nini? Ninataka kupata faida gani? " +
                "Kila asubuhi, panga siku yako. " +
                "Mpango mzuri = biashara nzuri. Tumia Msaidizi kupanga!",
                "The Magic of Thinking Big"))

            add(lesson(order++, "MINDSET",
                "Sio Kila Kitu Kwa Pesa", "It's Not All About Money",
                "Schwartz anasema: pesa ni zana, si lengo. " +
                "Lengo ni maisha mazuri — kwa wewe na familia yako. " +
                "Biashara yako inakupa uhuru. Uhuru wa kuchagua. " +
                "Uhuru wa kulea watoto wako. Uhuru wa kuwa na afya. " +
                "Pesa ni njia, si mwisho. Tumia vizuri!",
                "The Magic of Thinking Big"))

            // ═══ GIVING (10 lessons) ═══
            add(lesson(order++, "GIVING",
                "Njia ya Kutoa", "The Way of Giving",
                "Clason anasema: yule anayetoa haishuki. " +
                "Kutoa si kupoteza — ni kupanda mbegu. " +
                "Mbegu ikipandwa, inakua na kutoa matunda mengi. " +
                "Toa kwa moyo safi. Toa kwa furaha. " +
                "Mungu atakubariki mara kumi!",
                "The Richest Man in Babylon"))

            add(lesson(order++, "GIVING",
                "Zaka ya Kumi", "The Tithe",
                "Biblia inasema: leteni zaka yangu nyumbani. " +
                "10% ya mapato yako ni zaka. " +
                "KSh 1,000 za mauzo? Weka KSh 100 kwa zaka. " +
                "Hii si gharama — ni uwekezaji wa kiroho. " +
                "Msaidizi inaweza kukusaidia kufuatilia zaka yako. Anza leo!",
                "The Richest Man in Babylon"))

            add(lesson(order++, "GIVING",
                "Kutoa kwa Maskini", "Giving to the Needy",
                "Clason anasema: yule anayesaidia maskini anamsaidia Mungu. " +
                "Sio lazima uwe tajiri kutoa. " +
                "Hata KSh 20 kwa mtu mwenye njaa inatosha. " +
                "Kutoa kwa maskini kunakuweka na moyo mzuri. " +
                "Moyo mzuri = biashara nzuri. Toa!",
                "The Richest Man in Babylon"))

            add(lesson(order++, "GIVING",
                "Muda wako ni Zawadi", "Your Time is a Gift",
                "Kutoa sio pesa tu — muda wako ni zawadi. " +
                "Fundisha jirani yako kurekodi mauzo. " +
                "Msaidie mtu mpya kuanza biashara. " +
                "Muda wako wa kufundisha ni kutoa. " +
                "Ukifundisha mtu mmoja, unabadilisha maisha yake!",
                "The Richest Man in Babylon"))

            add(lesson(order++, "GIVING",
                "Kutoa kwa Furaha", "Joyful Giving",
                "Clason anasema: toa kwa furaha, si kwa huzuni. " +
                "Yule anayetoa kwa huzuni hana faida ya kutoa. " +
                "Yule anayetoa kwa furaha anapokea mara dufu. " +
                "Toa kwa moyo wako wote. Furaha ya kutoa ni zawadi yenyewe!",
                "The Richest Man in Babylon"))

            add(lesson(order++, "GIVING",
                "Kuongeza Kutoa", "Increasing Your Giving",
                "Clason anasema: kama unataka kutoa zaidi, fanya biashara zaidi. " +
                "Mauzo zaidi = faida zaidi = kutoa zaidi. " +
                "Hii ndio mzunguko mzuri. Biashara inakupa, wewe unatoa. " +
                "Ongeza mauzo yako. Ongeza kutoa kwako. Mungu atakubariki!",
                "The Richest Man in Babylon"))

            add(lesson(order++, "GIVING",
                "Kutoa kwa Jamii", "Community Giving",
                "Clason anasema: mtu peke yake haifanikiwi. " +
                "Jamii yako ndio nguvu yako. " +
                "Toa kwa shule, kanisa, msikiti, hospitali. " +
                "Jamii nzuri = mazingira mazuri = biashara nzuri. " +
                "Uwekezaji kwenye jamii ni uwekezaji kwako!",
                "The Richest Man in Babylon"))

            add(lesson(order++, "GIVING",
                "Kutoa ni Njia ya Utajiri", "Giving is the Path to Wealth",
                "Clason anasema: utajiri wa kweli ni utajiro wa moyo. " +
                "Yule anayetoa kwa moyo safi haishuki kamwe. " +
                "Pesa inakwenda na inarudi. Muhimu ni moyo wako. " +
                "Toa kwa upendo. Toa kwa imani. Toa kwa furaha. " +
                "Utajiri wa kweli ni kutoa!",
                "The Richest Man in Babylon"))

            add(lesson(order++, "GIVING",
                "Mzunguko wa Baraka", "The Cycle of Blessings",
                "Napoleon Hill anasema: kutoa kunafungua milango. " +
                "Ukitoa, Mungu anakufungulia milango mpya. " +
                "Wateja wapya, fursa mpya, baraka mpya. " +
                "Hii ndio mzunguko wa baraka. Anza leo. Toa — na uone!",
                "Think and Grow Rich"))

            add(lesson(order++, "GIVING",
                "Kumbuka Daima", "Always Remember",
                "George Clason anasema: kumbuka kanuni hizi tano: " +
                "1. Pesa inakuja kwa kazi. " +
                "2. Pesa inakaa kwa nidhamu. " +
                "3. Pesa inakua kwa uwekezaji. " +
                "4. Pesa inahifadhiwa na maarifa. " +
                "5. Pesa inabarikiwa na kutoa. " +
                "Tumia zote tano. Mafanikio yako yamehakikishwa!",
                "The Richest Man in Babylon"))
        }
    }

    private fun lesson(
        order: Int, category: String,
        titleSw: String, titleEn: String,
        contentSw: String, sourceBook: String
    ) = MindsetLessonEntity(
        lessonId = "lesson_${order.toString().padStart(3, '0')}",
        category = category,
        titleSw = titleSw,
        titleEn = titleEn,
        contentSw = contentSw,
        contentEn = contentSw, // Use Swahili as English fallback for now
        sourceBook = sourceBook,
        durationSeconds = 150,
        sortOrder = order
    )
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

data class VoiceLesson(
    val lessonId: String,
    val title: String,
    val content: String,
    val category: String,
    val sourceBook: String,
    val durationSeconds: Int,
    val language: String
)

data class AcademyProgress(
    val completedLessons: Int,
    val totalLessons: Int,
    val progressPercent: Int,
    val categoryProgress: Map<String, CategoryProgress>
)

data class CategoryProgress(
    val category: String,
    val nameSw: String,
    val completed: Int,
    val total: Int
)
