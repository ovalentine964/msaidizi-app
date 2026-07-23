# Msaidizi Super Agent Architecture

## Executive Summary

**Problem:** 33+ separate agent classes, 400 Kotlin files, massive constructor injection chains, and a god-class Orchestrator that takes 40+ dependencies. This works on $200 phones but is unsustainable on $50 Tecno/Infinix/Itel devices with 2GB RAM.

**Solution:** Collapse everything into ONE cognitive super agent with a unified cognitive loop, a tool registry, and three-layer memory. The agent has one brain (Qwen 0.8B), one voice (Sherpa-ONNX), and one memory (Hermes L1/L2/L3).

**Result:** ~60% fewer files, ~70% fewer classes, single dependency injection point, sub-second response for 90% of interactions (no LLM needed), and a clear path to on-device learning.

---

## Part 1: Current State Analysis

### 1.1 What Exists

The codebase has **400 Kotlin files** organized into these major subsystems:

#### Agent System (33+ classes)
| Class | Lines | Role | Status |
|-------|-------|------|--------|
| `Orchestrator` | ~600 | God-class coordinator, 40+ constructor deps | ⚠️ Overloaded |
| `IntentRouter` | ~500 | Regex-based intent classification | ✅ Works well |
| `BusinessAgent` | 1097 | Transaction recording, inventory | ✅ Solid |
| `AnalysisAgent` | 660 | Statistical analysis | ✅ Solid |
| `AdvisorAgent` | 569 | Advice generation | ✅ Solid |
| `LearningAgent` | 604 | Pattern learning | ✅ Solid |
| `ConversationManager` | ~500 | Memory, confidence routing, LLM escalation | ⚠️ Too many responsibilities |
| `ConversationMemory` | ~300 | Multi-turn context tracking | ✅ Clean |
| `TransactionHandler` | ~400 | Sale/purchase/expense recording | ✅ Decomposed well |
| `QueryHandler` | ~300 | Balance/profit/stock queries | ✅ Clean |
| `AdviceHandler` | ~200 | Advice/greeting/help | ✅ Clean |
| `GamificationHandler` | ~300 | Goals/loans/giving | ✅ Clean |
| `DomainRouter` | ~100 | Transport/farming/digital/service | ✅ Clean |
| `AdaptiveLearningEngine` | ~400 | On-device learning from corrections | ✅ Solid |
| `MoERouter` | ~300 | Model routing (on-device vs cloud) | ⚠️ Overengineered for 2GB |
| `AGIReadyLayer` | ~400 | Safety boundaries | ⚠️ Premature |
| `ProgressiveAutonomy` | ~300 | Autonomy levels | ⚠️ Premature |
| `A2AProtocol` | ~200 | Agent-to-agent protocol | ❌ Unused on-device |
| `CrossDomainKnowledgeGraph` | ~300 | Knowledge graph | ❌ Too heavy for 2GB |
| `HermesSessionManager` | ~500 | Worker-keyed sessions + skill learning | ✅ Good design |
| `EpisodicMemory` | ~500 | SQLite FTS5 cross-session memory | ✅ Excellent |
| `ProactiveAlertEngine` | ~200 | Anomaly detection, stock-out prediction | ✅ Useful |
| `InferenceHarness` | ~300 | Timeout/retry/circuit breaker | ✅ Good infra |
| `VoicePersonality` | ~200 | Swahili proverbs, cultural warmth | ✅ Nice touch |
| `OutputSanitizer` | ~200 | 10-layer output defense | ✅ Safety critical |

#### Voice Pipeline (20+ classes)
| Class | Role | Status |
|-------|------|--------|
| `VoicePipeline` | Full pipeline orchestrator | ✅ Well-designed mutual exclusion |
| `SpeechRecognizer` | Whisper-based STT | ✅ Works |
| `KokoroTtsEngine` | Best quality TTS | ✅ Good |
| `TextToSpeech` (Piper) | Lightweight TTS fallback | ✅ Essential for 2GB |
| `LlmEngine` | Facade over LlamaCppEngine | ✅ Clean |
| `LlamaCppEngine` | llama.cpp JNI bindings | ✅ Solid |
| `SherpaVoiceEngine` | Sherpa-ONNX integration | ✅ Preferred path |
| `AdaptiveAsrEngine` | Dialect-aware ASR | ✅ Innovative |
| `VoicePipelineHarness` | Quality gates | ✅ Good |
| `StreamingVoicePipeline` | Streaming STT/TTS | ⚠️ Memory-heavy |
| `SpeechToSpeechEngine` | Direct S2S | ❌ Too heavy for 2GB |

#### Memory System (3 layers)
| Layer | Implementation | Status |
|-------|---------------|--------|
| L1: ConversationMemory | In-memory, last 10 turns | ✅ Fast, clean |
| L2: EpisodicMemory | SQLite FTS5, 10K episodes | ✅ Excellent design |
| L3: WorkerProfile | HermesSessionManager, in-memory + Room | ✅ Good |

#### Loops (7 classes)
| Loop | Role | Status |
|------|------|--------|
| ReActLoop | Reasoning + Acting trace | ✅ Lightweight, useful |
| ReflexionLoop | Self-critique | ✅ Good for quality |
| PlanExecuteLoop | Multi-step planning | ⚠️ Rarely triggered |
| OodaLoop | Observe-Orient-Decide-Act | ⚠️ Duplicate of ReAct |
| FeedbackLoop | User feedback | ✅ Simple |
| MorningBriefingLoop | Daily briefings | ✅ Engagement driver |
| StreakProtectionLoop | Gamification streaks | ✅ Retention |

### 1.2 What's Broken

1. **Constructor Hell:** `Orchestrator` takes 40+ dependencies. Even with `dagger.Lazy`, this is fragile. One missing dependency = crash.

2. **Duplicated Reasoning:** ReActLoop, OodaLoop, ReflexionLoop, PlanExecuteLoop all do variations of "think → act → observe." Four loops for one cognitive cycle.

3. **Unused AGI Infrastructure:** A2AProtocol, AGIReadyLayer, ProgressiveAutonomy, CrossDomainKnowledgeGraph — these are designed for multi-agent cloud systems, not a single $50 phone. They add complexity without value.

4. **MoERouter Confusion:** Routes between 5 "experts" but on a 2GB phone, only one expert is available (Qwen 0.8B). The routing logic is dead code for the target device.

5. **Memory Leaks Risk:** GlobalScope.launch in Orchestrator for cross-domain insights. Multiple CoroutineScope instances without统一 lifecycle management.

6. **Voice Pipeline Fragmentation:** 3 separate TTS engines, 2 STT engines, streaming pipeline, S2S engine — all competing for the same 2GB of RAM.

### 1.3 What Works Well (Keep)

1. **IntentRouter** — Regex-based, 0 RAM, handles 90%+ of input without LLM. This is the right design for $50 phones.
2. **EpisodicMemory** — SQLite FTS5 is perfect: zero dependencies, sub-10ms, works offline.
3. **VoicePipeline mutual exclusion** — The Kokoro/Whisper memory management is exactly right.
4. **HermesSessionManager** — Worker-keyed sessions with skill learning is the right abstraction.
5. **ReActLoop** — Lightweight tracing, useful for debugging.
6. **ConversationMemory** — Clean, minimal, does one thing well.
7. **BusinessAgent** — Pure code + SQLite, zero LLM overhead for core operations.

---

## Part 2: Super Agent Design

### 2.1 The Cognitive Loop

One agent. One loop. Seven phases.

```
┌───────────────────────────────────────────────────────────────────┐
│                    SUPER AGENT COGNITIVE LOOP                      │
│                                                                   │
│  ┌─────────┐   ┌──────────┐   ┌─────────┐   ┌──────────┐        │
│  │  INPUT   │──▶│ PERCEIVE │──▶│ REMEMBER│──▶│  REASON  │        │
│  │ (voice/  │   │ (intent  │   │ (L1/L2/ │   │ (rules   │        │
│  │  text)   │   │  + NLU)  │   │  L3)    │   │  or LLM) │        │
│  └─────────┘   └──────────┘   └─────────┘   └──────────┘        │
│                                              │                    │
│                                              │ planSteps()        │
│                                              │ (multi-step        │
│                                              │  intents only)     │
│                                              ▼                    │
│  ┌─────────┐   ┌──────────┐   ┌─────────┐   ┌──────────────┐    │
│  │ OUTPUT   │◀──│  LEARN   │◀──│   ACT   │◀──│  REFLECT*   │    │
│  │ (voice/  │   │ (update  │   │ (tool   │   │ (optional:  │    │
│  │  text)   │   │  memory) │   │  exec)  │   │  self-crit  │    │
│  └─────────┘   └──────────┘   └─────────┘   │  for high-  │    │
│                                              │  stakes)    │    │
│                                              └──────────────┘    │
│                                                                   │
│  * REFLECT phase only activates for:                              │
│    - Financial transactions ≥ KSh 5,000                           │
│    - Loan commitments                                              │
│    - High-value inventory changes                                  │
│    - Multi-step planned intents                                    │
└───────────────────────────────────────────────────────────────────┘
```

Each phase is a **function**, not a class. The loop is a single `suspend fun processInput()` call.

**Per-Phase Metrics (local on-device storage):**
Each cognitive loop phase emits metrics stored locally via Room DB for offline observability:

| Phase | Latency Metric | Success Rate Metric | Error Count Metric | Storage |
|-------|---------------|--------------------|--------------------|---------|
| `perceive` | `phase_perceive_latency_ms` (histogram) | `phase_perceive_success_rate` (%) | `phase_perceive_error_count` (counter) | Room: `phase_metrics` table |
| `remember` | `phase_remember_latency_ms` (histogram) | `phase_remember_success_rate` (%) | `phase_remember_error_count` (counter) | Room: `phase_metrics` table |
| `reason` | `phase_reason_latency_ms` (histogram) | `phase_reason_success_rate` (%) | `phase_reason_error_count` (counter) | Room: `phase_metrics` table |
| `reflect` | `phase_reflect_latency_ms` (histogram) | `phase_reflect_success_rate` (%) | `phase_reflect_error_count` (counter) | Room: `phase_metrics` table |
| `act` | `phase_act_latency_ms` (histogram) | `phase_act_success_rate` (%) | `phase_act_error_count` (counter) | Room: `phase_metrics` table |
| `learn` | `phase_learn_latency_ms` (histogram) | `phase_learn_success_rate` (%) | `phase_learn_error_count` (counter) | Room: `phase_metrics` table |

**Alerting Thresholds (on-device):**

| Phase | Latency Warning | Latency Critical | Success Rate Warning |
|-------|----------------|-----------------|---------------------|
| `perceive` | > 200ms | > 500ms | < 95% |
| `remember` | > 50ms | > 200ms | < 90% |
| `reason` | > 500ms | > 2000ms | < 85% |
| `reflect` | > 1000ms | > 3000ms | < 80% |
| `act` | > 200ms | > 1000ms | < 90% |
| `learn` | > 100ms | > 500ms | < 90% |

Threshold breaches are logged to `metrics_alerts` Room table and surfaced in debug UI.

Phases instrumented: `perceive`, `remember`, `reason`, `reflect`, `act`, `learn`

### 2.2 Architecture: One Agent, Many Tools

