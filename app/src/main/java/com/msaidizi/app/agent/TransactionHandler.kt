package com.msaidizi.app.agent

import com.msaidizi.app.core.model.*
import com.msaidizi.app.gamification.GamificationEngine
import com.msaidizi.app.loops.MorningBriefingLoop
import com.msaidizi.app.loops.StreakProtectionLoop
import com.msaidizi.app.loops.VariableRewardsLoop
import com.msaidizi.app.loops.RewardAction
import com.msaidizi.app.onboarding.AhaMomentFlow
import com.msaidizi.app.mindset.RichHabitsScore
import com.msaidizi.app.cfo.BriefingDelivery
import com.msaidizi.app.evolution.SelfEvolutionManager
import timber.log.Timber
import java.util.UUID

/**
 * Handles all transaction-related intents: sale, purchase, expense.
 *
 * Extracted from Orchestrator to enforce Single Responsibility Principle.
 * Each method handles one transaction type with full gamification integration.
 *
 * **ECO 201 §1.2 (Producer Theory):**
 * - Sales increase revenue; purchases increase capital stock.
 * - Expenses reduce profit; all three update the cost basis.
 *
 * @see Orchestrator for the routing coordinator
 */
class TransactionHandler(
    private val businessAgent: BusinessAgent,
    private val adaptiveLearning: AdaptiveLearningEngine,
    private val learningAgent: LearningAgent,
    private val gamificationEngine: GamificationEngine? = null,
    private val ahaMomentFlow: AhaMomentFlow? = null,
    private val richHabitsScore: RichHabitsScore? = null,
    private val morningBriefingLoop: MorningBriefingLoop? = null,
    private val streakProtectionLoop: StreakProtectionLoop? = null,
    private val variableRewardsLoop: VariableRewardsLoop? = null,
    private val briefingDelivery: BriefingDelivery? = null,
    private val selfEvolution: SelfEvolutionManager? = null
) {
    // Last transaction for corrections
    var lastTransaction: Transaction? = null
        private set

    /**
     * Handle sale recording with full gamification pipeline.
     *
     * Pipeline: record sale → learn → gamification → streak → variable rewards → aha moment
     */
    suspend fun handleSale(intentResult: IntentResult, language: String): AgentResponse {
        val item = intentResult.extractedData["item"] ?: return AgentResponse(
            text = if (language == "sw") "Ni bidhaa gani?" else "What item?",
            type = ResponseType.CLARIFICATION
        )

        val quantity = intentResult.extractedData["quantity"]?.toDoubleOrNull() ?: 1.0
        val amount = intentResult.extractedData["amount"]?.toDoubleOrNull()
            ?: intentResult.extractedData["suggestedPrice"]?.toDoubleOrNull()
            ?: return AgentResponse(
                text = if (language == "sw") "Bei ni ngapi?" else "What price?",
                type = ResponseType.CLARIFICATION
            )

        return try {
            val transaction = businessAgent.recordSale(item, quantity, amount, language)
            lastTransaction = transaction

            // Learn from this transaction
            adaptiveLearning.learnFromTransaction(transaction)

            // Record sale time for pattern learning
            learningAgent.recordSaleTime(
                java.time.LocalTime.now().hour,
                java.time.LocalDate.now().dayOfWeek.value - 1
            )

            val profit = transaction.totalAmount - transaction.costBasis

            // Loop closure: morning briefing feedback
            morningBriefingLoop?.let { loop ->
                try { loop.onTransactionAfterBriefing(transaction) } catch (_: Throwable) {}
            } ?: run {
                briefingDelivery?.let { bd ->
                    try {
                        val pending = bd.getLatestPendingBriefing()
                        if (pending != null) {
                            bd.recordBriefingOutcome(
                                deliveryId = pending.id,
                                actualSales = amount,
                                actualProfit = profit,
                                adviceFollowed = null
                            )
                        }
                    } catch (_: Throwable) {}
                }
            }

            // Gamification + variable rewards
            val gamificationMessages = collectGamificationMessages(language)

            // Aha moment check
            val ahaPrompt = ahaMomentFlow?.onSaleRecorded(language)
            if (ahaPrompt != null) {
                gamificationMessages.add(ahaPrompt.getText(language))
            }

            val baseText = if (language == "sw") {
                "✅ Umeuza ${item} x${quantity.toInt()}, KSh ${"%.0f".format(amount)}. " +
                "Faida: KSh ${"%.0f".format(profit)}"
            } else {
                "✅ Sold ${item} x${quantity.toInt()}, KSh ${"%.0f".format(amount)}. " +
                "Profit: KSh ${"%.0f".format(profit)}"
            }

            val fullText = if (gamificationMessages.isNotEmpty()) {
                baseText + "\n\n" + gamificationMessages.joinToString("\n")
            } else baseText

            AgentResponse(
                text = fullText,
                type = ResponseType.CONFIRMATION,
                data = mapOf(
                    "transactionId" to transaction.id.toString(),
                    "item" to item,
                    "quantity" to quantity.toString(),
                    "amount" to amount.toString(),
                    "profit" to profit.toString()
                )
            )
        } catch (e: Throwable) {
            Timber.e(e, "Error recording sale: %s x%.0f @ %.0f", item, quantity, amount)
            AgentResponse(
                text = if (language == "sw") "⚠️ Imeshindikana kurekodi mauzo. Jaribu tena."
                else "⚠️ Failed to record sale. Please try again.",
                type = ResponseType.ERROR
            )
        }
    }

    /**
     * Handle purchase recording.
     * **ECO 201 §1.2:** Purchases increase capital stock and update weighted average cost.
     */
    suspend fun handlePurchase(intentResult: IntentResult, language: String): AgentResponse {
        val item = intentResult.extractedData["item"] ?: return AgentResponse(
            text = if (language == "sw") "Ni bidhaa gani?" else "What item?",
            type = ResponseType.CLARIFICATION
        )

        val quantity = intentResult.extractedData["quantity"]?.toDoubleOrNull() ?: 1.0
        val amount = intentResult.extractedData["amount"]?.toDoubleOrNull() ?: return AgentResponse(
            text = if (language == "sw") "Bei ni ngapi?" else "What price?",
            type = ResponseType.CLARIFICATION
        )

        return try {
            val transaction = businessAgent.recordPurchase(item, quantity, amount, language)
            lastTransaction = transaction
            adaptiveLearning.learnFromTransaction(transaction)

            AgentResponse(
                text = if (language == "sw") {
                    "✅ Umenunua ${item} x${quantity.toInt()}, KSh ${"%.0f".format(amount)}"
                } else {
                    "✅ Bought ${item} x${quantity.toInt()}, KSh ${"%.0f".format(amount)}"
                },
                type = ResponseType.CONFIRMATION,
                data = mapOf(
                    "transactionId" to transaction.id.toString(),
                    "item" to item,
                    "quantity" to quantity.toString(),
                    "amount" to amount.toString()
                )
            )
        } catch (e: Throwable) {
            Timber.e(e, "Error recording purchase: %s x%.0f @ %.0f", item, quantity, amount)
            AgentResponse(
                text = if (language == "sw") "⚠️ Imeshindikana kurekodi ununuzi. Jaribu tena."
                else "⚠️ Failed to record purchase. Please try again.",
                type = ResponseType.ERROR
            )
        }
    }

    /**
     * Handle expense recording.
     * **ECO 101 §1.4:** Expenses are fixed or variable costs that reduce profit.
     */
    suspend fun handleExpense(intentResult: IntentResult, language: String): AgentResponse {
        val rawCategory = intentResult.extractedData["category"] ?: "other"

        // F3: Auto-detect "mixed" category for business/personal edge cases
        // Keywords that indicate personal use even in a business context
        val personalKeywords = setOf(
            "personal", "family", "home", "school", "medical",
            "health", "funeral", "wedding", "church", "posho",
            "bima", "kodi", "nyumba", "shule", "hospitali"
        )
        val businessKeywords = setOf(
            "stock", "supplier", "transport", "delivery", "rent",
            "mali", "gharama", "usafiri", "pikipiki", "bodaboda"
        )
        val categoryLower = rawCategory.lowercase()
        val isPersonal = personalKeywords.any { categoryLower.contains(it) }
        val isBusiness = businessKeywords.any { categoryLower.contains(it) }
        val category = if (isPersonal && isBusiness) "mixed" else rawCategory

        val amount = intentResult.extractedData["amount"]?.toDoubleOrNull() ?: return AgentResponse(
            text = if (language == "sw") "Ni pesa ngapi?" else "How much?",
            type = ResponseType.CLARIFICATION
        )

        return try {
            val transaction = businessAgent.recordExpense(category, amount, language = language)
            AgentResponse(
                text = if (language == "sw") {
                    "✅ Umerekodi matumizi: $category, KSh ${"%.0f".format(amount)}"
                } else {
                    "✅ Recorded expense: $category, KSh ${"%.0f".format(amount)}"
                },
                type = ResponseType.CONFIRMATION,
                data = mapOf(
                    "transactionId" to transaction.id.toString(),
                    "category" to category,
                    "amount" to amount.toString()
                )
            )
        } catch (e: Throwable) {
            Timber.e(e, "Error recording expense: %s %.0f", category, amount)
            AgentResponse(
                text = if (language == "sw") "⚠️ Imeshindikana kurekodi matumizi. Jaribu tena."
                else "⚠️ Failed to record expense. Please try again.",
                type = ResponseType.ERROR
            )
        }
    }

    /**
     * Collect gamification messages from all gamification subsystems.
     */
    private suspend fun collectGamificationMessages(language: String): MutableList<String> {
        val messages = mutableListOf<String>()
        gamificationEngine?.let { ge ->
            try {
                val event = ge.onSaleRecorded(language)
                messages.addAll(event.messages)

                streakProtectionLoop?.let { spl ->
                    try { spl.checkStreakMilestone(language)?.let { messages.add(it) } } catch (_: Throwable) {}
                }

                variableRewardsLoop?.let { vrl ->
                    try { vrl.evaluateReward(RewardAction.SALE, language)?.let { messages.add(it.message) } } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }

        richHabitsScore?.let { rhs ->
            try {
                rhs.autoCompleteFromAction("sale", language).forEach { messages.add(it.message) }
            } catch (_: Throwable) {}
        }

        return messages
    }
}
