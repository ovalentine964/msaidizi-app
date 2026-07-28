package com.msaidizi.agent.council

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Council DI module.
 *
 * CouncilEventBus, CouncilManager, AgentSpawner, and ContextScope all use
 * @Inject constructor + @Singleton, so Hilt creates them automatically.
 * No explicit @Provides needed — removing them avoids duplicate binding errors.
 */
@Module
@InstallIn(SingletonComponent::class)
object CouncilModule
