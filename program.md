# Msaidizi Superagent — program.md

> Declarative configuration that "programs the program."
> An AI agent reads this file to understand the Msaidizi superagent's identity, capabilities, constraints, and operational parameters — all in one place.

---

## Identity

- **Name:** Msaidizi
- **Role:** Voice-first AI CFO for informal workers
- **Language:** Swahili-first, English secondary
- **Target:** Kenya's 17.5M informal workers
- **Personality:** Warm, friendly, like a trusted business partner. Mix Kiswahili and English naturally. Be concise. Give practical, actionable advice.
- **Voice Tone:** Natural, conversational. 1-3 sentences for simple queries.

## Architecture

- **Pattern:** Unified brain (1 agent with capability modules), NOT 5 separate agents
- **Pipeline:** Intent Router → Context Assembly → Capability Activation → Guardrails → Response
- **Execution Loop:** OODA (Observe-Orient-Decide-Act) with ReAct-style per-step reasoning and Reflexion-style self-critique
- **LLM:** On-device Qwen 0.8B via Hermes-style function calling

## Intent Types

### Business Operations (Mutating)

| Intent | Tool | Description |
|--------|------|-------------|
| `RECORD_SALE` | `record_sale` | Log a sale transaction |
| `RECORD_EXPENSE` | `record_expense` | Log an expense |
| `RECORD_PURCHASE` | `record_purchase` | Log a stock purchase |
| `RECORD_SERVICE` | `record_service` | Log a service rendered |
| `RECORD_PAYMENT` | `record_payment` | Record customer payment |
| `ADD_PRODUCT` | `add_product` | Add new product to inventory |
| `UPDATE_STOCK` | `update_stock` | Adjust stock levels |
| `QUICK_SALE` | `quick_sale` | One-tap fast sale |

### Queries (Read-Only)

| Intent | Tool | Description |
|--------|------|-------------|
| `ASK_SALES_TODAY` | `query_sales` | Today's sales total |
| `ASK_PROFIT` | `query_profit` | Profit calculation |
| `ASK_EXPENSES` | `query_expenses` | Today's expenses |
| `ASK_STOCK` | `check_stock` | Inventory levels |
| `ASK_DEBTORS` | `query_debtors` | Outstanding debts |
| `CHECK_CUSTOMER_DEBT` | `query_debtors` | Specific customer debt |

### Extended Tools

| Intent | Tool | Description |
|--------|------|-------------|
| `SCAN_RECEIPT` | `scan_receipt` | OCR receipt scanning |
| `VIEW_DASHBOARD` | `business_health_dashboard` | Business health overview |
| `CHAMA_MANAGE` | `chama_manager` | Group savings management |
| `CREDIT_CHECK` | `credit_readiness` | Credit score check |
| `LOAN_COMPARE` | `loan_comparison` | Compare loan options |
| `INSURANCE_MATCH` | `insurance_matcher` | Find insurance |
| `RIDE_SHARE` | `ride_share` | Transport/delivery |
| `MARKET_PRICE` | `market_price_broadcaster` | Current market prices |
| `PROOF_OF_INCOME` | `proof_of_income` | Generate income proof |
| `GOAL_TRACK` | `goal_tracker` | Business goal tracking |
| `WHATSAPP_REPORT` | `whatsapp_reporter` | Send report via WhatsApp |

### Conversational

| Intent | Description |
|--------|-------------|
| `GREETING` | Habari, hi, hello |
| `FAREWELL` | Kwaheri, bye |
| `THANKS` | Asante |
| `HELP` | What can you do? |
| `CHITCHAT` | Casual conversation |

## Intent Router — 3-Tier Hybrid

| Tier | Strategy | Coverage | Cost | Latency |
|------|----------|----------|------|---------|
| **Tier 1** | Pattern/keyword match | ~60% of inputs | Zero (CPU) | Instant |
| **Tier 2** | Hash-trick embedding similarity | ~25% of inputs | Zero (CPU) | Low |
| **Tier 3** | LLM function calling (Qwen 0.8B) | ~15% of inputs | Token cost | Higher |

### Tier Thresholds

| Parameter | Value | Source |
|-----------|-------|--------|
| `TIER1_CONFIDENCE` | `0.8` | Minimum confidence for pattern match to short-circuit |
| `TIER2_SIMILARITY` | `0.75` | Minimum cosine similarity for embedding match |
| `TIER2_ESCALATION_THRESHOLD` | `0.50` | Below this, escalate to Tier 3 |
| `EMBEDDING_DIM` | `256` | Hash-trick embedding vector dimensionality |
| `TIER3_MAX_TOKENS` | `128` | Max tokens for LLM classification call |

