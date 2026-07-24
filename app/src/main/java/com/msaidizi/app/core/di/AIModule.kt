package com.msaidizi.app.core.di

import android.content.Context
import com.msaidizi.app.agent.IntentPatternConfig
import com.msaidizi.app.agent.IntentRouter
import com.msaidizi.app.agent.ContextManager
import com.msaidizi.app.agent.ErrorCompactor
import com.msaidizi.app.agent.UnifiedStateManager
import com.msaidizi.app.agent.BusinessAgent
import com.msaidizi.app.agent.AnalysisAgent
import com.msaidizi.app.agent.AdvisorAgent
import com.msaidizi.app.agent.LearningAgent
import com.msaidizi.app.agent.AdaptiveLearningEngine
import com.msaidizi.app.agent.BusinessPatternTracker
import com.msaidizi.app.agent.ModelRouter
import com.msaidizi.app.agent.VoicePersonality
import com.msaidizi.app.agent.PreferenceLearner
import com.msaidizi.app.agent.proactive.ProactiveAnomalyDetector
import com.msaidizi.app.agent.proactive.StockOutPredictor
import com.msaidizi.app.agent.proactive.CashFlowPredictor
import com.msaidizi.app.agent.proactive.ProactiveAlertEngine
import com.msaidizi.app.agent.harness.InferenceHarness
import com.msaidizi.app.agent.agi.AGIReadyLayer
import com.msaidizi.app.agent.autonomy.ProgressiveAutonomy
import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.database.InventoryDao
import com.msaidizi.app.core.model.UserVocabularyDao
import com.msaidizi.app.core.model.UserCorrectionDao
import com.msaidizi.app.data.api.MsaidiziApi
import com.msaidizi.app.voice.LlmEngine
import com.msaidizi.app.finance.TitheTracker
import com.msaidizi.app.finance.GoalPlanner
import com.msaidizi.app.finance.LoanManager
import com.msaidizi.app.gamification.GamificationEngine
import com.msaidizi.app.gamification.InsightRewards
import com.msaidizi.app.gamification.MicroRewards
import com.msaidizi.app.mindset.MindsetAcademy
import com.msaidizi.app.mindset.RichHabitsScore
import com.msaidizi.app.onboarding.AhaMomentFlow
import com.msaidizi.app.cfo.BriefingDelivery
import com.msaidizi.app.cfo.CFOEngine
import com.msaidizi.app.loops.MorningBriefingLoop
import com.msaidizi.app.loops.StreakProtectionLoop
import com.msaidizi.app.loops.VariableRewardsLoop
import com.msaidizi.app.core.database.GamificationDao
import com.msaidizi.app.core.database.RichHabitsDao
import com.msaidizi.app.core.database.MindsetLessonDao
import com.msaidizi.app.core.database.TitheDao
import com.msaidizi.app.core.database.GoalDao
import com.msaidizi.app.core.database.LoanDao
import com.msaidizi.app.core.database.BriefingDeliveryDao
import com.msaidizi.app.core.dialect.AdaptiveVocabulary
import com.msaidizi.app.core.database.VocabularyLearningDao
import com.msaidizi.app.core.language.AdaptiveAsrEngine
import com.msaidizi.app.core.language.ConfidenceCalibrator
import com.msaidizi.app.core.language.PhonemeMapper
import com.msaidizi.app.core.language.LanguageModelRegistry
import com.msaidizi.app.core.language.FederatedLearningClient
import com.msaidizi.app.core.language.ConversationLearningPipeline
import com.msaidizi.app.security.privacy.ConsentManager
import com.msaidizi.app.core.network.PinnedHttpClient
import com.msaidizi.app.voice.SpeechRecognizer
import com.msaidizi.app.agent.cost.InferenceCostTracker
import com.msaidizi.app.social.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * AI, agent, and language-related dependencies.
 *
 * This module provides the OLD agent infrastructure that is still referenced
 * by ViewModels, CFO, gamification, and other subsystems. The main entry
 * point (Orchestrator) has been replaced by [com.msaidizi.app.superagent.engine.ReasoningEngine]
 * which is wired in [SuperagentModule].
 */
@Module
@InstallIn(SingletonComponent::class)
object AIModule {

    // ── 12-Factor Agent Infrastructure (still used by some subsystems) ──

    @Provides
    @Singleton
    fun provideContextManager(): ContextManager = ContextManager(agentName = "superagent")

    @Provides
    @Singleton
    fun provideErrorCompactor(): ErrorCompactor = ErrorCompactor(agentName = "superagent")

    @Provides
    @Singleton
    fun provideUnifiedStateManager(): UnifiedStateManager = UnifiedStateManager(agentName = "superagent")

    // ── Intent Classification (used by SuperagentModule's IntentClassifierAdapter) ──

    @Provides
    @Singleton
    fun provideIntentPatternConfig(@ApplicationContext context: Context): IntentPatternConfig = IntentPatternConfig(context)

    @Provides
    @Singleton
    fun provideIntentRouter(config: IntentPatternConfig): IntentRouter = IntentRouter(config)

    // ── Core Agents (still referenced by ViewModels, CFO, Social, etc.) ──

    @Provides
    @Singleton
    fun provideBusinessAgent(
        transactionDao: TransactionDao,
        inventoryDao: InventoryDao
    ): BusinessAgent = BusinessAgent(transactionDao, inventoryDao)

    @Provides
    @Singleton
    fun provideAnalysisAgent(
        transactionDao: TransactionDao
    ): AnalysisAgent = AnalysisAgent(transactionDao)

