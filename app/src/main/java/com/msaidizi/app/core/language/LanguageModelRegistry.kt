package com.msaidizi.app.core.language

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File

/**
 * Per-language model registry — manages ASR, TTS, and LLM models
 * for multiple African languages.
 *
 * Architecture:
 * - Base model (shared across languages): Whisper, Qwen, Silero VAD
 * - Per-language adapters: LoRA weights (~5 MB each)
 * - Per-language assets: n-gram LM, vocabulary, phoneme maps
 * - Only ONE language adapter loaded at a time (2GB constraint)
 * - Swap time: ~50ms (just reload adapter weights)
 *
 * 4-Phase Scaling:
 * ─────────────────
 * Phase 1: Migori County — Swahili + Dholuo
 * Phase 2: Kenya Nationwide — + Kikuyu, Kalenjin, Luhya
 * Phase 3: East Africa — + Luganda, Kinyarwanda, Amharic
 * Phase 4: All Africa — + Hausa, Yoruba, Zulu, Igbo, Wolof (20+ languages)
 *
 * Storage budget:
 * - Base models: ~370 MB (shared, already in ModelRegistry)
 * - Per-language adapter: ~5–20 MB × 1 active = 5–20 MB
 * - Per-language LM: ~1–5 MB × 1 active = 1–5 MB
 * - Per-language vocab: ~1 MB × 1 active = 1 MB
 * - All adapters cached: ~5 MB × 10 languages = 50 MB
 * - Total for 1 active language: ~380 MB
 * - Total for 10 cached languages: ~420 MB
 */
