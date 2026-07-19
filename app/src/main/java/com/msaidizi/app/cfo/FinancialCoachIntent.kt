package com.msaidizi.app.agent.coach

/**
 * Intent types specific to the AI Financial Coach pipeline.
 *
 * These extend the base IntentType enum and are handled by
 * the FinancialCoachOrchestrator's 3-agent pipeline.
 *
 * The coach pipeline: classify → analyze → strategize → advise
 *
 * @see BudgetAnalyzerAgent for transaction categorization
 * @see SavingsStrategistAgent for savings strategies
 * @see DebtAdvisorAgent for debt management
 */
enum class FinancialCoachIntent {
    // ── Budget Analysis ──
    /** "Nimetumia pesa gani wiki hii?" — Where did my money go? */
    SPENDING_ANALYSIS,

    /** "Ninatumia pesa nyingi wapi?" — Where am I overspending? */
    OVERSPENDING_DETECTION,

    /** "Budget yangu ikoje?" — How is my budget? */
    BUDGET_STATUS,

    /** "Niweke budget ya..." — Set a budget for... */
    BUDGET_CREATE,

    // ── Savings Strategy ──
    /** "Ninawezaje kuuza zaidi?" — How can I save more? */
    SAVINGS_ADVICE,

    /** "Niweke akiba ngapi?" — How much should I save? */
    SAVINGS_AMOUNT,

    /** "Akiba yangu ikoje?" — How is my savings? */
    SAVINGS_STATUS,

    /** "Lengo la akiba" — Savings goal */
    SAVINGS_GOAL,

    // ── Debt Management ──
    /** "Mkopo wangu" — My loan */
    DEBT_STATUS,

    /** "Nirudisheje mkopo?" — How should I repay? */
    DEBT_REPAYMENT_STRATEGY,

    /** "Ninakopa?" — Should I borrow? */
    BORROW_ADVICE,

    /** "Mkopo mwingine" — Another loan */
    DEBT_CONSOLIDATION,

    // ── Full Financial Health ──
    /** "Hali yangu ya fedha" — My financial health */
    FULL_HEALTH_CHECK,

    /** Coach greeting / onboarding */
    COACH_GREETING,

    /** Unknown coach intent */
    COACH_UNKNOWN
}
