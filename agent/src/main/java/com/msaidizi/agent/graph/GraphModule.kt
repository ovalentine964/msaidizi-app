package com.msaidizi.agent.graph

import com.msaidizi.core.database.MsaidiziDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for graph engineering components.
 * Provides all graph-related DAOs and injectable components.
 *
 * Add this to your Hilt setup — it auto-provides:
 *   - KgNodeDao, KgEdgeDao, KgFactDao (from MsaidiziDatabase)
 *   - ToolGraph, KnowledgeGraph, WorkflowDAG (singletons)
 *   - GraphAwareContextAssembler (singleton)
 *
 * The graph tables (kg_nodes, kg_edges, kg_facts) must be added to
 * MsaidiziDatabase's entity list and a migration must be written.
 */
@Module
@InstallIn(SingletonComponent::class)
object GraphModule {

    // ── DAO Providers ──────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideKgNodeDao(database: MsaidiziDatabase): KgNodeDao {
        return database.kgNodeDao()
    }

    @Provides
    @Singleton
    fun provideKgEdgeDao(database: MsaidiziDatabase): KgEdgeDao {
        return database.kgEdgeDao()
    }

    @Provides
    @Singleton
    fun provideKgFactDao(database: MsaidiziDatabase): KgFactDao {
        return database.kgFactDao()
    }
}
