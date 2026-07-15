package com.msaidizi.app.di

import com.msaidizi.app.agent.*
import com.msaidizi.app.agent.a2a.A2AProtocol
import com.msaidizi.app.agent.autonomy.ProgressiveAutonomy
import com.msaidizi.app.agent.harness.InferenceHarness
import com.msaidizi.app.agent.knowledge.CrossDomainKnowledgeGraph
import com.msaidizi.app.agent.proactive.ProactiveAlertEngine
import com.msaidizi.app.cfo.BriefingDelivery
import com.msaidizi.app.core.database.*
import com.msaidizi.app.core.dialect.AdaptiveVocabulary
import com.msaidizi.app.core.language.ConversationLearningPipeline
import com.msaidizi.app.evolution.SelfEvolutionManager
import com.msaidizi.app.finance.GoalPlanner
import com.msaidizi.app.finance.LoanManager
import com.msaidizi.app.finance.TitheTracker
import com.msaidizi.app.gamification.GamificationEngine
import com.msaidizi.app.loops.*
import com.msaidizi.app.mindset.MindsetAcademy
import com.msaidizi.app.mindset.RichHabitsScore
import com.msaidizi.app.onboarding.AhaMomentFlow
import com.msaidizi.app.social.SocialHandler
import com.msaidizi.app.voice.LlmEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing the multi-agent orchestration layer.
 *
 * Provides:
 * - Orchestrator (the central coordinator — 42 constructor params)
 * - Domain handlers (TransactionHandler, QueryHandler, AdviceHandler, etc.)
 * - Agent classes (BusinessAgent, AnalysisAgent, etc.)
 * - Loop classes (ReActLoop, ReflexionLoop, etc.)
 *
 * Many parameters are optional (nullable with defaults) — Hilt can't
 * handle Kotlin default parameters, so we explicitly provide null for
 * optional dependencies.
 *
 * ## Circular Dependency Mitigation
 * ModelRouter ↔ LlmEngine: LlmEngine is provided as nullable.
 * If a cycle is detected, use @Lazy or Provider<LlmEngine>.
 */
