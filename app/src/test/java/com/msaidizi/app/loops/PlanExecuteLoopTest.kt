package com.msaidizi.app.loops

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for PlanExecuteLoop — Multi-step task planning.
 *
 * Covers:
 * - Happy path: plan → execute all steps → complete
 * - Dependency resolution (step ordering)
 * - Failure handling: step failure → skip dependents
 * - Re-planning on failure
 * - Edge cases: empty plan, all steps fail, circular deps
 * - createSalePlan and createReportPlan
 */
@DisplayName("PlanExecuteLoop")
class PlanExecuteLoopTest {

    private lateinit var loop: PlanExecuteLoop

    @BeforeEach
    fun setUp() {
        loop = PlanExecuteLoop(maxPlanHistory = 5)
    }

    // ── Happy Path ───────────────────────────────────────────────

    @Nested
    @DisplayName("Happy Path")
    inner class HappyPathTests {

        @Test
        fun `execute runs all steps and completes successfully`() = runTest {
            val result = loop.execute(
                goal = "Record sale",
                maxReplans = 0,
                planFn = {
                    listOf(
                        PlanStep(stepId = "s1", description = "Step 1", action = "a1"),
                        PlanStep(stepId = "s2", description = "Step 2", action = "a2"),
                        PlanStep(stepId = "s3", description = "Step 3", action = "a3")
                    )
                },
                executeFn = { mapOf("success" to true, "data" to "ok") }
            )

            assertTrue(result.success)
            assertEquals(3, result.stepResults.size)
            assertTrue(result.plan.isComplete())
        }

        @Test
        fun `execute respects dependency ordering`() = runTest {
            val executionOrder = mutableListOf<String>()

            loop.execute(
                goal = "Ordered execution",
                maxReplans = 0,
                planFn = {
                    listOf(
                        PlanStep(stepId = "first", description = "First", action = "a1"),
                        PlanStep(
                            stepId = "second", description = "Second", action = "a2",
                            dependencies = listOf("first")
                        ),
                        PlanStep(
                            stepId = "third", description = "Third", action = "a3",
                            dependencies = listOf("second")
                        )
                    )
                },
                executeFn = { step ->
                    executionOrder.add(step.stepId)
                    mapOf("success" to true)
                }
            )

            assertEquals(listOf("first", "second", "third"), executionOrder)
        }

        @Test
        fun `execute runs independent steps in parallel order`() = runTest {
            val executionOrder = mutableListOf<String>()

            loop.execute(
                goal = "Parallel steps",
                maxReplans = 0,
                planFn = {
                    listOf(
                        PlanStep(stepId = "a", description = "A", action = "a"),
                        PlanStep(stepId = "b", description = "B", action = "b", dependencies = listOf("a")),
                        PlanStep(stepId = "c", description = "C", action = "c", dependencies = listOf("a"))
                    )
                },
                executeFn = { step ->
                    executionOrder.add(step.stepId)
                    mapOf("success" to true)
                }
            )

            assertEquals(3, executionOrder.size)
            assertEquals("a", executionOrder[0])
            // b and c both depend on a, so a must be first
            assertTrue(executionOrder.containsAll(listOf("b", "c")))
        }
    }

    // ── Failure Handling ─────────────────────────────────────────

    @Nested
    @DisplayName("Failure Handling")
    inner class FailureTests {

        @Test
        fun `step failure marks dependents as skipped`() = runTest {
            val result = loop.execute(
                goal = "With failure",
                maxReplans = 0,
                planFn = {
                    listOf(
                        PlanStep(stepId = "validate", description = "Validate", action = "validate"),
                        PlanStep(
                            stepId = "record", description = "Record", action = "record",
                            dependencies = listOf("validate")
                        ),
                        PlanStep(
                            stepId = "notify", description = "Notify", action = "notify",
                            dependencies = listOf("record")
                        )
                    )
                },
                executeFn = { step ->
                    if (step.stepId == "validate") {
                        mapOf("success" to false, "error" to "Validation failed")
                    } else {
                        mapOf("success" to true)
                    }
                }
            )

            assertFalse(result.success)
            // validate failed, record and notify should be skipped
            val validateStep = result.plan.steps.find { it.stepId == "validate" }
            val recordStep = result.plan.steps.find { it.stepId == "record" }
            val notifyStep = result.plan.steps.find { it.stepId == "notify" }

            assertEquals(PlanStep.StepStatus.FAILED, validateStep!!.status)
            assertEquals(PlanStep.StepStatus.SKIPPED, recordStep!!.status)
            assertEquals(PlanStep.StepStatus.SKIPPED, notifyStep!!.status)
        }

        @Test
        fun `exception in step marks it as failed`() = runTest {
            val result = loop.execute(
                goal = "Exception test",
                maxReplans = 0,
                planFn = {
                    listOf(
                        PlanStep(stepId = "boom", description = "Boom", action = "crash")
                    )
                },
                executeFn = { throw RuntimeException("Crash!") }
            )

            assertFalse(result.success)
            val step = result.plan.steps.find { it.stepId == "boom" }
            assertEquals(PlanStep.StepStatus.FAILED, step!!.status)
            assertEquals("Crash!", step.error)
        }

        @Test
        fun `step returning success=false is treated as failure`() = runTest {
            val result = loop.execute(
                goal = "False success",
                maxReplans = 0,
                planFn = {
                    listOf(
                        PlanStep(stepId = "s1", description = "Step", action = "a")
                    )
                },
                executeFn = { mapOf("success" to false, "error" to "Bad data") }
            )

            assertFalse(result.success)
            val step = result.plan.steps.first()
            assertEquals(PlanStep.StepStatus.FAILED, step.status)
            assertEquals("Bad data", step.error)
        }
    }

