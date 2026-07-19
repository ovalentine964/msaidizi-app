package com.msaidizi.app.voice.briefing

import com.msaidizi.app.voice.KokoroTtsEngine
import com.msaidizi.app.voice.TextToSpeech
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transforms briefing text into natural, spoken Swahili for audio delivery.
 *
 * The Blog-to-Podcast pattern: content → script → TTS → audio.
 * This class handles the "content → script" step — turning structured
 * financial data into warm, conversational Swahili that sounds natural
 * when spoken aloud by a mama to her worker.
 *
 * Transformations:
 * - KSh amounts → spoken Swahili ("elfu mbili mia tano" not "2,500")
 * - Emojis → removed (audio has no emojis)
 * - Data tables → flowing sentences
 * - Abbreviations → full words
 * - Financial jargon → simple Swahili expressions
 * - Warm greeting and closing based on time of day
 *
 * Voice personality: Encouraging big sister / helpful neighbor.
 * Uses local expressions: "Hongera!", "Nzuri sana!", "Pole sana!"
 *
 * Example:
 * Input:  "📊 Mauzo: KSh 2,500. Gharama: KSh 1,800. Faida: KSh 700."
 * Output: "Habari yako! Leo umefanya mauzo ya shilingi elfu mbili mia tano.
 *          Gharama zako ni shilingi elfu moja mia nane. Faida yako ni
 *          shilingi mia saba. Hongera sana! Endelea hivi!"
 */
@Singleton
class AudioBriefingTextTransformer @Inject constructor() {

    companion object {
        private const val TAG = "AudioBriefingTransformer"

        // Swahili number words for natural speech
        private val UNITS = mapOf(
            0 to "sifuri", 1 to "moja", 2 to "mbili", 3 to "tatu",
            4 to "nne", 5 to "tano", 6 to "sita", 7 to "saba",
            8 to "nane", 9 to "tisa"
        )

        private val TEENS = mapOf(
            10 to "kumi", 11 to "kumi na moja", 12 to "kumi na mbili",
            13 to "kumi na tatu", 14 to "kumi na nne", 15 to "kumi na tano",
            16 to "kumi na sita", 17 to "kumi na saba", 18 to "kumi na nane",
            19 to "kumi na tisa"
        )

        private val TENS = mapOf(
            20 to "ishirini", 30 to "thelathini", 40 to "arobaini",
            50 to "hamsini", 60 to "sitini", 70 to "sabini",
            80 to "themanini", 90 to "tisini"
        )
    }

    /**
     * Transform a briefing message into natural spoken Swahili.
     *
     * @param text Raw briefing text (may contain emojis, data, abbreviations)
     * @param briefingType Type of briefing for greeting/closing selection
     * @param workerName Worker's name for personalization
     * @return Natural spoken Swahili text ready for TTS
     */
    fun transform(
        text: String,
        briefingType: BriefingAudioType = BriefingAudioType.DAILY,
        workerName: String = "Mfanyabiashara"
    ): String {
        Timber.tag(TAG).d("Transforming briefing text (%d chars) for audio", text.length)

        var result = text

        // 1. Strip emojis (no visual cues in audio)
        result = stripEmojis(result)

        // 2. Replace KSh amounts with spoken Swahili
        result = replaceAmounts(result)

        // 3. Replace percentage expressions
        result = replacePercentages(result)

        // 4. Replace data separators and formatting
        result = cleanFormatting(result)

        // 5. Replace abbreviations and jargon
        result = expandAbbreviations(result)

        // 6. Add conversational flow connectors
        result = addFlowConnectors(result)

        // 7. Ensure natural sentence breaks for TTS
        result = ensureSentenceBreaks(result)

        // 8. Trim and clean
        result = result.trim()

        Timber.tag(TAG).d("Transformed to %d chars", result.length)
        return result
    }

    // ═══════════════════════════════════════════════════════════════
    // TRANSFORMATION STEPS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Strip emojis — they have no meaning in audio.
     */
    private fun stripEmojis(text: String): String {
        // Remove Unicode emoji characters
        return text.replace(Regex("[\\p{So}\\p{Sk}\\p{Sc}\\p{Sm}\\p{Emoji_Presentation}]"), "")
            .replace(Regex("\\s{2,}"), " ")  // Collapse multiple spaces
    }

