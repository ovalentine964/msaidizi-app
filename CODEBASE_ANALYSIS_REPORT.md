# Msaidizi App — Deep Codebase Analysis Report

**Date:** 2026-08-03  
**Analyst:** Msaidizi App Council  
**Repository:** `/home/work/.openclaw/workspace/msaidizi-app/`

---

## 1. Architecture Analysis

### 1.1 Module Structure

The project comprises **8 Gradle modules** organized in a layered architecture:

```
┌─────────────────────────────────────────────────┐
│                    :app                          │
│  (Application, Compose UI, DI wiring, Firebase)  │
├──────┬──────┬───────────────────┬────────────────┤
│ :voice│:agent│  :feature:finance │ :feature:agri  │
│       │      │  :feature:market  │ :feature:credit│
├──────┴──────┴───────────────────┴────────────────┤
│                    :core                          │
│  (Database, Network, Security, Models, DAOs)      │
└─────────────────────────────────────────────────┘
```

| Module | Type | Key Responsibilities |
|--------|------|---------------------|
| `:app` | Application | Compose UI, Hilt `@HiltAndroidApp`, AppModule (tool registry wiring), Firebase, signing |
| `:core` | Library | Room/SQLCipher database (40+ DAOs), Retrofit network layer, security (encryption, biometric, root detection), data models |
| `:voice` | Library | Native JNI (sherpa-onnx STT, llama.cpp LLM, VAD), CMake build, speech engine routing, model management |
| `:agent` | Library | **The brain** — 98 Kotlin files: tools (98 across 12 packages), OODA loop, council system, knowledge graph, flywheel learning, guardrails, memory |
| `:feature:finance` | Library | Marker module (empty, placeholder for finance UI screens) |
| `:feature:agriculture` | Library | Marker module (empty, placeholder for agriculture UI screens) |
| `:feature:market` | Library | Marker module (empty, placeholder for market UI screens) |
| `:feature:credit` | Library | Marker module (empty, placeholder for credit UI screens) |

**Dependency graph:**
- `:app` → `:core`, `:voice`, `:agent`, all `:feature:*`
- `:agent` → `:core`, `:voice`
- `:voice` → `:core`
- `:feature:*` → `:core`, `:agent`
- `:core` → (standalone, no project deps)

### 1.2 Tool Domain Packages (9+ domains, 98 tool files)

| Package | Tool Count | Examples |
|---------|-----------|----------|
| `tools/agriculture` | 12 | FishingLog, HarvestTracker, YieldPredictor, WeatherCacheManager, WasteReducer |
| `tools/core` | 12 | SyncEngine, SecurityGuard, ModelDownloader, GuardrailsTool, SOSSafetyButton |
| `tools/credit` | 7 | AlamaScore, CreditReadiness, HirePurchaseTracker, InsuranceMatcher, LoanComparison |
| `tools/financial` | 16 | TransactionRecorder, DebtTracker, CFOEngine, PricingEngine, TaxComplianceTool, FloatManager |
| `tools/food` | 1 | RecipeCostCalculator |
| `tools/inventory` | 2 | InventoryTracker, SupplierMatcher |
| `tools/market` | 12 | PricingAdvisor, DemandForecaster, QuickSale, MarketPooling, CompetitorTracker |
| `tools/services` | 5 | AppointmentManager, FundiJobQuoter, ServiceHistory, MaterialCostCalculator |
| `tools/social` | 8 | ChamaManager, BulkOrderCoordinator, CustomerInsights, GamificationEngine |
| `tools/transport` | 8 | BodaBodaRouter, FareIntelligence, FuelEfficiencyTracker, RideShare |
| `tools/voice` | 6 | VoicePipeline, LanguageDetector, CodeSwitchHandler, ReceiptScannerCV |

### 1.3 Hilt DI Setup

Three Hilt modules provide the DI graph:

