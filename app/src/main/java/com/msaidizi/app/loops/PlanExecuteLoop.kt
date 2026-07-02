package com.msaidizi.app.loops

import timber.log.Timber
import java.util.UUID

/**
 * Plan-and-Execute Loop — Multi-step task planning.
 *
 * Instead of executing tasks with no planning, this loop:
 * 1. Plans: Breaks the goal into steps with dependencies
 * 2. Executes: Runs each step, tracking progress
 * 3. Re-plans: If a step fails, creates a revised plan
 * 4. Aggregates: Combines step results into final output
 *
 * ## Theoretical Foundation
 *
 * ### ECO 104 — Mathematics for Economists II
 * - **Optimization (§1.2):** Planning is a constrained optimization problem:
 *   minimize total execution time subject to dependency constraints.
 * - **Dynamic Programming:** Re-planning is like Bellman's principle of
 *   optimality — at each step, choose the action that optimizes the
 *   remaining subproblem.
 *
 * ### MAT 124 — Calculus II
 * - **Sequential Optimization:** Each step is a local optimization,
 *   and the plan ensures global optimality through dependency ordering.
 *
 * ### ECO 315 — Econometrics
 * - **Sequential Testing:** Each step execution is like a sequential
 *   hypothesis test — if it fails, we revise our model (plan) and retry.
 *
 * @see ReActLoop for per-step reasoning traces
 * @see ReflexionLoop for per-step quality critique
 */
data class PlanStep(
    val stepId: String = UUID.randomUUID().toString().take(10),
    val description: String,
    val action: String,
    val parameters: Map<String, Any> = emptyMap(),
    val dependencies: List<String> = emptyList(), // step_ids this depends on
    var status: StepStatus = StepStatus.PENDING,
    var result: Map<String, Any>? = null,
    var error: String? = null
) {
    enum class StepStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        SKIPPED
    }

    fun toMap(): Map<String, Any> = mapOf(
        "stepId" to stepId,
        "description" to description,
        "action" to action,
        "parameters" to parameters.mapValues { it.value.toString().take(200) },
        "dependencies" to dependencies,
        "status" to status.name,
        "result" to (result?.toString()?.take(300) ?: ""),
        "error" to (error ?: "")
    )
}

/**
 * An execution plan with steps and dependency tracking.
 */
data class ExecutionPlan(
    val planId: String = UUID.randomUUID().toString().take(16),
    val goal: String,
    val steps: MutableList<PlanStep> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    var completedAt: Long? = null,
    var status: PlanStatus = PlanStatus.ACTIVE,
    var replanCount: Int = 0
) {
    enum class PlanStatus {
        ACTIVE,
        COMPLETED,
        FAILED,
        REPLANNED
    }

    /**
     * Get the next pending step whose dependencies are all met.
     */
    fun getNextStep(): PlanStep? {
        for (step in steps) {
            if (step.status != PlanStep.StepStatus.PENDING) continue
            val depsMet = step.dependencies.all { depId ->
                val dep = steps.find { it.stepId == depId }
                dep?.status == PlanStep.StepStatus.COMPLETED
            }
            if (depsMet) return step
        }
        return null
    }

    fun markStep(stepId: String, status: PlanStep.StepStatus, result: Map<String, Any>? = null, error: String? = null) {
        steps.find { it.stepId == stepId }?.let {
            it.status = status
            it.result = result
            it.error = error
        }
    }

    fun isComplete(): Boolean = steps.all {
        it.status == PlanStep.StepStatus.COMPLETED || it.status == PlanStep.StepStatus.SKIPPED
    }

    fun hasFailures(): Boolean = steps.any { it.status == PlanStep.StepStatus.FAILED }

    fun progress(): String {
        val completed = steps.count {
            it.status == PlanStep.StepStatus.COMPLETED || it.status == PlanStep.StepStatus.SKIPPED
        }
        return "$completed/${steps.size}"
    }

    fun toMap(): Map<String, Any> = mapOf(
        "planId" to planId,
        "goal" to goal,
        "steps" to steps.map { it.toMap() },
        "createdAt" to createdAt,
        "completedAt" to (completedAt ?: System.currentTimeMillis()),
        "status" to status.name,
        "replanCount" to replanCount,
        "progress" to progress()
    )
}

/**
 * Result of a plan execution.
 */