```
┌──────────────────────────────────────────────────────────────┐
│                      SuperAgent                               │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐    │
│  │                 Cognitive Engine                       │    │
│  │  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐        │    │
│  │  │Perceive│ │Remember│ │ Reason │ │  Learn │        │    │
│  │  └────────┘ └────────┘ └────────┘ └────────┘        │    │
│  └──────────────────────────────────────────────────────┘    │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐    │
│  │                  Tool Registry                         │    │
│  │  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐        │    │
│  │  │ Record │ │ Query  │ │ Advice │ │ Goal   │        │    │
│  │  │  Sale  │ │Balance │ │  Give  │ │  Set   │        │    │
│  │  └────────┘ └────────┘ └────────┘ └────────┘        │    │
│  │  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐        │    │
│  │  │ Loan   │ │ Stock  │ │Summary │ │Receipt │        │    │
│  │  │ Track  │ │ Check  │ │  Get   │ │  Scan  │        │    │
│  │  └────────┘ └────────┘ └────────┘ └────────┘        │    │
│  └──────────────────────────────────────────────────────┘    │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐    │
│  │               Memory (Hermes L1/L2/L3)                │    │
│  │  L1: ConversationMemory (in-memory, 10 turns)         │    │
│  │  L2: EpisodicMemory (SQLite FTS5, 10K episodes)       │    │
│  │  L3: WorkerProfile (Room, per-worker state)            │    │
│  └──────────────────────────────────────────────────────┘    │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐    │
│  │              Voice Pipeline (Sherpa-ONNX)              │    │
│  │  STT: Whisper Tiny INT4 │ TTS: Piper/Kokoro           │    │
│  │  VAD: Sherpa VAD │ Dialect: AdaptiveASR               │    │
│  └──────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────┘
```

### 2.3 The SuperAgent Class

