# Harness Concept in Msaidizi Android App — Research Report

**Date:** 2026-07-14  
**Scope:** How the AI "harness" concept applies to Msaidizi's architecture  

---

## 1. WHAT IS AN AGENT HARNESS?

### Definition (2026 Industry Consensus)

An **Agent Harness** is the infrastructure that **wraps around** an AI model/agent to manage long-running tasks. It is NOT the agent itself — it is the software system that **governs** how the agent operates, ensuring reliability, efficiency, and steerability.

> "The harness provides prompt presets, opinionated handling for tool calls, lifecycle hooks, and ready-to-use capabilities like planning, filesystem access, or sub-agent management."  
> — Philipp Schmid, "The importance of Agent Harness in 2026"

### The Computer Analogy

| Component | Role |
|-----------|------|
| **Model** | CPU — raw processing power |
| **Context Window** | RAM — limited volatile working memory |
| **Agent Harness** | Operating System — curates context, handles boot sequence, provides drivers |
| **Agent** | Application — specific user logic |

### Key Harness Responsibilities

1. **Control execution** — timeouts, retries, fallback chains
2. **Monitor performance** — latency, accuracy, cost per call
3. **Handle failures** — graceful degradation, circuit breakers
4. **Route between models** — task-aware model selection
5. **Collect telemetry** — reasoning chains, cost attribution
6. **Context engineering** — compaction, state offloading, sub-agent isolation
7. **Safety & validation** — output sanitization, HITL gates, trust scoring

### Reference Implementations

- **Claude Code** (Anthropic) — coding agent harness with Claude Agent SDK
- **DeerFlow** (ByteDance) — open-source "SuperAgent harness" for research, coding, creation
- **Temporal Sandbox** — orchestration harness for long-running agent workflows
- **Microsoft Agent Framework** (Build 2026) — enterprise agent harness

---

## 2. EXISTING HARNESS PATTERNS IN Msaidizi

Msaidizi **already has significant harness infrastructure** — it just doesn't call it that. Here's the mapping:

### 2.1 ModelRouter.kt — **Model Execution Harness** ✅

**File:** `app/src/main/java/com/msaidizi/app/agent/ModelRouter.kt`

This IS a harness. It wraps every model call with:

| Harness Feature | Implementation |
|----------------|---------------|
| **Fallback chains** | on-device → DeepSeek → GPT-nano → Claude → backend |
| **Cost tracking** | Per-user monthly budget ($0.013/user), micro-dollar attribution |
| **Budget enforcement** | Forces on-device when over budget |
| **Task-aware routing** | 14 task types mapped to optimal provider chains |
| **MoE routing** | Mixture-of-Experts gating network (Swarm 7) |
| **Reflexion self-critique** | Wraps inference with quality scoring and retry |
| **Reasoning chain audit** | Every call produces a `ReasoningChain` for debugging |
| **Result caching** | LRU cache to avoid redundant calls |
| **Provider health** | Tracks consecutive failures, auto-disables after 3 failures |
| **Test-time compute** | Scaling thinking tokens based on complexity |

**What makes this a harness:** Every model call is wrapped in monitoring, fallback, cost tracking, and quality validation. The model doesn't know it's being watched — the harness is invisible to the model.

### 2.2 Orchestrator.kt — **Agent Orchestration Harness** ✅

**File:** `app/src/main/java/com/msaidizi/app/agent/Orchestrator.kt`

This IS a harness. It wraps agent execution with:

| Harness Feature | Implementation |
|----------------|---------------|
| **Intent classification** | `IntentRouter` classifies before any handler runs |
| **Confidence-based routing** | LOW → clarify, MEDIUM → confirm, HIGH → execute |
| **LLM escalation** | Code-first (90%), LLM fallback when `needsLLM=true` |
| **ReAct traces** | Every `processInput` produces a full reasoning trace |
| **Reflexion critiques** | Post-response self-critique with scoring |
| **Output sanitization** | 10-layer defense-in-depth (OWASP-based) |
| **Error handling** | try-catch with OOM-specific handling |
| **Voice personality** | Cultural warmth layer applied AFTER sanitization |
| **Autonomy recording** | Every outcome feeds into `ProgressiveAutonomy` |
| **Cross-domain insights** | Knowledge graph generates insights after each interaction |

