package com.msaidizi.app.superagent.council

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

// AgentSpawnerTest.kt
@Test
fun `needsSpawning returns true for multi-council tools`() {
    val intent = UserIntent(
        type = IntentType.DAILY_REPORT,
        confidence = 0.9f,
        requiredTools = listOf("cfo_engine", "inventory_tracker", "gamification")
    )
    assertTrue(agentSpawner.needsSpawning(intent))
}

@Test
fun `needsSpawning returns false for single-council tools`() {
    val intent = UserIntent(
        type = IntentType.RECORD_SALE,
        confidence = 0.9f,
        requiredTools = listOf("record_transaction")
    )
    assertFalse(agentSpawner.needsSpawning(intent))
}

@Test
fun `spawn decomposes into correct sub-tasks`() = runTest {
    val intent = UserIntent(
        type = IntentType.DAILY_REPORT,
        confidence = 0.9f,
        requiredTools = listOf("cfo_engine", "inventory_tracker")
    )
    
    val result = agentSpawner.spawn(intent, "test-session")
    
    assertTrue(result.success)
    assertEquals(2, result.subTaskResults.size)
    assertTrue(result.subTaskResults.any { it.council == CouncilType.FINANCE })
    assertTrue(result.subTaskResults.any { it.council == CouncilType.INVENTORY })
}