1. **`core/di/AppModule`** — Provides database, 40+ DAOs, Gson
2. **`agent/graph/GraphModule`** — Provides KgNodeDao, KgEdgeDao, KgFactDao
3. **`app/core/di/AppModule`** — Provides `ToolRegistry` (the critical one)

The `provideToolRegistry()` function in `app/core/di/AppModule.kt` is **232 lines** with a single `@Provides` method that takes **~90 tool parameters** and registers each one:

```kotlin
@Provides @Singleton
fun provideToolRegistry(
    flywheelEngine: FlywheelEngine,
    transactionRecorder: TransactionRecorder,
    inventoryTracker: InventoryTracker,
    // ... 87 more parameters ...
    recipeCostCalculator: RecipeCostCalculator
): ToolRegistry {
    val registry = ToolRegistry(flywheelEngine)
    registry.register(transactionRecorder)
    // ... 89 more registrations ...
    return registry
}
```

**Every parameter type must be resolvable by KSP** for Hilt to generate the DI component. If any tool class has compilation errors, KSP cannot resolve its type, and Hilt code generation fails entirely.

### 1.4 Superagent Architecture

- **`Tool` interface** — All 98 tools implement `Tool` with `name`, `description`, `argsSchema`, `execute()`
- **`ToolRegistry`** — Central registry with schema validation, reliability scoring (via Flywheel), capability-based discovery
- **`OODALoop`** — Observe-Orient-Decide-Act cycle: intent routing → tool selection → execution → self-correction
- **Council System** — `CouncilManager`, `CouncilSupervisor`, `AgentSpawner`, `InterCouncilDebate` for multi-agent coordination
- **Knowledge Graph** — `KnowledgeGraph`, `ToolGraph`, `WorkflowDAG` for tool dependency tracking and context assembly
- **Flywheel Engine** — `FlywheelEngine`, `ABTestEngine`, `RCTFramework`, `TraceDrivenLearning` for continuous improvement
- **Guardrails** — `GuardrailsEngine`, `PrivacyGuard`, `SensitiveActionGuard`, `HumanApprovalInterceptor`, `BatterySaverManager`, `OfflineModeManager`

---

## 2. The KSP/Hilt Build Failure — ROOT CAUSE ANALYSIS

### 2.1 The Failure Mechanism

The CI pipeline fails at the **Kotlin compilation + KSP** stage. Here's the chain:

1. `./gradlew compileFullDebugKotlin` (or any task triggering Kotlin compilation) is executed
2. Kotlin compiler processes the `:agent` module and encounters **85 compilation errors**
3. KSP (Kotlin Symbol Processing) runs **during** Kotlin compilation as an annotation processor
4. KSP attempts to process Hilt's `@Module`/`@Provides` annotations in `app/core/di/AppModule.kt`
5. To generate the `Hilt_AppModule` component, KSP must resolve all **~90 parameter types** in `provideToolRegistry()`
6. Many of those parameter types (tool classes in `:agent`) have compilation errors → **types are unresolvable**
7. KSP fails with a resolution error → Hilt code generation fails → build fails

### 2.2 The Specific Compilation Errors (85 total in `:agent`)

The errors are **NOT** caused by a single unresolvable type. They are caused by **widespread compilation errors across 20+ tool files** in the `:agent` module. Here is the categorized breakdown:

#### Category A: Missing/Incorrect API References (BLOCKING)