```kotlin
/**
 * ONE agent to rule them all.
 *
 * Replaces: Orchestrator, BusinessAgent, AnalysisAgent, AdvisorAgent,
 * LearningAgent, ConversationManager, TransactionHandler, QueryHandler,
 * AdviceHandler, GamificationHandler, DomainRouter, MoERouter,
 * AGIReadyLayer, ProgressiveAutonomy, A2AProtocol, and more.
 *
 * The cognitive loop is a single suspend function:
 * INPUT → PERCEIVE → REMEMBER → REASON → [REFLECT*] → ACT → LEARN → OUTPUT
 *
 * * REFLECT is optional — only for high-stakes decisions (financial ≥KSh 5,000,
 *   loan commitments, multi-step planned intents). Integrates ReflexionLoop
 *   self-critique as an inline method, not a separate class.
 *
 * Tools are registered, not injected. The agent discovers tools at runtime.
 * Memory is unified through Hermes L1/L2/L3.
 * Each phase emits Prometheus metrics (latency, success rate, error count).
 */
@Singleton
class SuperAgent @Inject constructor(
    // ── Voice (2 deps) ──
    private val voicePipeline: VoicePipeline,
    private val sherpaEngine: SherpaVoiceEngine,

    // ── Memory (3 deps) ──
    private val conversationMemory: ConversationMemory,      // L1
    private val episodicMemory: EpisodicMemory,              // L2
    private val hermesSession: HermesSessionManager,         // L3

    // ── Reasoning (2 deps) ──
    private val llmEngine: LlmEngine,
    private val reActLoop: ReActLoop,

    // ── Infrastructure (3 deps) ──
    private val intentRouter: IntentRouter,
    private val adaptiveLearning: AdaptiveLearningEngine,
    private val inferenceHarness: InferenceHarness
) {
    // Tool registry — populated at init
    private val tools = mutableMapOf<String, Tool>()

    // Safety — inline, not a separate class
    private val safetyChecker = SafetyChecker()

    // Voice personality — inline
    private val personality = VoicePersonality()

    // Per-phase metrics (Room-backed local storage)
    private val phaseMetrics: PhaseMetrics // Injected via DI, backed by Room DB

    // Active worker context
    private var workerId: String = "anonymous"
    private var language: String = "sw"

    /**
     * Initialize the super agent. Registers all tools.
     * Called once from MsaidiziApp after database is ready.
     */
    fun initialize(db: AppDatabase) {
        // Register tools — these are the old "handlers" as pure functions
        registerTool(RecordSaleTool(db.transactionDao(), db.inventoryDao()))
        registerTool(RecordPurchaseTool(db.transactionDao(), db.inventoryDao()))
        registerTool(RecordExpenseTool(db.transactionDao()))
        registerTool(CheckBalanceTool(db.transactionDao()))
        registerTool(CheckProfitTool(db.transactionDao()))
        registerTool(CheckStockTool(db.inventoryDao()))
        registerTool(DailySummaryTool(db.transactionDao()))
        registerTool(WeeklySummaryTool(db.transactionDao()))
        registerTool(AdviceTool(this))
        registerTool(GreetingTool())
        registerTool(HelpTool())
        registerTool(CorrectionTool(adaptiveLearning))
        registerTool(RecordGoalTool(db.goalDao()))
        registerTool(CheckGoalTool(db.goalDao()))
        registerTool(RecordLoanTool(db.loanDao()))
        registerTool(CheckLoanTool(db.loanDao()))
        registerTool(RecordGivingTool(db.titheDao()))
        registerTool(ReceiptScanTool())
        // Domain-specific tools
        registerTool(TransportTool(db.transactionDao()))
        registerTool(FarmingTool(db.transactionDao()))
        registerTool(DigitalTool(db.transactionDao()))
        registerTool(ServiceTool(db.transactionDao()))
    }

    /**
     * THE cognitive loop. One function. One flow. One agent.
     *
     * INPUT → PERCEIVE → REMEMBER → REASON → [REFLECT*] → ACT → LEARN → OUTPUT
     *
     * * REFLECT is optional — activates for high-stakes decisions.
     *   Integrates ReflexionLoop self-critique as an inline method.
     *
     * Each phase emits Prometheus metrics: latency_ms, success_total, error_total.
     */
    suspend fun processInput(
        audioData: ByteArray? = null,
        textInput: String? = null,
        workerId: String = "anonymous",
        language: String = "sw"
    ): AgentResponse {
        this.workerId = workerId
        this.language = language

        val trace = reActLoop.startTrace("cognitive_loop")

        // ═══════════════ PHASE 1: INPUT ═══════════════
        val input = when {
            audioData != null -> {
                reActLoop.observe(trace, "Voice input received (${audioData.size} bytes)")
                voicePipeline.transcribe(audioData)
            }
            textInput != null -> {
                reActLoop.observe(trace, "Text input: '${textInput.take(50)}'")
                TranscriptionResult(text = textInput, confidence = 1.0f, success = true)
            }
            else -> return AgentResponse(
                text = "Sikuskia. Sema tena.",
                type = ResponseType.ERROR
            )
        }

        if (!input.success || input.text.isBlank()) {
            return AgentResponse(
                text = "Sikuskia vizuri. Sema tena taratibu.",
                type = ResponseType.CLARIFICATION
            )
        }

        val rawText = input.text
        reActLoop.think(trace, "Input: '$rawText' (confidence=${input.confidence})")

        // ═══════════════ PHASE 2: PERCEIVE ═══════════════
        val perceiveStart = System.currentTimeMillis()
        try {
            // Intent classification + context resolution + dialect normalization
            val enhancedText = adaptiveLearning.applyToTranscription(rawText)
            var intent = intentRouter.classify(enhancedText)
            reActLoop.think(trace, "Intent: ${intent.intent} (${String.format("%.2f", intent.confidence)})")

            // Correction check
            val correction = checkForCorrection(enhancedText, intent)
            if (correction != null) {
                reActLoop.act(trace, "correction", "Correction detected")
                phaseMetrics.recordPhase("perceive", System.currentTimeMillis() - perceiveStart, true)
                return correction
            }

            // Follow-up resolution using L1 memory
            if (conversationMemory.isFollowUp(enhancedText)) {
                intent = conversationMemory.resolveReferences(enhancedText, intent)
                reActLoop.think(trace, "Resolved follow-up → ${intent.intent}")
            }

            // Adaptive learning enhancement
            intent = adaptiveLearning.enhanceIntentWithLearning(intent, enhancedText)
            phaseMetrics.recordPhase("perceive", System.currentTimeMillis() - perceiveStart, true)

            // ═══════════════ PHASE 3: REMEMBER ═══════════════
            val rememberStart = System.currentTimeMillis()
            // Query L2 episodic memory for relevant past episodes
            val l2Context = try {
                val episodes = episodicMemory.search(enhancedText, workerId, limit = 3)
                episodes.forEach { episodicMemory.boostRelevance(it.id, 0.05) }
                if (episodes.isNotEmpty()) {
                    reActLoop.observe(trace, "L2: ${episodes.size} relevant episodes found")
                }
                episodes
            } catch (e: Throwable) {
                Timber.w(e, "L2 query failed")
                emptyList()
            }

            // Inject L2 context into L1
            l2Context.forEach { ep ->
                conversationMemory.addTurn(
                    speaker = "l2_memory",
                    text = "[Past] Q: ${ep.query.take(80)} A: ${ep.response.take(80)}",
                    intent = null,
                    extractedData = mapOf("source" to "episodic_memory")
                )
            }
            phaseMetrics.recordPhase("remember", System.currentTimeMillis() - rememberStart, true)

            // ═══════════════ PHASE 4: REASON ═══════════════
            val reasonStart = System.currentTimeMillis()
            // Lightweight planning for multi-step intents
            // Activates when input contains coordination markers:
            //   compare + suggest, analyze + recommend, check + record + set,
            //   first + then, show + tell, etc.
            val plan = if (isMultiStepIntent(enhancedText, intent)) {
                reActLoop.think(trace, "Multi-step intent detected — planning")
                planSteps(enhancedText, intent, l2Context)
            } else null

            // Decision: code-only (90%) or LLM escalation (10%)
            // For planned intents, the first step drives the primary tool selection;
            // remaining steps are executed sequentially during ACT.
            var response = if (plan != null && plan.isNotEmpty()) {
                // Plan drives reasoning — use the first step's tool as primary
                val firstStep = plan.first()
                reActLoop.act(trace, "planned_reasoning", "Plan: ${plan.size} steps, first=${firstStep.toolName}")
                ReasoningDecision(
                    text = "",
                    type = ResponseType.CONFIRMATION,
                    toolName = firstStep.toolName,
                    toolArgs = firstStep.args
                )
            } else if (intent.needsLLM || intent.confidence < 0.5) {
                reActLoop.act(trace, "llm_reasoning", "Escalating to Qwen 0.8B")
                reasonWithLlm(enhancedText, intent, l2Context)
            } else {
                reActLoop.act(trace, "code_reasoning", "Rules-based: ${intent.intent}")
                reasonWithCode(intent)
            }
            phaseMetrics.recordPhase("reason", System.currentTimeMillis() - reasonStart, true)

            // ═══════════════ PHASE 4.5: REFLECT (optional) ═══════════════
            // Self-critique for high-stakes decisions — integrates ReflexionLoop
            if (shouldReflect(response, intent)) {
                val reflectStart = System.currentTimeMillis()
                try {
                    response = reflectOnDecision(response, intent, enhancedText, l2Context)
                    reActLoop.think(trace, "Reflexion: self-critique applied")
                    phaseMetrics.recordPhase("reflect", System.currentTimeMillis() - reflectStart, true)
                } catch (e: Throwable) {
                    Timber.w(e, "Reflexion failed — proceeding with original decision")
                    phaseMetrics.recordPhase("reflect", System.currentTimeMillis() - reflectStart, false)
                }
            }

    // ═══════════════ PHASE 5: ACT ═══════════════
            val actStart = System.currentTimeMillis()
            // Execute the tool (if the reasoning selected one)
            // For multi-step plans: execute all steps sequentially, passing
            // each step's result as context to downstream dependents.
            val toolResult = if (plan != null && plan.size > 1) {
                // Multi-step execution: run full plan
                reActLoop.act(trace, "plan_exec", "Executing ${plan.size}-step plan")
                val planResult = executePlan(plan, trace)
                // The plan result becomes the tool result
                ToolResult(
                    text = planResult.finalText,
                    type = if (planResult.success) ResponseType.CONFIRMATION else ResponseType.ERROR,
                    data = planResult.data
                )
            } else {
                // Single-step execution (normal path)
                response.toolName?.let { toolName ->
                    val tool = tools[toolName]
                    if (tool != null) {
                        reActLoop.act(trace, "tool_exec", "Executing: $toolName")
                        try {
                            tool.execute(response.toolArgs ?: emptyMap(), language)
                        } catch (e: Throwable) {
                            Timber.e(e, "Tool execution failed: %s", toolName)
                            null
                        }
                    } else null
                }
            }
            phaseMetrics.recordPhase("act", System.currentTimeMillis() - actStart, toolResult != null)

            // ═══════════════ PHASE 6: LEARN ═══════════════
            val learnStart = System.currentTimeMillis()
            // Store in L1, L2, and update L3
            val finalText = toolResult?.text ?: response.text
            val outcome = if (toolResult != null && toolResult.type != ResponseType.ERROR) "success" else "failure"

            // L1: Conversation memory
            conversationMemory.addTurn("worker", enhancedText, intent, intent.extractedData)
            conversationMemory.addTurn("msaidizi", finalText)

            // L2: Episodic memory
            try {
                episodicMemory.storeEpisode(
                    workerId = workerId,
                    query = enhancedText,
                    response = finalText.take(500),
                    outcome = outcome,
                    dialect = language,
                    context = mapOf(
                        "intent" to intent.intent.name,
                        "confidence" to String.format("%.2f", intent.confidence)
                    )
                )
            } catch (e: Throwable) {
                Timber.w(e, "L2 store failed")
            }

            // L3: Update worker profile via Hermes
            hermesSession.getOrCreateSession(workerId).let { session ->
                session.contextWindow.add(ContextEntry(
                    role = "worker", content = enhancedText,
                    timestamp = System.currentTimeMillis()
                ))
                session.contextWindow.add(ContextEntry(
                    role = "msaidizi", content = finalText,
                    timestamp = System.currentTimeMillis(), outcome = outcome
                ))
            }

            // Adaptive learning signals
            adaptiveLearning.recordInteraction(intent, outcome)
            phaseMetrics.recordPhase("learn", System.currentTimeMillis() - learnStart, true)

            // ═══════════════ PHASE 7: OUTPUT ═══════════════
            // Safety check → personality → voice
            val safeText = safetyChecker.check(finalText, language)
            val personalizedText = personality.wrapResponse(safeText, response.type, language)

            reActLoop.complete(trace, outcome == "success", personalizedText)

            return AgentResponse(
                text = personalizedText,
                type = toolResult?.type ?: response.type,
                shouldSpeak = true,
                data = mapOf(
                    "intent" to intent.intent.name,
                    "confidence" to String.format("%.2f", intent.confidence),
                    "tool" to (response.toolName ?: "none"),
                    "l2_hits" to l2Context.size.toString(),
                    "reflected" to shouldReflect(response, intent).toString(),
                    "planned" to (plan != null).toString()
                )
            )
        } catch (e: Throwable) {
            phaseMetrics.recordPhase("perceive", System.currentTimeMillis() - perceiveStart, false)
            throw e
        }
    }

    // ── Reflexion (Optional Self-Critique for High-Stakes) ──

    /**
     * Determines if a decision requires self-critique before execution.
     * Integrates the old ReflexionLoop as an inline method.
     *
     * High-stakes criteria:
     * - Financial transactions ≥ KSh 5,000
     * - Loan commitments
     * - Multi-step planned intents (have a plan)
     * - High-value inventory changes
     */
    private fun shouldReflect(response: ReasoningDecision, intent: IntentResult): Boolean {
        // Financial thresholds
        if (response.toolName in setOf("record_sale", "record_purchase", "record_expense")) {
            val amount = response.toolArgs?.get("amount")?.toDoubleOrNull() ?: 0.0
            if (amount >= 5000.0) return true  // KSh 5,000+ = high-stakes
        }

        // Loan commitments always reflect
        if (response.toolName in setOf("record_loan")) return true

        // Multi-step intents with plans
        if (intent.extractedData.containsKey("_plan")) return true

        return false
    }

    /**
     * Self-critique: evaluate the decision before acting.
     * Uses the LLM to assess risk and suggest alternatives.
     *
     * This replaces the standalone ReflexionLoop.kt — integrated as a method.
     */
    private suspend fun reflectOnDecision(
        originalDecision: ReasoningDecision,
        intent: IntentResult,
        text: String,
        l2Episodes: List<Episode>
    ): ReasoningDecision {
        val critiquePrompt = buildString {
            append("You are a cautious business assistant. ")
            append("A worker wants to: ${intent.intent.name}. ")
            append("Action: ${originalDecision.toolName} with args ${originalDecision.toolArgs}. ")
            if (l2Episodes.isNotEmpty()) {
                append("Recent history: ${l2Episodes.first().query.take(60)} → ${l2Episodes.first().response.take(60)}. ")
            }
            append("Is this action safe and reasonable? Reply SAFE or CONCERN with brief reason.")
        }

        val critique = llmEngine.generateResponse(
            userInput = critiquePrompt,
            context = "",
            language = "en"  // Critique in English for accuracy
        )

        return if (critique.contains("CONCERN", ignoreCase = true)) {
            // Inject caution into response
            val cautionDisclaimer = if (language == "sw") {
                "\n\n⚠️ Kumbuka: Hakiki taarifa hii kabla ya kuendelea."
            } else {
                "\n\n⚠️ Please verify this information before proceeding."
            }
            originalDecision.copy(
                text = (originalDecision.text + cautionDisclaimer).trim(),
                type = ResponseType.CONFIRMATION  // Force confirmation for risky actions
            )
        } else {
            originalDecision  // Decision passes self-critique
        }
    }

    // ── Lightweight Planning for Multi-Step Intents ──
    //
    // Planning activates when the user's input contains coordination markers
    // that signal two or more distinct actions chained together:
    //
    //   Pattern                           Example
    //   ─────────────────────────────     ─────────────────────────────────────
    //   compare + suggest/pendekeza       "Compare this week's sales to last week and suggest pricing changes"
    //   check + record/rekodi             "Check my stock, record any missing items, and set a reorder goal"
    //   show/onyesha + tell/ambia         "Show my profit and tell me if I should increase prices"
    //   first/kwanza + then/kisha         "First check my loans, then record a payment"
    //   analyze/chambua + recommend       "Analyze my expenses and recommend where to cut"
    //
    // The planning process has three stages:
    //
    //   1. DECOMPOSE — LLM breaks the request into 2-4 ordered PlannedSteps,
    //      each mapping to a registered tool with args and dependency edges.
    //
    //   2. SEQUENCE — Steps are topologically sorted by dependsOn so that
    //      any step that needs output from another runs after it.
    //
    //   3. EXECUTE — During the ACT phase, executePlan() runs steps in order.
    //      Each completed step's ToolResult is stored and passed as context
    //      to dependent steps (e.g. advice tool receives prior query results).
    //
    // Plans are lightweight: the LLM call uses a constrained prompt asking for
    // a JSON array, parsed with regex (no JSON library needed). If parsing
    // fails, the request falls through to single-step reasoning.
    //
    // This replaces the standalone PlanExecuteLoop.kt — integrated as methods
    // on SuperAgent. The plan data (PlannedStep, PlanResult) lives alongside
    // ReasoningDecision as a supporting type.

    /**
     * Detects if an intent requires multi-step planning.
     * Returns true when the input contains coordination markers that link
     * two or more distinct actions (compare+suggest, check+record, etc.).
     *
     * Also activates if the IntentRouter tagged the input with _multi_step
     * in extractedData (e.g. via an explicit "do A then B" pattern).
     */
    private fun isMultiStepIntent(text: String, intent: IntentResult): Boolean {
        val multiStepPatterns = listOf(
            // compare ... and ... suggest
            Regex("(?i)(compare|linganisha).*(and|na).*(suggest|pendekeza)", RegexOption.LITERAL),
            // check ... and ... record
            Regex("(?i)(check|angalia).*(and|na).*(record|rekodi)", RegexOption.LITERAL),
            // show ... and ... tell/suggest
            Regex("(?i)(show|onyesha).*(and|na).*(tell|ambia|suggest)", RegexOption.LITERAL),
            // first ... then
            Regex("(?i)(first|kwanza).*(then|kisha)", RegexOption.LITERAL),
            // analyze ... and ... recommend
            Regex("(?i)(analyze|chambua).*(and|na).*(recommend|pendekeza)", RegexOption.LITERAL),
            // check ... record ... set
            Regex("(?i)(check|angalia).*(record|rekodi).*(set|weka)", RegexOption.LITERAL)
        )
        return multiStepPatterns.any { it.containsMatchIn(text) } ||
               intent.extractedData.containsKey("_multi_step")
    }

    /**
     * DECOMPOSE: Break a complex intent into ordered steps.
     * Each step is a tool call with arguments and dependency edges.
     *
     * The LLM is given the user's request and the list of available tools,
     * and returns a JSON array of steps. This is a constrained generation
     * task (small output, structured format) that Qwen 0.8B handles well.
     *
     * Example input: "Compare my sales this week to last week and suggest pricing changes"\     * Example output:
     *   Step 1: weekly_summary {period: "this_week"}
     *   Step 2: weekly_summary {period: "last_week"}   depends_on: []
     *   Step 3: advice {topic: "pricing"}               depends_on: [0, 1]
     *
     * @return Ordered list of PlannedSteps, or empty if decomposition fails
     *         (caller falls back to single-step reasoning).
     */
    private suspend fun planSteps(
        text: String,
        intent: IntentResult,
        l2Episodes: List<Episode>
    ): List<PlannedStep> {
        val planPrompt = buildString {
            append("Break this request into 2-4 ordered steps. ")
            append("Each step must use one of the available tools. ")
            append("Request: $text. ")
            append("Available tools: ${tools.keys.joinToString(", ")}. ")
            append("Reply ONLY as a JSON array: ")
            append("[{\"step\": 1, \"tool\": \"tool_name\", \"args\": {\"key\": \"val\"}, \"depends_on\": [], \"description\": \"what this step does\"}]")
        }

        val planResponse = llmEngine.generateResponse(
            userInput = planPrompt,
            context = "",
            language = "en"
        )

        return try {
            val parsed = parsePlanSteps(planResponse)
            if (parsed.isEmpty()) {
                Timber.w("Plan returned 0 steps — falling back")
            }
            parsed
        } catch (e: Throwable) {
            Timber.w(e, "Plan parsing failed — falling back to single-step")
            emptyList()
        }
    }

    /**
     * Parse the LLM's JSON plan response into PlannedStep objects.
     * Uses lightweight regex parsing — no JSON library dependency.
     * Handles the constrained output format from planSteps() prompt.
     */
    private fun parsePlanSteps(json: String): List<PlannedStep> {
        val steps = mutableListOf<PlannedStep>()
        // Match each step object in the JSON array
        val stepPattern = Regex(
            """"step":\s*(\d+).*?"tool":\s*"([^"]+)".*?(?:"depends_on":\s*\[([^]]*)\])?.*?(?:"description":\s*"([^"]*)")?""",
            RegexOption.DOT_MATCHES_ALL
        )
        stepPattern.findAll(json).forEach { match ->
            val dependsStr = match.groupValues[3].trim()
            val dependsOn = if (dependsStr.isNotEmpty()) {
                dependsStr.split(",").mapNotNull { it.trim().toIntOrNull()?.minus(1) } // Convert to 0-based
            } else emptyList()

            steps.add(PlannedStep(
                order = match.groupValues[1].toInt(),
                toolName = match.groupValues[2],
                args = emptyMap(), // Args populated from intent.extractedData during execution
                dependsOn = dependsOn,
                description = match.groupValues[4].ifEmpty { "Step ${match.groupValues[1]}" }
            ))
        }
        return steps.sortedBy { it.order }
    }

    /**
     * SEQUENCE + EXECUTE: Run a multi-step plan in order.
     *
     * Steps are executed sequentially by their order field. Before executing
     * a step, its dependsOn entries are checked — if any dependency failed,
     * the step is skipped and marked as failed.
     *
     * Each completed step's ToolResult is stored. If a step depends on prior
     * steps, their results are merged into the step's args under the key
     * "_prior_results" as a summary string, so downstream tools (especially
     * the advice tool) can reference what earlier steps found.
     *
     * @return PlanResult with aggregated text, per-step results, and success flag.
     */
    private suspend fun executePlan(
        plan: List<PlannedStep>,
        trace: ReActTrace
    ): PlanResult {
        val stepResults = mutableListOf<ToolResult?>()
        val completedSteps = mutableMapOf<Int, ToolResult>()
        var allSucceeded = true

        for (step in plan) {
            // Check dependencies
            val failedDeps = step.dependsOn.filter { depIdx ->
                depIdx !in completedSteps
            }
            if (failedDeps.isNotEmpty()) {
                reActLoop.observe(trace, "Step ${step.order}: skipping — dependency failed")
                stepResults.add(null)
                allSucceeded = false
                continue
            }

            // Merge prior results into args for dependent steps
            val enrichedArgs = step.args.toMutableMap()
            if (step.dependsOn.isNotEmpty()) {
                val priorSummary = step.dependsOn
                    .mapNotNull { completedSteps[it] }
                    .joinToString("; ") { it.text.take(120) }
                if (priorSummary.isNotEmpty()) {
                    enrichedArgs["_prior_results"] = priorSummary
                }
            }

            // Execute the tool
            val tool = tools[step.toolName]
            if (tool == null) {
                reActLoop.observe(trace, "Step ${step.order}: tool '${step.toolName}' not found")
                stepResults.add(null)
                allSucceeded = false
                continue
            }

            reActLoop.act(trace, "plan_step_${step.order}", "${step.description} (${step.toolName})")
            try {
                val result = tool.execute(enrichedArgs, language)
                stepResults.add(result)
                completedSteps[step.order - 1] = result // 0-based index
                if (result.type == ResponseType.ERROR) {
                    allSucceeded = false
                }
            } catch (e: Throwable) {
                Timber.e(e, "Plan step ${step.order} failed: ${step.toolName}")
                stepResults.add(null)
                allSucceeded = false
            }
        }

        // Aggregate results into a single response
        val finalText = buildString {
            stepResults.filterNotNull().forEachIndexed { idx, result ->
                if (idx > 0) append("\n\n")
                val step = plan[idx]
                if (plan.size > 1) {
                    append("${step.order}. ")
                }
                append(result.text)
            }
        }

        val mergedData = stepResults.filterNotNull()
            .flatMap { it.data.entries }
            .associate { it.key to it.value }
            .plus("plan_steps" to plan.size.toString())
            .plus("plan_success" to allSucceeded.toString())

        return PlanResult(
            steps = plan,
            stepResults = stepResults,
            finalText = finalText,
            success = allSucceeded,
            data = mergedData
        )
    }

    // ── Reasoning Strategies ──

    /**
     * Code-only reasoning: 90% of requests.
     * Maps intent directly to tool. Zero LLM overhead.
     */
    private fun reasonWithCode(intent: IntentResult): ReasoningDecision {
        val toolMapping = mapOf(
            IntentType.SALE to "record_sale",
            IntentType.PURCHASE to "record_purchase",
            IntentType.EXPENSE to "record_expense",
            IntentType.CHECK_BALANCE to "check_balance",
            IntentType.PROFIT_QUERY to "check_profit",
            IntentType.STOCK_QUERY to "check_stock",
            IntentType.DAILY_SUMMARY to "daily_summary",
            IntentType.WEEKLY_SUMMARY to "weekly_summary",
            IntentType.ASK_ADVICE to "advice",
            IntentType.GREETING to "greeting",
            IntentType.HELP to "help",
            IntentType.CORRECTION to "correction",
            IntentType.GOAL_CREATE to "record_goal",
            IntentType.GOAL_PROGRESS to "check_goal",
            IntentType.GOAL_REPORT to "check_goal",
            IntentType.LOAN_RECORD to "record_loan",
            IntentType.LOAN_QUERY to "check_loan",
            IntentType.LOAN_REPORT to "check_loan",
            IntentType.GIVING_RECORD to "record_giving",
            IntentType.RECEIPT_SCAN to "receipt_scan",
            IntentType.TRANSPORT_TRIP to "transport",
            IntentType.TRANSPORT_EXPENSE to "transport",
            IntentType.FARMING_ACTIVITY to "farming",
            IntentType.FARMING_INPUT to "farming",
            IntentType.DIGITAL_COMMISSION to "digital",
            IntentType.DIGITAL_TRANSACTION to "digital",
            IntentType.SERVICE_CLIENT to "service",
            IntentType.SERVICE_JOB to "service"
        )

        val toolName = toolMapping[intent.intent]
        return if (toolName != null) {
            ReasoningDecision(
                text = "", // Tool will generate the text
                type = ResponseType.CONFIRMATION,
                toolName = toolName,
                toolArgs = intent.extractedData
            )
        } else {
            // Unknown intent — use LLM
            ReasoningDecision(
                text = "Sijaelewa. Sema tena kwa uwazi zaidi.",
                type = ResponseType.CLARIFICATION
            )
        }
    }

    /**
     * LLM reasoning: 10% of requests (advice, complex queries, unknown).
     * Uses Qwen 0.8B on-device via llama.cpp.
     */
    private suspend fun reasonWithLlm(
        text: String,
        intent: IntentResult,
        l2Episodes: List<Episode>
    ): ReasoningDecision {
        val context = buildString {
            // Business context from L1
            val memCtx = conversationMemory.getContext()
            if (memCtx.lastItem != null) append("Bidhaa: ${memCtx.lastItem}. ")
            if (memCtx.lastAmount != null) append("Bei: KSh ${"%.0f".format(memCtx.lastAmount)}. ")

            // L2 episodic context
            if (l2Episodes.isNotEmpty()) {
                append("Historia: ")
                l2Episodes.take(2).forEach { ep ->
                    append("${ep.query.take(40)}→${ep.response.take(40)}. ")
                }
            }
        }

        val llmResponse = llmEngine.generateResponse(
            userInput = text,
            context = context,
            language = language
        )

        return if (llmResponse.isNotBlank()) {
            ReasoningDecision(
                text = llmResponse,
                type = when (intent.intent) {
                    IntentType.ASK_ADVICE -> ResponseType.ADVICE
                    else -> ResponseType.INFORMATION
                }
            )
        } else {
            ReasoningDecision(
                text = "Samahani, sijaweza kujibu. Jaribu tena.",
                type = ResponseType.ERROR
            )
        }
    }

    // ── Tool Registry ──

    private fun registerTool(tool: Tool) {
        tools[tool.name] = tool
    }

    fun getRegisteredTools(): List<String> = tools.keys.toList()

    // ── Correction Detection ──

    private suspend fun checkForCorrection(text: String, intent: IntentResult): AgentResponse? {
        // Simplified correction detection — checks if the user is correcting
        // the last transaction
        val lastTurn = conversationMemory.getContext().lastAgentTurn ?: return null
        if (lastTurn.intent?.intent !in setOf(IntentType.SALE, IntentType.PURCHASE, IntentType.EXPENSE)) {
            return null
        }

        val isCorrection = adaptiveLearning.parseAndRecordCorrection(
            text = text,
            lastTransaction = null, // TODO: wire to last transaction
            language = language
        )

        return if (isCorrection) {
            AgentResponse(
                text = "✅ Nimekumbuka! Nitakumbuka kwa mara ijayo.",
                type = ResponseType.CONFIRMATION
            )
        } else null
    }

    // ── Lifecycle ──

    fun getReActTraces(n: Int = 10) = reActLoop.getRecentTraces(n)
    fun getConversationMemory() = conversationMemory
    fun clearConversationMemory() = conversationMemory.clear()

    suspend fun getMemoryStats() = episodicMemory.getStats()
    fun getHermesStats() = hermesSession.getStats()
    fun getPhaseMetrics() = phaseMetrics.summary()
    fun exportPrometheusMetrics() = phaseMetrics.exportPrometheus()
}

// ── Supporting Types ──

/**
 * Per-phase cognitive loop metrics.
 * Stores latency, success rate, and error count per phase in Room DB.
 * Enables offline observability on $50 phones with zero network overhead.
 *
 * Storage: Room `phase_metrics` table
 * Retention: 7 days rolling window, auto-cleanup on app start
 * Export: JSON dump for sync when online (optional)
 */
@Singleton
class PhaseMetrics @Inject constructor(
    private val db: AppDatabase
) {
    private val metricsDao = db.phaseMetricsDao()

    /**
     * Record a phase execution.
     * @param phase One of: perceive, remember, reason, reflect, act, learn
     * @param latencyMs Execution time in milliseconds
     * @param success Whether the phase completed without error
     */
    suspend fun recordPhase(phase: String, latencyMs: Long, success: Boolean) {
        val entry = PhaseMetricEntry(
            phase = phase,
            latencyMs = latencyMs,
            success = success,
            timestamp = System.currentTimeMillis()
        )
        metricsDao.insert(entry)

        // Check thresholds and alert if breached
        checkThresholds(phase, latencyMs, success)
    }

    /**
     * Get aggregated stats for a phase over a time window.
     */
    suspend fun getPhaseStats(phase: String, windowMs: Long = 24 * 60 * 60 * 1000): PhaseStats {
        val since = System.currentTimeMillis() - windowMs
        val entries = metricsDao.getEntriesSince(phase, since)
        if (entries.isEmpty()) return PhaseStats.empty(phase)

        val latencies = entries.map { it.latencyMs }
        val successes = entries.count { it.success }
        val errors = entries.count { !it.success }

        return PhaseStats(
            phase = phase,
            count = entries.size,
            avgLatencyMs = latencies.average().toLong(),
            p50LatencyMs = percentile(latencies, 50),
            p95LatencyMs = percentile(latencies, 95),
            p99LatencyMs = percentile(latencies, 99),
            maxLatencyMs = latencies.maxOrNull() ?: 0,
            successRate = if (entries.isNotEmpty()) successes.toDouble() / entries.size * 100 else 0.0,
            errorCount = errors,
            windowStartMs = since,
            windowEndMs = System.currentTimeMillis()
        )
    }

    /**
     * Get all phase stats for dashboard/debug view.
     */
    suspend fun getAllPhaseStats(windowMs: Long = 24 * 60 * 60 * 1000): Map<String, PhaseStats> {
        val phases = listOf("perceive", "remember", "reason", "reflect", "act", "learn")
        return phases.associateWith { getPhaseStats(it, windowMs) }
    }

    /**
     * Cleanup old metrics (retention: 7 days).
     */
    suspend fun cleanup() {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000
        metricsDao.deleteBefore(cutoff)
    }

    private fun checkThresholds(phase: String, latencyMs: Long, success: Boolean) {
        val threshold = THRESHOLDS[phase] ?: return

        when {
            latencyMs > threshold.criticalMs -> {
                Timber.e("CRITICAL: Phase $phase took ${latencyMs}ms (threshold: ${threshold.criticalMs}ms)")
                metricsDao.insertAlert(PhaseMetricAlert(
                    phase = phase, level = "critical",
                    message = "Latency ${latencyMs}ms exceeds critical threshold ${threshold.criticalMs}ms",
                    timestamp = System.currentTimeMillis()
                ))
            }
            latencyMs > threshold.warningMs -> {
                Timber.w("WARNING: Phase $phase took ${latencyMs}ms (threshold: ${threshold.warningMs}ms)")
                metricsDao.insertAlert(PhaseMetricAlert(
                    phase = phase, level = "warning",
                    message = "Latency ${latencyMs}ms exceeds warning threshold ${threshold.warningMs}ms",
                    timestamp = System.currentTimeMillis()
                ))
            }
        }
    }

    private fun percentile(sorted: List<Long>, p: Int): Long {
        if (sorted.isEmpty()) return 0
        val index = (p / 100.0 * (sorted.size - 1)).toInt()
        return sorted.sorted()[index]
    }

    companion object {
        data class ThresholdConfig(
            val warningMs: Long,
            val criticalMs: Long,
            val successRateWarning: Double
        )

        val THRESHOLDS = mapOf(
            "perceive" to ThresholdConfig(warningMs = 200, criticalMs = 500, successRateWarning = 95.0),
            "remember" to ThresholdConfig(warningMs = 50, criticalMs = 200, successRateWarning = 90.0),
            "reason" to ThresholdConfig(warningMs = 500, criticalMs = 2000, successRateWarning = 85.0),
            "reflect" to ThresholdConfig(warningMs = 1000, criticalMs = 3000, successRateWarning = 80.0),
            "act" to ThresholdConfig(warningMs = 200, criticalMs = 1000, successRateWarning = 90.0),
            "learn" to ThresholdConfig(warningMs = 100, criticalMs = 500, successRateWarning = 90.0)
        )
    }
}

data class PhaseMetricEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phase: String,
    val latencyMs: Long,
    val success: Boolean,
    val timestamp: Long
)

data class PhaseMetricAlert(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phase: String,
    val level: String,  // "warning" or "critical"
    val message: String,
    val timestamp: Long
)

data class PhaseStats(
    val phase: String,
    val count: Int,
    val avgLatencyMs: Long,
    val p50LatencyMs: Long,
    val p95LatencyMs: Long,
    val p99LatencyMs: Long,
    val maxLatencyMs: Long,
    val successRate: Double,
    val errorCount: Int,
    val windowStartMs: Long,
    val windowEndMs: Long
) {
    companion object {
        fun empty(phase: String) = PhaseStats(
            phase = phase, count = 0, avgLatencyMs = 0, p50LatencyMs = 0,
            p95LatencyMs = 0, p99LatencyMs = 0, maxLatencyMs = 0,
            successRate = 0.0, errorCount = 0, windowStartMs = 0, windowEndMs = 0
        )
    }
}

@Dao
interface PhaseMetricsDao {
    @Insert
    suspend fun insert(entry: PhaseMetricEntry)

    @Insert
    suspend fun insertAlert(alert: PhaseMetricAlert)

    @Query("SELECT * FROM phase_metric_entries WHERE phase = :phase AND timestamp >= :since ORDER BY timestamp DESC")
    suspend fun getEntriesSince(phase: String, since: Long): List<PhaseMetricEntry>

    @Query("DELETE FROM phase_metric_entries WHERE timestamp < :before")
    suspend fun deleteBefore(before: Long)

    @Query("SELECT * FROM phase_metric_alerts WHERE timestamp >= :since ORDER BY timestamp DESC")
    suspend fun getAlertsSince(since: Long): List<PhaseMetricAlert>
}

data class ReasoningDecision(
    val text: String,
    val type: ResponseType,
    val toolName: String? = null,
    val toolArgs: Map<String, String>? = null
)

/**
 * A single step in a multi-step plan.
 * Plans are generated during REASON, executed sequentially during ACT.
 *
 * @property order Execution order (1-based)
 * @property toolName Tool to invoke from the registry
 * @property args Arguments to pass to the tool
 * @property dependsOn Indices of steps that must complete before this one (0-based)
 * @property description Human-readable description of what this step does (for trace/debug)
 */
data class PlannedStep(
    val order: Int,
    val toolName: String,
    val args: Map<String, String> = emptyMap(),
    val dependsOn: List<Int> = emptyList(),
    val description: String = ""
)

/**
 * Result of executing a full plan.
 * Aggregates individual step results into a single response.
 *
 * @property steps Ordered list of steps that were executed
 * @property stepResults Per-step tool results (same order as steps)
 * @property finalText Aggregated human-readable summary of all steps
 * @property success True if all steps succeeded
 * @property data Merged data from all step results
 */
data class PlanResult(
    val steps: List<PlannedStep>,
    val stepResults: List<ToolResult?>,
    val finalText: String,
    val success: Boolean,
    val data: Map<String, String> = emptyMap()
)

/**
 * Per-phase Prometheus-compatible metrics.
 * Each cognitive loop phase records latency, success, and errors.
 *
 * Metrics exported:
 * - msaidizi_phase_latency_ms{phase} — histogram
 * - msaidizi_phase_success_total{phase} — counter
 * - msaidizi_phase_error_total{phase} — counter
 */
class PhaseMetrics {
    private val latencyBuckets = mutableMapOf<String, MutableList<Long>>()
    private val successCounters = mutableMapOf<String, Long>()
    private val errorCounters = mutableMapOf<String, Long>()

    fun recordPhase(phase: String, latencyMs: Long, success: Boolean) {
        latencyBuckets.getOrPut(phase) { mutableListOf() }.add(latencyMs)
        if (success) {
            successCounters[phase] = (successCounters[phase] ?: 0L) + 1
        } else {
            errorCounters[phase] = (errorCounters[phase] ?: 0L) + 1
        }
    }

    /**
     * Export metrics in Prometheus text format.
     * Called by the metrics endpoint or sync payload.
     */
    fun exportPrometheus(): String = buildString {
        // Latency histograms
        appendLine("# HELP msaidizi_phase_latency_ms Cognitive loop phase latency in milliseconds")
        appendLine("# TYPE msaidizi_phase_latency_ms histogram")
        latencyBuckets.forEach { (phase, latencies) ->
            val sorted = latencies.sorted()
            val p50 = sorted.getOrElse(sorted.size / 2) { 0L }
            val p95 = sorted.getOrElse((sorted.size * 0.95).toInt()) { 0L }
            val p99 = sorted.getOrElse((sorted.size * 0.99).toInt()) { 0L }
            appendLine("msaidizi_phase_latency_ms{phase=\"$phase\",quantile=\"0.5\"} $p50")
            appendLine("msaidizi_phase_latency_ms{phase=\"$phase\",quantile=\"0.95\"} $p95")
            appendLine("msaidizi_phase_latency_ms{phase=\"$phase\",quantile=\"0.99\"} $p99")
        }
        // Success counters
        appendLine("# HELP msaidizi_phase_success_total Successful phase executions")
        appendLine("# TYPE msaidizi_phase_success_total counter")
        successCounters.forEach { (phase, count) ->
            appendLine("msaidizi_phase_success_total{phase=\"$phase\"} $count")
        }
        // Error counters
        appendLine("# HELP msaidizi_phase_error_total Phase execution errors")
        appendLine("# TYPE msaidizi_phase_error_total counter")
        errorCounters.forEach { (phase, count) ->
            appendLine("msaidizi_phase_error_total{phase=\"$phase\"} $count")
        }
    }

    /** Get summary stats for logging. */
    fun summary(): Map<String, PhaseStats> {
        val phases = (latencyBuckets.keys + successCounters.keys + errorCounters.keys).toSet()
        return phases.associateWith { phase ->
            val latencies = latencyBuckets[phase] ?: emptyList()
            PhaseStats(
                avgLatencyMs = if (latencies.isNotEmpty()) latencies.average().toLong() else 0L,
                p95LatencyMs = latencies.sorted().let { it.getOrElse((it.size * 0.95).toInt()) { 0L } },
                successCount = successCounters[phase] ?: 0L,
                errorCount = errorCounters[phase] ?: 0L,
                successRate = {
                    val s = successCounters[phase] ?: 0L
                    val e = errorCounters[phase] ?: 0L
                    if (s + e > 0) s.toDouble() / (s + e) else 1.0
                }()
            )
        }
    }
}

data class PhaseStats(
    val avgLatencyMs: Long,
    val p95LatencyMs: Long,
    val successCount: Long,
    val errorCount: Long,
    val successRate: Double
)

/**
 * Safety checker — inline, not a separate class.
 * Checks for manipulation, deception, financial advice disclaimers.
 */
class SafetyChecker {
    fun check(text: String, language: String): String {
        // Block manipulation patterns
        val blocked = listOf("haraka sana", "usikose", "mwisho wa")
        if (blocked.any { text.lowercase().contains(it) }) {
            return if (language == "sw") {
                "⚠️ Jibu limerekebishwa kwa usalama. $text"
            } else text
        }

        // Auto-inject financial disclaimer on advice
        if (text.contains(Regex("(?i)(wekeza|invest|mkopo|loan|akiba|save)"))) {
            val disclaimer = if (language == "sw") {
                "\n\n💡 Kumbuka: Hii ni ushauri wa jumla. Fanya uchunguzi wako mwenyewe."
            } else {
                "\n\n💡 Remember: This is general advice. Do your own research."
            }
            return text + disclaimer
        }

        return text
    }
}
```