**What makes this a harness:** The orchestrator doesn't generate responses — it controls *how* responses are generated, validates them, and records outcomes. It's the "operating system" for the agent.

### 2.3 ModelManager.kt — **Model Lifecycle Harness** ✅

**File:** `app/src/main/java/com/msaidizi/app/core/ai/ModelManager.kt`

This IS a harness. It manages model lifecycle:

| Harness Feature | Implementation |
|----------------|---------------|
| **Device-aware selection** | Classifies LOW/MID/HIGH tier, picks optimal model |
| **Memory monitoring** | 30-second polling, auto-unload at 85%/92% RAM |
| **Hot-swap** | Swap models without app restart, with rollback |
| **Performance metrics** | Per-model latency, error rate, P95 tracking |
| **Fallback chain** | Preferred → smaller variant → cloud |
| **Sequential loading** | LOW-tier devices load one model at a time |
| **Auto-unload** | Unloads after 10 min idle under memory pressure |

### 2.4 ProgressiveAutonomy.kt — **Trust Harness** ✅

**File:** `app/src/main/java/com/msaidizi/app/agent/autonomy/ProgressiveAutonomy.kt`

This IS a harness — specifically a **trust harness**:

| Harness Feature | Implementation |
|----------------|---------------|
| **5-level trust framework** | Supervised → Assisted → Delegated → Autonomous → Self-Governing |
| **Per-domain tracking** | Sales, Finance, Inventory, Reporting, Advice, Giving |
| **Promotion criteria** | Accuracy ≥ threshold + min interactions + max critical errors |
| **HITL gates** | Approval required based on level + action risk |
| **Automatic demotion** | User can demote, resets accuracy window |
| **Persistence** | State survives app restarts via PatternDao |

### 2.5 Additional Harness Components

| Component | File | Harness Role |
|-----------|------|-------------|
| **ReflexionLoop** | `loops/ReflexionLoop.kt` | Self-critique harness — wraps any output with quality scoring |
| **ReActLoop** | `loops/ReActLoop.kt` | Reasoning trace harness — every decision gets observe/think/act/reflect |
| **OutputSanitizer** | `agent/OutputSanitizer.kt` | Safety harness — 10-layer output validation |
| **InferenceCostTracker** | `agent/cost/InferenceCostTracker.kt` | Cost harness — per-call attribution with user/task breakdowns |
| **MoERouter** | `agent/moe/MoERouter.kt` | Expert routing harness — gating network for model selection |
| **ModelVersionManager** | `agent/version/ModelVersionManager.kt` | Version harness — A/B testing, rollback, upgrade paths |
| **ExpertRegistry** | `agent/moe/ExpertRegistry.kt` | Health harness — tracks expert latency/accuracy |
| **IntentRouter** | `agent/IntentRouter.kt` | Classification harness — code-first routing before any LLM call |

---

## 3. WHAT'S MISSING — HARNESS GAPS

Msaidizi has strong harness primitives but lacks a **unified harness layer**. The patterns are scattered across components.

### 3.1 No Unified Inference Harness

**Current state:** `ModelRouter.infer()` is 200+ lines handling routing, caching, budget, reflexion, MoE, and cost tracking in one method.

**Missing:** A reusable `InferenceHarness` that wraps ANY model call (on-device, cloud, backend) with:
- Standardized timeout/retry
- Uniform telemetry collection
- Consistent fallback behavior
- A/B testing hooks

### 3.2 No Voice Pipeline Harness

**Current state:** STT/TTS is handled by `LlmEngine` and voice components directly.

