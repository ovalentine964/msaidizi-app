package com.msaidizi.app.core.di

import android.content.Context
import com.msaidizi.app.agent.AgentEventBus
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
import com.msaidizi.app.agent.TransactionHandler
import com.msaidizi.app.agent.QueryHandler
import com.msaidizi.app.agent.AdviceHandler
import com.msaidizi.app.agent.GamificationHandler
import com.msaidizi.app.agent.DomainRouter
import com.msaidizi.app.agent.ConversationManager
import com.msaidizi.app.agent.VoicePersonality
import com.msaidizi.app.agent.PreferenceLearner
import com.msaidizi.app.agent.Orchestrator
import com.msaidizi.app.agent.proactive.ProactiveAnomalyDetector
import com.msaidizi.app.agent.proactive.StockOutPredictor
import com.msaidizi.app.agent.proactive.CashFlowPredictor
import com.msaidizi.app.agent.proactive.ProactiveAlertEngine
import com.msaidizi.app.agent.harness.InferenceHarness
import com.msaidizi.app.agent.agi.AGIReadyLayer
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
import com.msaidizi.app.mindset.MindsetAcademy
import com.msaidizi.app.mindset.RichHabitsScore
import com.msaidizi.app.onboarding.AhaMomentFlow
import com.msaidizi.app.cfo.BriefingDelivery
import com.msaidizi.app.cfo.CFOEngine
import com.msaidizi.app.loops.MorningBriefingLoop
import com.msaidizi.app.loops.StreakProtectionLoop
import com.msaidizi.app.loops.VariableRewardsLoop
import com.msaidizi.app.loops.ReActLoop
import com.msaidizi.app.loops.ReflexionLoop
import com.msaidizi.app.loops.PlanExecuteLoop
import com.msaidizi.app.agent.autonomy.ProgressiveAutonomy
import com.msaidizi.app.agent.a2a.A2AProtocol
import com.msaidizi.app.evolution.SelfEvolutionManager
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
import com.msaidizi.app.gamification.InsightRewards
import com.msaidizi.app.gamification.MicroRewards
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
 * Covers agents, model routing, ASR, language learning, proactive predictors.
 */
@Module
@InstallIn(SingletonComponent::class)
object AIModule {

    // ── 12-Factor Agent Infrastructure ──

    @Provides
    @Singleton
    fun provideContextManager(): ContextManager = ContextManager(agentName = "orchestrator")

    @Provides
    @Singleton
    fun provideErrorCompactor(): ErrorCompactor = ErrorCompactor(agentName = "orchestrator")

    @Provides
    @Singleton
    fun provideUnifiedStateManager(): UnifiedStateManager = UnifiedStateManager(agentName = "orchestrator")

    // ── Core Agents ──

    @Provides
    @Singleton
    fun provideAgentEventBus(): AgentEventBus = AgentEventBus.getInstance()

    @Provides
    @Singleton
    fun provideIntentPatternConfig(@ApplicationContext context: Context): IntentPatternConfig = IntentPatternConfig(context)

    @Provides
    @Singleton
    fun provideIntentRouter(config: IntentPatternConfig): IntentRouter = IntentRouter(config)

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

    // ── Decomposed Handlers ──

    @Provides
    @Singleton
    fun provideTransactionHandler(
        businessAgent: BusinessAgent,
        adaptiveLearning: AdaptiveLearningEngine,
        learningAgent: LearningAgent,
        gamificationEngine: GamificationEngine,
        ahaMomentFlow: AhaMomentFlow,
        richHabitsScore: RichHabitsScore,
        morningBriefingLoop: MorningBriefingLoop,
        streakProtectionLoop: StreakProtectionLoop,
        variableRewardsLoop: VariableRewardsLoop,
        briefingDelivery: BriefingDelivery,
        selfEvolution: SelfEvolutionManager
    ): TransactionHandler = TransactionHandler(
        businessAgent, adaptiveLearning, learningAgent,
        gamificationEngine, ahaMomentFlow, richHabitsScore,
        morningBriefingLoop, streakProtectionLoop, variableRewardsLoop,
        briefingDelivery, selfEvolution
    )