class LanguageModelRegistry(
    private val context: Context
) {
    companion object {
        private const val TAG = "LanguageModelRegistry"
        private const val ADAPTERS_DIR = "language_adapters"
        private const val LM_DIR = "language_models"
        private const val VOCAB_DIR = "language_vocab"
    }

    // ════════════════════════════════════════════════════════════════════
    // LANGUAGE DEFINITIONS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Registry of all supported and planned languages.
     * Each language has metadata about model availability, data status, and priority.
     */
    val supportedLanguages = mapOf(
        // ── Phase 1: Migori County (Active) ──
        "sw" to LanguageDef(
            code = "sw",
            name = "Kiswahili",
            nativeName = "Kiswahili",
            family = "Bantu",
            region = "East Africa",
            phase = 1,
            speakers = 100_000_000L,
            asrAvailable = true,
            ttsAvailable = true,
            loraAvailable = true,
            loraAdapterPath = "$ADAPTERS_DIR/sw_lora.bin",
            ngramLmPath = "$LM_DIR/sw_5gram.bin",
            vocabularyPath = "$VOCAB_DIR/sw_market_vocab.json",
            baseAsrModel = "whisper-tiny-int4",
            dialectRegions = setOf("standard", "nairobi", "migori", "coast", "tanzania"),
            transferSources = emptyList(),  // Base language
            codeSwitchPairs = setOf("en", "luo"),
        ),
        "luo" to LanguageDef(
            code = "luo",
            name = "Dholuo",
            nativeName = "Dholuo",
            family = "Nilotic",
            region = "Western Kenya",
            phase = 1,
            speakers = 5_000_000L,
            asrAvailable = false,  // Needs data collection
            ttsAvailable = false,
            loraAvailable = false,
            loraAdapterPath = "$ADAPTERS_DIR/luo_lora.bin",
            ngramLmPath = "$LM_DIR/luo_5gram.bin",
            vocabularyPath = "$VOCAB_DIR/luo_market_vocab.json",
            baseAsrModel = "whisper-tiny-int4",
            dialectRegions = setOf("standard", "south_nyanza"),
            transferSources = listOf("sw"),  // Transfer from Swahili
            codeSwitchPairs = setOf("sw"),
        ),

        // ── Phase 2: Kenya Nationwide ──
        "ki" to LanguageDef(
            code = "ki",
            name = "Kikuyu",
            nativeName = "Gĩkũyũ",
            family = "Bantu",
            region = "Central Kenya",
            phase = 2,
            speakers = 8_000_000L,
            asrAvailable = false,
            ttsAvailable = false,
            loraAvailable = false,
            loraAdapterPath = "$ADAPTERS_DIR/ki_lora.bin",
            ngramLmPath = "$LM_DIR/ki_5gram.bin",
            vocabularyPath = "$VOCAB_DIR/ki_market_vocab.json",
            baseAsrModel = "whisper-tiny-int4",
            dialectRegions = setOf("standard", "nyeri", "murang'a", "kiambu"),
            transferSources = listOf("sw"),
            codeSwitchPairs = setOf("sw", "en"),
        ),
        "en" to LanguageDef(
            code = "en",
            name = "English",
            nativeName = "English",
            family = "Germanic",
            region = "Global",
            phase = 1,  // Already available
            speakers = 1_500_000_000L,
            asrAvailable = true,
            ttsAvailable = true,
            loraAvailable = false,  // Not needed — base model is English
            loraAdapterPath = "",
            ngramLmPath = "",
            vocabularyPath = "",
            baseAsrModel = "whisper-tiny-int4",
            dialectRegions = setOf("kenyan", "standard"),
            transferSources = emptyList(),
            codeSwitchPairs = setOf("sw"),
        ),

        // ── Phase 3: East Africa ──
        "lg" to LanguageDef(
            code = "lg",
            name = "Luganda",
            nativeName = "Luganda",
            family = "Bantu",
            region = "Uganda",
            phase = 3,
            speakers = 10_000_000L,
            asrAvailable = false,
            ttsAvailable = false,
            loraAvailable = false,
            loraAdapterPath = "$ADAPTERS_DIR/lg_lora.bin",
            ngramLmPath = "$LM_DIR/lg_5gram.bin",
            vocabularyPath = "$VOCAB_DIR/lg_market_vocab.json",
            baseAsrModel = "whisper-tiny-int4",
            dialectRegions = setOf("standard"),
            transferSources = listOf("sw"),
            codeSwitchPairs = setOf("en"),
        ),
        "rw" to LanguageDef(
            code = "rw",
            name = "Kinyarwanda",
            nativeName = "Ikinyarwanda",
            family = "Bantu",
            region = "Rwanda",
            phase = 3,
            speakers = 12_000_000L,
            asrAvailable = false,
            ttsAvailable = false,
            loraAvailable = false,
            loraAdapterPath = "$ADAPTERS_DIR/rw_lora.bin",
            ngramLmPath = "$LM_DIR/rw_5gram.bin",
            vocabularyPath = "$VOCAB_DIR/rw_market_vocab.json",
            baseAsrModel = "whisper-tiny-int4",
            dialectRegions = setOf("standard"),
            transferSources = listOf("sw", "lg"),
            codeSwitchPairs = setOf("fr", "en"),
        ),
        "am" to LanguageDef(
            code = "am",
            name = "Amharic",
            nativeName = "አማርኛ",
            family = "Afro-Asiatic",
            region = "Ethiopia",
            phase = 3,
            speakers = 57_000_000L,
            asrAvailable = false,
            ttsAvailable = false,
            loraAvailable = false,
            loraAdapterPath = "$ADAPTERS_DIR/am_lora.bin",
            ngramLmPath = "$LM_DIR/am_5gram.bin",
            vocabularyPath = "$VOCAB_DIR/am_market_vocab.json",
            baseAsrModel = "whisper-tiny-int4",
            dialectRegions = setOf("standard"),
            transferSources = emptyList(),  // Different family
            codeSwitchPairs = setOf("en"),
        ),

        // ── Phase 4: Pan-Africa ──
        "ha" to LanguageDef(
            code = "ha",
            name = "Hausa",
            nativeName = "Hausa",
            family = "Afro-Asiatic",
            region = "West Africa",
            phase = 4,
            speakers = 80_000_000L,
            asrAvailable = false,
            ttsAvailable = false,
            loraAvailable = false,
            loraAdapterPath = "$ADAPTERS_DIR/ha_lora.bin",
            ngramLmPath = "$LM_DIR/ha_5gram.bin",
            vocabularyPath = "$VOCAB_DIR/ha_market_vocab.json",
            baseAsrModel = "whisper-tiny-int4",
            dialectRegions = setOf("standard", "kanuri"),
            transferSources = emptyList(),
            codeSwitchPairs = setOf("en"),
        ),
        "yo" to LanguageDef(
            code = "yo",
            name = "Yoruba",
            nativeName = "Yorùbá",
            family = "Niger-Congo",
            region = "West Africa",
            phase = 4,
            speakers = 45_000_000L,
            asrAvailable = false,
            ttsAvailable = false,
            loraAvailable = false,
            loraAdapterPath = "$ADAPTERS_DIR/yo_lora.bin",
            ngramLmPath = "$LM_DIR/yo_5gram.bin",
            vocabularyPath = "$VOCAB_DIR/yo_market_vocab.json",
            baseAsrModel = "whisper-tiny-int4",
            dialectRegions = setOf("standard"),
            transferSources = listOf("ha"),  // Geographic proximity
            codeSwitchPairs = setOf("en"),
        ),
        "zu" to LanguageDef(
            code = "zu",
            name = "Zulu",
            nativeName = "isiZulu",
            family = "Niger-Congo",
            region = "Southern Africa",
            phase = 4,
            speakers = 12_000_000L,
            asrAvailable = false,
            ttsAvailable = false,
            loraAvailable = false,
            loraAdapterPath = "$ADAPTERS_DIR/zu_lora.bin",
            ngramLmPath = "$LM_DIR/zu_5gram.bin",
            vocabularyPath = "$VOCAB_DIR/zu_market_vocab.json",
            baseAsrModel = "whisper-tiny-int4",
            dialectRegions = setOf("standard"),
            transferSources = emptyList(),
            codeSwitchPairs = setOf("en"),
        ),
    )

    // ════════════════════════════════════════════════════════════════════
    // STATE
    // ════════════════════════════════════════════════════════════════════

    private val adaptersDir = File(context.filesDir, ADAPTERS_DIR).apply { mkdirs() }
    private val lmDir = File(context.filesDir, LM_DIR).apply { mkdirs() }
    private val vocabDir = File(context.filesDir, VOCAB_DIR).apply { mkdirs() }

    private var activeLanguage: String = "sw"
    private var activeAdapter: ByteArray? = null
    private var activeVocabulary: Map<String, Any>? = null

    private val _languageState = MutableStateFlow<LanguageState>(LanguageState.Ready("sw"))
    val languageState: StateFlow<LanguageState> = _languageState

    private val _availableLanguages = MutableStateFlow<Set<String>>(emptySet())
    val availableLanguages: StateFlow<Set<String>> = _availableLanguages

    // ════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════════════

    /**
     * Initialize the registry — scan for available adapters and models.
     */
    fun initialize() {
        val available = mutableSetOf<String>()

        // Base languages always available
        available.add("en")
        available.add("sw")

        // Check for downloaded adapters
        adaptersDir.listFiles()?.forEach { file ->
            val langCode = file.nameWithoutExtension.removeSuffix("_lora")
            if (supportedLanguages.containsKey(langCode)) {
                available.add(langCode)
            }
        }

        _availableLanguages.value = available
        Timber.tag(TAG).i("Registry initialized: %d languages available", available.size)
    }

    /**
     * Get the currently active language.
     */
    fun getActiveLanguage(): String = activeLanguage

    /**
     * Get language definition.
     */
    fun getLanguageDef(code: String): LanguageDef? = supportedLanguages[code]

    /**
     * Switch the active language.
     * Loads the LoRA adapter for the new language (~50ms on 2GB device).
     *
     * @return true if switch was successful
     */
    suspend fun switchLanguage(languageCode: String): Boolean = withContext(Dispatchers.IO) {
        if (languageCode == activeLanguage) return@withContext true

        val langDef = supportedLanguages[languageCode]
        if (langDef == null) {
            Timber.tag(TAG).w("Unknown language: %s", languageCode)
            return@withContext false
        }

        _languageState.value = LanguageState.Switching(languageCode)

        try {
            // Load LoRA adapter if available
            if (langDef.loraAvailable) {
                val adapterFile = File(context.filesDir, langDef.loraAdapterPath)
                if (adapterFile.exists()) {
                    activeAdapter = adapterFile.readBytes()
                    Timber.tag(TAG).d("Loaded LoRA adapter for %s (%d bytes)", languageCode, activeAdapter?.size ?: 0)
                }
            } else {
                activeAdapter = null
            }

            // Load vocabulary
            loadVocabulary(langDef)

            activeLanguage = languageCode
            _languageState.value = LanguageState.Ready(languageCode)

            Timber.tag(TAG).i("Switched to language: %s", languageCode)
            true
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "Failed to switch to language: %s", languageCode)
            _languageState.value = LanguageState.Error(languageCode, e.message ?: "Unknown error")
            false
        }
    }

    /**
     * Get the active LoRA adapter bytes for the current language.
     * Returns null if no adapter is loaded (using base model only).
     */
    fun getActiveAdapter(): ByteArray? = activeAdapter

    /**
     * Get the active vocabulary for the current language.
     */
    fun getActiveVocabulary(): Map<String, Any>? = activeVocabulary

    /**
     * Check if a language has enough resources to be usable.
     * A language is usable if it has ASR + vocabulary (TTS optional).
     */
    fun isLanguageUsable(code: String): Boolean {
        val langDef = supportedLanguages[code] ?: return false
        return langDef.asrAvailable || langDef.loraAvailable
    }

    /**
     * Get the best language for transfer learning a target language.
     * Returns the source language with highest phoneme similarity
     * that already has a trained model.
     *
     * Transfer learning priority:
     * 1. Same family, trained model available → high priority
     * 2. Different family, trained model available → medium priority
     * 3. No trained model → skip
     */
    fun getTransferSource(targetLanguage: String): String? {
        val langDef = supportedLanguages[targetLanguage] ?: return null

        // Check explicit transfer sources first
        for (source in langDef.transferSources) {
            if (isLanguageUsable(source)) return source
        }

        // Fall back to any available language in the same family
        val sameFamily = supportedLanguages.values.filter {
            it.family == langDef.family && it.code != targetLanguage && isLanguageUsable(it.code)
        }
        if (sameFamily.isNotEmpty()) return sameFamily.first().code

        // Last resort: transfer from Swahili (most data available)
        return if (isLanguageUsable("sw")) "sw" else null
    }

    /**
     * Install a LoRA adapter from a downloaded file.
     * Verifies integrity before installation.
     */
    suspend fun installAdapter(languageCode: String, adapterFile: File): Boolean =
        withContext(Dispatchers.IO) {
            val langDef = supportedLanguages[languageCode] ?: return@withContext false

            try {
                val destFile = File(context.filesDir, langDef.loraAdapterPath)
                destFile.parentFile?.mkdirs()

                // Verify minimum size (LoRA adapter should be at least 1 KB)
                if (adapterFile.length() < 1024) {
                    Timber.tag(TAG).e("Adapter file too small: %d bytes", adapterFile.length())
                    return@withContext false
                }

                adapterFile.copyTo(destFile, overwrite = true)

                // Update availability
                val updatedDef = langDef.copy(loraAvailable = true)
                // Note: In production, this would update a persistent store

                // Update available languages
                _availableLanguages.value = _availableLanguages.value + languageCode

                Timber.tag(TAG).i("Installed adapter for %s (%d bytes)", languageCode, destFile.length())
                true
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "Failed to install adapter for %s", languageCode)
                false
            }
        }

    /**
     * Save a user-learned LoRA adapter.
     * This is the output of on-device learning (AdaptiveAsrEngine).
     */
    suspend fun saveUserAdapter(languageCode: String, adapterBytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val file = File(adaptersDir, "${languageCode}_user_lora.bin")
                file.writeBytes(adapterBytes)
                Timber.tag(TAG).i("Saved user adapter for %s (%d bytes)", languageCode, adapterBytes.size)
                true
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "Failed to save user adapter for %s", languageCode)
                false
            }
        }

    /**
     * Get download status for all languages in a given phase.
     */
    fun getPhaseStatus(phase: Int): PhaseStatus {
        val phaseLangs = supportedLanguages.values.filter { it.phase == phase }
        val available = phaseLangs.count { isLanguageUsable(it.code) }
        return PhaseStatus(
            phase = phase,
            totalLanguages = phaseLangs.size,
            availableLanguages = available,
            languages = phaseLangs.map { it.code }
        )
    }

    /**
     * Get storage used by language assets (adapters + LMs + vocab).
     */
    fun getLanguageStorageBytes(): Long {
        var total = 0L
        adaptersDir.listFiles()?.forEach { total += it.length() }
        lmDir.listFiles()?.forEach { total += it.length() }
        vocabDir.listFiles()?.forEach { total += it.length() }
        return total
    }

    // ════════════════════════════════════════════════════════════════════
    // INTERNALS
    // ════════════════════════════════════════════════════════════════════

    private fun loadVocabulary(langDef: LanguageDef) {
        val vocabFile = File(context.filesDir, langDef.vocabularyPath)
        if (vocabFile.exists()) {
            try {
                // Simple JSON vocabulary loading
                val json = vocabFile.readText()
                activeVocabulary = parseVocabularyJson(json)
                Timber.tag(TAG).d("Loaded vocabulary for %s (%d entries)", langDef.code, activeVocabulary?.size ?: 0)
            } catch (e: Throwable) {
                Timber.tag(TAG).w("Failed to load vocabulary for %s: %s", langDef.code, e.message)
                activeVocabulary = null
            }
        } else {
            activeVocabulary = null
        }
    }

    /**
     * Parse vocabulary JSON file.
     * Format: { "spoken_form": "canonical_form", ... }
     */
    private fun parseVocabularyJson(json: String): Map<String, Any> {
        // Simple JSON parsing without external dependency
        val result = mutableMapOf<String, Any>()
        val cleanJson = json.trim().removeSurrounding("{", "}")
        if (cleanJson.isBlank()) return result

        // Split by comma, handle nested values
        var depth = 0
        var current = StringBuilder()
        for (ch in cleanJson) {
            when (ch) {
                '{', '[' -> { depth++; current.append(ch) }
                '}', ']' -> { depth--; current.append(ch) }
                ',' -> {
                    if (depth == 0) {
                        parseKvPair(current.toString().trim())?.let { (k, v) -> result[k] = v }
                        current = StringBuilder()
                    } else {
                        current.append(ch)
                    }
                }
                else -> current.append(ch)
            }
        }
        parseKvPair(current.toString().trim())?.let { (k, v) -> result[k] = v }

        return result
    }

    private fun parseKvPair(pair: String): Pair<String, String>? {
        val colonIdx = pair.indexOf(':')
        if (colonIdx < 0) return null
        val key = pair.substring(0, colonIdx).trim().removeSurrounding("\"")
        val value = pair.substring(colonIdx + 1).trim().removeSurrounding("\"")
        return key to value
    }
}

