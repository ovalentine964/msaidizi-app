package com.msaidizi.app.agent

import com.msaidizi.app.gamification.GamificationEngine
import com.msaidizi.app.onboarding.AhaMomentFlow
import com.msaidizi.app.mindset.RichHabitsScore
import com.msaidizi.app.mindset.MindsetAcademy
import com.msaidizi.app.cfo.BriefingDelivery
import com.msaidizi.app.loops.MorningBriefingLoop
import com.msaidizi.app.loops.StreakProtectionLoop
import timber.log.Timber

/**
 * Handles giving/tithing, goal planning, and loan management intents.
 *
 * Extracted from Orchestrator for Single Responsibility.
 * Each domain (giving, goals, loans) has its own focused handler.
 */
class GamificationHandler(
    private val titheTracker: com.msaidizi.app.finance.TitheTracker? = null,
    private val goalPlanner: com.msaidizi.app.finance.GoalPlanner? = null,
    private val loanManager: com.msaidizi.app.finance.LoanManager? = null,
    private val gamificationEngine: GamificationEngine? = null,
    private val richHabitsScore: RichHabitsScore? = null
) {
    // ═══════════════ GIVING / TITHE ═══════════════

    suspend fun handleGivingRecord(intentResult: IntentResult, language: String, fallback: suspend () -> AgentResponse): AgentResponse {
        val tracker = titheTracker ?: return fallback()
        val text = intentResult.extractedData["originalText"] ?: ""
        val amount = intentResult.extractedData["amount"]?.toDoubleOrNull()

        return try {
            if (amount != null && amount > 0) {
                val record = tracker.parseGivingCommand(text) ?: com.msaidizi.app.finance.TitheTracker.GivingRecord(
                    amount = amount,
                    type = com.msaidizi.app.finance.TitheTracker.GivingType.OFFERING,
                    recipient = "",
                    date = System.currentTimeMillis()
                )
                tracker.recordGiving(record)
                AgentResponse(text = tracker.generateGivingConfirmation(record), type = ResponseType.CONFIRMATION)
            } else {
                AgentResponse(
                    text = if (language == "sw") "Umetoa pesa ngapi?" else "How much did you give?",
                    type = ResponseType.CLARIFICATION
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error recording giving")
            AgentResponse(text = if (language == "sw") "⚠️ Kuna tatizo. Jaribu tena." else "⚠️ Something went wrong.", type = ResponseType.ERROR)
        }
    }

    suspend fun handleGivingQuery(intentResult: IntentResult, language: String, fallback: suspend () -> AgentResponse): AgentResponse {
        val tracker = titheTracker ?: return fallback()
        return try {
            val summary = tracker.getGivingSummary("month")
            AgentResponse(text = tracker.generateSummaryResponse(summary), type = ResponseType.INFORMATION)
        } catch (e: Exception) {
            Timber.e(e, "Error querying giving")
            AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR)
        }
    }

    suspend fun handleGivingGoal(intentResult: IntentResult, language: String, fallback: suspend () -> AgentResponse): AgentResponse {
        val tracker = titheTracker ?: return fallback()
        return try {
            val amount = intentResult.extractedData["amount"]?.toDoubleOrNull()
            if (amount != null && amount > 0) {
                val goal = com.msaidizi.app.finance.TitheTracker.GivingGoal(
                    targetType = com.msaidizi.app.finance.TitheTracker.GivingType.TITHE,
                    targetAmount = amount, period = "monthly"
                )
                tracker.setGivingGoal(goal)
                AgentResponse(
                    text = if (language == "sw") "🎯 Lengo la kutoa: KSh ${"%.0f".format(amount)} kwa mwezi. Mungu akubariki!"
                    else "🎯 Giving goal: KSh ${"%.0f".format(amount)} per month.",
                    type = ResponseType.CONFIRMATION
                )
            } else {
                AgentResponse(text = if (language == "sw") "Lengo ni KSh ngapi?" else "What's the target amount?", type = ResponseType.CLARIFICATION)
            }
        } catch (e: Exception) { AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR) }
    }

    // ═══════════════ GOALS ═══════════════

    suspend fun handleGoalCreate(intentResult: IntentResult, language: String, fallback: suspend () -> AgentResponse): AgentResponse {
        val planner = goalPlanner ?: return fallback()
        val description = intentResult.extractedData["item"] ?: intentResult.extractedData["description"] ?: ""
        val amount = intentResult.extractedData["amount"]?.toDoubleOrNull()

        return try {
            if (description.isNotBlank() && amount != null && amount > 0) {
                val goal = planner.createGoal(description, amount, 0L)
                AgentResponse(
                    text = if (language == "sw") "🎯 Lengo: $description — KSh ${"%.0f".format(amount)}. Twende!"
                    else "🎯 Goal: $description — KSh ${"%.0f".format(amount)}. Let's go!",
                    type = ResponseType.CONFIRMATION
                )
            } else {
                AgentResponse(text = if (language == "sw") "Lengo lako ni nini? Bei ngapi?" else "What's your goal? How much?", type = ResponseType.CLARIFICATION)
            }
        } catch (e: Exception) { Timber.e(e, "Error creating goal"); AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR) }
    }

    suspend fun handleGoalProgress(intentResult: IntentResult, language: String, fallback: suspend () -> AgentResponse): AgentResponse {
        val planner = goalPlanner ?: return fallback()
        val amount = intentResult.extractedData["amount"]?.toDoubleOrNull()
        return try {
            val activeGoals = planner.getActiveGoals()
            val goal = activeGoals.firstOrNull()
            if (goal != null && amount != null && amount > 0) {
                val (updatedGoal, celebration) = planner.updateProgress(goal, amount)
                val percent = (updatedGoal.progress * 100).toInt()
                val baseText = if (language == "sw") "✅ Umefikia $percent% ya lengo lako!" else "✅ You've reached $percent% of your goal!"
                AgentResponse(text = if (celebration != null) baseText + "\n" + celebration.message else baseText, type = ResponseType.CONFIRMATION)
            } else if (goal == null) {
                AgentResponse(text = if (language == "sw") "Huna lengo. Sema 'Lengo langu ni...'" else "No goal set. Say 'My goal is...'", type = ResponseType.CLARIFICATION)
            } else {
                AgentResponse(text = if (language == "sw") "Umetoa KSh ngapi?" else "How much did you save?", type = ResponseType.CLARIFICATION)
            }
        } catch (e: Exception) { Timber.e(e, "Error updating goal progress"); AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR) }
    }

    suspend fun handleGoalReport(language: String): AgentResponse {
        val planner = goalPlanner ?: return AgentResponse(text = if (language == "sw") "Huna malengo bado." else "No goals yet.", type = ResponseType.INFORMATION)
        return try {
            val goals = planner.getAllGoals()
            val report = planner.getGoalReport(goals)
            AgentResponse(text = report.message, type = ResponseType.INFORMATION)
        } catch (e: Exception) { AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR) }
    }

    suspend fun handleGoalTimeForecast(language: String): AgentResponse {
        val planner = goalPlanner ?: return AgentResponse(text = if (language == "sw") "Huna lengo." else "No goal.", type = ResponseType.INFORMATION)
        return try {
            val goal = planner.getActiveGoals().firstOrNull()
            if (goal != null) { AgentResponse(text = planner.getTimeToGoal(goal).message, type = ResponseType.INFORMATION) }
            else { AgentResponse(text = if (language == "sw") "Huna lengo." else "No goal.", type = ResponseType.INFORMATION) }
        } catch (e: Exception) { AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR) }
    }

    suspend fun handleGoalAdjust(intentResult: IntentResult, language: String, fallback: suspend () -> AgentResponse): AgentResponse {
        val planner = goalPlanner ?: return fallback()
        return try {
            val goal = planner.getActiveGoals().firstOrNull()
            if (goal != null) {
                val amount = intentResult.extractedData["amount"]?.toDoubleOrNull()
                planner.adjustGoal(goal, newTarget = amount)
                AgentResponse(text = if (language == "sw") "✅ Lengo limesasishwa." else "✅ Goal updated.", type = ResponseType.CONFIRMATION)
            } else { AgentResponse(text = if (language == "sw") "Huna lengo." else "No goal.", type = ResponseType.INFORMATION) }
        } catch (e: Exception) { AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR) }
    }

    suspend fun handleGoalEncouragement(language: String): AgentResponse {
        val planner = goalPlanner ?: return AgentResponse(
            text = if (language == "sw") "Sema 'Lengo langu ni...' kuanza!" else "Say 'My goal is...' to start!",
            type = ResponseType.INFORMATION
        )
        return try {
            val goal = planner.getActiveGoals().firstOrNull()
            if (goal != null) { AgentResponse(text = planner.getEncouragement(goal), type = ResponseType.INFORMATION) }
            else { AgentResponse(text = if (language == "sw") "Anza na lengo! Sema 'Lengo langu ni...'" else "Start with a goal! Say 'My goal is...'", type = ResponseType.INFORMATION) }
        } catch (e: Exception) { AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR) }
    }

    // ═══════════════ LOANS ═══════════════

    suspend fun handleLoanRecord(intentResult: IntentResult, language: String, fallback: suspend () -> AgentResponse): AgentResponse {
        val manager = loanManager ?: return fallback()
        val amount = intentResult.extractedData["amount"]?.toDoubleOrNull()
        val purpose = intentResult.extractedData["item"] ?: intentResult.extractedData["purpose"] ?: "biashara"
        return try {
            if (amount != null && amount > 0) {
                val schedule = manager.generateRepaymentSchedule(amount, 0.15, 3, System.currentTimeMillis() / 1000)
                val loan = com.msaidizi.app.finance.LoanManager.Loan(
                    amount = amount, purpose = purpose, interestRate = 0.15,
                    repaymentSchedule = schedule, startDate = System.currentTimeMillis() / 1000,
                    endDate = System.currentTimeMillis() / 1000 + (90 * 86400),
                    lender = intentResult.extractedData["lender"] ?: "M-Shwari"
                )
                val recorded = manager.recordLoan(loan)
                AgentResponse(
                    text = if (language == "sw") "✅ Mkopo wa KSh ${"%.0f".format(amount)} umerekodiwa. Malipo ya KSh ${"%.0f".format(recorded.totalToRepay / 3)} kwa mwezi."
                    else "✅ Loan of KSh ${"%.0f".format(amount)} recorded. Payments of KSh ${"%.0f".format(recorded.totalToRepay / 3)} monthly.",
                    type = ResponseType.CONFIRMATION
                )
            } else {
                AgentResponse(text = if (language == "sw") "Mkopo ni KSh ngapi?" else "How much is the loan?", type = ResponseType.CLARIFICATION)
            }
        } catch (e: Exception) { Timber.e(e, "Error recording loan"); AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR) }
    }

    suspend fun handleLoanQuery(language: String): AgentResponse {
        val manager = loanManager ?: return AgentResponse(text = if (language == "sw") "Huna mkopo." else "No loans.", type = ResponseType.INFORMATION)
        return try { AgentResponse(text = manager.getRepaymentReminder(), type = ResponseType.INFORMATION) }
        catch (e: Exception) { AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR) }
    }

    suspend fun handleLoanReport(language: String): AgentResponse {
        val manager = loanManager ?: return AgentResponse(text = if (language == "sw") "Huna mkopo." else "No loans.", type = ResponseType.INFORMATION)
        return try { AgentResponse(text = manager.getLoanReport(), type = ResponseType.INFORMATION) }
        catch (e: Exception) { AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR) }
    }

    suspend fun handleLoanDeadline(language: String): AgentResponse {
        val manager = loanManager ?: return AgentResponse(text = if (language == "sw") "Huna mkopo." else "No loans.", type = ResponseType.INFORMATION)
        return try { AgentResponse(text = manager.getRepaymentReminder(), type = ResponseType.INFORMATION) }
        catch (e: Exception) { AgentResponse(text = "⚠️ Kuna tatizo.", type = ResponseType.ERROR) }
    }
}
