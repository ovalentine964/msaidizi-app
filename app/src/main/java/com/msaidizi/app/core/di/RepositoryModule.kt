package com.msaidizi.app.core.di

import com.msaidizi.app.scanner.ReceiptScanner
import com.msaidizi.app.agent.knowledge.CrossDomainKnowledgeGraph
import com.msaidizi.app.agent.recovery.TaskCheckpointManager
import com.msaidizi.app.agent.recovery.TaskCheckpointDao
import com.msaidizi.app.agent.recovery.AgentTraceDao
import com.msaidizi.app.agent.hermes.HermesSessionManager
import com.msaidizi.app.agent.AgentEventBus
import com.msaidizi.app.agent.AdaptiveLearningEngine
import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.core.database.KnowledgeDao
import com.msaidizi.app.core.database.SessionDao
import com.msaidizi.app.agent.BusinessPatternTracker
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
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.social.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Repository and business-logic singletons: gamification, finance, social, loops, etc.
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    // ── Knowledge & Recovery ──

    @Provides
    @Singleton
    fun provideTaskCheckpointManager(
        checkpointDao: TaskCheckpointDao,
        traceDao: AgentTraceDao
    ): TaskCheckpointManager = TaskCheckpointManager(checkpointDao, traceDao)

    @Provides
    @Singleton
    fun provideCrossDomainKnowledgeGraph(
        patternDao: PatternDao,
        patternTracker: BusinessPatternTracker,
        knowledgeDao: KnowledgeDao,
        eventBus: AgentEventBus
    ): CrossDomainKnowledgeGraph = CrossDomainKnowledgeGraph(
        patternDao, patternTracker, knowledgeDao, eventBus
    )

    @Provides
    @Singleton
    fun provideHermesSessionManager(
        eventBus: AgentEventBus,
        adaptiveLearning: AdaptiveLearningEngine,
        sessionDao: SessionDao
    ): HermesSessionManager = HermesSessionManager(
        eventBus = eventBus,
        adaptiveLearning = adaptiveLearning,
        sessionDao = sessionDao
    )

    // ── Receipt Scanning ──

    @Provides
    @Singleton
    fun provideReceiptScanner(): ReceiptScanner = ReceiptScanner()

    // ── Gamification & Stickiness ──

    @Provides
    @Singleton
    fun provideGamificationEngine(
        gamificationDao: GamificationDao,
        transactionDao: TransactionDao,
        patternDao: PatternDao
    ): GamificationEngine {
        val engine = GamificationEngine(gamificationDao)
        engine.microRewards = MicroRewards(gamificationDao, transactionDao, patternDao)
        engine.insightRewards = InsightRewards(gamificationDao, transactionDao, patternDao)
        return engine
    }

    @Provides
    @Singleton
    fun provideMindsetAcademy(
        mindsetLessonDao: MindsetLessonDao
    ): MindsetAcademy = MindsetAcademy(mindsetLessonDao)

    @Provides
    @Singleton
    fun provideRichHabitsScore(
        richHabitsDao: RichHabitsDao
    ): RichHabitsScore = RichHabitsScore(richHabitsDao)

    @Provides
    @Singleton
    fun provideTitheTracker(
        titheDao: TitheDao
    ): TitheTracker = TitheTracker(titheDao)

    @Provides
    @Singleton
    fun provideGoalPlanner(
        goalDao: GoalDao
    ): GoalPlanner = GoalPlanner(goalDao)

    @Provides
    @Singleton
    fun provideLoanManager(
        loanDao: LoanDao
    ): LoanManager = LoanManager(loanDao)

    @Provides
    @Singleton
    fun provideAhaMomentFlow(
        businessAgent: com.msaidizi.app.agent.BusinessAgent
    ): AhaMomentFlow = AhaMomentFlow(businessAgent)

    @Provides
    @Singleton
    fun provideCFOEngine(): CFOEngine = CFOEngine()

    @Provides
    @Singleton
    fun provideBriefingDelivery(
        cfoEngine: CFOEngine,
        businessAgent: com.msaidizi.app.agent.BusinessAgent,
        loanManager: LoanManager,
        gamificationEngine: GamificationEngine,
        mindsetAcademy: MindsetAcademy,
        richHabitsScore: RichHabitsScore,
        briefingDeliveryDao: BriefingDeliveryDao,
        peerComparison: PeerComparison,
        communityTips: CommunityTips,
        leaderboardService: LeaderboardService
    ): BriefingDelivery = BriefingDelivery(
        cfoEngine, businessAgent, loanManager,
        gamificationEngine, mindsetAcademy, richHabitsScore,
        briefingDeliveryDao, peerComparison, communityTips, leaderboardService
    )

    // ── Loops — Foundation engagement cycles ──

    @Provides
    @Singleton
    fun provideMorningBriefingLoop(
        cfoEngine: CFOEngine,
        briefingDelivery: BriefingDelivery,
        businessAgent: com.msaidizi.app.agent.BusinessAgent,
        transactionDao: TransactionDao,
        briefingDeliveryDao: BriefingDeliveryDao
    ): MorningBriefingLoop = MorningBriefingLoop(
        cfoEngine, briefingDelivery, businessAgent,
        transactionDao, briefingDeliveryDao
    )

    @Provides
    @Singleton
    fun provideStreakProtectionLoop(
        gamificationEngine: GamificationEngine,
        gamificationDao: GamificationDao,
        transactionDao: TransactionDao
    ): StreakProtectionLoop = StreakProtectionLoop(
        gamificationEngine, gamificationDao, transactionDao
    )

    @Provides
    @Singleton
    fun provideVariableRewardsLoop(
        gamificationEngine: GamificationEngine,
        gamificationDao: GamificationDao,
        transactionDao: TransactionDao,
        patternDao: PatternDao
    ): VariableRewardsLoop = VariableRewardsLoop(
        gamificationEngine, gamificationDao, transactionDao, patternDao
    )

    // ── Social Layer ──

    @Provides
    @Singleton
    fun providePeerComparison(
        socialDao: SocialDao,
        transactionDao: TransactionDao
    ): PeerComparison = PeerComparison(socialDao, transactionDao)

    @Provides
    @Singleton
    fun provideLeaderboardService(
        socialDao: SocialDao,
        transactionDao: TransactionDao,
        gamificationEngine: GamificationEngine
    ): LeaderboardService = LeaderboardService(socialDao, transactionDao, gamificationEngine)

    @Provides
    @Singleton
    fun provideCommunityTips(
        socialDao: SocialDao
    ): CommunityTips = CommunityTips(socialDao)

    @Provides
    @Singleton
    fun provideWhatsAppCommunity(
        socialDao: SocialDao
    ): WhatsAppCommunity = WhatsAppCommunity(socialDao)

    @Provides
    @Singleton
    fun provideSocialHandler(
        peerComparison: PeerComparison,
        leaderboardService: LeaderboardService,
        communityTips: CommunityTips,
        whatsappCommunity: WhatsAppCommunity
    ): SocialHandler = SocialHandler(peerComparison, leaderboardService, communityTips, whatsappCommunity)
}
