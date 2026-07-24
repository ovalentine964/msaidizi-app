package com.msaidizi.app.superagent.harness

import com.msaidizi.app.core.database.*
import com.msaidizi.app.core.util.DateTimeUtil
import com.msaidizi.app.model.BusinessProfile
import com.msaidizi.app.model.ConversationEntity
import com.msaidizi.app.model.UserProfileEntity
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Context Assembler — Gathers relevant context from all memory layers.
 *
 * Assembles:
 * - User profile & business info
 * - Recent conversation history
 * - Today's financial summary
 * - Relevant knowledge entries
 * - Learned patterns
 */
@Singleton
class ContextAssembler @Inject constructor(
    private val userProfileDao: UserProfileDao,
    private val saleDao: SaleDao,
    private val expenseDao: ExpenseDao,
    private val productDao: ProductDao,
    private val customerDao: CustomerDao,
    private val knowledgeDao: KnowledgeDao,
    private val gson: Gson
) {
    /**
     * Assemble full context for the harness.
     */
    suspend fun assemble(
        intent: UserIntent,
        sessionId: String,
        recentConversation: Flow<List<ConversationEntity>>
    ): AssembledContext {
        // Load user profile
        val profile = userProfileDao.getProfileOnce()

        // Parse business profile from JSON
        val businessProfile = profile?.businessProfile?.let {
            try {
                gson.fromJson(it, BusinessProfile::class.java)
            } catch (e: Exception) {
                Timber.w(e, "Failed to parse business profile")
                null
            }
        }

        // Get recent conversation
        val conversation = try {
            recentConversation.first()
        } catch (e: Exception) {
            emptyList()
        }

        // Get today's financial summary
        val financialSummary = buildFinancialSummary()

        // Get relevant knowledge based on intent
        val knowledge = getRelevantKnowledge(intent)

        // Get relevant patterns
        val patterns = getRelevantPatterns(intent)

        return AssembledContext(
            userProfile = profile,
            businessProfile = businessProfile,
            recentConversation = conversation,
            recentFinancialSummary = financialSummary,
            knowledgeContext = knowledge,
            relevantPatterns = patterns
        )
    }

    /**
     * Build a text summary of today's financials.
     */
    private suspend fun buildFinancialSummary(): String? {
        val todayStart = DateTimeUtil.startOfDay()
        val todayEnd = DateTimeUtil.endOfDay()

        val totalSales = saleDao.getTotalSalesBetween(todayStart, todayEnd).first() ?: 0.0
        val totalExpenses = expenseDao.getTotalExpensesBetween(todayStart, todayEnd).first() ?: 0.0
        val transactionCount = saleDao.getTransactionCountBetween(todayStart, todayEnd).first()

        if (totalSales == 0.0 && totalExpenses == 0.0) return null

        val profit = totalSales - totalExpenses
        val lowStock = productDao.getLowStock().first()

        return buildString {
            appendLine("Today (${DateTimeUtil.today()}):")
            appendLine("- Sales: ${DateTimeUtil.formatCurrency(totalSales)} ($transactionCount transactions)")
            appendLine("- Expenses: ${DateTimeUtil.formatCurrency(totalExpenses)}")
            appendLine("- Profit: ${DateTimeUtil.formatCurrency(profit)}")
            if (lowStock.isNotEmpty()) {
                appendLine("- LOW STOCK ALERT: ${lowStock.joinToString(", ") { it.name }}")
            }
        }
    }

    /**
     * Get knowledge entries relevant to the current intent.
     */
    private suspend fun getRelevantKnowledge(intent: UserIntent): List<String> {
        val knowledge = mutableListOf<String>()

        when (intent.type) {
            IntentType.RECORD_SALE, IntentType.ASK_SALES_TODAY -> {
                knowledgeDao.getByCategory("business_patterns").first()
                    .take(3)
                    .forEach { knowledge.add(it.value) }
            }
            IntentType.ASK_ADVICE -> {
                knowledgeDao.getByCategory("advice").first()
                    .sortedByDescending { it.confidence }
                    .take(5)
                    .forEach { knowledge.add(it.value) }
            }
            IntentType.ASK_STOCK, IntentType.RECORD_PURCHASE -> {
                knowledgeDao.getByCategory("stock_patterns").first()
                    .take(3)
                    .forEach { knowledge.add(it.value) }
            }
            else -> {}
        }

        return knowledge
    }

    /**
     * Get patterns relevant to the current intent.
     */
    private suspend fun getRelevantPatterns(intent: UserIntent): List<String> {
        val patterns = mutableListOf<String>()

        // Get vocabulary/dialect patterns for better understanding
        knowledgeDao.getByCategory("vocab").first()
            .sortedByDescending { it.usageCount }
            .take(5)
            .forEach { patterns.add("${it.key}: ${it.value}") }

        return patterns
    }
}