data class PlanExecutionResult(
    val plan: ExecutionPlan,
    val stepResults: Map<String, Map<String, Any>>,
    val success: Boolean,
    val totalDurationMs: Long
)

/**
 * Plan-and-Execute Loop Manager.
 *
 * Creates multi-step execution plans and runs them with
 * dependency tracking and automatic re-planning on failure.
 *
 * Usage:
 * ```
 * val result = planExecuteLoop.execute(
 *     goal = "Record sale and update inventory",
 *     maxReplans = 2
 * ) {
 *     // Define the plan
 *     listOf(
 *         PlanStep("validate", "Validate sale data", "validate_sale"),
 *         PlanStep("record", "Record transaction", "record_txn", dependencies = listOf("validate")),
 *         PlanStep("update_stock", "Update inventory", "update_stock", dependencies = listOf("record")),
 *         PlanStep("notify", "Send confirmation", "notify", dependencies = listOf("record"))
 *     )
 * } { step ->
 *     // Execute each step
 *     when (step.action) {
 *         "validate_sale" -> validateSale(step.parameters)
 *         "record_txn" -> recordTransaction(step.parameters)
 *         "update_stock" -> updateStock(step.parameters)
 *         "notify" -> sendNotification(step.parameters)
 *         else -> mapOf("success" to false, "error" to "Unknown action")
 *     }
 * }
 * ```
 */
