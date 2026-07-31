package com.msaidizi.app.core.di

import com.msaidizi.agent.flywheel.FlywheelEngine
import com.msaidizi.agent.tools.core.*
import com.msaidizi.agent.tools.agriculture.FishTripPlanner
import com.msaidizi.agent.tools.agriculture.LivestockManager
import com.msaidizi.agent.tools.transport.DailyEarningsTracker
import com.msaidizi.agent.tools.transport.VehicleMaintenanceTracker
import com.msaidizi.agent.tools.financial.CommissionTracker
import com.msaidizi.agent.tools.financial.DailyWageTracker
import com.msaidizi.agent.tools.financial.FloatManager
import com.msaidizi.agent.tools.services.AppointmentManager
import com.msaidizi.agent.tools.services.ServiceHistory
import com.msaidizi.agent.tools.services.FundiJobQuoter
import com.msaidizi.agent.tools.services.MaterialCostCalculator
import com.msaidizi.agent.tools.services.CustomerRetention
import com.msaidizi.agent.tools.food.RecipeCostCalculator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * App-level Hilt module.
 * Provides the ToolRegistry (depends on :agent module).
 * DAOs and database are provided by core's AppModule.
 * Graph DAOs are provided by agent's GraphModule.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideToolRegistry(
        flywheelEngine: FlywheelEngine,
        transactionRecorder: TransactionRecorder,
        inventoryTracker: InventoryTracker,
        cfoEngine: CFOEngine,
        voicePipeline: VoicePipeline,
        languageDetector: LanguageDetector,
        codeSwitchHandler: CodeSwitchHandler,
        gamificationEngine: GamificationEngine,
        goalTracker: GoalTracker,
        receiptScanner: ReceiptScanner,
        whatsappReporter: WhatsAppReporter,
        syncEngine: SyncEngine,
        securityGuard: SecurityGuard,
        modelDownloader: ModelDownloader,
        adaptiveLearner: AdaptiveLearner,
        memoryManager: com.msaidizi.agent.memory.MemoryManager,
        guardrailsEngine: GuardrailsEngine,
        anomalyDetector: AnomalyDetector,
        mpesaParser: MpesaParser,
        pricingAdvisor: PricingAdvisor,
        restockPredictor: RestockPredictor,
        alamaScore: AlamaScore,
        serviceMenu: ServiceMenu,
        serviceVoiceCommands: ServiceVoiceCommands,
        debtTracker: DebtTracker,
        chamaManager: ChamaManager,
        customerInsights: CustomerInsights,
        marketPooling: MarketPooling,
        rideShare: RideShare,
        receiptScannerCV: ReceiptScannerCV,
        autoRestock: AutoRestock,
        bulkOrderCoordinator: BulkOrderCoordinator,
        businessHealthDashboard: BusinessHealthDashboard,
        competitorTracker: CompetitorTracker,
        creditReadiness: CreditReadiness,
        insuranceMatcher: InsuranceMatcher,
        loanComparison: LoanComparison,
        marketDayPlanner: MarketDayPlanner,
        marketPriceBroadcaster: MarketPriceBroadcaster,
        mpesaAutoLogger: MpesaAutoLogger,
        priceNegotiator: PriceNegotiator,
        profitByProduct: ProfitByProduct,
        proofOfIncome: ProofOfIncome,
        quickSale: QuickSale,
        supplierMatcher: SupplierMatcher,
        wasteReducer: WasteReducer,
        bookingScheduler: BookingScheduler,
        customerMatcher: CustomerMatcher,
        fishingLog: FishingLog,
        harvestTracker: HarvestTracker,
        jobMatcher: JobMatcher,
        miningLog: MiningLog,
        postHarvestLossTracker: PostHarvestLossTracker,
        producePriceTracker: ProducePriceTracker,
        ratingSystem: RatingSystem,
        serviceMarketBroadcaster: ServiceMarketBroadcaster,
        servicePriceAdvisor: ServicePriceAdvisor,
        wageCalculator: WageCalculator,
        yieldPredictor: YieldPredictor,
        // Boda boda tools
        sosSafetyButton: SOSSafetyButton,
        netIncomeCalculator: NetIncomeCalculator,
        fuelEfficiencyTracker: FuelEfficiencyTracker,
        fareIntelligence: FareIntelligence,
        hirePurchaseTracker: HirePurchaseTracker,
        // Consolidated tools (merged from former app/superagent/)
        demandForecaster: DemandForecaster,
        spoilageTracker: SpoilageTracker,
        harvestTimingOptimizer: HarvestTimingOptimizer,
        storageDecisionCalculator: StorageDecisionCalculator,
        seasonalBudgetPlanner: SeasonalBudgetPlanner,
        cfoReportReview: CFOReportReview,
        cfoReportReviewer: CFOReportReviewer,
        chamaApprovalWorkflow: ChamaApprovalWorkflow,
        creditDecisionApproval: CreditDecisionApproval,
        weatherCacheManager: WeatherCacheManager,
        weatherForecastService: WeatherForecastService,
        bodaBodaRouter: BodaBodaRouter,
        archetypeFinancialModel: ArchetypeFinancialModel,
        pricingEngine: PricingEngine,
        taxComplianceTool: TaxComplianceTool,
        // Build council tools
        fishTripPlanner: FishTripPlanner,
        livestockManager: LivestockManager,
        dailyEarningsTracker: DailyEarningsTracker,
        vehicleMaintenanceTracker: VehicleMaintenanceTracker,
        commissionTracker: CommissionTracker,
        dailyWageTracker: DailyWageTracker,
        floatManager: FloatManager,
        appointmentManager: AppointmentManager,
        serviceHistory: ServiceHistory,
        fundiJobQuoter: FundiJobQuoter,
        materialCostCalculator: MaterialCostCalculator,
        customerRetention: CustomerRetention,
        recipeCostCalculator: RecipeCostCalculator
    ): ToolRegistry {
        val registry = ToolRegistry(flywheelEngine)
        registry.register(transactionRecorder)
        registry.register(inventoryTracker)
        registry.register(cfoEngine)
        registry.register(voicePipeline)
        registry.register(languageDetector)
        registry.register(codeSwitchHandler)
        registry.register(gamificationEngine)
        registry.register(goalTracker)
        registry.register(receiptScanner)
        registry.register(whatsappReporter)
        registry.register(syncEngine)
        registry.register(securityGuard)
        registry.register(modelDownloader)
        registry.register(adaptiveLearner)
        registry.register(memoryManager)
        registry.register(guardrailsEngine)
        registry.register(anomalyDetector)
        registry.register(mpesaParser)
        registry.register(pricingAdvisor)
        registry.register(restockPredictor)
        registry.register(alamaScore)
        registry.register(serviceMenu)
        registry.register(serviceVoiceCommands)
        registry.register(debtTracker)
        registry.register(chamaManager)
        registry.register(customerInsights)
        registry.register(marketPooling)
        registry.register(rideShare)
        registry.register(receiptScannerCV)
        registry.register(autoRestock)
        registry.register(bulkOrderCoordinator)
        registry.register(businessHealthDashboard)
        registry.register(competitorTracker)
        registry.register(creditReadiness)
        registry.register(insuranceMatcher)
        registry.register(loanComparison)
        registry.register(marketDayPlanner)
        registry.register(marketPriceBroadcaster)
        registry.register(mpesaAutoLogger)
        registry.register(priceNegotiator)
        registry.register(profitByProduct)
        registry.register(proofOfIncome)
        registry.register(quickSale)
        registry.register(supplierMatcher)
        registry.register(wasteReducer)
        registry.register(bookingScheduler)
        registry.register(customerMatcher)
        registry.register(fishingLog)
        registry.register(harvestTracker)
        registry.register(jobMatcher)
        registry.register(miningLog)
        registry.register(postHarvestLossTracker)
        registry.register(producePriceTracker)
        registry.register(ratingSystem)
        registry.register(serviceMarketBroadcaster)
        registry.register(servicePriceAdvisor)
        registry.register(wageCalculator)
        registry.register(yieldPredictor)
        // Boda boda tools
        registry.register(sosSafetyButton)
        registry.register(netIncomeCalculator)
        registry.register(fuelEfficiencyTracker)
        registry.register(fareIntelligence)
        registry.register(hirePurchaseTracker)
        // Consolidated tools
        registry.register(demandForecaster)
        registry.register(spoilageTracker)
        registry.register(harvestTimingOptimizer)
        registry.register(storageDecisionCalculator)
        registry.register(seasonalBudgetPlanner)
        registry.register(cfoReportReview)
        registry.register(cfoReportReviewer)
        registry.register(chamaApprovalWorkflow)
        registry.register(creditDecisionApproval)
        registry.register(weatherCacheManager)
        registry.register(weatherForecastService)
        // cuOpt GPU-accelerated routing
        registry.register(bodaBodaRouter)
        // Per-archetype financial models, pricing, and tax compliance
        registry.register(archetypeFinancialModel)
        registry.register(pricingEngine)
        registry.register(taxComplianceTool)
        // Build council tools
        registry.register(fishTripPlanner)
        registry.register(livestockManager)
        registry.register(dailyEarningsTracker)
        registry.register(vehicleMaintenanceTracker)
        registry.register(commissionTracker)
        registry.register(dailyWageTracker)
        registry.register(floatManager)
        registry.register(appointmentManager)
        registry.register(serviceHistory)
        registry.register(fundiJobQuoter)
        registry.register(materialCostCalculator)
        registry.register(customerRetention)
        registry.register(recipeCostCalculator)
        return registry
    }
}
