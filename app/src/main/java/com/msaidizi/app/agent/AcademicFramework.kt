package com.msaidizi.app.agent

/**
 * Academic Framework — maps university degree units to Msaidizi agent types.
 *
 * Each unit in a typical CS/IS/Statistics degree maps to a specific
 * agent capability in the Msaidizi system. This serves as:
 * 1. Documentation: How academic concepts manifest in the product
 * 2. Training: What each agent needs to learn
 * 3. Validation: Ensures we cover the full academic spectrum
 *
 * The 42 units span:
 * - Computer Science (algorithms, data structures, systems)
 * - Information Systems (databases, networks, security)
 * - Statistics & Mathematics (probability, inference, ML)
 * - Business & Economics (accounting, finance, operations)
 * - HCI & Design (UX, accessibility, localization)
 *
 * @see DomainRouter for how agent types are selected
 * @see Orchestrator for how agents coordinate
 */
enum class AcademicUnit(
    val unitCode: String,
    val unitName: String,
    val agentType: AgentType,
    val description: String,
    val implementationFiles: List<String> = emptyList()
) {
    // ═══════════════════════════════════════════════════════════════
    // COMPUTER SCIENCE — Core
    // ═══════════════════════════════════════════════════════════════

    CS101(
        unitCode = "CS 101",
        unitName = "Introduction to Programming",
        agentType = AgentType.ORCHESTRATOR,
        description = "Basic control flow maps to Orchestrator's request routing and state machine",
        implementationFiles = listOf("Orchestrator.kt", "IntentRouter.kt")
    ),
    CS201(
        unitCode = "CS 201",
        unitName = "Data Structures & Algorithms",
        agentType = AgentType.ANALYSIS,
        description = "Hash maps for vocabulary, trees for decision routing, sorting for rankings",
        implementationFiles = listOf("AnalysisAgent.kt", "AdaptiveLearningEngine.kt")
    ),
    CS202(
        unitCode = "CS 202",
        unitName = "Object-Oriented Programming",
        agentType = AgentType.STRUCTURE,
        description = "Sealed classes for state, interfaces for providers, DI for loose coupling",
        implementationFiles = listOf("StsProvider.kt", "AgentEvent.kt", "AppModule.kt")
    ),
    CS301(
        unitCode = "CS 301",
        unitName = "Operating Systems",
        agentType = AgentType.SYSTEM,
        description = "Process management, memory mapping for models, thread scheduling for inference",
        implementationFiles = listOf("ModelManager.kt", "MemoryManager.kt", "AudioRecorder.kt")
    ),
    CS302(
        unitCode = "CS 302",
        unitName = "Computer Networks",
        agentType = AgentType.NETWORK,
        description = "HTTP/2 for API calls, WebSocket for STS streaming, TLS for security",
        implementationFiles = listOf("PinnedHttpClient.kt", "SyncManager.kt", "GptRealtimeProvider.kt")
    ),
    CS303(
        unitCode = "CS 303",
        unitName = "Compiler Design",
        agentType = AgentType.LANGUAGE,
        description = "Parsing Swahili text, tokenization, language model decoding",
        implementationFiles = listOf("SwahiliParser.kt", "WhisperTokenizer.kt", "IntentRouter.kt")
    ),
    CS304(
        unitCode = "CS 304",
        unitName = "Artificial Intelligence",
        agentType = AgentType.REASONING,
        description = "Search, planning, decision-making under uncertainty",
        implementationFiles = listOf("ReasoningTemplates.kt", "ReActLoop.kt", "PlanExecuteLoop.kt")
    ),
    CS305(
        unitCode = "CS 305",
        unitName = "Machine Learning",
        agentType = AgentType.LEARNING,
        description = "LoRA fine-tuning, federated learning, online learning, calibration",
        implementationFiles = listOf("FederatedLearningClient.kt", "AdaptiveAsrEngine.kt", "ConfidenceCalibrator.kt")
    ),
    CS306(
        unitCode = "CS 306",
        unitName = "Natural Language Processing",
        agentType = AgentType.LANGUAGE,
        description = "Speech recognition, intent classification, dialect normalization, translation",
        implementationFiles = listOf("SpeechRecognizer.kt", "DialectDetectionEngine.kt", "PhonemeMapper.kt")
    ),
    CS307(
        unitCode = "CS 307",
        unitName = "Computer Vision",
        agentType = AgentType.MULTIMODAL,
        description = "Image understanding for receipt scanning, product recognition (future)",
        implementationFiles = listOf("MultimodalPipeline.kt")
    ),
    CS308(
        unitCode = "CS 308",
        unitName = "Distributed Systems",
        agentType = AgentType.SYNC,
        description = "Federated learning, offline-first sync, conflict resolution",
        implementationFiles = listOf("FederatedLearningClient.kt", "SyncManager.kt", "SyncQueue.kt")
    ),
    CS309(
        unitCode = "CS 309",
        unitName = "Software Engineering",
        agentType = AgentType.EVOLUTION,
        description = "CI/CD, self-evolution, feature tracking, A/B testing",
        implementationFiles = listOf("SelfEvolutionManager.kt", "AutoUpdater.kt", "FeatureRequestTracker.kt")
    ),
    CS310(
        unitCode = "CS 310",
        unitName = "Mobile Computing",
        agentType = AgentType.DEVICE,
        description = "Battery optimization, memory management, offline-first, low-end device support",
        implementationFiles = listOf("BatteryOptimizer.kt", "DeviceTier.kt", "ModelManager.kt")
    ),

    // ═══════════════════════════════════════════════════════════════
    // INFORMATION SYSTEMS
    // ═══════════════════════════════════════════════════════════════

    IS101(
        unitCode = "IS 101",
        unitName = "Introduction to Information Systems",
        agentType = AgentType.ORCHESTRATOR,
        description = "System design, information flow, stakeholder analysis",
        implementationFiles = listOf("Orchestrator.kt", "BriefingDelivery.kt")
    ),
    IS201(
        unitCode = "IS 201",
        unitName = "Database Systems",
        agentType = AgentType.DATA,
        description = "Room DB, WAL mode, migrations, indexing, query optimization",
        implementationFiles = listOf("AppDatabase.kt", "TransactionDao.kt", "Converters.kt")
    ),
    IS202(
        unitCode = "IS 202",
        unitName = "Systems Analysis & Design",
        agentType = AgentType.ANALYSIS,
        description = "Business process modeling, requirement gathering from voice interactions",
        implementationFiles = listOf("BusinessPatternTracker.kt", "WorkerClassifier.kt")
    ),
    IS301(
        unitCode = "IS 301",
        unitName = "Information Security",
        agentType = AgentType.SECURITY,
        description = "End-to-end encryption, differential privacy, biometric auth, PQC",
        implementationFiles = listOf("KeyManager.kt", "ConsentManager.kt", "HybridKeyExchange.kt")
    ),
    IS302(
        unitCode = "IS 302",
        unitName = "Enterprise Systems",
        agentType = AgentType.BUSINESS,
        description = "ERP concepts for small business: inventory, accounting, CRM",
        implementationFiles = listOf("BusinessAgent.kt", "CFOEngine.kt", "BriefingDelivery.kt")
    ),
    IS303(
        unitCode = "IS 303",
        unitName = "Data Warehousing & Analytics",
        agentType = AgentType.ANALYSIS,
        description = "Business analytics, trend analysis, ABC analysis, forecasting",
        implementationFiles = listOf("AnalysisAgent.kt", "CusumDriftTracker.kt")
    ),
    IS304(
        unitCode = "IS 304",
        unitName = "E-Commerce Systems",
        agentType = AgentType.BUSINESS,
        description = "M-Pesa integration, digital payments, inventory management",
        implementationFiles = listOf("DarajaClient.kt", "MpesaStatementParser.kt")
    ),
    IS305(
        unitCode = "IS 305",
        unitName = "IT Project Management",
        agentType = AgentType.EVOLUTION,
        description = "Sprint planning, feature prioritization, feedback loops",
        implementationFiles = listOf("SelfEvolutionManager.kt", "FeedbackCollector.kt")
    ),

    // ═══════════════════════════════════════════════════════════════
    // STATISTICS & MATHEMATICS
    // ═══════════════════════════════════════════════════════════════

    STA101(
        unitCode = "STA 101",
        unitName = "Introduction to Statistics",
        agentType = AgentType.ANALYSIS,
        description = "Descriptive statistics for sales data, mean/median/mode for pricing",
        implementationFiles = listOf("AnalysisAgent.kt", "BusinessPatternTracker.kt")
    ),
    STA201(
        unitCode = "STA 201",
        unitName = "Probability & Distributions",
        agentType = AgentType.PROBABILITY,
        description = "Bayesian confidence calibration, Gaussian noise for differential privacy",
        implementationFiles = listOf("ConfidenceCalibrator.kt", "FederatedLearningClient.kt")
    ),
    STA301(
        unitCode = "STA 301",
        unitName = "Statistical Inference",
        agentType = AgentType.INFERENCE,
        description = "Hypothesis testing for convergence detection, confidence intervals for predictions",
        implementationFiles = listOf("FederatedLearningClient.kt", "CusumDriftTracker.kt")
    ),
    STA302(
        unitCode = "STA 302",
        unitName = "Regression Analysis",
        agentType = AgentType.PREDICTION,
        description = "Sales forecasting, price prediction, trend analysis",
        implementationFiles = listOf("AnalysisAgent.kt", "BusinessPatternTracker.kt")
    ),
    STA303(
        unitCode = "STA 303",
        unitName = "Time Series Analysis",
        agentType = AgentType.PREDICTION,
        description = "Seasonal patterns, CUSUM drift detection, moving averages",
        implementationFiles = listOf("CusumDriftTracker.kt", "BusinessPatternTracker.kt")
    ),
    STA304(
        unitCode = "STA 304",
        unitName = "Multivariate Analysis",
        agentType = AgentType.ANALYSIS,
        description = "ABC analysis (Pareto), multi-factor business health scoring",
        implementationFiles = listOf("AnalysisAgent.kt", "BusinessPatternTracker.kt")
    ),
    STA341(
        unitCode = "STA 341",
        unitName = "Estimation Theory",
        agentType = AgentType.ESTIMATION,
        description = "Learning rate scheduling for LoRA, MLE for calibration parameters",
        implementationFiles = listOf("FederatedLearningClient.kt", "ConfidenceCalibrator.kt")
    ),
    STA342(
        unitCode = "STA 342",
        unitName = "Hypothesis Testing",
        agentType = AgentType.TESTING,
        description = "Convergence detection t-test, A/B testing for feature evaluation",
        implementationFiles = listOf("FederatedLearningClient.kt", "SelfEvolutionManager.kt")
    ),
    STA401(
        unitCode = "STA 401",
        unitName = "Machine Learning Statistics",
        agentType = AgentType.LEARNING,
        description = "LoRA (low-rank adaptation), federated averaging, differential privacy",
        implementationFiles = listOf("FederatedLearningClient.kt", "AdaptiveAsrEngine.kt")
    ),
    MTH101(
        unitCode = "MTH 101",
        unitName = "Calculus I",
        agentType = AgentType.OPTIMIZATION,
        description = "Gradient descent for model optimization, loss functions",
        implementationFiles = listOf("FederatedLearningClient.kt")
    ),
    MTH201(
        unitCode = "MTH 201",
        unitName = "Linear Algebra",
        agentType = AgentType.OPTIMIZATION,
        description = "Matrix operations for LoRA (B·A decomposition), embeddings",
        implementationFiles = listOf("FederatedLearningClient.kt", "PhonemeMapper.kt")
    ),
    MTH301(
        unitCode = "MTH 301",
        unitName = "Numerical Methods",
        agentType = AgentType.OPTIMIZATION,
        description = "Numerical stability in inference, quantization (INT4), floating-point optimization",
        implementationFiles = listOf("SpeechRecognizer.kt", "LlamaCppEngine.kt")
    ),

    // ═══════════════════════════════════════════════════════════════
    // BUSINESS & ECONOMICS
    // ═══════════════════════════════════════════════════════════════

    BUS101(
        unitCode = "BUS 101",
        unitName = "Introduction to Business",
        agentType = AgentType.BUSINESS,
        description = "Business types, informal economy, market dynamics",
        implementationFiles = listOf("WorkerClassifier.kt", "BusinessAgent.kt")
    ),
    ACC101(
        unitCode = "ACC 101",
        unitName = "Financial Accounting",
        agentType = AgentType.FINANCE,
        description = "Cash flow tracking, profit/loss, balance sheets for micro-businesses",
        implementationFiles = listOf("CFOEngine.kt", "BusinessAgent.kt", "TransactionHandler.kt")
    ),
    ACC201(
        unitCode = "ACC 201",
        unitName = "Management Accounting",
        agentType = AgentType.FINANCE,
        description = "Cost analysis, break-even, margin calculation, pricing strategy",
        implementationFiles = listOf("CFOEngine.kt", "AdvisorAgent.kt")
    ),
    FIN201(
        unitCode = "FIN 201",
        unitName = "Corporate Finance",
        agentType = AgentType.FINANCE,
        description = "Working capital management, loan analysis, investment decisions",
        implementationFiles = listOf("LoanManager.kt", "GoalPlanner.kt", "TitheTracker.kt")
    ),
    ECO101(
        unitCode = "ECO 101",
        unitName = "Microeconomics",
        agentType = AgentType.ECONOMICS,
        description = "Supply/demand for pricing, elasticity for inventory, market structure",
        implementationFiles = listOf("BusinessPatternTracker.kt", "AdvisorAgent.kt")
    ),
    ECO201(
        unitCode = "ECO 201",
        unitName = "Macroeconomics",
        agentType = AgentType.ECONOMICS,
        description = "Inflation tracking, currency support, economic indicators",
        implementationFiles = listOf("AfricanCurrency.kt", "AfricanTimezone.kt")
    ),

    // ═══════════════════════════════════════════════════════════════
    // HCI & DESIGN
    // ═══════════════════════════════════════════════════════════════

    HCI101(
        unitCode = "HCI 101",
        unitName = "Human-Computer Interaction",
        agentType = AgentType.ACCESSIBILITY,
        description = "Voice-first UI, touch targets, error recovery, non-literate user support",
        implementationFiles = listOf("VoicePipeline.kt", "AccessibilityTtsHelper.kt", "VoiceButton.kt")
    ),
    HCI201(
        unitCode = "HCI 201",
        unitName = "User Interface Design",
        agentType = AgentType.ACCESSIBILITY,
        description = "Material Design, color contrast, font sizing for outdoor visibility",
        implementationFiles = listOf("Theme.kt", "StatCard.kt", "DashboardScreen.kt")
    ),
    HCI301(
        unitCode = "HCI 301",
        unitName = "Accessibility & Inclusive Design",
        agentType = AgentType.ACCESSIBILITY,
        description = "Screen reader support, voice output, high contrast, large touch targets",
        implementationFiles = listOf("AccessibilityTtsHelper.kt", "VoiceInputHelper.kt")
    );

    companion object {
        /**
         * Get all units that map to a specific agent type.
         */
        fun unitsForAgentType(type: AgentType): List<AcademicUnit> {
            return entries.filter { it.agentType == type }
        }

        /**
         * Get all unique agent types covered by the academic framework.
         */
        fun coveredAgentTypes(): Set<AgentType> {
            return entries.map { it.agentType }.toSet()
        }

        /**
         * Get coverage report: which agent types have academic backing.
         */
        fun coverageReport(): Map<AgentType, Int> {
            return entries.groupBy { it.agentType }.mapValues { it.value.size }
        }
    }
}

