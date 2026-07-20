package com.msaidizi.app.core.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Top-level Hilt module aggregator.
 *
 * Delegates to feature modules for actual provisions:
 * - [NetworkModule]     — HTTP clients, API services, sync infrastructure
 * - [DatabaseModule]    — Room database, all DAOs, SQLCipher encryption
 * - [AIModule]          — Agents, model routing, ASR, language learning, predictors
 * - [SecurityModule]    — Encryption, privacy consent, pinned HTTP
 * - [RepositoryModule]  — Gamification, finance, social, loops, knowledge graph
 *
 * Each feature module is @InstallIn(SingletonComponent::class) and provides
 * its own @Provides methods. Hilt merges all modules at compile time.
 */
@Module(
    includes = [
        NetworkModule::class,
        DatabaseModule::class,
        AIModule::class,
        SecurityModule::class,
        RepositoryModule::class
    ]
)
@InstallIn(SingletonComponent::class)
object AppModule
