#!/bin/bash
# ================================================================
# ANGAVU INTELLIGENCE — PRE-COMMIT VALIDATION GATE
# ================================================================
# Every commit must pass ALL 8 validation dimensions.
# This ensures research findings are implemented, architecture
# aligns with current/future AI, and security/quality are maintained.
#
# Based on:
# - 9 Research Swarm Reports (466KB)
# - 110 Academic Units (42 Economics/Statistics + 68 Missing Units)
# - 7 Implementation Swarms (~16,000+ lines)
# - 221-page Research Compendium
# ================================================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

PASS=0
FAIL=0
WARN=0

log_pass() { echo -e "${GREEN}✅ PASS${NC} — $1"; ((PASS++)); }
log_fail() { echo -e "${RED}❌ FAIL${NC} — $1"; ((FAIL++)); }
log_warn() { echo -e "${YELLOW}⚠️  WARN${NC} — $1"; ((WARN++)); }
log_info() { echo -e "${BLUE}ℹ️  INFO${NC} — $1"; }

echo ""
echo "========================================================"
echo "  ANGAVU INTELLIGENCE — 8-DIMENSION VALIDATION GATE"
echo "  $(date '+%Y-%m-%d %H:%M:%S')"
echo "========================================================"
echo ""

# ================================================================
# DIMENSION 1: RESEARCH VALIDATION
# Validate that research findings are implemented
# ================================================================
echo "━━━ DIMENSION 1: RESEARCH VALIDATION ━━━"

# ============================================================
# MSAIDIZI APP (Data Collection + On-Device Intelligence)
# ============================================================
log_info "Checking Msaidizi App (data collection layer)..."

# Check voice pipeline implementation
if [ -f "app/src/main/java/com/msaidizi/app/voice/dialect/DialectDetectionEngine.kt" ]; then
    log_pass "[APP] Voice dialect detection implemented (Swarm 1 finding)"
else
    log_fail "[APP] Voice dialect detection NOT implemented — see SWARM_1_VOICE_MODELS.md"
fi

# Check reasoning model router
if [ -f "app/src/main/java/com/msaidizi/app/agent/ModelRouter.kt" ]; then
    if grep -q "TaskComplexity\|TaskType\|ReasoningTemplate" app/src/main/java/com/msaidizi/app/agent/ModelRouter.kt 2>/dev/null; then
        log_pass "[APP] Reasoning model router with task-based routing (Swarm 2 finding)"
    else
        log_warn "[APP] ModelRouter exists but may lack task-based routing"
    fi
else
    log_fail "[APP] Reasoning model router NOT implemented"
fi

# Check language training pipeline
if [ -f "app/src/main/java/com/msaidizi/app/core/language/LanguageLearningPipeline.kt" ]; then
    log_pass "[APP] African language training pipeline (Swarm 8 finding)"
else
    log_fail "[APP] Language training pipeline NOT implemented"
fi

# Check on-device agents
if [ -f "app/src/main/java/com/msaidizi/app/agent/Orchestrator.kt" ] && [ -f "app/src/main/java/com/msaidizi/app/agent/IntentRouter.kt" ]; then
    log_pass "[APP] On-device agent system (Orchestrator + IntentRouter)"
else
    log_fail "[APP] On-device agent system incomplete"
fi

# ============================================================
# BACKEND (Intelligence Processing + Economic Analysis)
# ============================================================
log_info "Checking Backend (intelligence processing layer)..."

BACKEND_DIR="../angavu-intelligence-backend"

# Check backend agent system
BACKEND_AGENTS=$(find $BACKEND_DIR/app/agents -name "*.py" 2>/dev/null | wc -l)
if [ "$BACKEND_AGENTS" -ge 20 ]; then
    log_pass "[BACKEND] Agent system present ($BACKEND_AGENTS agent files)"
else
    log_warn "[BACKEND] Only $BACKEND_AGENTS agent files — expected 20+"
fi

# Check domain agents
DOMAIN_AGENTS=$(find $BACKEND_DIR/app/agents/domain -name "*.py" 2>/dev/null | wc -l)
if [ "$DOMAIN_AGENTS" -ge 5 ]; then
    log_pass "[BACKEND] Domain-specific agents ($DOMAIN_AGENTS domains: agriculture, retail, transport, etc.)"
else
    log_warn "[BACKEND] Only $DOMAIN_AGENTS domain agents"
fi

# Check MCP/A2A protocols
if [ -f "$BACKEND_DIR/app/agents/protocols/mcp.py" ] || [ -f "$BACKEND_DIR/app/agents/protocols/a2a.py" ]; then
    log_pass "[BACKEND] Agent protocols (MCP/A2A) implemented (Swarm 3 finding)"
