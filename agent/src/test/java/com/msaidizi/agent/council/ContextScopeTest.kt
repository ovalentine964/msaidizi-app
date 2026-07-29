package com.msaidizi.agent.council

import org.junit.Assert.*
import org.junit.Test

@Test
fun `sale recording triggers inventory update and gamification`() = runTest {
    // Setup: subscribe to events
    val inventoryEvents = mutableListOf<CouncilEvent>()
    val growthEvents = mutableListOf<CouncilEvent>()
    
    eventBus.subscribe(CouncilEventType.TRANSACTION_RECORDED) { event ->
        // Simulate inventory reaction
        inventoryEvents.add(event)
    }
    eventBus.subscribe(CouncilEventType.TRANSACTION_RECORDED) { event ->
        // Simulate growth reaction
        growthEvents.add(event)
    }
    
    // Execute: record a sale
    val intent = UserIntent(
        type = IntentType.RECORD_SALE,
        confidence = 0.9f,
        requiredTools = listOf("record_transaction"),
        toolParams = mapOf("record_transaction" to mapOf(
            "type" to "sale", "amount" to "500", "product" to "tomatoes"
        ))
    )
    
    supervisor.process(intent, mockContext, "test-session")
    
    // Verify: events were published
    delay(200) // Allow async dispatch
    assertEquals(1, inventoryEvents.size)
    assertEquals(1, growthEvents.size)
    assertEquals(CouncilType.FINANCE, inventoryEvents[0].sourceCouncil)
}