### 2.4 Tool Interface

```kotlin
/**
 * Every capability is a Tool. Tools are registered, not injected.
 * The agent calls tools by name. Tools are pure functions with DB access.
 */
interface Tool {
    val name: String
    val description: String
    val intents: List<IntentType>  // Which intents this tool handles

    suspend fun execute(args: Map<String, String>, language: String): ToolResult
}

data class ToolResult(
    val text: String,
    val type: ResponseType,
    val data: Map<String, String> = emptyMap()
)

/**
 * Example tool: Record a sale.
 * Replaces TransactionHandler.handleSale() + BusinessAgent.recordSale()
 */
class RecordSaleTool(
    private val transactionDao: TransactionDao,
    private val inventoryDao: InventoryDao
) : Tool {
    override val name = "record_sale"
    override val description = "Record a sale transaction"
    override val intents = listOf(IntentType.SALE)

    override suspend fun execute(args: Map<String, String>, language: String): ToolResult {
        val item = args["item"] ?: return ToolResult(
            text = if (language == "sw") "Umeuza nini?" else "What did you sell?",
            type = ResponseType.CLARIFICATION
        )
        val amount = args["amount"]?.toDoubleOrNull() ?: return ToolResult(
            text = if (language == "sw") "Bei ni ngapi?" else "What price?",
            type = ResponseType.CLARIFICATION
        )
        val quantity = args["quantity"]?.toDoubleOrNull() ?: 1.0

        // Record transaction
        val transaction = Transaction(
            type = TransactionType.SALE,
            item = item,
            quantity = quantity,
            amount = amount,
            timestamp = System.currentTimeMillis()
        )
        transactionDao.insert(transaction)

        // Update inventory
        inventoryDao.decrementStock(item, quantity)

        return ToolResult(
            text = if (language == "sw") {
                "✅ Umefanya mauzo ya $item, KSh ${"%.0f".format(amount)}"
            } else {
                "✅ Recorded sale: $item, KSh ${"%.0f".format(amount)}"
            },
            type = ResponseType.CONFIRMATION,
            data = mapOf("item" to item, "amount" to amount.toString())
        )
    }
}
```

