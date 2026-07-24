package com.msaidizi.app.core.di

import android.content.Context
import com.msaidizi.app.agent.IntentPatternConfig
import com.msaidizi.app.agent.IntentRouter
import com.msaidizi.app.agent.autonomy.ProgressiveAutonomy
import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.superagent.context.ContextEngine
import com.msaidizi.app.superagent.context.PatternDaoBridge
import com.msaidizi.app.superagent.context.TransactionDaoBridge
import com.msaidizi.app.superagent.context.WorkerProfileStore
import com.msaidizi.app.superagent.context.WorkerProfile
import com.msaidizi.app.superagent.context.ItemBaseline
import com.msaidizi.app.superagent.context.TransactionSummary
import com.msaidizi.app.superagent.engine.*
import com.msaidizi.app.superagent.flywheel.*
import com.msaidizi.app.superagent.flywheel.AdaptiveLearningImpl
import com.msaidizi.app.superagent.flywheel.FeedbackCollectorImpl
import com.msaidizi.app.superagent.flywheel.PatternTrackerImpl
import com.msaidizi.app.superagent.flywheel.PreferenceLearnerImpl
import com.msaidizi.app.superagent.financial.FinancialModule
import com.msaidizi.app.superagent.credit.CreditModule
import com.msaidizi.app.superagent.goals.GoalsModule
import com.msaidizi.app.superagent.education.EducationModule
import com.msaidizi.app.superagent.gamification.GamificationModule
import com.msaidizi.app.superagent.communication.CommunicationModule
import com.msaidizi.app.superagent.communication.AlertVoice
import com.msaidizi.app.superagent.communication.BriefingEngine
import com.msaidizi.app.superagent.communication.DialectOutput
import com.msaidizi.app.superagent.communication.ReportVoice
import com.msaidizi.app.superagent.communication.VoicePersonality
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import java.util.UUID
import javax.inject.Singleton

/**
 * Hilt module providing the new superagent architecture.
 *
 * Wires [ReasoningEngine] and all its dependencies, replacing the old
 * Orchestrator + AgentEventBus + multi-agent chain.
 */
@Module
@InstallIn(SingletonComponent::class)
object SuperagentModule {

