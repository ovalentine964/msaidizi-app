package com.msaidizi.app.superagent.context

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [ContextEngine] — the 4-layer memory hierarchy.
 *
 * Covers L1 working memory, profile management, session tracking,
 * and context retrieval.
 */
class ContextEngineTest {

    private lateinit var profileStore: InMemoryWorkerProfileStore
    private lateinit var engine: ContextEngine

    @BeforeEach
    fun setup() {
        profileStore = InMemoryWorkerProfileStore()
        engine = ContextEngine(
            workerProfileStore = profileStore,
            maxWorkingMemoryTurns = 5
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // L1 WORKING MEMORY
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class WorkingMemory {

        @Test
        fun `store interaction adds to working memory`() {
            engine.storeInteraction("Nimeuziwa mandazi", "SALE", "Sawa, nimeandika.")

            val turns = engine.getRecentTurns()
            assertEquals(1, turns.size)
            assertEquals("Nimeuziwa mandazi", turns[0].input)
            assertEquals("SALE", turns[0].intent)
        }

        @Test
        fun `working memory bounded by max turns`() {
            for (i in 1..10) {
                engine.storeInteraction("input $i", "SALE", "response $i")
            }

            val turns = engine.getRecentTurns()
            assertEquals(5, turns.size) // maxWorkingMemoryTurns = 5
            assertEquals("input 6", turns[0].input) // oldest kept
            assertEquals("input 10", turns[4].input) // newest
        }

        @Test
        fun `clear session resets everything`() {
            engine.storeInteraction("test", "SALE", "ok")
            engine.clearSession()

            val turns = engine.getRecentTurns()
            assertEquals(0, turns.size)

            val info = engine.getSessionInfo()
            assertEquals(0, info.turnCount)
        }

        @Test
        fun `get recent turns with limit`() {
            for (i in 1..5) {
                engine.storeInteraction("input $i", "SALE", "response $i")
            }

            val turns = engine.getRecentTurns(limit = 2)
            assertEquals(2, turns.size)
        }

        @Test
        fun `get current topic returns last intent`() {
            engine.storeInteraction("sale", "SALE", "ok")
            engine.storeInteraction("query", "PROFIT_QUERY", "profit is 500")

            assertEquals("PROFIT_QUERY", engine.getCurrentTopic())
        }

        @Test
        fun `current topic is null when empty`() {
            assertNull(engine.getCurrentTopic())
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CONVERSATION SUMMARY
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class ConversationSummary {

        @Test
        fun `summary includes recent turns`() {
            engine.storeInteraction("Nimeuziwa mandazi", "SALE", "Sawa")
            engine.storeInteraction("Faida ngapi?", "PROFIT_QUERY", "Faida ni 500")

            val summary = engine.getConversationSummary()
            assertTrue(summary.contains("Nimeuziwa mandazi"))
            assertTrue(summary.contains("Faida ngapi?"))
        }

        @Test
        fun `empty summary when no interactions`() {
            val summary = engine.getConversationSummary()
            assertEquals("", summary)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // WORKER PROFILE
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class WorkerProfile {

        @Test
        fun `get profile returns stored profile`() {
            profileStore.saveProfile(WorkerProfile(
                name = "Amina",
                businessType = "food_vendor",
                location = "Nairobi"
            ))

            val profile = engine.getWorkerProfile()
            assertEquals("Amina", profile.name)
            assertEquals("food_vendor", profile.businessType)
        }

        @Test
        fun `update profile field`() {
            profileStore.saveProfile(WorkerProfile(name = "Amina"))

            engine.updateWorkerProfile("location", "Mombasa")

            val profile = engine.getWorkerProfile()
            assertEquals("Mombasa", profile.location)
            assertEquals("Amina", profile.name) // unchanged
        }

        @Test
        fun `update custom field`() {
            profileStore.saveProfile(WorkerProfile())

            engine.updateWorkerProfile("favorite_product", "mandazi")

            val profile = engine.getWorkerProfile()
            assertEquals("mandazi", profile.customFields["favorite_product"])
        }

        @Test
        fun `finalize profile marks complete`() {
            profileStore.saveProfile(WorkerProfile(name = "Amina"))
            engine.finalizeWorkerProfile()

            val profile = engine.getWorkerProfile()
            assertTrue(profile.isComplete)
        }

        @Test
        fun `worker summary includes available data`() {
            profileStore.saveProfile(WorkerProfile(
                name = "Amina",
                businessType = "food_vendor",
                location = "Nairobi",
                language = "sw",
                streakDays = 15
            ))

            val summary = engine.getWorkerSummary()
            assertTrue(summary.contains("Amina"))
            assertTrue(summary.contains("food_vendor"))
            assertTrue(summary.contains("Nairobi"))
            assertTrue(summary.contains("15 days"))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CORRECTION DETECTION
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class CorrectionDetection {

        @Test
        fun `detects correction intent`() {
            engine.storeInteraction("sio mandazi, ni maandazi", "CORRECTION", "Sawa")
            assertTrue(engine.hasPendingCorrection())
        }

        @Test
        fun `detects si prefix as correction`() {
            engine.storeInteraction("si hivyo", "UNKNOWN", "Sema tena")
            assertTrue(engine.hasPendingCorrection())
        }

        @Test
        fun `no correction for normal input`() {
            engine.storeInteraction("Nimeuziwa mandazi", "SALE", "Sawa")
            assertFalse(engine.hasPendingCorrection())
        }

        @Test
        fun `no correction when empty`() {
            assertFalse(engine.hasPendingCorrection())
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SESSION INFO
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class SessionInfo {

        @Test
        fun `session info tracks turn count`() {
            engine.storeInteraction("a", "A", "b")
            engine.storeInteraction("c", "C", "d")

            val info = engine.getSessionInfo()
            assertEquals(2, info.turnCount)
            assertTrue(info.durationMs >= 0)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // METRICS
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class Metrics {

        @Test
        fun `metrics report working memory size`() {
            engine.storeInteraction("a", "A", "b")
            engine.storeInteraction("c", "C", "d")

            val metrics = engine.getMetrics()
            assertEquals(2, metrics.workingMemorySize)
            assertEquals(2, metrics.sessionTurnCount)
        }

        @Test
        fun `profile cached flag`() {
            assertFalse(engine.getMetrics().profileCached)
            engine.getWorkerProfile() // triggers cache
            assertTrue(engine.getMetrics().profileCached)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// TEST HELPERS
// ═══════════════════════════════════════════════════════════════════

class InMemoryWorkerProfileStore : WorkerProfileStore {
    private var profile = WorkerProfile()

    override fun loadProfile(): WorkerProfile = profile

    override fun saveProfile(profile: WorkerProfile) {
        this.profile = profile
    }
}