### 2.5 Memory Architecture (Hermes L1/L2/L3)

```
┌─────────────────────────────────────────────────────────┐
│                    MEMORY ARCHITECTURE                    │
│                                                         │
│  ┌─────────────────────────────────────────────────┐    │
│  │  L1: ConversationMemory (In-Memory)              │    │
│  │  • Last 10 turns, FIFO                           │    │
│  │  • 30-minute context window                      │    │
│  │  • Pronoun/reference resolution                  │    │
│  │  • Sub-millisecond access                        │    │
│  └─────────────────────────────────────────────────┘    │
│                         │                                │
│                    (consolidate)                          │
│                         ▼                                │
│  ┌─────────────────────────────────────────────────┐    │
│  │  L2: EpisodicMemory (SQLite FTS5)               │    │
│  │  • 10K episodes, BM25 ranked                     │    │
│  │  • Sub-10ms retrieval on $50 phones              │    │
│  │  • Skills from closed learning loop              │    │
│  │  • Relevance decay (30-day half-life)            │    │
│  │  • Eviction at capacity (oldest 10%)             │    │
│  └─────────────────────────────────────────────────┘    │
│                         │                                │
│                    (aggregate)                            │
│                         ▼                                │
│  ┌─────────────────────────────────────────────────┐    │
│  │  L3: WorkerProfile (Room DB)                     │    │
│  │  • Per-worker preferences and patterns           │    │
│  │  • Frequent topics, skill affinities             │    │
│  │  • Satisfaction trend (last 50 interactions)     │    │
│  │  • Business domain, preferred language           │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

**Memory Flow:**
1. Every interaction → L1 (immediate)
2. Every interaction → L2 (persistent, searchable)
3. Every 30 interactions → L2 consolidation → L3 (patterns extracted)
4. L2 queried before every response (relevant past episodes injected into L1)

---

## Part 3: File-by-File Changes

### 3.1 Files to CREATE

| File | Purpose |
|------|---------|
| `agent/SuperAgent.kt` | The unified cognitive agent (replaces Orchestrator + all handlers) |
| `agent/Tool.kt` | Tool interface + ToolResult data class |
| `agent/tools/RecordSaleTool.kt` | Sale recording (from TransactionHandler + BusinessAgent) |
| `agent/tools/RecordPurchaseTool.kt` | Purchase recording |
| `agent/tools/RecordExpenseTool.kt` | Expense recording |
| `agent/tools/CheckBalanceTool.kt` | Balance query (from QueryHandler) |
| `agent/tools/CheckProfitTool.kt` | Profit query |
| `agent/tools/CheckStockTool.kt` | Stock query |
| `agent/tools/DailySummaryTool.kt` | Daily summary |
| `agent/tools/WeeklySummaryTool.kt` | Weekly summary |
| `agent/tools/AdviceTool.kt` | Advice generation (from AdviceHandler) |
| `agent/tools/GreetingTool.kt` | Greeting response |
| `agent/tools/HelpTool.kt` | Help response |
| `agent/tools/CorrectionTool.kt` | Correction handling |
| `agent/tools/RecordGoalTool.kt` | Goal recording (from GamificationHandler) |
| `agent/tools/CheckGoalTool.kt` | Goal progress check |
| `agent/tools/RecordLoanTool.kt` | Loan recording |
| `agent/tools/CheckLoanTool.kt` | Loan query |
| `agent/tools/RecordGivingTool.kt` | Giving/tithe recording |
| `agent/tools/ReceiptScanTool.kt` | Receipt scanning prompt |
| `agent/tools/TransportTool.kt` | Transport domain (from DomainRouter) |
| `agent/tools/FarmingTool.kt` | Farming domain |
| `agent/tools/DigitalTool.kt` | Digital/gig domain |
| `agent/tools/ServiceTool.kt` | Service domain |
| `agent/SafetyChecker.kt` | Inline safety checks (replaces AGIReadyLayer) |
| `agent/ReasoningDecision.kt` | Reasoning output data class |
| `agent/PhaseMetrics.kt` | Per-phase Prometheus metrics (latency, success rate, errors) |
| `agent/VectorClock.kt` | Vector clock for sync conflict resolution |
| `agent/ConflictResolver.kt` | Per-data-type conflict resolution (replaces last-write-wins) |

### 3.2 Files to MODIFY

| File | Change |
|------|--------|
| `core/di/AIModule.kt` | Replace Orchestrator provider with SuperAgent provider. Remove all handler/agent providers. |
| `core/di/RepositoryModule.kt` | Keep gamification/finance/social providers. Remove HermesSessionManager provider (now in SuperAgent). |
| `MsaidiziApp.kt` | Call `superAgent.initialize(db)` instead of `orchestrator.initialize()` + `orchestrator.initializeAGI()`. |
| `voice/VoicePipeline.kt` | Add `transcribe(audioData: ByteArray): TranscriptionResult` method for SuperAgent to call. |
| `agent/IntentRouter.kt` | No changes needed — works as-is. |
| `agent/ConversationMemory.kt` | No changes needed — works as-is. |
| `memory/EpisodicMemory.kt` | No changes needed — works as-is. |
| `agent/hermes/HermesSessionManager.kt` | Simplify: remove A2A integration, keep core session + skill logic. |
| `agent/AdaptiveLearningEngine.kt` | Add `recordInteraction(intent, outcome)` method. |
| `voice/LlmEngine.kt` | No changes needed — works as-is. |
| `voice/LlamaCppEngine.kt` | No changes needed — works as-is. |
| `ui/home/HomeViewModel.kt` | Replace `Orchestrator` injection with `SuperAgent`. |
| `ui/record/RecordViewModel.kt` | Replace `Orchestrator` injection with `SuperAgent`. |

### 3.3 Files to DELETE

| File | Reason |
|------|--------|
| `agent/Orchestrator.kt` | Replaced by SuperAgent |
| `agent/BusinessAgent.kt` | Logic moved to RecordSaleTool, RecordPurchaseTool, RecordExpenseTool |
| `agent/AnalysisAgent.kt` | Logic moved to CheckProfitTool, DailySummaryTool, WeeklySummaryTool |
| `agent/AdvisorAgent.kt` | Logic moved to AdviceTool |
| `agent/LearningAgent.kt` | Logic absorbed into AdaptiveLearningEngine |
| `agent/ConversationManager.kt` | Logic absorbed into SuperAgent cognitive loop |
| `agent/TransactionHandler.kt` | Replaced by tools |
| `agent/QueryHandler.kt` | Replaced by tools |
| `agent/AdviceHandler.kt` | Replaced by tools |
| `agent/GamificationHandler.kt` | Replaced by tools |
| `agent/DomainRouter.kt` | Replaced by domain tools |
| `agent/ModelRouter.kt` | MoE routing unnecessary — one model on 2GB |
| `agent/moe/MoERouter.kt` | Dead code for target device |
| `agent/moe/ExpertRegistry.kt` | Dead code |
| `agent/agi/AGIReadyLayer.kt` | Replaced by SafetyChecker |
| `agent/agi/ReasoningModelManager.kt` | Unnecessary — one model |
| `agent/autonomy/ProgressiveAutonomy.kt` | Premature — removed |
| `agent/a2a/A2AProtocol.kt` | Unused on-device |
| `agent/knowledge/CrossDomainKnowledgeGraph.kt` | Too heavy for 2GB |
| `agent/cost/CostBudgetManager.kt` | On-device = free |
| `agent/cost/InferenceCostTracker.kt` | On-device = free |
| `agent/credit/CreditScoringLogic.kt` | Premature |
| `agent/dataanalysis/QueryIntent.kt` | Absorbed into tools |
| `agent/dataanalysis/ResultSummarizer.kt` | Absorbed into tools |
| `agent/dataanalysis/SafeQueryExecutor.kt` | Absorbed into tools |
| `agent/dataanalysis/SwahiliQueryParser.kt` | Absorbed into tools |
| `agent/multimodal/MultimodalPipeline.kt` | Too heavy for 2GB |
| `agent/version/ModelVersionManager.kt` | Unnecessary |
| `agent/WorkerClassifier.kt` | Absorbed into IntentRouter |
| `agent/ReasoningTemplates.kt` | Absorbed into tools |
| `agent/UnifiedStateManager.kt` | Absorbed into SuperAgent |
| `agent/ContextManager.kt` | Absorbed into SuperAgent |
| `agent/ErrorCompactor.kt` | Absorbed into SuperAgent |
| `agent/harness/LearningHarness.kt` | Absorbed into AdaptiveLearningEngine |
| `loops/OodaLoop.kt` | Duplicate of ReActLoop |
| `loops/PlanExecuteLoop.kt` | Absorbed into SuperAgent.planSteps() method |
| `loops/ReflexionLoop.kt` | Absorbed into SuperAgent.reflectOnDecision() method |
| `loops/FeedbackLoop.kt` | Absorbed into AdaptiveLearningEngine |
| `voice/SpeechToSpeechEngine.kt` | Too heavy for 2GB |
| `voice/sts/StsProvider.kt` | Too heavy |
| `voice/sts/providers/*.kt` | Too heavy |
| `voice/streaming/StreamingVoicePipeline.kt` | Too heavy for 2GB |
| `voice/KokoroTtsEngine.kt` | Keep only if 3GB+ target; otherwise remove |
| `voice/MMSTextToSpeech.kt` | Too heavy for 2GB primary target |
| `voice/WhisperTokenizer.kt` | Absorbed into SherpaVoiceEngine |
| `security/crypto/pqc/*.kt` | Premature post-quantum crypto |
| `security/privacy/DifferentialPrivacy.kt` | Premature |
| `security/privacy/FederatedLearningPrivacy.kt` | Keep FederatedLearningClient only |
| `security/simswap/*.kt` | Premature |

### 3.4 Dependency Injection Simplification

**Before (AIModule.kt):**
```kotlin
// 40+ @Provides methods for Orchestrator and all its dependencies
@Provides fun provideOrchestrator(...) = Orchestrator(...) // 40 params
@Provides fun provideTransactionHandler(...) = TransactionHandler(...)
@Provides fun provideQueryHandler(...) = QueryHandler(...)
@Provides fun provideAdviceHandler(...) = AdviceHandler(...)
@Provides fun provideGamificationHandler(...) = GamificationHandler(...)
@Provides fun provideDomainRouter(...) = DomainRouter(...)
@Provides fun provideConversationManager(...) = ConversationManager(...)
// ... 30 more
```

**After (AIModule.kt):**
```kotlin
// ONE agent, minimal deps
@Provides @Singleton
fun provideSuperAgent(
    voicePipeline: VoicePipeline,
    sherpaEngine: SherpaVoiceEngine,
    conversationMemory: ConversationMemory,
    episodicMemory: EpisodicMemory,
    hermesSession: HermesSessionManager,
    llmEngine: LlmEngine,
    reActLoop: ReActLoop,
    intentRouter: IntentRouter,
    adaptiveLearning: AdaptiveLearningEngine,
    inferenceHarness: InferenceHarness
): SuperAgent = SuperAgent(
    voicePipeline, sherpaEngine, conversationMemory,
    episodicMemory, hermesSession, llmEngine,
    reActLoop, intentRouter, adaptiveLearning, inferenceHarness
)
```

**Reduction: 40+ providers → 10 providers.**

---

## Part 3.5: Sync Conflict Resolution — Vector Clocks

### Why Not Last-Write-Wins?

The original sync protocol used last-write-wins with merge. This causes **silent data loss** when:
- A worker records a sale offline, then edits it on another device
- Two devices record conflicting inventory counts
- A worker's preferences are updated simultaneously on phone and tablet

**Solution: Vector Clocks with Per-Data-Type Resolution**

```kotlin
/**
 * Vector clock for causality tracking.
 * Each device maintains a logical clock. On sync, clocks are compared
 * to determine if events are concurrent, causal, or conflicting.
 */