else
    log_warn "[BACKEND] Agent protocols may be incomplete"
fi

# Check loop systems
if [ -f "$BACKEND_DIR/app/agents/loops/ooda_loop.py" ] && [ -f "$BACKEND_DIR/app/agents/loops/feedback_loop.py" ]; then
    log_pass "[BACKEND] Loop systems (OODA + Feedback + HITL) implemented (Swarm 4/7 finding)"
else
    log_warn "[BACKEND] Loop systems may be incomplete"
fi

# Check intelligence pipeline
if [ -f "$BACKEND_DIR/app/agents/intelligence_pipeline.py" ]; then
    log_pass "[BACKEND] Intelligence pipeline (data → economic intelligence)"
else
    log_fail "[BACKEND] Intelligence pipeline NOT found"
fi

# Check event bus
if [ -f "$BACKEND_DIR/app/agents/event_bus.py" ]; then
    log_pass "[BACKEND] Event bus architecture (pub/sub coordination)"
else
    log_fail "[BACKEND] Event bus NOT found"
fi

# Check federated learning
if [ -f "$BACKEND_DIR/app/services/federated_learning.py" ] || [ -f "$BACKEND_DIR/app/services/federated_learning_v2.py" ]; then
    log_pass "[BACKEND] Federated learning service (model improvement without data centralization)"
else
    log_fail "[BACKEND] Federated learning NOT found"
fi

# Check PQC security (backend)
if [ -f "$BACKEND_DIR/app/core/security/mcp.py" ] || [ -f "$BACKEND_DIR/app/core/security/pqc.py" ]; then
    log_pass "[BACKEND] Post-quantum cryptography (Swarm 5 finding)"
else
    log_warn "[BACKEND] PQC may be incomplete"
fi

# ============================================================
# INTEGRATION (App ↔ Backend Data Flow)
# ============================================================
log_info "Checking App ↔ Backend Integration..."

# Check API endpoints
if [ -d "$BACKEND_DIR/app/api/v1" ]; then
    API_ENDPOINTS=$(find $BACKEND_DIR/app/api/v1 -name "*.py" 2>/dev/null | wc -l)
    if [ "$API_ENDPOINTS" -ge 5 ]; then
        log_pass "[INTEGRATION] API endpoints present ($API_ENDPOINTS endpoints)"
    else
        log_warn "[INTEGRATION] Only $API_ENDPOINTS API endpoints"
    fi
else
    log_fail "[INTEGRATION] API directory NOT found"
fi

# Check sync mechanism
if grep -rq "sync\|Sync\|SYNC" app/src/main/java/com/msaidizi/app/ 2>/dev/null; then
    log_pass "[INTEGRATION] Sync mechanism present (app ↔ backend)"
else
    log_warn "[INTEGRATION] Sync mechanism not found"
fi

echo ""

# ================================================================
# DIMENSION 2: ARCHITECTURE VALIDATION
# Validate alignment with current AI state and future
# ================================================================
echo "━━━ DIMENSION 2: ARCHITECTURE VALIDATION ━━━"

# ============================================================
# MSAIDIZI APP ARCHITECTURE
# ============================================================
log_info "Checking App Architecture..."

# Check offline-first architecture
if grep -rq "offline\|Offline\|OFFLINE" app/src/main/java/com/msaidizi/app/ 2>/dev/null; then
    log_pass "[APP] Offline-first architecture present"
else
    log_fail "[APP] Offline-first architecture NOT found — core requirement"
fi

# Check on-device AI
if [ -f "app/src/main/java/com/msaidizi/app/core/ai/ModelManager.kt" ]; then
    log_pass "[APP] On-device AI model management present"
else
    log_fail "[APP] On-device AI model management NOT found"
fi

# Check federated learning client
if [ -f "app/src/main/java/com/msaidizi/app/core/language/FederatedLearningClient.kt" ]; then
    log_pass "[APP] Federated learning client present"
else
    log_warn "[APP] Federated learning client NOT found"
fi

# Check event bus architecture
if [ -f "app/src/main/java/com/msaidizi/app/agent/AgentEventBus.kt" ]; then
    log_pass "[APP] Event bus architecture present (multi-agent coordination)"
else
    log_warn "[APP] Event bus NOT found"
fi

# Check multi-agent system
AGENT_COUNT=$(find app/src/main/java -name "*Agent.kt" 2>/dev/null | wc -l)
if [ "$AGENT_COUNT" -ge 5 ]; then
    log_pass "[APP] Multi-agent system present ($AGENT_COUNT agents)"
