package com.msaidizi.app.superagent.council

import org.junit.Assert.*
import org.junit.Test

// CouncilEventBusTest.kt
@Test
fun `publish delivers to type subscribers`() = runTest {
    val received = mutableListOf<CouncilEvent>()
    val subscription = eventBus.subscribe(CouncilEventType.TRANSACTION_RECORDED) {
        received.add(it)
    }
    
    eventBus.publish(CouncilEvent(
        type = CouncilEventType.TRANSACTION_RECORDED,
        sourceCouncil = CouncilType.FINANCE
    ))
    
    delay(100) // Allow dispatch
    assertEquals(1, received.size)
    assertEquals(CouncilType.FINANCE, received[0].sourceCouncil)
    
    subscription.unsubscribe()
}

@Test
fun `targeted send delivers to correct council channel`() = runTest {
    val channel = eventBus.getChannel(CouncilType.INVENTORY)
    
    eventBus.sendTo(CouncilType.INVENTORY, CouncilEvent(
        type = CouncilEventType.STOCK_LOW,
        sourceCouncil = CouncilType.FINANCE,
        payload = mapOf("product" to "tomatoes")
    ))
    
    val event = channel.receive()
    assertEquals(CouncilEventType.STOCK_LOW, event.type)
    assertEquals("tomatoes", event.payload["product"])
}