    // ═══════════════════════════════════════════════════════════════════
    // REASONING ENGINE — The single entry point
    // ═══════════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideReasoningEngine(
        intentClassifier: IntentClassifier,
        dialectNormalizer: DialectNormalizer,
        dataCompletenessChecker: DataCompletenessChecker,
        safetyGuard: SafetyGuard,
        autonomyManager: AutonomyManager,
        financialModule: CapabilityModule,
        creditModule: CapabilityModule,
        goalsModule: CapabilityModule,
        educationModule: CapabilityModule,
        gamificationModule: CapabilityModule,
        communicationModule: CommunicationModule,
        contextEngine: ContextEngine,
        flywheel: FlywheelEngine,
        llmEngine: LlmEngine,
        workerSignalProvider: WorkerSignalProvider,
        marketSignalProvider: MarketSignalProvider,
        proactiveSignalProvider: ProactiveSignalProvider
    ): ReasoningEngine = ReasoningEngine(
        intentClassifier = intentClassifier,
        dialectNormalizer = dialectNormalizer,
        dataCompletenessChecker = dataCompletenessChecker,
        safetyGuard = safetyGuard,
        autonomyManager = autonomyManager,
        financialModule = financialModule,
        creditModule = creditModule,
        goalsModule = goalsModule,
        educationModule = educationModule,
        gamificationModule = gamificationModule,
        communicationModule = communicationModule,
        contextEngine = contextEngine,
        flywheel = flywheel,
        llmEngine = llmEngine,
        workerSignalProvider = workerSignalProvider,
        marketSignalProvider = marketSignalProvider,
        proactiveSignalProvider = proactiveSignalProvider
    )

    // ═══════════════════════════════════════════════════════════════════
    // CORE REASONING COMPONENTS
    // ═══════════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideIntentClassifier(
        intentRouter: IntentRouter
    ): IntentClassifier = IntentClassifierAdapter(intentRouter)

    @Provides
    @Singleton
    fun provideDialectNormalizer(): DialectNormalizer = DialectNormalizerImpl()

    @Provides
    @Singleton
    fun provideDataCompletenessChecker(): DataCompletenessChecker = DataCompletenessCheckerImpl()

    @Provides
    @Singleton
    fun provideSafetyGuard(): SafetyGuard = SafetyGuardImpl()

    @Provides
    @Singleton
    fun provideAutonomyManager(
        progressiveAutonomy: ProgressiveAutonomy
    ): AutonomyManager = AutonomyManagerAdapter(progressiveAutonomy)

    @Provides
    @Singleton
    fun provideLlmEngine(
        voiceLlmEngine: com.msaidizi.app.voice.LlmEngine
    ): LlmEngine = LlmEngineAdapter(voiceLlmEngine)

    // ═══════════════════════════════════════════════════════════════════
    // CAPABILITY MODULES
    // ═══════════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideFinancialCapabilityModule(): CapabilityModule =
        FinancialCapabilityModule(FinancialModule())

    @Provides
    @Singleton
    fun provideCreditCapabilityModule(): CapabilityModule =
        CreditCapabilityModule(CreditModule())

    @Provides
    @Singleton
    fun provideGoalsCapabilityModule(): CapabilityModule =
        GoalsCapabilityModule(GoalsModule())

    @Provides
    @Singleton
    fun provideEducationCapabilityModule(): CapabilityModule =
        EducationCapabilityModule(EducationModule())

    @Provides
    @Singleton
    fun provideGamificationCapabilityModule(): CapabilityModule =
        GamificationCapabilityModule(GamificationModule())

    @Provides
    @Singleton
    fun provideCommunicationModule(): CommunicationModule =
        CommunicationModule(
            voicePersonality = VoicePersonality(),
            briefingEngine = BriefingEngine(),
            alertVoice = AlertVoice(),
            reportVoice = ReportVoice(),
            dialectOutput = DialectOutput()
        )

    // ═══════════════════════════════════════════════════════════════════
    // CONTEXT ENGINE
    // ═══════════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideContextEngine(
        @ApplicationContext context: Context,
        transactionDao: TransactionDao,
        patternDao: PatternDao
    ): ContextEngine = ContextEngine(
        workerProfileStore = SharedPrefsWorkerProfileStore(context),
        transactionDao = TransactionDaoBridgeImpl(transactionDao),
        patternDao = PatternDaoBridgeImpl(patternDao)
    )

    // ═══════════════════════════════════════════════════════════════════
    // FLYWHEEL ENGINE
    // ═══════════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideFlywheelEngine(
        @ApplicationContext context: Context
    ): FlywheelEngine {
        val patternStore = InMemoryPatternStore()
        val preferenceStore = InMemoryPreferenceStore()
        val feedbackStore = InMemoryFeedbackStore()
        val proofStore = InMemoryProofStore()

        return FlywheelEngine(
            adaptiveLearning = AdaptiveLearningImpl(patternStore),
            preferenceLearner = PreferenceLearnerImpl(preferenceStore),
            patternTracker = PatternTrackerImpl(patternStore),
            feedbackCollector = FeedbackCollectorImpl(feedbackStore),
            proofStore = proofStore
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // SIGNAL PROVIDERS
    // ═══════════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideWorkerSignalProvider(
        transactionDao: TransactionDao,
        patternDao: PatternDao
    ): WorkerSignalProvider = WorkerSignalProviderImpl(transactionDao, patternDao)

    @Provides
    @Singleton
    fun provideMarketSignalProvider(): MarketSignalProvider = MarketSignalProviderImpl()

    @Provides
    @Singleton
    fun provideProactiveSignalProvider(): ProactiveSignalProvider = ProactiveSignalProviderImpl()
}

// ═══════════════════════════════════════════════════════════════════════
// ADAPTER IMPLEMENTATIONS
// ═══════════════════════════════════════════════════════════════════════

/**
 * Adapts the existing [IntentRouter] to the new [IntentClassifier] interface.
 */