data class VectorClock(
    val clocks: MutableMap<String, Long> = mutableMapOf() // deviceId → counter
) {
    fun increment(deviceId: String) {
        clocks[deviceId] = (clocks[deviceId] ?: 0L) + 1
    }

    fun merge(other: VectorClock): VectorClock {
        val merged = clocks.toMutableMap()
        other.clocks.forEach { (id, count) ->
            merged[id] = maxOf(merged[id] ?: 0L, count)
        }
        return VectorClock(merged)
    }

    /**
     * Compare two clocks:
     * - BEFORE: this happened before other (this < other)
     * - AFTER: this happened after other (this > other)
     * - CONCURRENT: happened at the same time (conflict!)
     * - EQUAL: same events
     */
    fun compare(other: VectorClock): ClockComparison {
        var thisBefore = false
        var thisAfter = false

        val allKeys = clocks.keys + other.clocks.keys
        for (key in allKeys) {
            val thisCount = clocks[key] ?: 0L
            val otherCount = other.clocks[key] ?: 0L
            when {
                thisCount < otherCount -> thisBefore = true
                thisCount > otherCount -> thisAfter = true
            }
        }

        return when {
            thisBefore && !thisAfter -> ClockComparison.BEFORE
            thisAfter && !thisBefore -> ClockComparison.AFTER
            !thisBefore && !thisAfter -> ClockComparison.EQUAL
            else -> ClockComparison.CONCURRENT  // Conflict!
        }
    }
}