## OODA Loop Parameters

| Parameter | Value | Description |
|-----------|-------|-------------|
| `MAX_ITERATIONS` | `3` | Hard safety cap per request |
| `CONFIDENCE_THRESHOLD` | `0.85` | Accept output without further iteration |
| `CONVERGENCE_THRESHOLD` | `0.05` | Stop if improvement < this |
| `LATENCY_BUDGET_MS` | `30000` (30s) | Max total latency per request |

### Simple Operations (Single Pass, No Iteration)

`GREETING`, `FAREWELL`, `THANKS`, `HELP`, `CHITCHAT`, `ASK_SALES_TODAY`, `ASK_EXPENSES`, `ASK_STOCK`, `CHECK_STOCK`

## Memory Hierarchy

| Layer | Name | Storage | Scope | Capacity |
|-------|------|---------|-------|----------|
| **L1** | Working Memory | RAM (ConcurrentLinkedDeque) | Current session | 1000 entries, rolling window of 10 |
| **L2** | Conversation | SQLite | Cross-session | 1000 entries |
| **L3** | Daily Summaries | SQLite | Per-day | 1000 entries |
| **L4** | Long-Term Patterns | SQLite | Weekly/monthly | 1000 entries |

### Memory Parameters

| Parameter | Value | Source |
|-----------|-------|--------|
| `MAX_ENTRIES_PER_LAYER` | `1000` | Cap per memory layer |
| `WORKING_WINDOW` | `10` | Last N turns kept in L1 |
| `RECENT_CONVERSATION_LIMIT` | `20` | Recent conversations loaded for context |
| `PRUNE_AGE_DAYS` | `30` | Default pruning age |

### Eviction Policy

- Each layer capped at `MAX_ENTRIES_PER_LAYER`
- L1: Evicts oldest from deque head
- L2: Deletes conversations older than 7 days when over limit
- L3/L4: Deletes least-used / lowest-confidence entries
- Search: Inverted index for O(1) keyword lookup (tokenized, stopword-filtered)

## Context Assembly — 5 Layers

| Layer | Content | Refresh |
|-------|---------|---------|
| **1. System Identity** | User profile, business type, location, Alama score | Cached |
| **2. Working Memory / OODA State** | Recent observations, active decisions | Per-iteration |
| **3. Session Memory** | Recent conversation (last 20 turns), session summaries | Per-turn |
| **4. Knowledge Base** | Financial summary, patterns, market insights | Per-request |
| **5. Flywheel Insights** | Learned vocabulary, business rhythms, relevant patterns | Continuous |

## Guardrails — 7 Safety Pillars

### Pillar 1: Financial Integrity — "No Number Without Source" (7-Layer Defense)

| Layer | Check | Action |
|-------|-------|--------|
| 1 | Source Existence | Block if no source for financial amount |
| 2 | Source Verifiability | Block if source not in verified registry |
| 3 | Temporal Validity | Warn if source data is stale (default max: 24h) |
| 4 | Amount Reasonableness | Block/warn if outside expected bounds |
| 5 | Cross-Reference | Flag if wildly different from recent averages |
| 6 | Transaction Integrity | Block if amount ≤ 0 |
| 7 | Audit Trail | Flag duplicates within 5-min window |

### Amount Bounds (Ksh)

| Type | Min | Max |
|------|-----|-----|
| `SALE` | 1 | 500,000 |
| `EXPENSE` | 1 | 200,000 |
| `PURCHASE` | 1 | 1,000,000 |
| `SERVICE` | 10 | 100,000 |
| `REPORT` | 0 | 10,000,000 |

### Transaction Validation

| Rule | Value |
|------|-------|
| Max single transaction | **1,000,000 Ksh** |
| Valid payment methods | `cash`, `mpesa`, `credit`, `bank`, `card` |
| Duplicate detection window | 5 minutes |

### Pillar 2: Hallucination Detection — 5-Stage Pipeline

| Stage | Check | Confidence Multiplier |
|-------|-------|-----------------------|
| 1. Provenance | Can every financial claim be traced to a source? | ×0.5 if unverified |
| 2. Cross-Reference | Do claims match known data? | ×0.7 if contradiction |
| 3. Plausibility | Are claims economically reasonable? | ×0.4 / ×0.7 / ×0.9 by severity |
| 4. Consistency | Do claims contradict each other or history? | ×0.6 if self-contradictory |
| 5. Confidence Labeling | Assign 🟢🟡🔴 | Block if 🔴 |

### Confidence Labels