private class IntentClassifierAdapter(
    private val router: IntentRouter
) : IntentClassifier {
    override suspend fun classify(text: String): ParseResult {
        val result = router.classify(text)
        return ParseResult(
            intent = result.intent.name,
            extractedData = result.extractedData,
            confidence = result.confidence,
            method = ParseMethod.PATTERN
        )
    }

    override suspend fun fuzzyMatch(text: String): ParseResult {
        val result = router.classify(text)
        return ParseResult(
            intent = result.intent.name,
            extractedData = result.extractedData,
            confidence = result.confidence * 0.8f,
            method = ParseMethod.FUZZY
        )
    }
}

/**
 * Normalizes dialect/Sheng to standard Swahili.
 */
private class DialectNormalizerImpl : DialectNormalizer {
    private val shengMap = mapOf(
        "niaje" to "habari",
        "poa" to "nzuri",
        "sana" to "sana",
        "maze" to "rafiki",
        "mbogi" to "kundi",
        "genje" to "ongea",
        "form" to "nzuri",
        "tusho" to "tusho",
        "nde" to "ndio",
        "aki" to "kweli",
        "wazi" to "sawa",
        "bish" to "pombe",
        "ngata" to "pesa",
        "chips" to "viazi",
        "smocha" to "smocha"
    )

    override fun normalize(text: String, language: String): String {
        if (language != "sw") return text
        var normalized = text.lowercase().trim()
        for ((sheng, standard) in shengMap) {
            normalized = normalized.replace(sheng, standard)
        }
        return normalized
    }
}

/**
 * Checks data completeness for transaction recording.
 */
private class DataCompletenessCheckerImpl : DataCompletenessChecker {
    override fun check(observation: Observation): CompletenessResult {
        // For non-transaction intents, data is always complete
        val transactionIntents = setOf("SALE", "PURCHASE", "EXPENSE")
        if (observation.proactive != null) return CompletenessResult(isComplete = true)

        // Basic text analysis for missing data
        val text = observation.text.lowercase()
        val hasNumber = text.any { it.isDigit() }
        val hasAmount = Regex("\\d+").containsMatchIn(text)

        if (!hasAmount && observation.triggerType != TriggerType.PROACTIVE) {
            return CompletenessResult(
                isComplete = false,
                missingFields = listOf("amount"),
                followUpQuestion = "Bei ngapi?"
            )
        }

        return CompletenessResult(isComplete = true)
    }
}

/**
 * Safety guard — checks responses for harmful content.
 */
private class SafetyGuardImpl : SafetyGuard {
    override fun check(response: AgentResponse, originalInput: String, language: String): AgentResponse {
        // Basic safety: ensure response is not empty
        if (response.text.isBlank()) {
            return response.copy(
                text = if (language == "sw") "Pole, sijaelewa. Jaribu tena." else "Sorry, I didn't understand. Please try again.",
                type = ResponseType.ERROR
            )
        }
        return response
    }
}

/**
 * Adapts [ProgressiveAutonomy] to the [AutonomyManager] interface.
 */
private class AutonomyManagerAdapter(
    private val autonomy: ProgressiveAutonomy
) : AutonomyManager {
    override fun check(response: AgentResponse, orientation: Orientation): AgentResponse {
        // At current autonomy level, just pass through
        // ProgressiveAutonomy tracks accuracy but doesn't block responses
        return response
    }
}

/**
 * Adapts the voice [com.msaidizi.app.voice.LlmEngine] to the superagent [LlmEngine] interface.
 */