| File | Error | Root Cause |
|------|-------|------------|
| `SyncEngine.kt:177-220` | `Cannot access class 'retrofit2.Response'` (6 errors) | `SyncEngine` imports `retrofit2.Response` directly, but the Retrofit dependency may not be on the KSP classpath correctly. The `SyncApi` interface returns `Response<SyncResponse>`, so the type is needed. **Fix:** Ensure `retrofit` is available as `api` (not just `implementation`) from `:core`, or add explicit dependency. |
| `OODALoop.kt:448` | `Unresolved reference 'getToolNames'` | `ToolRegistry` has `getAllTools()` but no `getToolNames()` method. Likely a stale reference from before a refactor. **Fix:** Replace with `toolRegistry.getAllTools().map { it.name }`. |
| `MpesaSmsReconciler.kt` | 14 errors: `recordRepayment`, `findByPhone`, `firstOrNull`, `getAll`, `name` unresolved | Calls DAO methods that don't exist on `DebtRepaymentDao` (no `recordRepayment`), `CustomerDao` (no `findByPhone` returning expected type), and incorrect lambda usage. **Fix:** Align with actual DAO APIs. |
| `OnboardingController.kt:202` | `Unresolved reference 'upsert'` | Calls `upsert()` on a DAO that doesn't have it. **Fix:** Use `insert()` + `update()` or add `@Upsert` to DAO. |
| `CreditReadiness.kt:1333,1686-1762` | 12 errors: `milestones`, `getAll`, `it` unresolved, type mismatches | Multiple broken lambda chains and missing properties. **Fix:** Rewrite the broken code blocks. |

#### Category B: Type Mismatches (BLOCKING)

| File | Error | Root Cause |
|------|-------|------------|
| `AlamaScore.kt:170,199` | `List<DoubleArray>` vs `Array<DoubleArray>` (6 errors) | Kotlin List/Array confusion. **Fix:** Use `.toTypedArray()` or `.toList()`. |
| `CreditReadiness.kt:481` | `Any?` vs `String` (2 errors) | Un typed map access. **Fix:** Cast or use typed accessor. |
| `AlamaScoreValidator.kt:409` | `Double` vs `Int` assignment | **Fix:** Use `.toInt()` or change field type. |
| `PricingEngine.kt:65` | `String` vs `Int?` argument | **Fix:** Parse string to int or change parameter type. |
| `TaxComplianceTool.kt:73` | `String` vs `Int?` argument | Same as PricingEngine. |
| `SeasonalBudgetPlanner.kt:524` | `Int` vs `CapturedType(*)` | Generic type inference failure. **Fix:** Add explicit type parameter. |
| `WeatherCacheManager.kt:860` | `Boolean` vs `Pair<K,V>` (2 errors) | Incorrect map operation. **Fix:** Fix the map/filter chain. |
| `WasteReducer.kt:173` | `Int` == `Double` comparison | **Fix:** Use `.toDouble()` or compare with tolerance. |
| `CodeSwitchHandler.kt:545` | `Long?` vs `Int` return type | **Fix:** Use `.toLong()`. |

#### Category C: Syntax/Visibility Errors (BLOCKING)

| File | Error | Root Cause |
|------|-------|------------|
| `GuardrailsTool.kt:78` | `Unsupported escape sequence` | Invalid regex/string escape. **Fix:** Use raw string `"""` or escape properly. |
| `CreditReadiness.kt:1747` | `Unsupported escape sequence` | Same. |
| `CustomerInsights.kt:144-145` | `Too many characters in a character literal` | Using `'...'` with multiple chars instead of `"..."`. **Fix:** Use double quotes. |
| `PostHarvestLossTracker.kt:140` | `public property exposes private type` | Visibility mismatch. **Fix:** Make type `internal` or change property visibility. |
| `ProofOfIncome.kt:808` | `public function exposes private type` | Same visibility issue. |
| `FishingLog.kt:526` | `Unresolved reference 'catch'` | `catch` is a Kotlin keyword. **Fix:** Use backtick-escaped `` `catch` `` or rename. |

#### Category D: Coroutine/Suspension Errors (BLOCKING)

| File | Error | Root Cause |
|------|-------|------------|
| `PricingAdvisor.kt:63` | `Suspend function called from non-suspend` | Missing `suspend` modifier on enclosing function. |
| `ChamaManager.kt:487-488` | `Suspension functions in coroutine body` (2 errors) | Likely missing `suspend` or wrong coroutine scope. |
| `CreditReadiness.kt:1333` | `Suspension functions can only be called within coroutine body` | Same. |

#### Category E: Smart Cast / Module Boundary Errors (NON-BLOCKING but noisy)