enum class ClockComparison { BEFORE, AFTER, CONCURRENT, EQUAL }
```

### Per-Data-Type Conflict Resolution

| Data Type | Resolution Strategy | Rationale |
|---|---|---|
| **Transactions** (sales, purchases, expenses) | **Merge (additive)** | Both recordings are valid — they represent real events. Add both amounts. |
| **Inventory** (stock counts) | **Latest timestamp wins** | Physical reality: only one count is correct at any moment. Use device timestamp. |
| **Preferences** (language, UI, report format) | **Merge (union)** | Union of preferences. If conflict (e.g., language=sw vs language=en), use most recent. |
| **Skills** (learned patterns, vocabulary) | **Merge (union + confidence scoring)** | Combine skills from both devices. Confidence = average if same skill exists on both. |

```kotlin
/**
 * Conflict resolver — applies per-data-type strategy when vector clocks show CONCURRENT.
 */
class ConflictResolver {

    fun resolveConflict(
        local: SyncEntry,
        remote: SyncEntry,
        dataType: DataType
    ): SyncEntry {
        val clockComparison = local.vectorClock.compare(remote.vectorClock)

        return when (clockComparison) {
            ClockComparison.BEFORE -> remote   // Remote is newer
            ClockComparison.AFTER -> local      // Local is newer
            ClockComparison.EQUAL -> local      // Same — no conflict
            ClockComparison.CONCURRENT -> {     // True conflict!
                when (dataType) {
                    DataType.TRANSACTION -> mergeTransactions(local, remote)
                    DataType.INVENTORY -> latestTimestampWins(local, remote)
                    DataType.PREFERENCES -> mergePreferences(local, remote)
                    DataType.SKILLS -> mergeSkills(local, remote)
                }
            }
        }
    }

    private fun mergeTransactions(local: SyncEntry, remote: SyncEntry): SyncEntry {
        // Both are real events — keep both (additive merge)
        return local.copy(
            data = local.data + remote.data,
            vectorClock = local.vectorClock.merge(remote.vectorClock)
        )
    }

    private fun latestTimestampWins(local: SyncEntry, remote: SyncEntry): SyncEntry {
        return if (local.timestamp >= remote.timestamp) local else remote
    }

    private fun mergePreferences(local: SyncEntry, remote: SyncEntry): SyncEntry {
        // Union of preference maps — most recent wins on key conflicts
        val merged = local.data.toMutableMap()
        remote.data.forEach { (key, value) ->
            if (!merged.containsKey(key) || remote.timestamp > local.timestamp) {
                merged[key] = value
            }
        }
        return local.copy(data = merged, vectorClock = local.vectorClock.merge(remote.vectorClock))
    }

    private fun mergeSkills(local: SyncEntry, remote: SyncEntry): SyncEntry {
        // Union with confidence averaging for overlapping skills
        val merged = local.data.toMutableMap()
        remote.data.forEach { (key, remoteValue) ->
            val localValue = merged[key]
            if (localValue != null) {
                // Average confidence scores
                val localConf = localValue.toDoubleOrNull() ?: 0.5
                val remoteConf = remoteValue.toDoubleOrNull() ?: 0.5
                merged[key] = ((localConf + remoteConf) / 2.0).toString()
            } else {
                merged[key] = remoteValue
            }
        }
        return local.copy(data = merged, vectorClock = local.vectorClock.merge(remote.vectorClock))
    }
}

enum class DataType { TRANSACTION, INVENTORY, PREFERENCES, SKILLS }
```

### Updated Sync Protocol

```kotlin
class SyncManager(
    private val syncQueue: SyncQueue,
    private val networkMonitor: NetworkMonitor,
    private val api: MsaidiziApi,
    private val conflictResolver: ConflictResolver  // NEW
) {
    // Device ID for vector clock
    private val deviceId = DeviceUtils.getUniqueDeviceId()

    suspend fun syncWhenOnline() {
        if (!networkMonitor.isOnline()) return

        // Batch and upload with vector clocks
        val batch = syncQueue.dequeue(limit = 100)
        if (batch.isNotEmpty()) {
            try {
                val response = api.syncBatch(batch)

                // Resolve conflicts using vector clocks
                response.conflicts.forEach { conflict ->
                    val resolved = conflictResolver.resolveConflict(
                        local = conflict.localEntry,
                        remote = conflict.remoteEntry,
                        dataType = conflict.dataType
                    )
                    syncQueue.applyResolution(resolved)
                }

                syncQueue.markSynced(batch.map { it.id })
            } catch (e: Throwable) {
                syncQueue.requeue(batch)
            }
        }
    }
}
```

---

## Part 4: Offline-First Design

### 4.1 Everything Works Without Internet

| Feature | Offline Behavior | Online Enhancement |
|---------|-----------------|-------------------|
| Voice Input | Sherpa-ONNX Whisper Tiny INT4 (on-device) | Same — no cloud STT |
| Intent Classification | Regex patterns (IntentRouter) | OTA pattern updates |
| Transaction Recording | SQLite (local) | Sync to cloud |
| Balance/Profit Queries | SQLite queries | Same |
| Advice | Qwen 0.8B on-device (llama.cpp) | Cloud LLM for complex queries |
| Memory | SQLite FTS5 (EpisodicMemory) | Cloud backup |
| TTS | Piper (25MB, on-device) | Kokoro (better quality) |
| Dialect Learning | On-device AdaptiveLearningEngine | Federated learning upload |
| Briefings | On-device generation | Cloud-enhanced |

### 4.2 What Syncs When Connected

#### Vector Clock Format

Every mutable entity carries a **vector clock** — a map of `node_id → logical_counter` — instead of a single timestamp. The device and backend are each a node.

```json
{
  "vector_clock": {
    "device:abc123": 7,
    "backend:primary": 3
  }
}
```

**Rules:**
1. On every local mutation, increment own entry: `vc[my_node_id]++`
2. On send, attach full vector clock.
3. On receive, merge clocks: `vc_merged[k] = max(vc_local[k], vc_remote[k])` for all keys, then increment own entry.
4. **Conflict detection**: Two versions are **concurrent** (conflicted) if neither clock dominates the other — i.e., `vc_a` is not ≤ `vc_b` AND `vc_b` is not ≤ `vc_a`.

#### Conflict Resolution Strategy Per Data Type

| Data Type | Strategy | Rationale |
|---|---|---|
| **Transactions** (sales, purchases, expenses) | **Merge (additive)** | Both sides record independent events; sum both. Both transactions are kept with unique IDs. |
| **Inventory** (stock levels) | **Latest timestamp wins** | Stock is a physical quantity; the most recent observation is closest to ground truth. |
| **Preferences** (language, domain, settings) | **Merge (union)** | Union of non-conflicting settings. For same-key conflicts, latest timestamp wins. |
| **Skills / Adaptive Learning** | **Merge (union + confidence scoring)** | Union of learned patterns. If same pattern exists on both sides, keep the one with higher confidence score. Average if both are similar. |
| **Episodic Memory** | **Merge (union)** | Both sides may have recorded different episodes; keep all unique episodes. |
| **Goals / Loans** | **Latest timestamp wins** | These are stateful objects (active/paid/completed); latest state reflects reality. |

#### Automatic vs Escalated Resolution

**Automatic (no worker notification):**
- Transactions: always merge (additive, no data loss)
- Episodic memory: always merge (union)
- Skills: always merge (union with confidence scoring)
- Preferences: merge non-conflicting keys; same-key → latest timestamp

**Escalated to worker (notification required):**
- Inventory conflicts where the delta exceeds 20% of recorded stock (likely physical count mismatch)
- Goal/loan state transitions that contradict (e.g., device marks loan as paid, backend still shows active)
- Any conflict where both sides deleted the same entity (tombstone conflict)

```kotlin
/**
 * Sync queue with vector clock conflict resolution.
 * Uses WorkManager for reliable background sync.
 *
 * Priority:
 * 1. Transactions (critical — user data)
 * 2. Episodic memory episodes (important — learning)
 * 3. Worker profile updates (useful — personalization)
 * 4. Dialect corrections (nice-to-have — federated learning)
 * 5. Usage analytics (optional — product improvement)
 */
class SyncManager(
    private val syncQueue: SyncQueue,
    private val networkMonitor: NetworkMonitor,
    private val api: MsaidiziApi,
    private val vectorClock: VectorClock,
    private val conflictResolver: ConflictResolver
) {
    companion object {
        val DEVICE_NODE_ID = "device:${DeviceIdProvider.get()}"
    }

    suspend fun syncWhenOnline() {
        if (!networkMonitor.isOnline()) return

        // Batch and upload with vector clocks
        val batch = syncQueue.dequeue(limit = 100)
        if (batch.isNotEmpty()) {
            try {
                val response = api.syncBatch(batch.map { it.withVectorClock() })

                // Handle conflicts returned by backend
                val conflicts = response.conflicts
                if (conflicts.isNotEmpty()) {
                    for (conflict in conflicts) {
                        val resolution = conflictResolver.resolve(conflict)
                        when (resolution.action) {
                            ResolutionAction.MERGE -> {
                                api.applyResolution(resolution.mergedEntity)
                            }
                            ResolutionAction.KEEP_LATEST -> {
                                api.applyResolution(resolution.winner)
                            }
                            ResolutionAction.ESCALATE -> {
                                // Notify worker of conflict needing manual review
                                conflictNotifier.notifyWorker(conflict)
                            }
                        }
                    }
                }

                // Update local vector clock with backend's clock
                vectorClock.merge(response.backendClock)

                // Pull any backend-side changes
                val serverChanges = api.pullChanges(
                    since = vectorClock,
                    nodeId = DEVICE_NODE_ID
                )
                for (change in serverChanges) {
                    applyServerChange(change)
                }

                syncQueue.markSynced(batch.map { it.id })
            } catch (e: Throwable) {
                syncQueue.requeue(batch) // Put back for retry
            }
        }
    }

    /**
     * Increment local clock before syncing an entity.
     */
    private fun SyncEntity.withVectorClock(): SyncEntity {
        vectorClock.increment(DEVICE_NODE_ID)
        return this.copy(vectorClock = vectorClock.snapshot())
    }
}

/**
 * Vector clock — tracks causality across device and backend.
 */
class VectorClock {
    private val clock = mutableMapOf<String, Long>()

    fun increment(nodeId: String) {
        clock[nodeId] = (clock[nodeId] ?: 0L) + 1
    }

    fun merge(other: Map<String, Long>) {
        for ((node, count) in other) {
            clock[node] = maxOf(clock[node] ?: 0L, count)
        }
    }

    /**
     * Returns true if `this` happened-before `other` (this < other).
     */
    fun happenedBefore(other: Map<String, Long>): Boolean {
        val allKeys = clock.keys + other.keys
        var thisBeforeOrEqual = true
        var strictlyLess = false
        for (key in allKeys) {
            val a = clock[key] ?: 0L
            val b = other[key] ?: 0L
            if (a > b) { thisBeforeOrEqual = false; break }
            if (a < b) strictlyLess = true
        }
        return thisBeforeOrEqual && strictlyLess
    }

    /**
     * Two clocks are concurrent (conflicted) if neither dominates.
     */
    fun isConcurrentWith(other: Map<String, Long>): Boolean {
        return !happenedBefore(other) && !other.happenedBefore(clock) && clock != other
    }

    fun snapshot(): Map<String, Long> = clock.toMap()
}

/**
 * Resolves sync conflicts based on data type strategy.
 */