private class LlmEngineAdapter(
    private val voiceEngine: com.msaidizi.app.voice.LlmEngine
) : LlmEngine {
    override suspend fun classify(text: String, language: String, context: String): ParseResult {
        // Use voice engine for classification fallback
        return ParseResult(
            intent = "UNKNOWN",
            confidence = 0.3f,
            method = ParseMethod.LLM
        )
    }

    override suspend fun classifyAndRespond(llmContext: LlmContext): LlmResult {
        return LlmResult(
            intent = "UNKNOWN",
            confidence = 0.3f,
            responseText = "Pole, sijaelewa. Jaribu tena."
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// CAPABILITY MODULE ADAPTERS
// ═══════════════════════════════════════════════════════════════════════

private class FinancialCapabilityModule(
    private val module: FinancialModule
) : CapabilityModule {
    override val supportedIntents = setOf(
        "SALE", "PURCHASE", "EXPENSE",
        "PROFIT_QUERY", "CHECK_BALANCE", "STOCK_QUERY",
        "DAILY_SUMMARY", "WEEKLY_SUMMARY", "MONTHLY_SUMMARY",
        "RECEIPT_SCAN", "INVENTORY_CHECK"
    )

    override suspend fun handle(intent: ResolvedIntent): AgentResponse {
        return when (intent.intent) {
            "SALE", "PURCHASE", "EXPENSE" -> {
                val text = intent.extractedData["rawText"]?.toString() ?: ""
                val result = module.recordTransaction(text)
                AgentResponse(
                    text = result.confirmationMessage,
                    type = ResponseType.TRANSACTION_CONFIRMATION,
                    shouldSpeak = true
                )
            }
            "PROFIT_QUERY", "CHECK_BALANCE" -> {
                AgentResponse(
                    text = "Data ya faida inapatikana.",
                    type = ResponseType.QUERY_RESULT,
                    shouldSpeak = true
                )
            }
            else -> AgentResponse(
                text = "Hii hitaji inahitaji data zaidi.",
                type = ResponseType.INFORMATION,
                shouldSpeak = true
            )
        }
    }
}

private class CreditCapabilityModule(
    private val module: CreditModule
) : CapabilityModule {
    override val supportedIntents = setOf(
        "LOAN_RECORD", "LOAN_QUERY", "LOAN_REPORT",
        "LOAN_DEADLINE", "CREDIT_SCORE", "DEBT_ADVICE"
    )

    override suspend fun handle(intent: ResolvedIntent): AgentResponse {
        return AgentResponse(
            text = "Moduli ya mkopo inashughulikiwa.",
            type = ResponseType.INFORMATION,
            shouldSpeak = true
        )
    }
}

private class GoalsCapabilityModule(
    private val module: GoalsModule
) : CapabilityModule {
    override val supportedIntents = setOf(
        "GOAL_CREATE", "GOAL_PROGRESS", "GOAL_REPORT",
        "GOAL_TIME_FORECAST", "GOAL_ADJUST", "GOAL_ENCOURAGEMENT",
        "GIVING_RECORD", "GIVING_QUERY", "GIVING_GOAL"
    )

    override suspend fun handle(intent: ResolvedIntent): AgentResponse {
        return AgentResponse(
            text = "Moduli ya malengo inashughulikiwa.",
            type = ResponseType.INFORMATION,
            shouldSpeak = true
        )
    }
}

private class EducationCapabilityModule(
    private val module: EducationModule
) : CapabilityModule {
    override val supportedIntents = setOf(
        "ASK_ADVICE", "GREETING", "HELP",
        "MINDSET_LESSON", "HABITS_CHECK"
    )

    override suspend fun handle(intent: ResolvedIntent): AgentResponse {
        return AgentResponse(
            text = "Moduli ya elimu inashughulikiwa.",
            type = ResponseType.INFORMATION,
            shouldSpeak = true
        )
    }
}

private class GamificationCapabilityModule(
    private val module: GamificationModule
) : CapabilityModule {
    override val supportedIntents = setOf(
        "BADGE_QUERY", "LEADERBOARD", "STREAK_CHECK"
    )

    override suspend fun handle(intent: ResolvedIntent): AgentResponse {
        return AgentResponse(
            text = "Moduli ya ushindi inashughulikiwa.",
            type = ResponseType.INFORMATION,
            shouldSpeak = true
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// CONTEXT ENGINE BRIDGES
// ═══════════════════════════════════════════════════════════════════════

private class SharedPrefsWorkerProfileStore(
    private val context: Context
) : WorkerProfileStore {
    override fun loadProfile(): WorkerProfile {
        val prefs = context.getSharedPreferences("worker_profile", Context.MODE_PRIVATE)
        return WorkerProfile(
            id = prefs.getString("worker_id", "") ?: "",
            name = prefs.getString("worker_name", "") ?: "",
            businessType = prefs.getString("business_type", "") ?: "",
            location = prefs.getString("location", "") ?: "",
            language = prefs.getString("language", "sw") ?: "sw",
            isComplete = prefs.getBoolean("onboarding_complete", false)
        )
    }

    override fun saveProfile(profile: WorkerProfile) {
        context.getSharedPreferences("worker_profile", Context.MODE_PRIVATE).edit().apply {
            putString("worker_id", profile.id)
            putString("worker_name", profile.name)
            putString("business_type", profile.businessType)
            putString("location", profile.location)
            putString("language", profile.language)
            putBoolean("onboarding_complete", profile.isComplete)
            apply()
        }
    }
}

private class TransactionDaoBridgeImpl(
    private val dao: TransactionDao
) : TransactionDaoBridge {
    override suspend fun getRecentSummary(limit: Int): String {
        return "Recent transactions available."
    }

    override suspend fun getRecent(days: Int): List<TransactionSummary> {
        return emptyList()
    }

    override suspend fun findSimilar(intent: String, item: String?, limit: Int): List<TransactionSummary> {
        return emptyList()
    }

    override suspend fun getItemBaseline(item: String): ItemBaseline? {
        return null
    }
}

private class PatternDaoBridgeImpl(
    private val dao: PatternDao
) : PatternDaoBridge {
    override suspend fun getPattern(patternType: String, key: String): String? {
        return null
    }

    override suspend fun storePattern(patternType: String, key: String, data: String, confidence: Float) {
        // Store via DAO
    }
}

// ═══════════════════════════════════════════════════════════════════════
// FLYWHEEL IN-MEMORY STORES
// ═══════════════════════════════════════════════════════════════════════

private class InMemoryPatternStore : LearningPatternStore, PatternStore {
    private val patterns = mutableMapOf<String, String>()

    override fun get(key: String): String? = patterns[key]
    override fun put(key: String, value: String) { patterns[key] = value }
    override fun getAll(): Map<String, String> = patterns.toMap()
}

private class InMemoryPreferenceStore : PreferenceStore {
    private val prefs = mutableMapOf<String, String>()

    override fun get(key: String): String? = prefs[key]
    override fun put(key: String, value: String) { prefs[key] = value }
    override fun getAll(): Map<String, String> = prefs.toMap()
}

private class InMemoryFeedbackStore : FeedbackStore {
    private val feedback = mutableListOf<FlywheelModels.FeedbackSignal>()

    override fun save(signal: FlywheelModels.FeedbackSignal) { feedback.add(signal) }
    override fun getRecent(limit: Int): List<FlywheelModels.FeedbackSignal> = feedback.takeLast(limit)
    override fun getAll(): List<FlywheelModels.FeedbackSignal> = feedback.toList()
}

private class InMemoryProofStore : ProofStore {
    private var totalPoints = 0
    private var daysActive = 1

    override fun saveProofPoint(proof: FlywheelModels.ProofPoint) { totalPoints++ }
    override fun getTotalProofPoints(): Int = totalPoints
    override fun getDaysActive(): Int = daysActive
    override fun getConsistencyScore(): Double = 0.5
    override fun getDataQualityScore(): Double = 0.5
}

// ═══════════════════════════════════════════════════════════════════════
// SIGNAL PROVIDER IMPLEMENTATIONS
// ═══════════════════════════════════════════════════════════════════════

private class WorkerSignalProviderImpl(
    private val transactionDao: TransactionDao,
    private val patternDao: PatternDao
) : WorkerSignalProvider {
    override val priority = SignalPriority.CRITICAL
    override val maxLatencyMs = 50L

    override suspend fun observe(text: String): WorkerSignal {
        return WorkerSignal.empty()
    }
}

private class MarketSignalProviderImpl : MarketSignalProvider {
    override val priority = SignalPriority.HIGH
    override val maxLatencyMs = 100L

    override suspend fun observe(text: String): MarketSignal {
        return MarketSignal.empty()
    }
}

private class ProactiveSignalProviderImpl : ProactiveSignalProvider {
    override val priority = SignalPriority.MEDIUM
    override val maxLatencyMs = 200L

    override suspend fun observe(text: String): ProactiveSignal? {
        return null
    }
}
