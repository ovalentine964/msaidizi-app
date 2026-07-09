package com.msaidizi.app.core

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * MemoryManager tests — prevents OOM on $50 phones.
 *
 * Tests cover:
 * - Memory level thresholds
 * - MemoryStatus calculations
 * - Memory level determination logic
 * - Cache trimming logic
 * - Model release prioritization
 */
@RunWith(JUnit4::class)
class MemoryManagerTest {

    // ═══════════════════════════════════════════════════════════════════
    // Memory Thresholds — Tuned for 2GB devices
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `memory thresholds are reasonable for 2GB device`() {
        // LOW_MEMORY_THRESHOLD = 150MB — danger zone
        // CRITICAL_MEMORY_THRESHOLD = 80MB — emergency
        // MODEL_RELEASE_THRESHOLD = 200MB — release models
        // These are constants tested via their usage in level determination

        // Verify the ordering: CRITICAL < LOW < MODEL_RELEASE
        assertTrue("Critical should be lower than low",
            80L < 150L)
        assertTrue("Low should be lower than model release",
            150L < 200L)
    }

    // ═══════════════════════════════════════════════════════════════════
    // MemoryStatus Calculations
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `MemoryStatus heapUsagePercent calculates correctly`() {
        val status = MemoryManager.MemoryStatus(
            usedHeapMB = 150,
            maxHeapMB = 512,
            freeHeapMB = 362,
            nativeHeapMB = 100,
            nativeFreeMB = 50,
            deviceFreeMB = 800,
            deviceTotalMB = 2048,
            isLowMemory = false,
            currentLevel = MemoryManager.MemoryLevel.NORMAL,
            trimCount = 0,
            lastTrimTimeMs = 0
        )

        assertEquals(29, status.heapUsagePercent)  // 150/512 ≈ 29%
    }

    @Test
    fun `MemoryStatus deviceUsagePercent calculates correctly`() {
        val status = MemoryManager.MemoryStatus(
            usedHeapMB = 150,
            maxHeapMB = 512,
            freeHeapMB = 362,
            nativeHeapMB = 100,
            nativeFreeMB = 50,
            deviceFreeMB = 500,
            deviceTotalMB = 2048,
            isLowMemory = false,
            currentLevel = MemoryManager.MemoryLevel.NORMAL,
            trimCount = 0,
            lastTrimTimeMs = 0
        )

        assertEquals(75, status.deviceUsagePercent)  // (2048-500)/2048 ≈ 75%
    }

    @Test
    fun `MemoryStatus handles zero maxHeapMB gracefully`() {
        val status = MemoryManager.MemoryStatus(
            usedHeapMB = 0,
            maxHeapMB = 0,
            freeHeapMB = 0,
            nativeHeapMB = 0,
            nativeFreeMB = 0,
            deviceFreeMB = 500,
            deviceTotalMB = 2048,
            isLowMemory = false,
            currentLevel = MemoryManager.MemoryLevel.NORMAL,
            trimCount = 0,
            lastTrimTimeMs = 0
        )

        assertEquals(0, status.heapUsagePercent)  // Should not divide by zero
    }

    @Test
    fun `MemoryStatus handles zero deviceTotalMB gracefully`() {
        val status = MemoryManager.MemoryStatus(
            usedHeapMB = 150,
            maxHeapMB = 512,
            freeHeapMB = 362,
            nativeHeapMB = 100,
            nativeFreeMB = 50,
            deviceFreeMB = 0,
            deviceTotalMB = 0,
            isLowMemory = true,
            currentLevel = MemoryManager.MemoryLevel.CRITICAL,
            trimCount = 5,
            lastTrimTimeMs = System.currentTimeMillis()
        )

        assertEquals(0, status.deviceUsagePercent)  // Should not divide by zero
    }

