package com.msaidizi.app.core.di

import android.content.Context
import com.msaidizi.app.agent.LlmEngine
import com.msaidizi.app.agent.IntentRouter
import com.msaidizi.app.agent.SuperAgent
import com.msaidizi.app.agent.tools.ToolRegistry
import com.msaidizi.app.core.metrics.PhaseMetrics
import com.msaidizi.app.core.federated.FederatedLearningClient
import com.msaidizi.app.data.dao.*
import com.msaidizi.app.data.database.AppDatabase
import com.msaidizi.app.memory.*
import com.msaidizi.app.security.SafetyChecker
import com.msaidizi.app.security.WorkerIdProvider
import com.msaidizi.app.security.privacy.DifferentialPrivacy
import com.msaidizi.app.voice.SpeechRecognizer
import com.msaidizi.app.voice.TtsEngine
import com.msaidizi.app.voice.VoicePipeline
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * DI Module — Provides all dependencies for the SuperAgent architecture.
 * 
 * Reduced from 40+ providers to ~15 providers.
 * ONE agent, minimal dependencies.
 * 
 * Design: arch_android.md Section 3.4
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ── Identity ──────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): android.content.SharedPreferences {
        return context.getSharedPreferences("msaidizi_prefs", Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    fun provideWorkerIdProvider(prefs: android.content.SharedPreferences): WorkerIdProvider {
        return WorkerIdProvider(prefs)
    }

    // ── Database ──────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.create(context)
    }

    @Provides
    fun provideTransactionDao(db: AppDatabase): TransactionDao = db.transactionDao()

    @Provides
    fun provideInventoryDao(db: AppDatabase): InventoryDao = db.inventoryDao()

    @Provides
    fun provideGoalDao(db: AppDatabase): GoalDao = db.goalDao()

    @Provides
    fun provideLoanDao(db: AppDatabase): LoanDao = db.loanDao()

    @Provides
    fun provideGivingDao(db: AppDatabase): GivingDao = db.givingDao()

    @Provides
    fun provideEpisodeDao(db: AppDatabase): EpisodeDao = db.episodeDao()

    @Provides
    fun providePhaseMetricsDao(db: AppDatabase): PhaseMetricsDao = db.phaseMetricsDao()

    // ── Privacy & Federated Learning ─────────────────────────────

    @Provides
    @Singleton
    fun provideDifferentialPrivacy(prefs: android.content.SharedPreferences): DifferentialPrivacy {
        return DifferentialPrivacy(prefs)
    }

    @Provides
    @Singleton
    fun provideFederatedLearningClient(
        @ApplicationContext context: Context,
        prefs: android.content.SharedPreferences,
        workerIdProvider: WorkerIdProvider,
        dp: DifferentialPrivacy
    ): FederatedLearningClient {
        return FederatedLearningClient(context, prefs, workerIdProvider, dp)
    }

    // ── Memory ────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideWorkingMemory(): WorkingMemory = WorkingMemory()

    @Provides
    @Singleton
    fun provideEpisodicMemory(episodeDao: EpisodeDao): EpisodicMemory {
        return EpisodicMemory(episodeDao)
    }

    @Provides
    @Singleton
    fun provideBehavioralModelManager(): BehavioralModelManager {
        return BehavioralModelManager()
    }

    @Provides
    @Singleton
    fun provideUnifiedMemoryBridge(
        l1: WorkingMemory,
        l2: EpisodicMemory,
        l3: BehavioralModelManager,
        workerIdProvider: WorkerIdProvider,
        flClient: FederatedLearningClient
    ): UnifiedMemoryBridge {
        return UnifiedMemoryBridge(l1, l2, l3, workerIdProvider, flClient)
    }

    // ── Voice ─────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideSpeechRecognizer(@ApplicationContext context: Context): SpeechRecognizer {
        return SpeechRecognizer(context)
    }

    @Provides
    @Singleton
    fun provideTtsEngine(@ApplicationContext context: Context): TtsEngine {
        return TtsEngine(context)
    }

    @Provides
    @Singleton
    fun provideVoicePipeline(
        @ApplicationContext context: Context,
        speechRecognizer: SpeechRecognizer,
        ttsEngine: TtsEngine,
        metrics: PhaseMetrics
    ): VoicePipeline {
        return VoicePipeline(context, speechRecognizer, ttsEngine, metrics)
    }

    // ── Agent ─────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideIntentRouter(): IntentRouter = IntentRouter()

    @Provides
    @Singleton
    fun provideLlmEngine(): LlmEngine = LlmEngine()

    @Provides
    @Singleton
    fun provideSafetyChecker(): SafetyChecker = SafetyChecker()

    @Provides
    @Singleton
    fun providePhaseMetrics(metricsDao: PhaseMetricsDao): PhaseMetrics {
        return PhaseMetrics(metricsDao)
    }

    @Provides
    @Singleton
    fun provideToolRegistry(
        transactionDao: TransactionDao,
        inventoryDao: InventoryDao,
        goalDao: GoalDao,
        loanDao: LoanDao,
        givingDao: GivingDao,
        llmEngine: LlmEngine
    ): ToolRegistry {
        return ToolRegistry(transactionDao, inventoryDao, goalDao, loanDao, givingDao, llmEngine)
    }

    @Provides
    @Singleton
    fun provideSuperAgent(
        toolRegistry: ToolRegistry,
        memoryBridge: UnifiedMemoryBridge,
        safetyChecker: SafetyChecker,
        metrics: PhaseMetrics,
        llmEngine: LlmEngine,
        intentRouter: IntentRouter,
        voicePipeline: VoicePipeline,
        workerIdProvider: WorkerIdProvider
    ): SuperAgent {
        return SuperAgent(toolRegistry, memoryBridge, safetyChecker, metrics, llmEngine, intentRouter, voicePipeline, workerIdProvider)
    }
}