/**
 * Agent types derived from the academic framework.
 * Each agent type corresponds to a capability domain in Msaidizi.
 */
enum class AgentType {
    /** Core orchestration and routing */
    ORCHESTRATOR,
    /** Data analysis and insights */
    ANALYSIS,
    /** Business logic and operations */
    BUSINESS,
    /** Financial tracking and advice */
    FINANCE,
    /** Language processing and understanding */
    LANGUAGE,
    /** Learning and adaptation */
    LEARNING,
    /** Security and privacy */
    SECURITY,
    /** Network and sync */
    NETWORK,
    /** Sync and distributed */
    SYNC,
    /** System-level (OS, memory, battery) */
    SYSTEM,
    /** Device optimization */
    DEVICE,
    /** Data persistence */
    DATA,
    /** Evolution and self-improvement */
    EVOLUTION,
    /** Probability and statistics */
    PROBABILITY,
    /** Statistical inference */
    INFERENCE,
    /** Prediction and forecasting */
    PREDICTION,
    /** Estimation and calibration */
    ESTIMATION,
    /** Hypothesis testing */
    TESTING,
    /** Mathematical optimization */
    OPTIMIZATION,
    /** Economics */
    ECONOMICS,
    /** Reasoning and planning */
    REASONING,
    /** Multimodal (vision, audio) */
    MULTIMODAL,
    /** Accessibility and HCI */
    ACCESSIBILITY,
    /** Code structure and architecture */
    STRUCTURE
}