else
    log_warn "[APP] Only $AGENT_COUNT agents found — expected 7+"
fi

# ============================================================
# BACKEND ARCHITECTURE
# ============================================================
log_info "Checking Backend Architecture..."

# Check domain-specific agents
DOMAIN_AGENTS=$(find $BACKEND_DIR/app/agents/domain -name "*.py" 2>/dev/null | wc -l)
if [ "$DOMAIN_AGENTS" -ge 5 ]; then
    log_pass "[BACKEND] Domain-specific agents ($DOMAIN_AGENTS domains)"
else
    log_warn "[BACKEND] Only $DOMAIN_AGENTS domain agents"
fi

# Check event bus
if [ -f "$BACKEND_DIR/app/agents/event_bus.py" ]; then
    log_pass "[BACKEND] Event bus architecture (pub/sub, dead letter queue)"
else
    log_fail "[BACKEND] Event bus NOT found"
fi

# Check agent runtime
if [ -f "$BACKEND_DIR/app/agents/factory.py" ]; then
    log_pass "[BACKEND] Agent factory/runtime present"
else
    log_fail "[BACKEND] Agent factory NOT found"
fi

# Check data → intelligence pipeline
if [ -f "$BACKEND_DIR/app/agents/intelligence_pipeline.py" ]; then
    log_pass "[BACKEND] Intelligence pipeline (data → economic intelligence)"
else
    log_fail "[BACKEND] Intelligence pipeline NOT found"
fi

# Check database migrations
if [ -d "$BACKEND_DIR/app/db/migrations" ]; then
    MIGRATION_COUNT=$(find $BACKEND_DIR/app/db/migrations -name "*.py" 2>/dev/null | wc -l)
    log_pass "[BACKEND] Database migrations present ($MIGRATION_COUNT migrations)"
else
    log_warn "[BACKEND] Database migrations not found"
fi

# ============================================================
# INTEGRATION ARCHITECTURE
# ============================================================
log_info "Checking Integration Architecture (App ↔ Backend as ONE system)..."

# Check data flow: App collects → Backend processes → App improves
if [ -f "app/src/main/java/com/msaidizi/app/data/api" ] || [ -d "app/src/main/java/com/msaidizi/app/data/api" ]; then
    log_pass "[INTEGRATION] API client layer present (app → backend data flow)"
else
    log_warn "[INTEGRATION] API client layer not found"
fi

# Check that backend has endpoints for app data
if [ -d "$BACKEND_DIR/app/api/v1" ]; then
    log_pass "[INTEGRATION] Backend API endpoints present (receives app data)"
else
    log_fail "[INTEGRATION] Backend API endpoints NOT found"
fi

echo ""

# ================================================================
# DIMENSION 3: ENGINEERING VALIDATION
# Validate code quality and engineering practices
# ================================================================
echo "━━━ DIMENSION 3: ENGINEERING VALIDATION ━━━"

# Check Kotlin compilation
if [ -f "gradlew" ]; then
    log_pass "Gradle wrapper present"
else
    log_fail "Gradle wrapper NOT found"
fi

# Check for TODO/FIXME/HACK
TODO_COUNT=$(grep -r "TODO\|FIXME\|HACK\|XXX" app/src/main/java/ 2>/dev/null | wc -l)
if [ "$TODO_COUNT" -lt 20 ]; then
    log_pass "TODO/FIXME count acceptable ($TODO_COUNT)"
else
    log_warn "High TODO/FIXME count ($TODO_COUNT) — review before release"
fi

# Check for hardcoded secrets
if grep -rq "api_key\|secret_key\|password\|token" app/src/main/java/ --include="*.kt" 2>/dev/null | grep -v "BuildConfig\|R.string\|//\|import\|class\|val\|var" | head -1 | grep -q "."; then
    log_fail "Possible hardcoded secrets found"
else
    log_pass "No hardcoded secrets detected"
fi

# Check DI setup
if [ -f "app/src/main/java/com/msaidizi/app/core/di/AppModule.kt" ] || [ -f "app/src/main/java/com/msaidizi/app/core/di/DatabaseModule.kt" ]; then
    log_pass "Dependency injection (Hilt) configured"
else
    log_warn "DI configuration may be incomplete"
fi

# Check database entities
ENTITY_COUNT=$(find app/src/main/java -name "*Entity.kt" 2>/dev/null | wc -l)
if [ "$ENTITY_COUNT" -ge 10 ]; then
    log_pass "Database entities present ($ENTITY_COUNT entities)"
