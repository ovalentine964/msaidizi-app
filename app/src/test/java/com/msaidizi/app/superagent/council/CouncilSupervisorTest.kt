package com.msaidizi.app.superagent.council

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

// ContextScopeTest.kt
@Test
fun `scoped context for FINANCE includes financial data`() {
    scope.setGlobalContext(fullContext)
    val scoped = scope.getScopedContext(CouncilType.FINANCE)
    
    assertNotNull(scoped.recentFinancialSummary)
    assertNotNull(scoped.userProfile)
    assertNotNull(scoped.businessProfile)
}

@Test
fun `scoped context for VOICE excludes financial data`() {
    scope.setGlobalContext(fullContext)
    val scoped = scope.getScopedContext(CouncilType.VOICE)
    
    assertNull(scoped.recentFinancialSummary)
    assertNull(scoped.userProfile)
    assertNotNull(scoped.learnedVocabulary)
}

@Test
fun `context request can be fulfilled`() {
    scope.setGlobalContext(fullContext)
    val requestId = scope.requestContext(CouncilType.GROWTH, ContextDataType.FINANCIAL_SUMMARY)
    val result = scope.fulfillRequest(requestId)
    
    assertNotNull(result)
    assertTrue(result!!.containsKey("financial_summary"))
}