| File | Count | Issue |
|------|-------|-------|
| `DebtTracker.kt` | 4 | Smart cast impossible on `dueDate` (public API in different module) |
| `HirePurchaseTracker.kt` | 2 | Smart cast impossible on `totalPurchasePrice` |
| `CustomerInsights.kt` | 1 | Smart cast impossible on `dueDate` |
| `FloatManager.kt` | 1 | `Unresolved reference 'abs'` — missing `import kotlin.math.abs` |
| `BulkOrderCoordinator.kt` | 3 | None of the candidates is applicable |
| `CFOReportReview.kt` | 1 | Non-exhaustive `when` expression |

### 2.3 ROOT CAUSE SUMMARY

**There is no single "unresolvable type" causing the KSP failure.** The root cause is that the `:agent` module has **85 compilation errors across 20+ files**. When KSP processes `AppModule.provideToolRegistry()`, it attempts to resolve ~90 tool types. Since many of those tool classes fail to compile, their types become unresolvable, and KSP generates an error.

The CI comment `// KSP has a resolution issue with one tool class` is **misleading** — the issue is systemic, not isolated.

### 2.4 Proposed Fix Strategy

**Phase 1: Fix blocking compilation errors (unblocks KSP)**

Priority order (by impact on KSP resolution):

1. **`SyncEngine.kt`** — Fix `retrofit2.Response` access (6 errors). Either change `agent/build.gradle.kts` to use `api(libs.retrofit)` from core, or stop importing `retrofit2.Response` directly (the `SyncApi` return type already carries it).

2. **`MpesaSmsReconciler.kt`** — Fix 14 errors by aligning with actual DAO APIs. The `DebtRepaymentDao` has `insert()` but not `recordRepayment()`. The `CustomerDao` has `findByPhone(phone: String)` returning `CustomerEntity?` — code must match.

3. **`CreditReadiness.kt`** — Fix 12 errors. Broken lambda chains, missing properties, type mismatches. Most critical: the `milestones` reference at line 1333 and the `getAll()` calls at lines 1686+.

4. **`AlamaScore.kt`** — Fix 6 `List<Array>` vs `Array<Array>` errors with `.toTypedArray()` conversions.

5. **`WeatherCacheManager.kt`** — Fix 2 errors in map operation at line 860.

6. **`OODALoop.kt`** — Fix `getToolNames` → `getAllTools().map { it.name }`.

7. **`BulkOrderCoordinator.kt`** — Fix 3 candidate resolution errors.

8. **`CustomerInsights.kt`** — Fix character literal and smart cast errors.

9. **Remaining ~30 errors** — Type mismatches, visibility, syntax, coroutine issues across 10+ files.

**Phase 2: Verify KSP resolution**

After fixing all 85 errors, run `./gradlew compileFullDebugKotlin` to verify KSP generates Hilt components successfully.

**Phase 3: Address non-blocking issues**

Smart cast warnings, missing exhaustive `when` branches, etc.

---

## 3. CI Pipeline Health

### 3.1 Pipeline Structure (6+ stages)

```
lint → test → security → build-check → build-with-models → firebase-distribute
                                                              ↓
                                                      ota-model-check
```

Plus a separate `build-apk.yml` workflow with: `prepare-models → build (matrix) → verify → release`

### 3.2 Current State