@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    // ═══════════════════════════════════════════════════════════════
    // ORCHESTRATOR — The central coordinator
    // ═══════════════════════════════════════════════════════════════

    /**
     * Provide the Orchestrator — the thin coordinator that routes
     * user input to domain-specific handlers.
     *
     * This has 42 constructor parameters. Most are optional (nullable).
     * Hilt can't auto-inject this, so we provide it explicitly.
     *
     * Required params (no defaults):
     * - intentRouter, businessAgent, analysisAgent, advisorAgent,
     *   learningAgent, adaptiveLearning, transactionHandler,
     *   queryHandler, adviceHandler, gamificationHandler,
     *   domainRouter, conversationManager
     *
     * Optional params (nullable with defaults):
     * - All the legacy/AGI/social dependencies
     */
    @Provides
    @Singleton
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
        // Optional — injected if available in the graph, null otherwise
        gamificationEngine: GamificationEngine?,
        ahaMomentFlow: AhaMomentFlow?,
        richHabitsScore: RichHabitsScore?,
        mindsetAcademy: MindsetAcademy?,
        titheTracker: TitheTracker?,
        goalPlanner: GoalPlanner?,
        loanManager: LoanManager?,
        titheDao: TitheDao?,
        goalDao: GoalDao?,
        loanDao: LoanDao?,
        briefingDelivery: BriefingDelivery?,
        morningBriefingLoop: MorningBriefingLoop?,
        streakProtectionLoop: StreakProtectionLoop?,
        variableRewardsLoop: VariableRewardsLoop?,
        selfEvolution: SelfEvolutionManager?,
        preferenceLearner: PreferenceLearner?,
        adaptiveVocabulary: AdaptiveVocabulary?,
        conversationLearningPipeline: ConversationLearningPipeline?,
        llmEngine: LlmEngine?,
        voicePersonality: VoicePersonality?,
        progressiveAutonomy: ProgressiveAutonomy?,
        proactiveAlertEngine: ProactiveAlertEngine?,
        a2aProtocol: A2AProtocol?,
        knowledgeGraph: CrossDomainKnowledgeGraph?,
        socialHandler: SocialHandler?,
        inferenceHarness: InferenceHarness?
    ): Orchestrator {
        return Orchestrator(
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
            llmEngine = llmEngine,
            voicePersonality = voicePersonality,
            progressiveAutonomy = progressiveAutonomy,
            proactiveAlertEngine = proactiveAlertEngine,
            a2aProtocol = a2aProtocol,
            knowledgeGraph = knowledgeGraph,
            socialHandler = socialHandler,
            inferenceHarness = inferenceHarness
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // DOMAIN HANDLERS — Decomposed from Orchestrator god class
    // ═══════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideTransactionHandler(
        businessAgent: BusinessAgent,
        adaptiveLearning: AdaptiveLearningEngine,
        learningAgent: LearningAgent,
        gamificationEngine: GamificationEngine?,
        ahaMomentFlow: AhaMomentFlow?,
        richHabitsScore: RichHabitsScore?,
        morningBriefingLoop: MorningBriefingLoop?,
        streakProtectionLoop: StreakProtectionLoop?,
        variableRewardsLoop: VariableRewardsLoop?,
        briefingDelivery: BriefingDelivery?,
        selfEvolution: SelfEvolutionManager?
    ): TransactionHandler {
        return TransactionHandler(
            businessAgent = businessAgent,
            adaptiveLearning = adaptiveLearning,
            learningAgent = learningAgent,
            gamificationEngine = gamificationEngine,
            ahaMomentFlow = ahaMomentFlow,
            richHabitsScore = richHabitsScore,
            morningBriefingLoop = morningBriefingLoop,
            streakProtectionLoop = streakProtectionLoop,
            variableRewardsLoop = variableRewardsLoop,
            briefingDelivery = briefingDelivery,
            selfEvolution = selfEvolution
        )
    }

    @Provides
    @Singleton
    fun provideConversationManager(
        llmEngine: LlmEngine?,
        selfEvolution: SelfEvolutionManager?,
        adaptiveLearning: AdaptiveLearningEngine,
        learningAgent: LearningAgent,
        preferenceLearner: PreferenceLearner?,
        inferenceHarness: InferenceHarness?,
        hermesSession: com.msaidizi.app.agent.hermes.HermesSessionManager?
    ): ConversationManager {
        return ConversationManager(
            llmEngine = llmEngine,
            selfEvolution = selfEvolution,
            adaptiveLearning = adaptiveLearning,
            learningAgent = learningAgent,
            preferenceLearner = preferenceLearner,
            inferenceHarness = inferenceHarness,
            hermesSession = hermesSession
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // LOOP CLASSES — ReAct, Reflexion, Plan-Execute
    // ═══════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideReActLoop(): ReActLoop = ReActLoop()

    @Provides
    @Singleton
    fun provideReflexionLoop(): ReflexionLoop = ReflexionLoop()

    @Provides
    @Singleton
    fun providePlanExecuteLoop(): PlanExecuteLoop = PlanExecuteLoop()

    // ═══════════════════════════════════════════════════════════════
    // AGENT EVENT BUS — Singleton event bus
    // ═══════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideAgentEventBus(): AgentEventBus = AgentEventBus.getInstance()

    // ═══════════════════════════════════════════════════════════════
    // NOTE: The following classes need @Inject constructor to be
    // auto-provided by Hilt. If they DON'T have @Inject, add
    // @Provides methods here for each one:
    //
    // - IntentRouter (needs IntentPatternConfig)
    // - BusinessAgent
    // - AnalysisAgent
    // - AdvisorAgent
    // - LearningAgent
    // - AdaptiveLearningEngine
    // - QueryHandler
    // - AdviceHandler
    // - GamificationHandler
    // - DomainRouter
    // - PreferenceLearner
    // - HermesSessionManager
    //
    // Check each class for @Inject constructor. If missing,
    // add a @Provides method here.
    // ═══════════════════════════════════════════════════════════════
}