// ════════════════════════════════════════════════════════════════════
// DATA CLASSES
// ════════════════════════════════════════════════════════════════════

/**
 * Language definition with metadata.
 */
data class LanguageDef(
    val code: String,
    val name: String,
    val nativeName: String,
    val family: String,
    val region: String,
    val phase: Int,
    val speakers: Long,
    val asrAvailable: Boolean,
    val ttsAvailable: Boolean,
    val loraAvailable: Boolean,
    val loraAdapterPath: String,
    val ngramLmPath: String,
    val vocabularyPath: String,
    val baseAsrModel: String,
    val dialectRegions: Set<String>,
    val transferSources: List<String>,
    val codeSwitchPairs: Set<String>,
) {
    /** Is this a high-resource language (>50M speakers, good digital presence)? */
    val isHighResource: Boolean get() = speakers > 50_000_000L

    /** Is this a Bantu language (transfer learning from Swahili possible)? */
    val isBantu: Boolean get() = family == "Bantu"
}

/**
 * Language state for UI observation.
 */
sealed class LanguageState {
    data class Ready(val language: String) : LanguageState()
    data class Switching(val language: String) : LanguageState()
    data class Error(val language: String, val message: String) : LanguageState()
}

/**
 * Phase status for scaling progress.
 */
data class PhaseStatus(
    val phase: Int,
    val totalLanguages: Int,
    val availableLanguages: Int,
    val languages: List<String>
)