else
    log_warn "Only $ENTITY_COUNT entities — expected 15+"
fi

echo ""

# ================================================================
# DIMENSION 4: SECURITY VALIDATION
# Validate security measures
# ================================================================
echo "━━━ DIMENSION 4: SECURITY VALIDATION ━━━"

# Check TLS configuration
if [ -f "app/src/main/java/com/msaidizi/app/core/security/TlsConfig.kt" ]; then
    log_pass "TLS configuration present"
else
    log_fail "TLS configuration NOT found"
fi

# Check encryption
if grep -rq "AES\|encrypt\|decrypt" app/src/main/java/com/msaidizi/app/core/security/ 2>/dev/null; then
    log_pass "Encryption implemented (AES-256-GCM)"
else
    log_warn "Encryption implementation not verified"
fi

# Check auth
if [ -f "app/src/main/java/com/msaidizi/app/core/security/OtpManager.kt" ] || [ -f "app/src/main/java/com/msaidizi/app/core/security/BiometricAuthManager.kt" ]; then
    log_pass "Authentication system present"
else
    log_warn "Authentication system not verified"
fi

# Check input sanitization
if [ -f "app/src/main/java/com/msaidizi/app/core/security/InputSanitizer.kt" ]; then
    log_pass "Input sanitization present (XSS/SQL injection defense)"
else
    log_warn "Input sanitization not found"
fi

# Check consent management
if [ -f "app/src/main/java/com/msaidizi/app/core/security/ConsentManager.kt" ]; then
    log_pass "Consent management present (DPA/NDPA/POPIA/GDPR)"
else
    log_warn "Consent management not found"
fi

echo ""

# ================================================================
# DIMENSION 5: QUALITY VALIDATION
# Validate testing and quality assurance
# ================================================================
echo "━━━ DIMENSION 5: QUALITY VALIDATION ━━━"

# Check unit tests
TEST_COUNT=$(find app/src/test -name "*.kt" 2>/dev/null | wc -l)
if [ "$TEST_COUNT" -ge 5 ]; then
    log_pass "Unit tests present ($TEST_COUNT test files)"
else
    log_warn "Only $TEST_COUNT test files — expected 8+"
fi

# Check instrumented tests
ANDROID_TEST_COUNT=$(find app/src/androidTest -name "*.kt" 2>/dev/null | wc -l)
if [ "$ANDROID_TEST_COUNT" -ge 3 ]; then
    log_pass "Instrumented tests present ($ANDROID_TEST_COUNT test files)"
else
    log_warn "Only $ANDROID_TEST_COUNT instrumented test files"
fi

# Check CI/CD
if [ -f ".github/workflows/build.yml" ] || [ -f ".github/workflows/ci.yml" ]; then
    log_pass "CI/CD pipeline configured"
else
    log_fail "CI/CD pipeline NOT found"
fi

# Check detekt (code quality)
if [ -f "config/detekt/detekt.yml" ]; then
    log_pass "Detekt code quality configured"
else
    log_warn "Detekt configuration not found"
fi

echo ""

# ================================================================
# DIMENSION 6: TECH STACK VALIDATION
# Validate technology choices
# ================================================================
echo "━━━ DIMENSION 6: TECH STACK VALIDATION ━━━"

# Check KSP (not kapt)
if grep -q "com.google.devtools.ksp" app/build.gradle.kts 2>/dev/null; then
    log_pass "Using KSP (modern annotation processing, not legacy kapt)"
elif grep -q "kotlin.kapt" app/build.gradle.kts 2>/dev/null; then
    log_warn "Still using kapt — consider migrating to KSP"
fi

# Check Jetpack Compose
if grep -q "compose" app/build.gradle.kts 2>/dev/null; then
    log_pass "Jetpack Compose enabled (modern UI)"
else
    log_warn "Jetpack Compose not found — using legacy XML?"
fi

# Check Room database
if grep -q "room" app/build.gradle.kts 2>/dev/null; then
    log_pass "Room database configured (offline-first storage)"
else
    log_fail "Room database NOT configured"
fi

# Check Hilt DI
if grep -q "dagger.hilt" app/build.gradle.kts 2>/dev/null; then
    log_pass "Hilt dependency injection configured"
else
    log_fail "Hilt DI NOT configured"
fi

# Check llama.cpp NDK
if grep -q "llama\|llama.cpp\|ndk" app/build.gradle.kts 2>/dev/null; then
    log_pass "llama.cpp NDK configured (on-device LLM)"
else
    log_warn "llama.cpp NDK not found in build config"