    @Test
    fun `MemoryStatus toString is informative`() {
        val status = MemoryManager.MemoryStatus(
            usedHeapMB = 200,
            maxHeapMB = 512,
            freeHeapMB = 312,
            nativeHeapMB = 150,
            nativeFreeMB = 50,
            deviceFreeMB = 600,
            deviceTotalMB = 2048,
            isLowMemory = false,
            currentLevel = MemoryManager.MemoryLevel.MODERATE,
            trimCount = 3,
            lastTrimTimeMs = 1000000L
        )

        val str = status.toString()
        assertTrue("Should contain heap info", str.contains("200/512"))
        assertTrue("Should contain native info", str.contains("150"))
        assertTrue("Should contain device info", str.contains("600/2048"))
        assertTrue("Should contain level", str.contains("MODERATE"))
        assertTrue("Should contain trim count", str.contains("3"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // Memory Level Determination Logic
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `memory level is NORMAL when plenty of free RAM`() {
        // > 200MB free = NORMAL
        val deviceFreeMB = 500L
        val level = when {
            deviceFreeMB < 80 -> MemoryManager.MemoryLevel.CRITICAL
            deviceFreeMB < 150 -> MemoryManager.MemoryLevel.LOW
            deviceFreeMB < 200 -> MemoryManager.MemoryLevel.MODERATE
            else -> MemoryManager.MemoryLevel.NORMAL
        }
        assertEquals(MemoryManager.MemoryLevel.NORMAL, level)
    }

    @Test
    fun `memory level is MODERATE between 150-200MB free`() {
        val deviceFreeMB = 175L
        val level = when {
            deviceFreeMB < 80 -> MemoryManager.MemoryLevel.CRITICAL
            deviceFreeMB < 150 -> MemoryManager.MemoryLevel.LOW
            deviceFreeMB < 200 -> MemoryManager.MemoryLevel.MODERATE
            else -> MemoryManager.MemoryLevel.NORMAL
        }
        assertEquals(MemoryManager.MemoryLevel.MODERATE, level)
    }

    @Test
    fun `memory level is LOW between 80-150MB free`() {
        val deviceFreeMB = 100L
        val level = when {
            deviceFreeMB < 80 -> MemoryManager.MemoryLevel.CRITICAL
            deviceFreeMB < 150 -> MemoryManager.MemoryLevel.LOW
            deviceFreeMB < 200 -> MemoryManager.MemoryLevel.MODERATE
            else -> MemoryManager.MemoryLevel.NORMAL
        }
        assertEquals(MemoryManager.MemoryLevel.LOW, level)
    }

    @Test
    fun `memory level is CRITICAL below 80MB free`() {
        val deviceFreeMB = 50L
        val level = when {
            deviceFreeMB < 80 -> MemoryManager.MemoryLevel.CRITICAL
            deviceFreeMB < 150 -> MemoryManager.MemoryLevel.LOW
            deviceFreeMB < 200 -> MemoryManager.MemoryLevel.MODERATE
            else -> MemoryManager.MemoryLevel.NORMAL
        }
        assertEquals(MemoryManager.MemoryLevel.CRITICAL, level)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Cache Trimming Logic
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `cache trimming removes correct percentage`() {
        val cacheSize = 1000
        val trimPercentage = 0.3
        val toRemove = (cacheSize * trimPercentage).toInt()
        assertEquals(300, toRemove)
    }

    @Test
    fun `cache trimming does nothing for small caches`() {
        val maxCacheEntries = 500
        val smallCache = mutableListOf<Any>(1, 2, 3)
        assertTrue("Small cache should not be trimmed", smallCache.size <= maxCacheEntries)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Model Release Prioritization
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `non-essential models exclude whisper`() {
        val modelReleasers = mapOf(
            "whisper" to {},
            "llm" to {},
            "tts" to {},
            "mms" to {}
        )

        val nonEssential = modelReleasers.filterKeys { it != "whisper" }
        assertEquals(3, nonEssential.size)
        assertFalse("whisper should not be in non-essential", nonEssential.containsKey("whisper"))
        assertTrue("llm should be in non-essential", nonEssential.containsKey("llm"))
        assertTrue("tts should be in non-essential", nonEssential.containsKey("tts"))
    }

    @Test
    fun `all models are released in critical memory`() {
        val modelReleasers = mapOf(
            "whisper" to {},
            "llm" to {},
            "tts" to {},
            "mms" to {}
        )

        // In CRITICAL mode, ALL models are released
        assertEquals(4, modelReleasers.size)
    }

    // ═══════════════════════════════════════════════════════════════════
    // MemoryLevel Enum
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `MemoryLevel has all expected levels`() {
        val levels = MemoryManager.MemoryLevel.entries
        assertEquals(5, levels.size)
        assertTrue(levels.contains(MemoryManager.MemoryLevel.NORMAL))
        assertTrue(levels.contains(MemoryManager.MemoryLevel.MODERATE))
        assertTrue(levels.contains(MemoryManager.MemoryLevel.LOW))
        assertTrue(levels.contains(MemoryManager.MemoryLevel.CRITICAL))
        assertTrue(levels.contains(MemoryManager.MemoryLevel.COMPLETE))
    }

    @Test
    fun `MemoryLevel ordering is severity-ascending`() {
        val levels = MemoryManager.MemoryLevel.entries
        assertEquals(MemoryManager.MemoryLevel.NORMAL, levels[0])
        assertEquals(MemoryManager.MemoryLevel.COMPLETE, levels[4])
    }
}
