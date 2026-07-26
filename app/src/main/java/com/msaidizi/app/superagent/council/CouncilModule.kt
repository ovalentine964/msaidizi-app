package com.msaidizi.app.superagent.council

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CouncilModule {

    @Provides
    @Singleton
    fun provideCouncilEventBus(): CouncilEventBus {
        return CouncilEventBus()
    }

    @Provides
    @Singleton
    fun provideCouncilManager(
        eventBus: CouncilEventBus
    ): CouncilManager {
        return CouncilManager(eventBus)
    }

    @Provides
    @Singleton
    fun provideAgentSpawner(
        councilManager: CouncilManager,
        eventBus: CouncilEventBus
    ): AgentSpawner {
        return AgentSpawner(councilManager, eventBus)
    }

    @Provides
    @Singleton
    fun provideContextScope(): ContextScope {
        return ContextScope()
    }
}