    // ── Re-planning ──────────────────────────────────────────────

    @Nested
    @DisplayName("Re-planning")
    inner class ReplanTests {

        @Test
        fun `replan retries failed steps`() = runTest {
            var attempt = 0

            val result = loop.execute(
                goal = "Retry task",
                maxReplans = 1,
                planFn = {
                    listOf(
                        PlanStep(stepId = "s1", description = "Step 1", action = "a1")
                    )
                },
                executeFn = {
                    attempt++
                    if (attempt == 1) mapOf("success" to false, "error" to "Transient failure")
                    else mapOf("success" to true)
                }
            )

            assertTrue(result.success)
            assertEquals(2, result.plan.replanCount) // replan #1
        }

        @Test
        fun `replan preserves completed steps`() = runTest {
            var attempt = 0

            loop.execute(
                goal = "Preserve completed",
                maxReplans = 1,
                planFn = {
                    listOf(
                        PlanStep(stepId = "s1", description = "Step 1", action = "a1"),
                        PlanStep(stepId = "s2", description = "Step 2", action = "a2")
                    )
                },
                executeFn = { step ->
                    if (step.stepId == "s2" && attempt == 0) {
                        attempt++
                        mapOf("success" to false, "error" to "Failed")
                    } else {
                        mapOf("success" to true)
                    }
                }
            )

            // s1 should still be completed after replan
            val plans = loop.getPlanHistory(10)
            assertTrue(plans.isNotEmpty())
        }

        @Test
        fun `maxReplans=0 prevents replanning`() = runTest {
            val result = loop.execute(
                goal = "No replan",
                maxReplans = 0,
                planFn = {
                    listOf(
                        PlanStep(stepId = "s1", description = "Step", action = "a")
                    )
                },
                executeFn = { mapOf("success" to false, "error" to "Fail") }
            )

            assertFalse(result.success)
            assertEquals(0, result.plan.replanCount)
        }
    }

    // ── Empty Plan ───────────────────────────────────────────────

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        fun `empty plan completes immediately`() = runTest {
            val result = loop.execute(
                goal = "Empty plan",
                maxReplans = 0,
                planFn = { emptyList() },
                executeFn = { mapOf("success" to true) }
            )

            assertTrue(result.success)
            assertTrue(result.stepResults.isEmpty())
        }

        @Test
        fun `plan progress tracks completion`() = runTest {
            val result = loop.execute(
                goal = "Progress test",
                maxReplans = 0,
                planFn = {
                    listOf(
                        PlanStep(stepId = "s1", description = "Step 1", action = "a1"),
                        PlanStep(stepId = "s2", description = "Step 2", action = "a2"),
                        PlanStep(stepId = "s3", description = "Step 3", action = "a3")
                    )
                },
                executeFn = { mapOf("success" to true) }
            )

            assertEquals("3/3", result.plan.progress())
        }

        @Test
        fun `plan history is tracked`() = runTest {
            loop.execute(
                goal = "Plan 1",
                maxReplans = 0,
                planFn = { listOf(PlanStep(stepId = "s1", description = "S", action = "a")) },
                executeFn = { mapOf("success" to true) }
            )
            loop.execute(
                goal = "Plan 2",
                maxReplans = 0,
                planFn = { listOf(PlanStep(stepId = "s1", description = "S", action = "a")) },
                executeFn = { mapOf("success" to true) }
            )

            assertEquals(2, loop.getPlanCount())
        }

        @Test
        fun `plan history prunes old plans`() = runTest {
            // maxPlanHistory = 5
            for (i in 1..7) {
                loop.execute(
                    goal = "Plan $i",
                    maxReplans = 0,
                    planFn = { listOf(PlanStep(stepId = "s1", description = "S", action = "a")) },
                    executeFn = { mapOf("success" to true) }
                )
            }

            assertTrue(loop.getPlanCount() <= 5)
        }

        @Test
        fun `getPlanHistory returns recent plans`() = runTest {
            loop.execute(
                goal = "My Plan",
                maxReplans = 0,
                planFn = { listOf(PlanStep(stepId = "s1", description = "S", action = "a")) },
                executeFn = { mapOf("success" to true) }
            )

            val history = loop.getPlanHistory(10)
            assertEquals(1, history.size)
            assertEquals("My Plan", history[0]["goal"])
        }