    @Provides
    @Singleton
    fun provideQueryHandler(
        businessAgent: BusinessAgent,
        analysisAgent: AnalysisAgent,
        advisorAgent: AdvisorAgent,
        gamificationEngine: GamificationEngine,
        richHabitsScore: RichHabitsScore
    ): QueryHandler = QueryHandler(
        businessAgent, analysisAgent, advisorAgent,
        gamificationEngine, richHabitsScore
    )

    @Provides
    @Singleton
    fun provideAdviceHandler(
        advisorAgent: AdvisorAgent,
        adaptiveLearning: AdaptiveLearningEngine,
        selfEvolution: SelfEvolutionManager,
        preferenceLearner: PreferenceLearner
    ): AdviceHandler = AdviceHandler(
        advisorAgent, adaptiveLearning, selfEvolution, preferenceLearner
    )

    @Provides
    @Singleton
    fun provideGamificationHandler(
        titheTracker: TitheTracker,
        goalPlanner: GoalPlanner,
        loanManager: LoanManager,
        gamificationEngine: GamificationEngine,
        richHabitsScore: RichHabitsScore
    ): GamificationHandler = GamificationHandler(
        titheTracker, goalPlanner, loanManager, gamificationEngine, richHabitsScore
    )

    @Provides
    @Singleton
    fun provideDomainRouter(
        businessAgent: BusinessAgent,
        advisorAgent: AdvisorAgent
    ): DomainRouter = DomainRouter(businessAgent, advisorAgent)