class PlanExecuteLoop(
    private val maxPlanHistory: Int = 50
) {
    private val planHistory = mutableListOf<ExecutionPlan>()

    /**
     * Execute a multi-step plan.
     *
     * @param goal Description of the overall goal
     * @param maxReplans Maximum number of re-planning attempts
     * @param planFn Function to create the execution plan
     * @param executeFn Function to execute each step
     * @return PlanExecutionResult with aggregated results
     */
    suspend fun execute(
        goal: String,
        maxReplans: Int = 2,
        planFn: () -> List<PlanStep>,
        executeFn: suspend (PlanStep) -> Map<String, Any>
    ): PlanExecutionResult {
        val startTime = System.currentTimeMillis()
        var plan = ExecutionPlan(
            goal = goal,
            steps = planFn().toMutableList()
        )
        planHistory.add(plan)

        Timber.d("Plan created: %s with %d steps", goal, plan.steps.size)

        val stepResults = mutableMapOf<String, Map<String, Any>>()

        while (!plan.isComplete()) {
            val step = plan.getNextStep()

            if (step == null) {
                // No executable steps — check if we need to replan
                if (plan.hasFailures() && plan.replanCount < maxReplans) {
                    Timber.d("Plan has failures, replanning (attempt %d)", plan.replanCount + 1)
                    plan = replan(plan, goal)
                    planHistory.add(plan)
                    continue
                } else {
                    Timber.w("Plan stuck: no executable steps and cannot replan")
                    break
                }
            }

            // Execute the step
            plan.markStep(step.stepId, PlanStep.StepStatus.RUNNING)
            Timber.d("Executing step: %s (%s)", step.stepId, step.description)

            try {
                val result = executeFn(step)
                val success = result["success"] as? Boolean ?: true

                if (success) {
                    plan.markStep(step.stepId, PlanStep.StepStatus.COMPLETED, result)
                    stepResults[step.stepId] = result
                    Timber.d("Step completed: %s", step.stepId)
                } else {
                    val error = result["error"] as? String ?: "Unknown error"
                    plan.markStep(step.stepId, PlanStep.StepStatus.FAILED, result, error)
                    stepResults[step.stepId] = result
                    Timber.w("Step failed: %s — %s", step.stepId, error)

                    // Mark dependent steps as skipped
                    skipDependents(plan, step.stepId)
                }
            } catch (e: Exception) {
                plan.markStep(step.stepId, PlanStep.StepStatus.FAILED, error = e.message)
                Timber.e(e, "Step exception: %s", step.stepId)
                skipDependents(plan, step.stepId)
            }
        }

        val duration = System.currentTimeMillis() - startTime
        plan.completedAt = System.currentTimeMillis()
        plan.status = if (plan.isComplete()) ExecutionPlan.PlanStatus.COMPLETED else ExecutionPlan.PlanStatus.FAILED

        // Trim history
        while (planHistory.size > maxPlanHistory) {
            planHistory.removeAt(0)
        }

        Timber.d(
            "Plan completed: %s, success=%b, progress=%s, duration=%dms",
            goal, plan.isComplete(), plan.progress(), duration
        )

        return PlanExecutionResult(
            plan = plan,
            stepResults = stepResults,
            success = plan.isComplete(),
            totalDurationMs = duration
        )
    }

    /**
     * Replan — create a new plan based on the failed plan.
     *
     * Removes failed steps and their dependents, keeps completed steps.
     */
    private fun replan(failedPlan: ExecutionPlan, goal: String): ExecutionPlan {
        val newSteps = failedPlan.steps.map { step ->
            when (step.status) {
                PlanStep.StepStatus.COMPLETED -> step.copy() // Keep completed steps
                PlanStep.StepStatus.FAILED -> step.copy(status = PlanStep.StepStatus.PENDING) // Retry failed
                PlanStep.StepStatus.SKIPPED -> step.copy(status = PlanStep.StepStatus.PENDING) // Retry skipped
                else -> step.copy() // Keep pending/running as-is
            }
        }.toMutableList()

        return ExecutionPlan(
            goal = "$goal (replan #${failedPlan.replanCount + 1})",
            steps = newSteps,
            replanCount = failedPlan.replanCount + 1
        )
    }

    /**
     * Skip all steps that depend on a failed step.
     */
    private fun skipDependents(plan: ExecutionPlan, failedStepId: String) {
        val toSkip = plan.steps.filter { step ->
            step.dependencies.contains(failedStepId) &&
                step.status == PlanStep.StepStatus.PENDING
        }
        toSkip.forEach { step ->
            plan.markStep(step.stepId, PlanStep.StepStatus.SKIPPED, error = "Dependency failed: $failedStepId")
            Timber.d("Skipped step %s (depends on failed %s)", step.stepId, failedStepId)
            // Recursively skip dependents of skipped steps
            skipDependents(plan, step.stepId)
        }
    }

    /**
     * Get recent plans for debugging.
     */
    fun getPlanHistory(n: Int = 10): List<Map<String, Any>> =
        planHistory.takeLast(n).map { it.toMap() }

    /**
     * Get plan history count.
     */
    fun getPlanCount(): Int = planHistory.size

    /**
     * Create a sale recording plan.
     *
     * Standard plan for recording a sale with all side effects.
     */
    fun createSalePlan(item: String, quantity: Double, amount: Double): List<PlanStep> {
        val validateStep = PlanStep(
            stepId = "validate",
            description = "Validate sale data: $item x$quantity @ KSh $amount",
            action = "validate_sale",
            parameters = mapOf("item" to item, "quantity" to quantity, "amount" to amount)
        )
        val recordStep = PlanStep(
            stepId = "record",
            description = "Record transaction in database",
            action = "record_transaction",
            parameters = mapOf("item" to item, "quantity" to quantity, "amount" to amount),
            dependencies = listOf("validate")
        )
        val profitStep = PlanStep(
            stepId = "profit",
            description = "Calculate profit margin",
            action = "calculate_profit",
            parameters = mapOf("item" to item, "amount" to amount),
            dependencies = listOf("record")
        )
        val gamifyStep = PlanStep(
            stepId = "gamify",
            description = "Update gamification (streaks, achievements)",
            action = "update_gamification",
            parameters = emptyMap(),
            dependencies = listOf("record")
        )
        val respondStep = PlanStep(
            stepId = "respond",
            description = "Generate confirmation response",
            action = "generate_response",
            parameters = mapOf("item" to item, "quantity" to quantity, "amount" to amount),
            dependencies = listOf("profit", "gamify")
        )

        return listOf(validateStep, recordStep, profitStep, gamifyStep, respondStep)
    }

    /**
     * Create a report generation plan.
     */
    fun createReportPlan(period: String, language: String): List<PlanStep> {
        val fetchStep = PlanStep(
            stepId = "fetch",
            description = "Fetch transaction data for $period",
            action = "fetch_transactions",
            parameters = mapOf("period" to period)
        )
        val analyzeStep = PlanStep(
            stepId = "analyze",
            description = "Analyze transaction patterns",
            action = "analyze_data",
            parameters = mapOf("period" to period),
            dependencies = listOf("fetch")
        )
        val formatStep = PlanStep(
            stepId = "format",
            description = "Format report in $language",
            action = "format_report",
            parameters = mapOf("language" to language),
            dependencies = listOf("analyze")
        )

        return listOf(fetchStep, analyzeStep, formatStep)
    }
}