| Label | Threshold | Meaning |
|-------|-----------|---------|
| 🟢 GREEN | ≥ 0.8, no HIGH issues | High confidence |
| 🟡 YELLOW | ≥ 0.5 | Medium — verify important decisions |
| 🔴 RED | < 0.5 | Low — block output, verify independently |

### Pillar 7: Trust Building

- Source attribution on every response
- Confidence indicators (🟢🟡🔴)
- "Why?" explainability for flagged responses

### Sensitive Action Guard

- Pre-execution gate for high-stakes actions
- Decisions: `ALLOW` / `REQUIRE_CONFIRMATION` / `BLOCK`
- Human approval required for confirmation-gated actions

### Escalation Levels

| Level | Action |
|-------|--------|
| `CRITICAL` | Block immediately |
| `HIGH` | Include advisory in response |
| `MODERATE` | Include advisory in response |
| `LOW` | Log only |

## Escalation Triggers

- OODA confidence < 0.3 → check for escalation
- Hallucination issues detected → escalate based on severity
- Tool failures recorded via `EscalationManager.recordToolFailure()`
- User complaints detected via `EscalationManager.detectComplaint()`

## Mutable Files (Agent Can Edit)

These files contain patterns, vocabulary, and configuration that the agent can modify through its learning flywheel:

- `assets/knowledge/intent_patterns.json` — Intent keyword patterns
- Knowledge database (L3/L4 entries) — Learned vocabulary, business patterns
- Flywheel learned data — Tool reliability scores, vocabulary words
- `HEARTBEAT.md` — Periodic check configuration

## Protected Files (Agent Cannot Edit)

These are core infrastructure files — the agent reads but never writes:

- `SuperagentHarness.kt` — Main pipeline orchestrator
- `IntentRouter.kt` — 3-tier intent classification
- `OODALoop.kt` — OODA loop execution engine
- `GuardrailsEngine.kt` — Safety pillars
- `MemoryManager.kt` — 4-layer memory hierarchy
- `ToolRegistry.kt` — Tool registration and validation
- `SelfCorrectionLoop.kt` — Self-correction for tool execution
- `CircuitBreaker.kt` — External tool circuit breaker
- `EscalationManager.kt` — Escalation decision engine
- `SensitiveActionGuard.kt` — Sensitive action pre-gate
- `HumanApprovalInterceptor.kt` — Human-in-the-loop approval
- `AdviceRefinementLoop.kt` — Advice quality refinement
- `FlywheelEngine.kt` — Learning flywheel
- All DAO interfaces and database schema

## Metrics

- **Primary:** User retention (daily active users)
- **Secondary:** Transaction recording accuracy
- **Tertiary:** Advice quality score

## Experiment Budget

- **Max experiments per day:** 3 (matches `MAX_ITERATIONS`)
- **Max token budget per experiment:** 128 tokens (Tier 3 classification)

## Run Command

```bash
# Build
./gradlew assembleDebug

# Test
./gradlew test

# Lint
./gradlew lint
```

## Output Parsing

- **Success:** Response delivered with confidence label (🟢/🟡)
- **Failure:** "Pole sana, something went wrong. Please try again." (error path)
- **Blocked:** "Samahani, I can't do that." or "Unakubali? (Do you approve?)" (guardrail/approval)
- **Crash:** Exception caught in harness → returns `HarnessResponse(error = e.message)`

## Commit/Revert Rules

- **Keep:** If metric improves (retention, accuracy, quality)
- **Revert:** If metric degrades or crash occurs
- **OODA loop auto-reverts:** If confidence < threshold after max iterations

## Human Escalation

- **Escalate when:**
  - OODA confidence < 0.3
  - CRITICAL escalation level triggered
  - Hallucination detected (🔴 RED label)
  - Transaction amount > 1,000,000 Ksh
  - User complaint detected
- **Contact:** Via `EscalationManager` → logs + advisory in response

## Exhaustion Criteria

- **Stop when:**
  - OODA confidence ≥ 0.85 (threshold met)
  - 3 iterations completed (max reached)
  - Improvement < 0.05 between iterations (converged)
  - Latency > 21,000ms (70% of 30s budget)
  - Guardrails block output

## Safe Content Patterns (Blocked in Output)

- "your bank account"
- "transfer money"
- "send to"
- "loan application"

## Entity Extraction Patterns

| Pattern | Regex |
|---------|-------|
| Currency | `ksh\|kes\|shillings?\s*(\d+)` or `(\d+)\s*(ksh\|kes\|shillings?)` |
| Phone | `(?:\+?254\|0)[17]\d{8}` |
| Number | `\d+\.?\d*` |