    @Provides
    @Singleton
    fun provideConversationManager(
        adaptiveLearning: AdaptiveLearningEngine,
        learningAgent: LearningAgent,
        selfEvolution: SelfEvolutionManager,
        preferenceLearner: PreferenceLearner,
        llmEngine: LlmEngine,
        inferenceHarness: InferenceHarness,
        hermesSessionManager: com.msaidizi.app.agent.hermes.HermesSessionManager
    ): ConversationManager = ConversationManager(
        llmEngine = llmEngine,
        selfEvolution = selfEvolution,
        adaptiveLearning = adaptiveLearning,
        learningAgent = learningAgent,
        preferenceLearner = preferenceLearner,
        inferenceHarness = inferenceHarness,
        hermesSession = hermesSessionManager
    )

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
        inventoryDao: InventoryDao,
        eventBus: AgentEventBus
    ): ProactiveAlertEngine = ProactiveAlertEngine(
        patternTracker, anomalyDetector, stockOutPredictor, cashFlowPredictor,
        transactionDao, inventoryDao, eventBus
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

    // ── Orchestrator ──

    @Provides
    @Singleton
    fun provideReActLoop(): ReActLoop = ReActLoop()

    @Provides
    @Singleton
    fun provideReflexionLoop(): ReflexionLoop = ReflexionLoop()

    @Provides
    @Singleton
    fun providePlanExecuteLoop(): PlanExecuteLoop = PlanExecuteLoop()

    @Provides
    @Singleton
    fun provideProgressiveAutonomy(
        patternDao: PatternDao,
        eventBus: AgentEventBus
    ): ProgressiveAutonomy = ProgressiveAutonomy(patternDao, eventBus)

    @Provides
    @Singleton
    fun provideA2AProtocol(
        eventBus: AgentEventBus
    ): A2AProtocol = A2AProtocol(eventBus)

    @Provides
    @Singleton
    @JvmSuppressWildcards
    fun provideOrchestrator(
        intentRouter: IntentRouter,
        businessAgent: BusinessAgent,
        analysisAgent: AnalysisAgent,
        advisorAgent: AdvisorAgent,
        learningAgent: LearningAgent,
        adaptiveLearning: AdaptiveLearningEngine,
        transactionHandler: TransactionHandler,
        queryHandler: QueryHandler,
        adviceHandler: AdviceHandler,
        gamificationHandler: GamificationHandler,
        domainRouter: DomainRouter,
        conversationManager: ConversationManager,
        reActLoop: ReActLoop,
        reflexionLoop: ReflexionLoop,
        planExecuteLoop: PlanExecuteLoop,
        llmEngine: LlmEngine,
        agiReadyLayer: AGIReadyLayer,
        taskCheckpointManager: com.msaidizi.app.agent.recovery.TaskCheckpointManager,
        gamificationEngine: dagger.Lazy<GamificationEngine>,
        ahaMomentFlow: dagger.Lazy<AhaMomentFlow>,
        richHabitsScore: dagger.Lazy<RichHabitsScore>,
        mindsetAcademy: dagger.Lazy<MindsetAcademy>,
        titheTracker: dagger.Lazy<TitheTracker>,
        goalPlanner: dagger.Lazy<GoalPlanner>,
        loanManager: dagger.Lazy<LoanManager>,
        titheDao: dagger.Lazy<TitheDao>,
        goalDao: dagger.Lazy<GoalDao>,
        loanDao: dagger.Lazy<LoanDao>,
        briefingDelivery: dagger.Lazy<BriefingDelivery>,
        morningBriefingLoop: dagger.Lazy<MorningBriefingLoop>,
        streakProtectionLoop: dagger.Lazy<StreakProtectionLoop>,
        variableRewardsLoop: dagger.Lazy<VariableRewardsLoop>,
        selfEvolution: dagger.Lazy<SelfEvolutionManager>,
        preferenceLearner: dagger.Lazy<PreferenceLearner>,
        adaptiveVocabulary: dagger.Lazy<AdaptiveVocabulary>,
        conversationLearningPipeline: dagger.Lazy<ConversationLearningPipeline>,
        eventBus: dagger.Lazy<AgentEventBus>,
        voicePersonality: dagger.Lazy<VoicePersonality>,
        progressiveAutonomy: dagger.Lazy<ProgressiveAutonomy>,
        proactiveAlertEngine: dagger.Lazy<ProactiveAlertEngine>,
        a2aProtocol: dagger.Lazy<A2AProtocol>,
        knowledgeGraph: dagger.Lazy<com.msaidizi.app.agent.knowledge.CrossDomainKnowledgeGraph>,
        socialHandler: dagger.Lazy<SocialHandler>,
        inferenceHarness: dagger.Lazy<InferenceHarness>,
        episodicMemory: dagger.Lazy<com.msaidizi.app.memory.EpisodicMemory>
    ): Orchestrator = Orchestrator(
        intentRouter = intentRouter,
        businessAgent = businessAgent,
        analysisAgent = analysisAgent,
        advisorAgent = advisorAgent,
        learningAgent = learningAgent,
        adaptiveLearning = adaptiveLearning,
        transactionHandler = transactionHandler,
        queryHandler = queryHandler,
        adviceHandler = adviceHandler,
        gamificationHandler = gamificationHandler,
        domainRouter = domainRouter,
        conversationManager = conversationManager,
        reActLoop = reActLoop,
        reflexionLoop = reflexionLoop,
        planExecuteLoop = planExecuteLoop,
        llmEngine = llmEngine,
        agiReadyLayer = agiReadyLayer,
        taskCheckpointManager = taskCheckpointManager,
        gamificationEngine = gamificationEngine,
        ahaMomentFlow = ahaMomentFlow,
        richHabitsScore = richHabitsScore,
        mindsetAcademy = mindsetAcademy,
        titheTracker = titheTracker,
        goalPlanner = goalPlanner,
        loanManager = loanManager,
        titheDao = titheDao,
        goalDao = goalDao,
        loanDao = loanDao,
        briefingDelivery = briefingDelivery,
        morningBriefingLoop = morningBriefingLoop,
        streakProtectionLoop = streakProtectionLoop,
        variableRewardsLoop = variableRewardsLoop,
        selfEvolution = selfEvolution,
        preferenceLearner = preferenceLearner,
        adaptiveVocabulary = adaptiveVocabulary,
        conversationLearningPipeline = conversationLearningPipeline,
        eventBus = eventBus,
        voicePersonality = voicePersonality,
        progressiveAutonomy = progressiveAutonomy,
        proactiveAlertEngine = proactiveAlertEngine,
        a2aProtocol = a2aProtocol,
        knowledgeGraph = knowledgeGraph,
        socialHandler = socialHandler,
        inferenceHarness = inferenceHarness,
        episodicMemory = episodicMemory
    )

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