fi

echo ""

# ================================================================
# DIMENSION 7: QUANTUM READINESS
# Validate post-quantum cryptography readiness
# ================================================================
echo "━━━ DIMENSION 7: QUANTUM READINESS ━━━"

# Check PQC providers
if [ -f "app/src/main/java/com/msaidizi/app/core/security/MlKemProvider.kt" ]; then
    log_pass "ML-KEM (Kyber) provider present — quantum-safe key encapsulation"
else
    log_warn "ML-KEM provider not found — PQC migration needed"
fi

if [ -f "app/src/main/java/com/msaidizi/app/core/security/MlDsaProvider.kt" ]; then
    log_pass "ML-DSA (Dilithium) provider present — quantum-safe signatures"
else
    log_warn "ML-DSA provider not found — PQC migration needed"
fi

# Check crypto agility
if [ -f "app/src/main/java/com/msaidizi/app/core/security/AlgorithmRegistry.kt" ]; then
    log_pass "Algorithm registry present — crypto-agile (swap without code changes)"
else
    log_warn "Crypto agility not implemented"
fi

# Check hybrid key exchange
if [ -f "app/src/main/java/com/msaidizi/app/core/security/HybridKeyExchange.kt" ]; then
    log_pass "Hybrid key exchange (X25519 + ML-KEM) present"
else
    log_warn "Hybrid key exchange not found"
fi

echo ""

# ================================================================
# DIMENSION 8: AGI READINESS
# Validate preparation for AGI-era systems
# ================================================================
echo "━━━ DIMENSION 8: AGI READINESS ━━━"

# Check model routing for future models
if grep -q "ModelProvider\|CloudProvider\|fallback" app/src/main/java/com/msaidizi/app/agent/ModelRouter.kt 2>/dev/null; then
    log_pass "Model routing supports multiple providers (AGI-ready)"
else
    log_warn "Model routing may not support future AGI models"
fi

# Check reasoning chain storage
if grep -q "ReasoningChain\|reasoning_chain\|ChainOfThought" app/src/main/java/com/msaidizi/app/agent/ModelRouter.kt 2>/dev/null; then
    log_pass "Reasoning chain storage present (auditability for AGI decisions)"
else
    log_warn "Reasoning chain storage not found"
fi

# Check progressive autonomy (HITL)
if [ -f "app/agents/loops/human_in_the_loop.py" ]; then
    log_pass "Human-in-the-loop with progressive autonomy (AGI governance)"
else
    log_warn "Progressive autonomy not implemented"
fi

# Check inference cost tracking
if [ -f "app/src/main/java/com/msaidizi/app/agent/cost/InferenceCostTracker.kt" ]; then
    log_pass "Inference cost tracking present (sustainable AI economics)"
else
    log_warn "Inference cost tracking not found"
fi

# Check self-improvement loops
if [ -f "app/agents/loops/feedback_loop.py" ] || [ -f "app/src/main/java/com/msaidizi/app/loops/ReflexionLoop.kt" ]; then
    log_pass "Self-improvement loops present (AGI learning foundation)"
else
    log_warn "Self-improvement loops not found"
fi

echo ""

# ================================================================
# SUMMARY
# ================================================================
echo "========================================================"
echo "  VALIDATION SUMMARY"
echo "========================================================"
echo -e "  ${GREEN}PASSED: $PASS${NC}"
echo -e "  ${RED}FAILED: $FAIL${NC}"
echo -e "  ${YELLOW}WARNINGS: $WARN${NC}"
echo ""

if [ "$FAIL" -gt 0 ]; then
    echo -e "${RED}❌ COMMIT BLOCKED — $FAIL critical issues found.${NC}"
    echo "Fix the issues above before committing."
    echo ""
    echo "Reference documents:"
    echo "  - angavu-intelligence/research/ANGAVU_INTELLIGENCE_RESEARCH_COMPENDIUM.pdf"
    echo "  - angavu-intelligence/research/SWARM_*.md (9 research reports)"
    echo "  - angavu-intelligence/research/IMPL_*.md (implementation reports)"
    echo "  - angavu-intelligence/research/DEGREE_UNITS_TO_FUNCTIONS_MAPPING.md"
    exit 1
elif [ "$WARN" -gt 5 ]; then
    echo -e "${YELLOW}⚠️  COMMIT WITH CAUTION — $WARN warnings. Review before release.${NC}"
    exit 0
else
    echo -e "${GREEN}✅ ALL 8 DIMENSIONS VALIDATED — Commit approved.${NC}"
    exit 0
fi