class ConflictResolver {
    fun resolve(conflict: SyncConflict): ConflictResolution {
        return when (conflict.entityType) {
            // Merge (additive) — both transactions are valid
            EntityType.TRANSACTION -> ConflictResolution(
                action = ResolutionAction.MERGE,
                mergedEntity = mergeTransactions(conflict.local, conflict.remote)
            )

            // Latest timestamp wins — stock is physical truth
            EntityType.INVENTORY -> {
                val localTs = conflict.local.timestamp
                val remoteTs = conflict.remote.timestamp
                val delta = abs(localTs - remoteTs)
                val threshold = conflict.local.expectedStock * 0.2

                if (conflict.localDelta > threshold) {
                    // Large mismatch — escalate to worker for physical verification
                    ConflictResolution(action = ResolutionAction.ESCALATE)
                } else {
                    ConflictResolution(
                        action = ResolutionAction.KEEP_LATEST,
                        winner = if (localTs > remoteTs) conflict.local else conflict.remote
                    )
                }
            }

            // Merge (union) — combine non-conflicting preferences
            EntityType.PREFERENCE -> ConflictResolution(
                action = ResolutionAction.MERGE,
                mergedEntity = mergePreferences(conflict.local, conflict.remote)
            )

            // Merge (union + confidence) — keep highest confidence skills
            EntityType.SKILL -> ConflictResolution(
                action = ResolutionAction.MERGE,
                mergedEntity = mergeSkills(conflict.local, conflict.remote)
            )

            // Union — keep all unique episodes
            EntityType.EPISODE -> ConflictResolution(
                action = ResolutionAction.MERGE,
                mergedEntity = mergeEpisodes(conflict.local, conflict.remote)
            )

            // Latest timestamp wins — goals/loans are stateful
            EntityType.GOAL, EntityType.LOAN -> {
                if (isContradictoryState(conflict.local, conflict.remote)) {
                    // e.g., one says paid, other says active
                    ConflictResolution(action = ResolutionAction.ESCALATE)
                } else {
                    ConflictResolution(
                        action = ResolutionAction.KEEP_LATEST,
                        winner = if (conflict.local.timestamp > conflict.remote.timestamp)
                            conflict.local else conflict.remote
                    )
                }
            }
        }
    }
}
```

### 4.3 Federated Learning On-Device

```kotlin
/**
 * Federated learning client — uploads learned patterns without sharing raw data.
 *
 * What gets uploaded:
 * - Dialect corrections (word → correct word mappings)
 * - Intent classification accuracy (which patterns work)
 * - Business domain vocabulary (new terms learned)
 * - NOT: raw audio, transaction amounts, personal data
 *
 * Privacy guarantees:
 * - Differential privacy: noise added to all uploads
 * - k-anonymity: only patterns seen by 5+ users are aggregated
 * - Worker IDs hashed: SHA-256, not reversible
 * - Opt-in only: requires explicit consent
 */
class FederatedLearningClient(
    private val pinnedHttpClient: PinnedHttpClient,
    private val consentManager: ConsentManager,
    private val cryptoService: CryptoService
) {
    suspend fun uploadLearnedPatterns(patterns: LearnedPatterns) {
        if (!consentManager.hasConsent(ConsentType.FEDERATED_LEARNING)) return

        // Add differential privacy noise
        val noisyPatterns = addDifferentialPrivacyNoise(patterns, epsilon = 1.0)

        // Encrypt and upload
        val encrypted = cryptoService.encrypt(noisyPatterns.toJson())
        pinnedHttpClient.post("/api/v1/federated/upload", encrypted)
    }

    suspend fun downloadAggregatedPatterns(): AggregatedPatterns? {
        if (!consentManager.hasConsent(ConsentType.FEDERATED_LEARNING)) return null

        val response = pinnedHttpClient.get("/api/v1/federated/patterns")
        return AggregatedPatterns.fromJson(response)
    }
}
```

### 4.4 Memory Budget on 2GB Device

```
┌─────────────────────────────────────────────────────────┐
│              MEMORY BUDGET: 2GB DEVICE                    │
│                                                         │
│  OS + System:          ~800 MB (fixed)                   │
│  App (Dalvik/ART):     ~150 MB                           │
│  SQLite databases:      ~20 MB (transactions + episodes) │
│  ─────────────────────────────────────────────           │
│  Available for models: ~1030 MB                          │
│                                                         │
│  MODEL LOADING (mutual exclusion):                       │
│  ┌─────────────────────────────────────────────┐        │
│  │  During STT:                                 │        │
│  │    Whisper Tiny INT4:    ~40 MB              │        │
│  │    Piper TTS (standby):  ~25 MB              │        │
│  │    Total:                ~65 MB              │        │
│  │                                              │        │
│  │  During TTS:                                 │        │
│  │    Piper TTS:            ~25 MB              │        │
│  │    (Whisper unloaded)                        │        │
│  │    Total:                ~25 MB              │        │
│  │                                              │        │
│  │  During LLM inference:                       │        │
│  │    Qwen 0.8B Q4_K_M:   ~500 MB              │        │
│  │    (STT + TTS unloaded)                      │        │
│  │    Total:               ~500 MB              │        │
│  │                                              │        │
│  │  Idle (no models loaded):                    │        │
│  │    App + DB:            ~170 MB              │        │
│  └─────────────────────────────────────────────┘        │
│                                                         │
│  RULE: Only ONE heavy model in memory at a time.        │
│  VoicePipeline enforces this via MemoryManager.          │
└─────────────────────────────────────────────────────────┘
```

---

## Part 5: Migration Plan

### Phase 1: Foundation (Week 1)
1. Create `Tool.kt` interface and `ToolResult` data class
2. Create `SafetyChecker.kt` (inline safety)
3. Create `ReasoningDecision.kt`
4. Create `SuperAgent.kt` with empty tool registry
5. Wire `SuperAgent` into DI alongside existing `Orchestrator` (dual-run)

### Phase 2: Tool Migration (Week 2)
1. Extract `RecordSaleTool` from `TransactionHandler` + `BusinessAgent`
2. Extract `RecordPurchaseTool`, `RecordExpenseTool`
3. Extract all query tools from `QueryHandler`
4. Extract all advice tools from `AdviceHandler`
5. Extract all gamification tools from `GamificationHandler`
6. Extract all domain tools from `DomainRouter`
7. Register all tools in `SuperAgent.initialize()`

### Phase 3: Cognitive Loop (Week 3)
1. Implement `processInput()` cognitive loop in `SuperAgent`
2. Wire L1/L2/L3 memory into the loop
3. Wire LLM reasoning path
4. Wire code-only reasoning path
5. Test dual-run: both Orchestrator and SuperAgent produce same results

### Phase 4: Cutover (Week 4)
1. Switch all ViewModels to inject `SuperAgent` instead of `Orchestrator`
2. Delete `Orchestrator` and all decommissioned files
3. Update DI modules
4. Run full test suite
5. Performance benchmark on 2GB device

### Phase 5: Optimization (Week 5)
1. Remove unused AGI/A2A/MoE infrastructure
2. Optimize memory allocation
3. Profile on Tecno Spark Go (2GB)
4. Battery optimization
5. Final cleanup

---

## Part 6: Key Design Decisions

### 6.1 Why One Agent, Not Many?

| Approach | Pros | Cons |
|----------|------|------|
| 33 Agents (current) | Separation of concerns | Constructor hell, memory overhead, routing complexity |
| 1 Super Agent + Tools | Simple DI, clear flow, one cognitive loop | Larger single class (~800 lines) |
| Microservices (cloud) | Scalable | Not applicable — on-device |

**Decision:** One agent with tools. The "separation of concerns" happens at the **tool level**, not the agent level. Each tool is a self-contained unit with its own DAO access. The agent is just the cognitive loop that decides which tool to call.

### 6.2 Why Regex + LLM, Not Pure LLM?

| Approach | Latency | RAM | Accuracy (Swahili) | Cost |
|----------|---------|-----|-------------------|------|
| Pure LLM (Qwen 0.8B) | 2-5s | 500MB | ~70% | Free |
| Pure Regex | <1ms | 0MB | ~90% (known patterns) | Free |
| Regex + LLM fallback | <1ms (90%), 2-5s (10%) | 0MB (90%), 500MB (10%) | ~95% | Free |

**Decision:** Regex first, LLM fallback. The IntentRouter handles 90% of input without any model loaded. The LLM only activates for advice, complex queries, and unknown intents. This gives sub-second response for most interactions.

### 6.3 Why SQLite FTS5, Not Vector Embeddings?

| Approach | Dependencies | RAM | Latency (2GB) | Swahili Support |
|----------|-------------|-----|---------------|-----------------|
| SQLite FTS5 | 0 (native Android) | ~5MB | <10ms | Excellent (unicode61) |
| Vector Embeddings | ONNX Runtime + model | ~200MB | ~50ms | Depends on model |
| Cloud Embeddings | Network | 0MB | 200-500ms | Good |

**Decision:** SQLite FTS5. Zero dependencies, sub-10ms on $50 phones, works offline, excellent Swahili support via `unicode61 remove_diacritics 2` tokenizer.

### 6.4 Why Sherpa-ONNX Over Raw ONNX Runtime?

| Approach | Setup | Memory | Quality | African Languages |
|----------|-------|--------|---------|-------------------|
| Sherpa-ONNX | JNI libs + models | Same | Same | Built-in VAD + ASR + TTS |
| Raw ONNX Runtime | Manual pipeline | Same | Same | Manual integration |

**Decision:** Sherpa-ONNX. It provides a unified API for VAD, ASR, and TTS with built-in support for Whisper, Piper, and other models. Less code to maintain.

---

## Appendix A: Class Count Comparison

| Category | Before | After | Reduction |
|----------|--------|-------|-----------|
| Agent classes | 33 | 1 (SuperAgent) + 22 tools | -30% |
| Handler classes | 6 | 0 (absorbed into tools) | -100% |
| Loop classes | 7 | 1 (ReActLoop) | -86% |
| AGI classes | 6 | 0 (removed) | -100% |
| MoE classes | 2 | 0 (removed) | -100% |
| Memory classes | 3 | 3 (kept as-is) | 0% |
| Voice classes | 20 | 12 (removed heavy) | -40% |
| **Total** | **~77** | **~38** | **-51%** |

## Appendix B: Constructor Parameter Count

| Class | Before | After |
|-------|--------|-------|
| Orchestrator | 40 | N/A (deleted) |
| SuperAgent | N/A | 10 |
| ConversationManager | 10 | N/A (deleted) |
| TransactionHandler | 10 | N/A (deleted) |
| QueryHandler | 5 | N/A (deleted) |
| AdviceHandler | 4 | N/A (deleted) |
| GamificationHandler | 5 | N/A (deleted) |
| DomainRouter | 2 | N/A (deleted) |

**Total DI parameters: 76 → 10 (87% reduction)**

## Appendix C: Response Latency Targets

| Interaction Type | Current | Target | Method |
|-----------------|---------|--------|--------|
| Sale recording | ~50ms | <50ms | Code-only (IntentRouter → Tool) |
| Balance check | ~50ms | <50ms | Code-only |
| Daily summary | ~100ms | <100ms | Code-only (SQLite query) |
| Advice | 2-5s | 2-5s | Qwen 0.8B on-device |
| Unknown intent | 2-5s | 2-5s | Qwen 0.8B on-device |
| Voice input | +500ms | +300ms | Sherpa-ONNX Whisper |
| Voice output | +1s | +500ms | Piper TTS (25MB) |

---

*Architecture by: Chief Android Architect*
*Date: 2026-07-24*
*Target: $50 phones, 2GB RAM, Tecno/Infinix/Itel*
*Model: Qwen 0.8B via llama.cpp, Sherpa-ONNX STT/TTS*
*Memory: Hermes L1/L2/L3*
*Language: Swahili first, learn dialects*