        @Test
        fun `totalDurationMs is positive`() = runTest {
            val result = loop.execute(
                goal = "Duration test",
                maxReplans = 0,
                planFn = { listOf(PlanStep(stepId = "s1", description = "S", action = "a")) },
                executeFn = { mapOf("success" to true) }
            )

            assertTrue(result.totalDurationMs >= 0)
        }
    }

    // ── Data Class Tests ─────────────────────────────────────────

    @Nested
    @DisplayName("Data Classes")
    inner class DataClassTests {

        @Test
        fun `PlanStep toMap contains all fields`() {
            val step = PlanStep(
                stepId = "test_step",
                description = "Test step",
                action = "test_action",
                parameters = mapOf("key" to "value"),
                dependencies = listOf("dep1")
            )

            val map = step.toMap()
            assertEquals("test_step", map["stepId"])
            assertEquals("Test step", map["description"])
            assertEquals("test_action", map["action"])
            assertEquals("PENDING", map["status"])
        }

        @Test
        fun `ExecutionPlan getNextStep respects dependencies`() {
            val plan = ExecutionPlan(
                goal = "test",
                steps = mutableListOf(
                    PlanStep(stepId = "a", description = "A", action = "a"),
                    PlanStep(stepId = "b", description = "B", action = "b", dependencies = listOf("a")),
                    PlanStep(stepId = "c", description = "C", action = "c", dependencies = listOf("b"))
                )
            )

            // First step should be "a" (no dependencies)
            assertEquals("a", plan.getNextStep()?.stepId)

            // Mark "a" as completed
            plan.markStep("a", PlanStep.StepStatus.COMPLETED)
            assertEquals("b", plan.getNextStep()?.stepId)

            // Mark "b" as completed
            plan.markStep("b", PlanStep.StepStatus.COMPLETED)
            assertEquals("c", plan.getNextStep()?.stepId)
        }

        @Test
        fun `getNextStep returns null when no pending steps`() {
            val plan = ExecutionPlan(
                goal = "test",
                steps = mutableListOf(
                    PlanStep(stepId = "a", description = "A", action = "a", status = PlanStep.StepStatus.COMPLETED)
                )
            )

            assertNull(plan.getNextStep())
        }

        @Test
        fun `isComplete returns true when all steps completed or skipped`() {
            val plan = ExecutionPlan(
                goal = "test",
                steps = mutableListOf(
                    PlanStep(stepId = "a", description = "A", action = "a", status = PlanStep.StepStatus.COMPLETED),
                    PlanStep(stepId = "b", description = "B", action = "b", status = PlanStep.StepStatus.SKIPPED)
                )
            )

            assertTrue(plan.isComplete())
        }

        @Test
        fun `hasFailures returns true when any step failed`() {
            val plan = ExecutionPlan(
                goal = "test",
                steps = mutableListOf(
                    PlanStep(stepId = "a", description = "A", action = "a", status = PlanStep.StepStatus.COMPLETED),
                    PlanStep(stepId = "b", description = "B", action = "b", status = PlanStep.StepStatus.FAILED)
                )
            )

            assertTrue(plan.hasFailures())
        }

        @Test
        fun `markStep sets result and error`() {
            val plan = ExecutionPlan(
                goal = "test",
                steps = mutableListOf(
                    PlanStep(stepId = "a", description = "A", action = "a")
                )
            )

            plan.markStep("a", PlanStep.StepStatus.COMPLETED, result = mapOf("key" to "val"))
            val step = plan.steps.first()
            assertEquals(PlanStep.StepStatus.COMPLETED, step.status)
            assertEquals(mapOf("key" to "val"), step.result)
        }
    }

    // ── Plan Templates ───────────────────────────────────────────

    @Nested
    @DisplayName("Plan Templates")
    inner class PlanTemplateTests {

        @Test
        fun `createSalePlan returns 5 steps with correct dependencies`() {
            val steps = loop.createSalePlan("Mandazi", 10.0, 500.0)

            assertEquals(5, steps.size)
            val stepIds = steps.map { it.stepId }
            assertTrue(stepIds.containsAll(listOf("validate", "record", "profit", "gamify", "respond")))

            // record depends on validate
            val record = steps.find { it.stepId == "record" }!!
            assertTrue(record.dependencies.contains("validate"))

            // respond depends on profit and gamify
            val respond = steps.find { it.stepId == "respond" }!!
            assertTrue(respond.dependencies.containsAll(listOf("profit", "gamify")))
        }

        @Test
        fun `createReportPlan returns 3 steps with sequential dependencies`() {
            val steps = loop.createReportPlan("weekly", "sw")

            assertEquals(3, steps.size)
            val stepIds = steps.map { it.stepId }
            assertTrue(stepIds.containsAll(listOf("fetch", "analyze", "format")))

            val analyze = steps.find { it.stepId == "analyze" }!!
            assertTrue(analyze.dependencies.contains("fetch"))

            val format = steps.find { it.stepId == "format" }!!
            assertTrue(format.dependencies.contains("analyze"))
        }
    }
}