**Missing:** A `VoicePipelineHarness` that wraps the full STT→LLM→TTS pipeline with:
- STT confidence scoring and fallback
- Latency budget management (user expects <2s response)
- Quality scoring on TTS output
- Graceful degradation (STT fails → text input fallback)

### 3.3 No Learning System Harness

**Current state:** `AdaptiveLearningEngine`, `ConversationLearningPipeline`, and `SelfEvolutionManager` operate independently.

**Missing:** A `LearningHarness` that wraps all learning systems with:
- A/B testing between learning strategies
- Rollback when learning degrades performance
- Validation gates (don't apply learning that reduces accuracy)
- Telemetry on learning effectiveness

### 3.4 No Vision Pipeline Harness

**Current state:** `MultimodalPipeline` handles vision but without quality gates.

**Missing:** A `VisionHarness` that wraps CV operations with:
- Confidence thresholds (reject low-confidence OCR)
- Correction tracking (user fixes → learning signal)
- Fallback chains (on-device vision → cloud vision → manual entry)
- Quality scoring per vision task

### 3.5 No Unified Telemetry Layer

**Current state:** Each component tracks its own metrics (`InferenceCostTracker`, `ModelPerformanceMetrics`, `ExpertRegistry.ExpertHealth`, `ReActLoop` traces).

**Missing:** A central `TelemetryCollector` that aggregates:
- All model call metrics in one place
- Cross-component correlation (intent → model → cost → outcome)
- Exportable dashboards
- Alert thresholds

---

## 4. WHERE HARNESS CAN BE APPLIED — SPECIFIC CODE RECOMMENDATIONS

### 4.1 InferenceHarness (Priority: HIGH)

**What:** A decorator/wrapper around any model call.

```kotlin
// app/src/main/java/com/msaidizi/app/agent/harness/InferenceHarness.kt

class InferenceHarness(
    private val costTracker: InferenceCostTracker,
    private val telemetry: TelemetryCollector
) {
    /**
     * Wrap any model call with monitoring, timeout, fallback, and telemetry.
     */
    suspend fun <T> execute(
        operation: String,
        providerId: String,
        timeoutMs: Long = 15_000,
        fallback: (suspend () -> T)? = null,
        block: suspend () -> T
    ): HarnessResult<T> {
        val startTime = System.currentTimeMillis()
        var attempt = 0
        var lastError: Exception? = null

        // Try with timeout
        try {
            val result = withTimeout(timeoutMs) {
                block()
            }
            val latencyMs = System.currentTimeMillis() - startTime
            telemetry.record(operation, providerId, latencyMs, success = true)
            return HarnessResult.Success(result, latencyMs, attempt)
        } catch (e: TimeoutCancellationException) {
            lastError = e
            Timber.w("Harness timeout for %s after %dms", operation, timeoutMs)
        } catch (e: Exception) {
            lastError = e
            Timber.w(e, "Harness error for %s", operation)
        }

        // Fallback
        if (fallback != null) {
            try {
                val result = fallback()
                val latencyMs = System.currentTimeMillis() - startTime
                telemetry.record(operation, "$providerId:fallback", latencyMs, success = true)
                return HarnessResult.Fallback(result, latencyMs, lastError)
            } catch (e: Exception) {
                telemetry.record(operation, providerId, System.currentTimeMillis() - startTime, success = false)
                return HarnessResult.Failure(e, lastError)
            }
        }

        telemetry.record(operation, providerId, System.currentTimeMillis() - startTime, success = false)
        return HarnessResult.Failure(lastError!!, null)
    }
}

sealed class HarnessResult<T> {
    data class Success<T>(val value: T, val latencyMs: Long, val attempts: Int) : HarnessResult<T>()
    data class Fallback<T>(val value: T, val latencyMs: Long, val originalError: Exception?) : HarnessResult<T>()
    data class Failure<T>(val error: Exception, val fallbackError: Exception?) : HarnessResult<T>()
}
```

### 4.2 VoicePipelineHarness (Priority: HIGH)

```kotlin
// app/src/main/java/com/msaidizi/app/voice/VoicePipelineHarness.kt

class VoicePipelineHarness(
    private val inferenceHarness: InferenceHarness,
    private val telemetry: TelemetryCollector
) {
    /**
     * Wrap the full STT → LLM → TTS pipeline with quality gates.
     */
    suspend fun processVoiceInput(
        audioData: ByteArray,
        language: String = "sw"
    ): VoiceHarnessResult {
        val pipelineStart = System.currentTimeMillis()

        // Stage 1: STT with confidence check
        val sttResult = inferenceHarness.execute(
            operation = "stt",
            providerId = "whisper-on-device",
            timeoutMs = 5_000
        ) {
            speechToText(audioData, language)
        }

        when (sttResult) {
            is HarnessResult.Failure -> return VoiceHarnessResult.SttFailed(sttResult.error)
            else -> {}
        }

        val transcription = (sttResult as HarnessResult.Success).value
        if (transcription.confidence < 0.5f) {
            return VoiceHarnessResult.LowConfidence(transcription.text, transcription.confidence)
        }

        // Stage 2: LLM processing (delegates to ModelRouter harness)
        // Stage 3: TTS with quality check
        // ... (similar harness wrapping)

        return VoiceHarnessResult.Success(
            text = transcription.text,
            response = "...",
            totalLatencyMs = System.currentTimeMillis() - pipelineStart
        )
    }
}
```

### 4.3 TelemetryCollector (Priority: MEDIUM)

```kotlin
// app/src/main/java/com/msaidizi/app/agent/harness/TelemetryCollector.kt

@Singleton
class TelemetryCollector @Inject constructor() {
    private val records = ArrayDeque<TelemetryRecord>(1000)

    fun record(operation: String, provider: String, latencyMs: Long, success: Boolean, metadata: Map<String, Any> = emptyMap()) {
        records.addLast(TelemetryRecord(operation, provider, latencyMs, success, metadata))
        if (records.size > 1000) records.removeFirst()
    }

    fun getStats(operation: String? = null): TelemetryStats {
        val filtered = if (operation != null) records.filter { it.operation == operation } else records.toList()
        return TelemetryStats(
            totalCalls = filtered.size,
            successRate = filtered.count { it.success }.toDouble() / filtered.size.coerceAtLeast(1),
            avgLatencyMs = filtered.map { it.latencyMs }.average(),
            p95LatencyMs = filtered.sortedBy { it.latencyMs }.getOrNull((filtered.size * 0.95).toInt())?.latencyMs ?: 0
        )
    }
}
```

### 4.4 VisionHarness (Priority: LOW — future)

```kotlin
// app/src/main/java/com/msaidizi/app/agent/harness/VisionHarness.kt

class VisionHarness(
    private val multimodalPipeline: MultimodalPipeline,
    private val inferenceHarness: InferenceHarness
) {
    /**
     * Process an image with confidence thresholds and correction tracking.
     */
    suspend fun processImage(
        imagePath: String,
        taskType: String,
        confidenceThreshold: Float = 0.7f
    ): VisionHarnessResult {
        val result = inferenceHarness.execute(
            operation = "vision:$taskType",
            providerId = "on-device-vision",
            timeoutMs = 10_000,
            fallback = { processImageCloud(imagePath, taskType) }
        ) {
            multimodalPipeline.processImage(imagePath, taskType)
        }

        return when (result) {
            is HarnessResult.Success -> {
                if (result.value.confidence >= confidenceThreshold) {
                    VisionHarnessResult.Success(result.value)
                } else {
                    VisionHarnessResult.LowConfidence(result.value, needsReview = true)
                }
            }
            is HarnessResult.Fallback -> VisionHarnessResult.Success(result.value, fromFallback = true)
            is HarnessResult.Failure -> VisionHarnessResult.Failed(result.error)
        }
    }
}
```

---

## 5. BENEFITS FOR Msaidizi

### 5.1 Better Error Handling
- Every model call wrapped in timeout + fallback
- OOM handled gracefully (already exists in Orchestrator, needs standardization)
- Circuit breakers prevent cascading failures

### 5.2 Performance Monitoring
- Latency tracking per provider, per task type
- P95 latency for SLA compliance (voice < 2s)
- Error rate monitoring with alerting

### 5.3 A/B Testing Between Models
- `ModelVersionManager` already supports this
- Harness makes it plug-and-play: swap model, track metrics, auto-rollback

### 5.4 Progressive Autonomy
- Already implemented in `ProgressiveAutonomy.kt`
- Harness adds: automatic trust scoring based on telemetry, not just outcome recording

### 5.5 Quality Assurance
- Every output validated by `OutputSanitizer`
- Reflexion loop adds self-critique
- Harness standardizes this across all pipelines

### 5.6 Cost Control
- $0.013/user/month budget already enforced
- Harness adds per-task cost attribution for optimization
- Identify which task types are driving costs

---

## 6. IMPLEMENTATION PLAN

### Phase 1: Extract InferenceHarness (1 week)

**Goal:** Extract the harness pattern from `ModelRouter.infer()` into a reusable wrapper.

| Day | Task |
|-----|------|
| 1 | Create `InferenceHarness` class with timeout/retry/fallback |
| 2 | Create `TelemetryCollector` singleton |
| 3 | Refactor `ModelRouter.infer()` to use `InferenceHarness` |
| 4 | Add harness wrapping to `callOnDevice()`, `callCloud()`, `callBackend()` |
| 5 | Integration tests: verify fallback chains, timeout behavior |

**Fastest path:** Start with `InferenceHarness` — it's the most reusable and touches the most critical code path.

### Phase 2: Voice Pipeline Harness (1 week)

| Day | Task |
|-----|------|
| 1 | Create `VoicePipelineHarness` wrapping STT → LLM → TTS |
| 2 | Add STT confidence thresholds and fallback |
| 3 | Add latency budget management (<2s total) |
| 4 | Add TTS quality scoring |
| 5 | Integration tests with real voice input |

### Phase 3: Unified Telemetry (3 days)

| Day | Task |
|-----|------|
| 1 | Wire `TelemetryCollector` into `ModelRouter`, `Orchestrator`, `ModelManager` |
| 2 | Add cross-component correlation |
| 3 | Add debug dashboard / stats endpoint |

### Phase 4: Learning & Vision Harnesses (future)

Lower priority — current systems work. Add when scaling.

---

## 7. SUMMARY

### Msaidizi's Harness Maturity

| Aspect | Status | Notes |
|--------|--------|-------|
| Model execution harness | ✅ Strong | `ModelRouter` has fallback, cost, MoE, reflexion |
| Agent orchestration harness | ✅ Strong | `Orchestrator` has confidence routing, ReAct, sanitization |
| Model lifecycle harness | ✅ Strong | `ModelManager` has memory monitoring, hot-swap, tiers |
| Trust/autonomy harness | ✅ Strong | `ProgressiveAutonomy` has 5-level trust, HITL gates |
| Unified inference harness | ❌ Missing | Patterns scattered, not reusable |
| Voice pipeline harness | ❌ Missing | No quality gates on STT/TTS |
| Learning harness | ❌ Missing | No A/B testing or rollback for learning |
| Vision harness | ❌ Missing | No confidence thresholds on CV |
| Unified telemetry | ❌ Missing | Metrics siloed per component |

### Key Insight

Msaidizi is **already operating as a harness** — it just doesn't formalize it. The `Orchestrator` IS the operating system, `ModelRouter` IS the driver layer, `ProgressiveAutonomy` IS the security layer. The next step is **extracting these patterns into reusable, testable harness classes** rather than having them embedded in large files.

### Recommendation

**Start with `InferenceHarness`** — it's the highest-leverage refactor. Every model call in the app would benefit from standardized timeout/retry/telemetry. It's also the fastest path: extract from existing `ModelRouter.infer()` code, not writing from scratch.
