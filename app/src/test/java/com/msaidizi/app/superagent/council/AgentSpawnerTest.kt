package com.msaidizi.app.superagent.council

import org.junit.Assert.*
import org.junit.Test

// CouncilSupervisorTest.kt
@Test
fun `process routes single-council intent correctly`() = runTest {
    val intent = UserIntent(
        type = IntentType.RECORD_SALE,
        confidence = 0.9f,
        requiredTools = listOf("record_transaction"),
        toolParams = mapOf("record_transaction" to mapOf(
            "type" to "sale", "amount" to "500", "product" to "tomatoes"
        ))
    )
    
    val result = supervisor.process(intent, mockContext, "test-session")
    
    assertEquals(ExecutionStrategy.SINGLE_COUNCIL, result.councilUsed, CouncilType.FINANCE)
    assertTrue(result.toolResults.isNotEmpty())
}

@Test
fun `process falls back to direct when council unhealthy`() = runTest {
    // Simulate unhealthy council by recording many failures
    repeat(10) {
        supervisor.process(unhealthyIntent, mockContext, "test-session")
    }
    
    val result = supervisor.process(intent, mockContext, "test-session")
    assertEquals(ExecutionStrategy.DIRECT_FALLBACK, result.strategy)
}