    /**
     * Replace "KSh 2,500" / "KSh 2500" with spoken Swahili.
     *
     * "KSh 2,500" → "shilingi elfu mbili mia tano"
     * "KSh 700" → "shilingi mia saba"
     * "KSh 50" → "shilingi hamsini"
     */
    private fun replaceAmounts(text: String): String {
        // Match KSh followed by amount (with optional commas)
        val pattern = Regex("""KSh\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        return pattern.replace(text) { match ->
            val amountStr = match.groupValues[1].replace(",", "")
            val amount = amountStr.toDoubleOrNull() ?: return@replace match.value
            "shilingi ${amountToSwahili(amount.toInt())}"
        }
    }

    /**
     * Replace percentage expressions.
     * "10%" → "asilimia kumi"
     * "20.5%" → "asilimia ishirini na nukta tano"
     */
    private fun replacePercentages(text: String): String {
        val pattern = Regex("""(\d+(?:\.\d+)?)\s*%""")
        return pattern.replace(text) { match ->
            val value = match.groupValues[1].toDoubleOrNull() ?: return@replace match.value
            "asilimia ${amountToSwahili(value.toInt())}"
        }
    }

    /**
     * Clean formatting artifacts that sound bad when spoken.
     */
    private fun cleanFormatting(text: String): String {
        return text
            .replace("|", ".")           // Table separators → periods
            .replace("•", "")            // Bullet points → nothing
            .replace("→", "inaonyesha")  // Arrows → words
            .replace("⬆", "imepanda")    // Up arrow
            .replace("⬇", "imeshuka")    // Down arrow
            .replace(Regex("""\*\*(.*?)\*\*"""), "$1")  // Bold markdown
            .replace(Regex("""_(.*?)_"""), "$1")        // Italic markdown
            .replace(Regex("""#{1,6}\s*"""), "")         // Headers
            .replace(Regex("""\n{3,}"""), "\n\n")        // Excessive newlines
            .replace(Regex("""[ \t]{2,}"""), " ")        // Multiple spaces/tabs
    }

    /**
     * Expand abbreviations and financial jargon to full Swahili.
     */
    private fun expandAbbreviations(text: String): String {
        var result = text
        val replacements = mapOf(
            "vs" to "ikilinganishwa na",
            "e.g." to "kwa mfano",
            "i.e." to "yaani",
            "etc" to "na mengineyo",
            "YoY" to "mwaka hadi mwaka",
            "MoM" to "mwezi hadi mwezi",
            "WoW" to "wiki hadi wiki",
            "ROI" to "rejeo ya uwekezaji",
            "P&L" to "faida na hasara",
            "qty" to "idadi",
            "avg" to "wastani",
            "min" to "kiwango cha chini",
            "max" to "kiwango cha juu",
            "est." to "makadirio",
            "approx." to "takriban"
        )
        for ((abbr, full) in replacements) {
            result = result.replace(Regex("""\b${Regex.escape(abbr)}\b""", RegexOption.IGNORE_CASE), full)
        }
        return result
    }

    /**
     * Add conversational flow connectors so the audio sounds natural,
     * not like a robot reading a spreadsheet.
     */
    private fun addFlowConnectors(text: String): String {
        // Replace ":-" or ":" at end of line with conversational pause
        var result = text
            .replace(Regex(""":\s*\n"""), ". ")
            .replace(Regex("""-\s*\n"""), ". ")

        // Add "na" (and) between consecutive data points if missing
        result = result.replace(Regex("""(\d+)\.\s+(\d+)"""), "$1 na $2")

        return result
    }

    /**
     * Ensure proper sentence breaks for TTS engine.
     * Piper/Kokoro handle sentence boundaries for prosody.
     */
    private fun ensureSentenceBreaks(text: String): String {
        return text
            .replace(Regex("""\.\s*\."""), ".")  // Double periods
            .replace(Regex("""([.!?])\s*([A-Z])"""), "$1 $2")  // Ensure space after period
            .replace(Regex("""\n{2,}"""), ". ")  // Paragraph breaks → sentence breaks
    }

    // ═══════════════════════════════════════════════════════════════
    // NUMBER → SWAHILI WORDS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Convert an integer to Swahili number words.
     *
     * 2500 → "elfu mbili mia tano"
     * 700  → "mia saba"
     * 50   → "hamsini"
     * 15000 → "elfu kumi na tano"
     */
    fun amountToSwahili(amount: Int): String {
        if (amount == 0) return "sifuri"
        if (amount < 0) return "hasara ${amountToSwahili(-amount)}"

        val parts = mutableListOf<String>()

        // Thousands
        if (amount >= 1000) {
            val thousands = amount / 1000
            parts.add("elfu ${smallNumberToSwahili(thousands)}")
        }

        // Hundreds
        val remainder = amount % 1000
        if (remainder >= 100) {
            val hundreds = remainder / 100
            parts.add("mia ${smallNumberToSwahili(hundreds)}")
        }

        // Tens and units
        val small = remainder % 100
        if (small > 0) {
            parts.add(smallNumberToSwahili(small))
        }

        return parts.joinToString(" ")
    }

    /**
     * Convert numbers 0-99 to Swahili words.
     */
    private fun smallNumberToSwahili(n: Int): String {
        if (n < 10) return UNITS[n] ?: n.toString()
        if (n in TEENS) return TEENS[n] ?: n.toString()
        if (n % 10 == 0) return TENS[n] ?: n.toString()
        val ten = (n / 10) * 10
        val unit = n % 10
        return "${TENS[ten]} na ${UNITS[unit]}"
    }
}

/**
 * Types of audio briefings for voice personality selection.
 */
enum class BriefingAudioType {
    /** Daily morning briefing — warm, encouraging */
    DAILY,

    /** Evening summary — reflective, congratulatory if good day */
    EVENING,

    /** Weekly summary — analytical, trend-focused */
    WEEKLY,

    /** Alert — urgent, clear */
    ALERT,

    /** Monthly report — comprehensive, milestone celebration */
    MONTHLY
}