| Stage | Status | Details |
|-------|--------|---------|
| **Lint** | ⚠️ SKIPPED | Deliberately skipped with comment: "KSP has a resolution issue with one tool class". ktlint not configured. TODO/FIXME scan runs but only on `app/src/main/`. |
| **Unit Tests** | ❌ BLOCKED | Depends on `lint` (passes because lint is skipped), but `./gradlew testFullDebugUnitTest` fails because Kotlin compilation fails first. |
| **Security** | ⚠️ PARTIAL | Runs (doesn't depend on compilation), but `./gradlew :app:dependencies` may fail due to compilation errors. CodeQL has `continue-on-error: true`. |
| **Build Check** | ❌ BLOCKED | `./gradlew compileFullDebugKotlin` fails with 85 errors. |
| **Build with Models** | ❌ BLOCKED | Depends on build-check. Never reaches APK assembly. |
| **Firebase Distribute** | ❌ BLOCKED | Depends on build-with-models. |
| **Test Build** (separate workflow) | ❌ BLOCKED | Same `compileFullDebugKotlin` failure. |
| **Build & Release APK** (separate workflow) | ❌ BLOCKED | Same compilation failure. |

### 3.3 CI Issues Beyond Compilation

1. **Lint skip is a workaround, not a fix** — The comment acknowledges the KSP issue but doesn't address it
2. **TODO/FIXME scan only covers `app/src/main/`** — Should scan all modules
3. **ktlint not configured** — No code style enforcement
4. **Security scan depends on Gradle tasks** — May fail independently of compilation
5. **No caching for Kotlin compilation** — Only native deps and models are cached
6. **Firebase secrets check is good** — Graceful skip when secrets not configured

---

## 4. Code Quality

### 4.1 Metrics

| Metric | Value | Assessment |
|--------|-------|------------|
| Total Kotlin files (main) | 256 | Large codebase for stage of development |
| Test files | 3 | **Critically low** — only `AlamaScoreTest`, `EncryptionManagerTest`, `PinHasherTest` |
| TODO/FIXME/HACK/XXX | 14 | Moderate |
| Compilation errors | 85 | **Build-breaking** |
| Tool classes | 98 | Well-organized across 12 domain packages |
| Hilt parameters in `provideToolRegistry` | ~90 | **God method** — should be refactored |

### 4.2 Technical Debt

1. **God Method `provideToolRegistry()`** — 232 lines, ~90 parameters. This is the #1 architectural smell. Should be split into domain-specific `@IntoSet` multibinding modules.

2. **Feature modules are empty shells** — `:feature:finance`, `:feature:agriculture`, `:feature:market`, `:feature:credit` contain only marker classes. No actual UI code.

3. **Smart cast issues** — 7+ errors from Kotlin's inability to smart-cast public properties across module boundaries. Properties should use `val` with backing field or access via local variable.

4. **Inconsistent error handling** — Some tools use `try/catch` with `ToolResult.error()`, others let exceptions propagate.

5. **No ProGuard rules verification** — CI checks for file existence but doesn't validate rules against code.

6. **Missing `abs` import** — `FloatManager.kt` uses `abs()` without importing `kotlin.math.abs`.

7. **Kotlin keyword conflict** — `FishingLog.kt` uses `catch` as a property name without backtick escaping.

### 4.3 Test Coverage

**Near zero.** Only 3 test files exist:
- `AlamaScoreTest.kt` — Tests credit scoring algorithm
- `EncryptionManagerTest.kt` — Tests encryption
- `PinHasherTest.kt` — Tests PIN hashing

No tests for: tools, OODA loop, council system, knowledge graph, flywheel, guardrails, MPESA parsing, sync engine, or any UI components.

### 4.4 Dependency Health

| Dependency | Version | Status |
|------------|---------|--------|
| Kotlin | 2.1.0 | ✅ Current |
| KSP | 2.1.0-1.0.29 | ✅ Matches Kotlin |
| Hilt | 2.53.1 | ✅ Current |
| Room | 2.7.1 | ✅ Current |
| Retrofit | 2.11.0 | ✅ Current |
| OkHttp | 4.12.0 | ✅ Current |
| Compose BOM | 2024.12.01 | ✅ Recent |
| AGP | 8.7.3 | ✅ Current |
| SQLCipher | 4.5.4 | ✅ Current |
| Coroutines | 1.9.0 | ✅ Current |

All dependencies are current or near-current. No known vulnerable versions.

---

## 5. Recommendations

### 5.1 IMMEDIATE: Fix Compilation Errors (Unblock CI)

**Estimated effort:** 2-4 hours for an experienced Kotlin developer.

**Step 1: Fix `SyncEngine.kt` (6 errors)**
```kotlin
// Remove direct import — the type comes through SyncApi's return type
// import retrofit2.Response  ← DELETE THIS LINE

// The response from syncApi.syncAnonymized() already returns Response<SyncResponse>
// All usages of .isSuccessful, .body(), .code(), .message() work through the inferred type
```

Or alternatively, in `agent/build.gradle.kts`, ensure Retrofit is properly available:
```kotlin
implementation(libs.retrofit)  // Already present — verify it resolves
```

**Step 2: Fix `MpesaSmsReconciler.kt` (14 errors)**
- `recordRepayment()` → Use `debtRepaymentDao.insert()`
- `findByPhone()` → Verify `CustomerDao.findByPhone(phone: String): CustomerEntity?` signature
- `firstOrNull` → Import `kotlin.collections.firstOrNull` or use proper collection type
- `getAll()` → Verify the correct DAO method name and return type
- `name` → Verify entity property exists

**Step 3: Fix `CreditReadiness.kt` (12 errors)**
- Line 1333: `milestones` — Define the property or use correct reference
- Lines 1686-1762: Rewrite broken lambda chains with proper types
- Line 1747: Fix unsupported escape sequence (use `"""` raw string)
- Line 481: Cast `Any?` to `String` explicitly

**Step 4: Fix `AlamaScore.kt` (6 errors)**
```kotlin
// Replace List<DoubleArray> with Array<DoubleArray> or vice versa
// Use .toTypedArray() or .toList() at conversion points
```

**Step 5: Fix remaining errors (~47 across 15+ files)**
- `WeatherCacheManager.kt:860` — Fix map operation
- `OODALoop.kt:448` — `getToolNames` → `getAllTools().map { it.name }`
- `BulkOrderCoordinator.kt:786-790` — Fix candidate resolution
- `CustomerInsights.kt:144-145` — `'...'` → `"..."`
- `FishingLog.kt:526` — `catch` → `` `catch` ``
- `FloatManager.kt:173` — Add `import kotlin.math.abs`
- Type mismatches: Add `.toInt()`, `.toLong()`, `.toDouble()` as needed
- Visibility: Make private types `internal` or change property visibility
- Coroutine: Add `suspend` modifier where needed
- `when` exhaustiveness: Add missing branches or `else`

### 5.2 SHORT-TERM: Refactor `provideToolRegistry`

**Problem:** 90-parameter god method is fragile and KSP-hostile.

**Solution:** Use Hilt's `@IntoSet` multibinding:

```kotlin
// In each domain module, create a module that provides tools into a set:
@Module
@InstallIn(SingletonComponent::class)
object FinancialToolsModule {
    @Provides @IntoSet
    fun provideTransactionRecorder(tool: TransactionRecorder): Tool = tool
    
    @Provides @IntoSet
    fun provideDebtTracker(tool: DebtTracker): Tool = tool
    // ...
}

// In AppModule, inject the set:
@Provides @Singleton
fun provideToolRegistry(
    flywheelEngine: FlywheelEngine,
    tools: Set<@JvmSuppressWildcards Tool>
): ToolRegistry {
    val registry = ToolRegistry(flywheelEngine)
    tools.forEach { registry.register(it) }
    return registry
}
```

This eliminates the 90-parameter method entirely and makes KSP resolution incremental.

### 5.3 MEDIUM-TERM: Test Coverage

**Target:** 40%+ line coverage for critical paths.

Priority test targets:
1. `ToolRegistry` — Schema validation, tool lookup, execution
2. `MpesaSmsParser` — SMS parsing (high business value, easy to test)
3. `OODALoop` — Intent routing, tool selection
4. `AlamaScore` — Credit scoring algorithm (already has 1 test)
5. `SyncEngine` — Anonymization, retry logic
6. `FlywheelEngine` — Reliability tracking

### 5.4 MEDIUM-Term: CI Improvements

1. **Enable ktlint** — Add `org.jlleitschuh.gradle.ktlint` plugin
2. **Scan all modules for TODO/FIXME** — Not just `app/src/main/`
3. **Add dependency vulnerability scanning** — Use OWASP Dependency-Check plugin
4. **Cache Kotlin compilation** — Use Gradle build cache
5. **Add lint baseline** — Create `lint-baseline.xml` to track existing issues without blocking

### 5.5 LONG-TERM: Architecture

1. **Split feature modules** — Move tool implementations into their respective feature modules
2. **Add UI layer** — Feature modules are empty; build Compose screens
3. **Implement offline-first sync** — The `SyncEngine` exists but needs integration testing
4. **Model management** — The OTA model update system needs end-to-end testing
5. **Accessibility** — Voice-first design needs TalkBack/VoiceOver testing

---

## Appendix A: File-Level Error Summary

| File | Error Count | Severity |
|------|-------------|----------|
| `MpesaSmsReconciler.kt` | 14 | 🔴 Critical |
| `CreditReadiness.kt` | 12 | 🔴 Critical |
| `SyncEngine.kt` | 6 | 🔴 Critical |
| `AlamaScore.kt` | 6 | 🔴 Critical |
| `DebtTracker.kt` | 4 | 🟡 Medium |
| `BulkOrderCoordinator.kt` | 3 | 🟡 Medium |
| `CustomerInsights.kt` | 3 | 🟡 Medium |
| `WeatherCacheManager.kt` | 2 | 🟡 Medium |
| `HirePurchaseTracker.kt` | 2 | 🟡 Medium |
| `ChamaManager.kt` | 2 | 🟡 Medium |
| `OODALoop.kt` | 1 | 🟡 Medium |
| `OnboardingController.kt` | 1 | 🟡 Medium |
| `FishingLog.kt` | 1 | 🟡 Medium |
| `PostHarvestLossTracker.kt` | 1 | 🟡 Medium |
| `WasteReducer.kt` | 1 | 🟡 Medium |
| `GuardrailsTool.kt` | 1 | 🟡 Medium |
| `SOSSafetyButton.kt` | 1 | 🟡 Medium |
| `MpesaSmsParser.kt` | 1 | 🟡 Medium |
| `AlamaScoreValidator.kt` | 1 | 🟡 Medium |
| `PricingEngine.kt` | 1 | 🟡 Medium |
| `TaxComplianceTool.kt` | 1 | 🟡 Medium |
| `SeasonalBudgetPlanner.kt` | 1 | 🟡 Medium |
| `ProofOfIncome.kt` | 1 | 🟡 Medium |
| `PricingAdvisor.kt` | 1 | 🟡 Medium |
| `FloatManager.kt` | 1 | 🟡 Medium |
| `CFOReportReview.kt` | 1 | 🟢 Low |
| `CodeSwitchHandler.kt` | 1 | 🟢 Low |
| **TOTAL** | **85** | |

## Appendix B: Key File Locations

| Component | Path |
|-----------|------|
| Root build | `build.gradle.kts` |
| Version catalog | `gradle/libs.versions.toml` |
| Settings | `settings.gradle.kts` |
| App DI Module | `app/src/main/java/com/msaidizi/app/core/di/AppModule.kt` |
| Core DI Module | `core/src/main/java/com/msaidizi/core/di/AppModule.kt` |
| Network Module | `core/src/main/java/com/msaidizi/core/network/NetworkModule.kt` |
| Graph Module | `agent/src/main/java/com/msaidizi/agent/graph/GraphModule.kt` |
| Tool Interface | `agent/src/main/java/com/msaidizi/agent/tools/core/ToolRegistry.kt` |
| OODA Loop | `agent/src/main/java/com/msaidizi/agent/loops/OODALoop.kt` |
| CI Pipeline | `.github/workflows/ci.yml` |
| Build Pipeline | `.github/workflows/build-apk.yml` |
| Test Pipeline | `.github/workflows/test.yml` |
| Error Log | `all_ci_errors.txt` |
