package com.msaidizi.app.voice

import android.content.Context
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whisper tokenizer for decoding output token IDs to text.
 * Loads vocabulary from a JSON asset file (whisper_vocab.json).
 *
 * The Whisper model outputs token IDs which this class maps back to text.
 * Uses SentencePiece-style tokenization where the underscore prefix represents a space.
 *
 * If the vocab file is missing, a minimal fallback is used.
 * For production, ship the real vocab in assets/.
 */
@Singleton
class WhisperTokenizer @Inject constructor() {

    private var vocab: Map<Int, String> = emptyMap()
    private var reverseVocab: Map<String, Int> = emptyMap()
    private var isLoaded = false

    /**
     * Load tokenizer vocabulary from assets.
     * Expects: assets/whisper_vocab.json mapping token IDs to strings.
     */
    fun load(context: Context) {
        if (isLoaded) return
        try {
            val json = context.assets.open("whisper_vocab.json")
                .bufferedReader().readText()
            val map = mutableMapOf<Int, String>()
            // Parse simple key-value JSON mapping: {"0": "token", "1": "token", ...}
            val pattern = Regex(""""(\d+)":\s*"([^"]*?)"""")
            for (match in pattern.findAll(json)) {
                val id = match.groupValues[1].toIntOrNull() ?: continue
                map[id] = match.groupValues[2]
            }
            if (map.isNotEmpty()) {
                vocab = map
                reverseVocab = map.entries.associate { (k, v) -> v to k }
                isLoaded = true
                Timber.d("WhisperTokenizer: Loaded %d tokens from asset", vocab.size)
            } else {
                Timber.w("WhisperTokenizer: Vocab file parsed but empty, using fallback")
                loadFallbackVocab()
            }
        } catch (e: Exception) {
            Timber.w(e, "WhisperTokenizer: Failed to load whisper_vocab.json, using fallback")
            loadFallbackVocab()
        }
    }

    /**
     * Decode token IDs to text string.
     * Joins tokens and replaces SentencePiece space markers with actual spaces.
     */
    fun decode(tokenIds: LongArray): String {
        if (!isLoaded) return ""
        return tokenIds
            .mapNotNull { vocab[it.toInt()] }
            .joinToString("")
            .replace(SPIECE_SPACE, " ")
            .trim()
    }

    /**
     * Encode text string to token IDs.
     * Returns empty array if tokenizer is not loaded or text is empty.
     */
    fun encode(text: String): LongArray {
        if (!isLoaded || text.isBlank()) return longArrayOf()
        // Simple word-level encoding using reverse vocab
        val tokens = mutableListOf<Long>()
        val words = text.split(" ")
        for ((i, word) in words.withIndex()) {
            val prefixed = if (i == 0) word else "$SPIECE_SPACE$word"
            val id = reverseVocab[prefixed] ?: reverseVocab[word]
            if (id != null) {
                tokens.add(id.toLong())
            }
        }
        return tokens.toLongArray()
    }

    /**
     * Check if the tokenizer vocabulary has been loaded.
     */
    fun isReady(): Boolean = isLoaded

    /**
     * Get the vocabulary size.
     */
    fun vocabSize(): Int = vocab.size

    /**
     * Unload vocabulary to free memory.
     */
    fun unload() {
        vocab = emptyMap()
        reverseVocab = emptyMap()
        isLoaded = false
        Timber.d("WhisperTokenizer: Unloaded")
    }

    /**
     * Load a minimal fallback vocabulary for common tokens.
     * Used when whisper_vocab.json is not available in assets.
     * Covers basic punctuation and common Swahili/English subwords.
     */
    private fun loadFallbackVocab() {
        val map = mutableMapOf<Int, String>()
        // Special tokens
        map[50256] = END_OF_TEXT
        map[50257] = START_OF_TRANSCRIPT
        map[50258] = NO_TIMESTAMPS
        // Common punctuation
        map[0] = "!"
        map[1] = "\""
        map[2] = "#"
        map[3] = "$"
        map[4] = "%"
        map[5] = "&"
        map[6] = "'"
        map[7] = "("
        map[8] = ")"
        map[9] = "*"
        map[10] = "+"
        map[11] = ","
        map[12] = "-"
        map[13] = "."
        map[14] = "/"
        map[15] = "0"
        // Digits 1-9
        for (i in 1..9) map[15 + i] = i.toString()
        // Common Swahili subwords (SentencePiece style)
        map[220] = " "
        map[262] = "${SPIECE_SPACE}na"
        map[264] = "${SPIECE_SPACE}ya"
        map[268] = "${SPIECE_SPACE}wa"
        map[270] = "${SPIECE_SPACE}ni"
        map[272] = "${SPIECE_SPACE}la"
        map[274] = "${SPIECE_SPACE}ka"
        map[276] = "${SPIECE_SPACE}za"
        map[278] = "${SPIECE_SPACE}ma"
        map[280] = "${SPIECE_SPACE}ku"

        vocab = map
        reverseVocab = map.entries.associate { (k, v) -> v to k }
        isLoaded = true
        Timber.d("WhisperTokenizer: Loaded fallback vocab with %d tokens", vocab.size)
    }

    companion object {
        /** SentencePiece underscore character representing a space prefix */
        const val SPIECE_SPACE = "\u2581"

        /** Special tokens */
        const val END_OF_TEXT = "</eot>"
        const val START_OF_TRANSCRIPT = "<|startoftranscript|>"
        const val NO_TIMESTAMPS = "<|notimestamps|>"
    }
}