    @Provides
    @Singleton
    fun provideAdvisorAgent(
        businessAgent: BusinessAgent,
        analysisAgent: AnalysisAgent,
        voicePersonality: VoicePersonality
    ): AdvisorAgent = AdvisorAgent(businessAgent, analysisAgent, voicePersonality)

    @Provides
    @Singleton
    fun provideLearningAgent(
        patternDao: PatternDao,
        inventoryDao: InventoryDao
    ): LearningAgent = LearningAgent(patternDao, inventoryDao)

    @Provides
    @Singleton
    fun provideBusinessPatternTracker(
        transactionDao: TransactionDao,
        patternDao: PatternDao
    ): BusinessPatternTracker = BusinessPatternTracker(transactionDao, patternDao)

    @Provides
    @Singleton
    fun provideAdaptiveLearningEngine(
        userVocabularyDao: UserVocabularyDao,
        userCorrectionDao: UserCorrectionDao,
        transactionDao: TransactionDao,
        patternDao: PatternDao,
        patternTracker: BusinessPatternTracker,
        learningAgent: LearningAgent,
        learningHarness: com.msaidizi.app.agent.harness.LearningHarness
    ): AdaptiveLearningEngine = AdaptiveLearningEngine(
        userVocabularyDao, userCorrectionDao, transactionDao, patternDao,
        patternTracker, learningAgent, learningHarness
    )

    // ── Voice & Preference ──

    @Provides
    @Singleton
    fun provideVoicePersonality(): VoicePersonality = VoicePersonality()

    @Provides
    @Singleton
    fun providePreferenceLearner(): PreferenceLearner = PreferenceLearner()

    // ── On-Device AI Predictors ──

    @Provides
    @Singleton
    fun provideProactiveAnomalyDetector(
        transactionDao: TransactionDao
    ): ProactiveAnomalyDetector = ProactiveAnomalyDetector(transactionDao)

    @Provides
    @Singleton
    fun provideStockOutPredictor(
        inventoryDao: InventoryDao,
        transactionDao: TransactionDao
    ): StockOutPredictor = StockOutPredictor(inventoryDao, transactionDao)

    @Provides
    @Singleton
    fun provideCashFlowPredictor(
        transactionDao: TransactionDao
    ): CashFlowPredictor = CashFlowPredictor(transactionDao)

    @Provides
    @Singleton
    fun provideProactiveAlertEngine(
        patternTracker: BusinessPatternTracker,
        anomalyDetector: ProactiveAnomalyDetector,
        stockOutPredictor: StockOutPredictor,
        cashFlowPredictor: CashFlowPredictor,
        transactionDao: TransactionDao,
        inventoryDao: InventoryDao
    ): ProactiveAlertEngine = ProactiveAlertEngine(
        patternTracker, anomalyDetector, stockOutPredictor, cashFlowPredictor,
        transactionDao, inventoryDao
    )

    // ── Model Router ──

    @Provides
    @Singleton
    fun provideModelRouter(
        @ApplicationContext context: Context,
        llmEngine: LlmEngine,
        api: MsaidiziApi,
        inferenceHarness: InferenceHarness
    ): ModelRouter = ModelRouter(context, llmEngine = llmEngine, apiClient = api, inferenceHarness = inferenceHarness)

    // ── Autonomy ──

    @Provides
    @Singleton
    fun provideProgressiveAutonomy(
        patternDao: PatternDao
    ): ProgressiveAutonomy = ProgressiveAutonomy(patternDao)

    // ── Episodic Memory (L2) — SQLite FTS5 cross-session memory ──

    @Provides
    @Singleton
    fun provideEpisodicMemory(
        @ApplicationContext context: Context
    ): com.msaidizi.app.memory.EpisodicMemory = com.msaidizi.app.memory.EpisodicMemory(context)

    // ── Dialect & Adaptive Vocabulary ──

    @Provides
    @Singleton
    fun provideAdaptiveVocabulary(
        learningDao: VocabularyLearningDao,
        userVocabDao: UserVocabularyDao
    ): AdaptiveVocabulary = AdaptiveVocabulary(learningDao, userVocabDao)

    // ── Adaptive ASR & Language Learning ──

    @Provides
    @Singleton
    fun provideConfidenceCalibrator(): ConfidenceCalibrator = ConfidenceCalibrator()

    @Provides
    @Singleton
    fun providePhonemeMapper(): PhonemeMapper = PhonemeMapper()

    @Provides
    @Singleton
    fun provideLanguageModelRegistry(
        @ApplicationContext context: Context
    ): LanguageModelRegistry = LanguageModelRegistry(context)

    @Provides
    @Singleton
    fun provideAdaptiveAsrEngine(
        speechRecognizer: SpeechRecognizer,
        confidenceCalibrator: ConfidenceCalibrator,
        phonemeMapper: PhonemeMapper,
        languageModelRegistry: LanguageModelRegistry,
        userCorrectionDao: UserCorrectionDao,
        userVocabularyDao: UserVocabularyDao
    ): AdaptiveAsrEngine = AdaptiveAsrEngine(
        speechRecognizer, confidenceCalibrator, phonemeMapper,
        languageModelRegistry, userCorrectionDao, userVocabularyDao
    )

    @Provides
    @Singleton
    fun provideFederatedLearningClient(
        @ApplicationContext context: Context,
        pinnedHttpClient: PinnedHttpClient,
        consentManager: ConsentManager,
        cryptoService: com.msaidizi.app.security.crypto.CryptoService
    ): FederatedLearningClient = FederatedLearningClient(context, pinnedHttpClient, consentManager, cryptoService)

    // ── Harness Layer ──

    @Provides
    @Singleton
    fun provideInferenceCostTracker(): InferenceCostTracker = InferenceCostTracker()
}
