"""
Goal Achievement Service — helps workers actually reach their goals.

The difference between setting a goal and achieving it:
- Setting: "Ninataka kununua friji" (I want to buy a fridge)
- Achieving: Actually saving KSh 500/day for 50 days and buying it

This service bridges that gap through:
1. Data-driven predictions (when will you reach your goal?)
2. Obstacle identification (what's stopping you?)
3. Adjustment recommendations (what to change)
4. Milestone celebrations (encouragement along the way)

Academic foundations:
- STA 244 (Time Series): Trend analysis of goal progress
- STA 341 (Estimation): Forecasting time to goal
- ECO 206 (Microfinance): Savings behavior optimization
- ECO 210 (Quantitative Methods): Break-even analysis
"""

import asyncio
from datetime import datetime, timedelta
from typing import Optional
from dataclasses import dataclass, field
import math


@dataclass
class ProgressPoint:
    """A single progress data point."""
    timestamp: datetime
    amount: float
    note: str = ""


@dataclass
class GoalData:
    """Goal data from the client."""
    goal_id: str
    worker_id: str
    description: str
    target_amount: float
    current_amount: float
    deadline: datetime
    category: str
    progress_entries: list = field(default_factory=list)
    created_at: datetime = field(default_factory=datetime.now)
    is_active: bool = True


@dataclass
class GoalPrediction:
    """Prediction of when a goal will be achieved."""
    goal_id: str
    predicted_days: int
    predicted_date: datetime
    daily_rate_needed: float
    current_daily_rate: float
    is_on_track: bool
    confidence: float
    message_swahili: str


@dataclass
class Obstacle:
    """An obstacle to goal achievement."""
    obstacle_type: str  # "spending", "income_drop", "seasonal", "irregular"
    severity: str  # "high", "medium", "low"
    description: str
    impact_days: int  # How many extra days this adds
    recommendation_swahili: str


@dataclass
class Adjustment:
    """A recommended adjustment to reach the goal."""
    adjustment_type: str  # "save_more", "extend_deadline", "reduce_target", "earn_more"
    description: str
    new_daily_target: Optional[float] = None
    new_deadline: Optional[datetime] = None
    message_swahili: str = ""


class GoalAchievementService:
    """
    Helps workers achieve their goals through data-driven insights.

    The service analyzes:
    - Historical progress patterns (how fast are they saving?)
    - Spending behavior (what's eating their money?)
    - Income patterns (when do they earn most?)
    - Seasonal effects (holidays, school fees, etc.)

    Returns actionable Swahili recommendations.
    """

    # Minimum data points for reliable predictions
    MIN_DATA_POINTS = 3

    # Confidence thresholds
    HIGH_CONFIDENCE = 0.80
    MEDIUM_CONFIDENCE = 0.60

    # Savings behavior optimization (ECO 206)
    OPTIMAL_SAVINGS_RATIO = 0.20  # 20% of income
    MIN_SAVINGS_RATIO = 0.10  # 10% minimum

    # Seasonal patterns in Kenya
    SEASONAL_EXPENSES = {
        1: "Januari — Shule inaanza, ada ya shule inahitajika",
        2: "Februari — Muda wa kawaida, akiba ni nzuri",
        3: "Machi — Msimu wa mvua, biashara inaweza kupungua",
        4: "Aprili — Sikukuu za Pasaka, matumizi ya ziada",
        5: "Mei — Muda wa kawaida",
        6: "Juni — Mwisho wa muhula, ada ya shule",
        7: "Julai — Msimu wa baridi, biashara inaweza kuathiriwa",
        8: "Agosti — Mwisho wa muhula wa pili",
        9: "Septembi — Shule inaanza, ada ya shule",
        10: "Oktoba — Muda wa kawaida, akiba ni nzuri",
        11: "Novembi — Msimu wa kuanza Krismasi",
        12: "Desembi — Krismasi, matumizi mengi"
    }

    async def predict_goal_achievement(
        self,
        worker_id: str,
        goal_id: str,
        goal_data: Optional[GoalData] = None
    ) -> GoalPrediction:
        """
        Predict when a worker will achieve their goal.

        Uses linear regression on progress entries (STA 244: time series).
        Accounts for:
        - Current savings rate
        - Historical variance (how consistent are they?)
        - Remaining amount

        Args:
            worker_id: Worker identifier
            goal_id: Goal identifier
            goal_data: Goal data (would come from database in production)

        Returns:
            GoalPrediction with Swahili message
        """
        # In production, fetch from database
        if goal_data is None:
            # Placeholder for API integration
            return GoalPrediction(
                goal_id=goal_id,
                predicted_days=0,
                predicted_date=datetime.now(),
                daily_rate_needed=0,
                current_daily_rate=0,
                is_on_track=False,
                confidence=0,
                message_swahili="Data haijapatikana. Tafadhali rekodi mauzo yako."
            )

        remaining = goal_data.target_amount - goal_data.current_amount
        days_to_deadline = max(1, (goal_data.deadline - datetime.now()).days)

        # Calculate daily rate from progress entries
        entries = goal_data.progress_entries
        if len(entries) < self.MIN_DATA_POINTS:
            daily_needed = remaining / days_to_deadline
            return GoalPrediction(
                goal_id=goal_id,
                predicted_days=days_to_deadline,
                predicted_date=goal_data.deadline,
                daily_rate_needed=daily_needed,
                current_daily_rate=0,
                is_on_track=False,
                confidence=0.0,
                message_swahili=(
                    f"Bado nakusanya data. Rekodi mauzo yako kila siku.\n"
                    f"Unahitaji kuweka KSh {daily_needed:,.0f} kwa siku."
                )
            )

        # Sort entries by time
        entries.sort(key=lambda e: e.timestamp)

        # Calculate daily rate
        time_span_days = max(1, (entries[-1].timestamp - entries[0].timestamp).days)
        total_saved = sum(e.amount for e in entries)
        daily_rate = total_saved / time_span_days

        # Calculate variance for confidence (STA 341)
        if len(entries) > 1:
            daily_amounts = []
            for i in range(1, len(entries)):
                days_diff = max(1, (entries[i].timestamp - entries[i-1].timestamp).days)
                daily_amounts.append(entries[i].amount / days_diff)
            
            if daily_amounts:
                mean_daily = sum(daily_amounts) / len(daily_amounts)
                variance = sum((x - mean_daily) ** 2 for x in daily_amounts) / len(daily_amounts)
                std_dev = math.sqrt(variance)
                # Coefficient of variation — lower is more consistent
                cv = std_dev / mean_daily if mean_daily > 0 else 1.0
                confidence = max(0.3, 1.0 - cv)
            else:
                confidence = self.MEDIUM_CONFIDENCE
        else:
            confidence = self.MEDIUM_CONFIDENCE

        # Predict days to goal
        predicted_days = int(remaining / daily_rate) if daily_rate > 0 else 999
        predicted_date = datetime.now() + timedelta(days=predicted_days)
        daily_needed = remaining / days_to_deadline
        is_on_track = predicted_days <= days_to_deadline

        # Generate message
        if goal_data.current_amount >= goal_data.target_amount:
            message = "Umefikia lengo lako! Hongera! 🎉"
        elif is_on_track:
            message = (
                f"Kwa kasi hii, utafikia lengo lako siku {predicted_days}. "
                f"Uko kwenye njia sahihi! 👍\n"
                f"Unaweka KSh {daily_rate:,.0f} kwa siku."
            )
        else:
            extra_days = predicted_days - days_to_deadline
            message = (
                f"Kwa kasi hii, utahitaji siku {predicted_days} — ni siku {extra_days} "
                f"zaidi ya tarehe yako ya mwisho.\n\n"
                f"Ushauri:\n"
                f"• Ongeza akiba yako ya siku hadi KSh {daily_needed:,.0f}\n"
                f"• Au ongeza tarehe ya mwisho kwa siku {extra_days}"
            )

        return GoalPrediction(
            goal_id=goal_id,
            predicted_days=predicted_days,
            predicted_date=predicted_date,
            daily_rate_needed=daily_needed,
            current_daily_rate=daily_rate,
            is_on_track=is_on_track,
            confidence=confidence,
            message_swahili=message
        )

    async def identify_obstacles(
        self,
        worker_id: str,
        goal_id: str,
        transactions: list = None,
        goal_data: Optional[GoalData] = None
    ) -> dict:
        """
        Identify obstacles preventing goal achievement.

        Analyzes:
        - Spending patterns (ECO 206: savings behavior)
        - Income fluctuations (STA 244: time series)
        - Seasonal effects (Kenya-specific patterns)
        - Transaction irregularity

        Args:
            worker_id: Worker identifier
            goal_id: Goal identifier
            transactions: Recent transactions (for spending analysis)
            goal_data: Goal data

        Returns:
            Dict with obstacles list and Swahili message
        """
        obstacles: list[Obstacle] = []

        # Check seasonal obstacles
        current_month = datetime.now().month
        seasonal_note = self.SEASONAL_EXPENSES.get(current_month, "")

        if current_month in [1, 6, 9]:  # School fee months
            obstacles.append(Obstacle(
                obstacle_type="seasonal",
                severity="medium",
                description="Msimu wa ada ya shule",
                impact_days=15,
                recommendation_swahili=(
                    "Mwezi huu ada ya shule inaweza kupunguza akiba yako. "
                    "Fikiria kuongeza akiba katika miezi mingine."
                )
            ))

        if current_month == 12:  # December spending
            obstacles.append(Obstacle(
                obstacle_type="seasonal",
                severity="high",
                description="Krismasi — matumizi mengi",
                impact_days=20,
                recommendation_swahili=(
                    "Desembi ni mwezi wa matumizi mengi. Weka akiba kabla ya Krismasi "
                    "na upunguze matumizi yasiyo ya lazima."
                )
            ))

        # Check spending patterns if transactions available
        if transactions:
            # Calculate expense ratio (ECO 206)
            total_income = sum(
                t.get("amount", 0) for t in transactions
                if t.get("type") == "SALE"
            )
            total_expenses = sum(
                t.get("amount", 0) for t in transactions
                if t.get("type") in ["EXPENSE", "PURCHASE"]
            )

            if total_income > 0:
                expense_ratio = total_expenses / total_income
                if expense_ratio > 0.8:
                    obstacles.append(Obstacle(
                        obstacle_type="spending",
                        severity="high",
                        description="Matumizi ni makubwa kuliko mapato",
                        impact_days=30,
                        recommendation_swahili=(
                            "Matumizi yako ni {expense_ratio:.0f}% ya mapato yako. "
                            "Jaribu kupunguza matumizi yasiyo ya lazima — "
                            "kama vile pombe, au chakula cha nje."
                        ).format(expense_ratio=expense_ratio * 100)
                    ))

                # Check for non-essential spending
                non_essential = sum(
                    t.get("amount", 0) for t in transactions
                    if t.get("category") in ["entertainment", "alcohol", "eating_out"]
                )
                if non_essential > total_income * 0.1:
                    obstacles.append(Obstacle(
                        obstacle_type="spending",
                        severity="medium",
                        description="Matumizi ya ziada (burudani/chakula cha nje)",
                        impact_days=10,
                        recommendation_swahili=(
                            f"Umekuwa ukifanya matumizi ya KSh {non_essential:,.0f} "
                            f"kwenye burudani. Punguza kidogo — itakusaidia kufikia lengo lako haraka."
                        )
                    ))

        # Generate summary message
        if not obstacles:
            message = "Hakuna vizuizi vilivyogunduliwa! Endelea na kazi nzuri! 💪"
        else:
            total_impact = sum(o.impact_days for o in obstacles)
            high_obstacles = [o for o in obstacles if o.severity == "high"]
            
            message = f"Vizuizi {len(obstacles)} vimegunduliwa:\n\n"
            for i, obs in enumerate(obstacles, 1):
                emoji = "🔴" if obs.severity == "high" else "🟡" if obs.severity == "medium" else "🟢"
                message += f"{i}. {emoji} {obs.description}\n"
                message += f"   {obs.recommendation_swahili}\n\n"
            
            message += f"\nVizuizi hivi vinaweza kuongeza siku {total_impact} kwenye lengo lako."

        return {
            "obstacles": [
                {
                    "type": o.obstacle_type,
                    "severity": o.severity,
                    "description": o.description,
                    "impact_days": o.impact_days,
                    "recommendation": o.recommendation_swahili
                }
                for o in obstacles
            ],
            "message_swahili": message,
            "total_impact_days": sum(o.impact_days for o in obstacles)
        }

    async def recommend_adjustments(
        self,
        worker_id: str,
        goal_id: str,
        goal_data: Optional[GoalData] = None
    ) -> dict:
        """
        Recommend adjustments to help reach the goal.

        Strategies:
        1. Save more (reduce expenses, increase income)
        2. Extend deadline (more realistic timeline)
        3. Reduce target (partial achievement is better than none)
        4. Earn more (side hustle, more hours)

        Args:
            worker_id: Worker identifier
            goal_id: Goal identifier
            goal_data: Goal data

        Returns:
            Dict with adjustments list and Swahili message
        """
        if goal_data is None:
            return {
                "adjustments": [],
                "message_swahili": "Data haijapatikana."
            }

        remaining = goal_data.target_amount - goal_data.current_amount
        days_to_deadline = max(1, (goal_data.deadline - datetime.now()).days)
        daily_needed = remaining / days_to_deadline

        adjustments: list[Adjustment] = []

        # Get current rate from progress entries
        entries = goal_data.progress_entries
        current_daily_rate = 0.0
        if entries and len(entries) >= self.MIN_DATA_POINTS:
            entries.sort(key=lambda e: e.timestamp)
            time_span = max(1, (entries[-1].timestamp - entries[0].timestamp).days)
            total_saved = sum(e.amount for e in entries)
            current_daily_rate = total_saved / time_span

        # Adjustment 1: Save more (ECO 206 — savings behavior)
        if current_daily_rate > 0 and current_daily_rate < daily_needed:
            increase_needed = daily_needed - current_daily_rate
            adjustments.append(Adjustment(
                adjustment_type="save_more",
                description=f"Ongeza akiba ya siku kwa KSh {increase_needed:,.0f}",
                new_daily_target=daily_needed,
                message_swahili=(
                    f"Ongeza akiba yako ya siku kutoka KSh {current_daily_rate:,.0f} "
                    f"hadi KSh {daily_needed:,.0f}.\n\n"
                    f"Jinsi ya kufanya:\n"
                    f"• Punguza matumizi ya chakula cha nje\n"
                    f"• Nunua bidhaa za bei rahisi\n"
                    f"• Fikiria kazi ya ziada"
                )
            ))

        # Adjustment 2: Extend deadline
        if current_daily_rate > 0:
            realistic_days = int(remaining / current_daily_rate)
            if realistic_days > days_to_deadline:
                new_deadline = datetime.now() + timedelta(days=realistic_days)
                adjustments.append(Adjustment(
                    adjustment_type="extend_deadline",
                    description=f"Ongeza muda hadi siku {realistic_days}",
                    new_deadline=new_deadline,
                    message_swahili=(
                        f"Ikiwa utaendelea na KSh {current_daily_rate:,.0f} kwa siku, "
                        f"utafikia lengo lako siku {realistic_days} — "
                        f"siku {realistic_days - days_to_deadline} zaidi ya tarehe ya mwisho.\n"
                        f"Ongeza tarehe ya mwisho ili usikate tamaa."
                    )
                ))

        # Adjustment 3: Reduce target (partial achievement)
        if current_daily_rate > 0:
            achievable_amount = goal_data.current_amount + (current_daily_rate * days_to_deadline)
            if achievable_amount < goal_data.target_amount:
                adjustments.append(Adjustment(
                    adjustment_type="reduce_target",
                    description=f"Punguza lengo hadi KSh {achievable_amount:,.0f}",
                    new_daily_target=current_daily_rate,
                    message_swahili=(
                        f"Kwa KSh {current_daily_rate:,.0f} kwa siku, utaweza kufikia "
                        f"KSh {achievable_amount:,.0f} kwa siku {days_to_deadline}.\n"
                        f"Fikiria kupunguza lengo lako — kufikia sehemu ya lengo ni bora "
                        f"kuliko kutofikia chochote."
                    )
                ))

        # Adjustment 4: Earn more (income optimization)
        adjustments.append(Adjustment(
            adjustment_type="earn_more",
            description="Ongeza mapato yako",
            message_swahili=(
                "Njia za kuongeza mapato:\n"
                "• Ongeza masaa ya kazi\n"
                "• Anza biashara ndogo ya ziada\n"
                "• Tafuta wateja wapya\n"
                "• Ongeza bei kidogo (ikiwa soko linaruhusu)"
            )
        ))

        # Build summary message
        if not adjustments:
            message = "Uko kwenye njia sahihi! Endelea hivyo! 👍"
        else:
            message = "Mapendekezo ya kufikia lengo lako:\n\n"
            for i, adj in enumerate(adjustments, 1):
                emoji = {
                    "save_more": "💰",
                    "extend_deadline": "📅",
                    "reduce_target": "🎯",
                    "earn_more": "💪"
                }.get(adj.adjustment_type, "📌")
                message += f"{i}. {emoji} {adj.description}\n"
                if adj.message_swahili:
                    message += f"   {adj.message_swahili}\n"
                message += "\n"

        return {
            "adjustments": [
                {
                    "type": a.adjustment_type,
                    "description": a.description,
                    "new_daily_target": a.new_daily_target,
                    "new_deadline": a.new_deadline.isoformat() if a.new_deadline else None,
                    "message": a.message_swahili
                }
                for a in adjustments
            ],
            "message_swahili": message
        }

    async def get_savings_optimization(
        self,
        worker_id: str,
        monthly_income: float,
        monthly_expenses: float,
        goal_amount: float
    ) -> dict:
        """
        Optimize savings behavior to reach goal faster.

        ECO 206 (Microfinance): Savings behavior optimization.
        Uses the 50/30/20 rule adapted for informal workers.

        Args:
            worker_id: Worker identifier
            monthly_income: Average monthly income
            monthly_expenses: Average monthly expenses
            goal_amount: Target goal amount

        Returns:
            Savings optimization plan in Swahili
        """
        disposable_income = monthly_income - monthly_expenses
        optimal_savings = monthly_income * self.OPTIMAL_SAVINGS_RATIO
        min_savings = monthly_income * self.MIN_SAVINGS_RATIO

        if disposable_income <= 0:
            return {
                "plan": {
                    "status": "critical",
                    "monthly_savings_possible": 0,
                    "months_to_goal": 0
                },
                "message_swahili": (
                    "⚠️ Matumizi yako ni sawa na au zaidi ya mapato yako.\n\n"
                    "Hatua za kuchukua:\n"
                    "1. Orodhesha matumizi yako yote\n"
                    "2. Tenganisha ya lazima na yasiyo ya lazima\n"
                    "3. Punguza matumizi yasiyo ya lazima\n"
                    "4. Fikiria kuongeza mapato"
                )
            }

        # Can they save the optimal amount?
        can_save_optimal = disposable_income >= optimal_savings
        actual_savings = min(optimal_savings, disposable_income)
        months_to_goal = math.ceil(goal_amount / actual_savings) if actual_savings > 0 else 0

        savings_level = "optimal" if can_save_optimal else "adjusted"

        message = f"📊 Mpango wa Akiba\n\n"
        message += f"Mapato ya mwezi: KSh {monthly_income:,.0f}\n"
        message += f"Matumizi ya mwezi: KSh {monthly_expenses:,.0f}\n"
        message += f"Kiasi cha kuweka akiba: KSh {actual_savings:,.0f}\n\n"

        if can_save_optimal:
            message += f"Unaweza kuweka akiba {self.OPTIMAL_SAVINGS_RATIO*100:.0f}% ya mapato yako! 👍\n"
        else:
            message += f"Jaribu kuweka angalau {self.MIN_SAVINGS_RATIO*100:.0f}% ya mapato yako.\n"

        message += f"\nKwa KSh {actual_savings:,.0f} kwa mwezi:\n"
        message += f"• Utahitaji miezi {months_to_goal} kufikia lengo lako\n"
        message += f"• Weka KSh {actual_savings/30:,.0f} kwa siku"

        return {
            "plan": {
                "status": savings_level,
                "monthly_savings_possible": actual_savings,
                "months_to_goal": months_to_goal,
                "savings_ratio": actual_savings / monthly_income if monthly_income > 0 else 0
            },
            "message_swahili": message
        }


# ═══════════════════════════════════════════════════════════════
# VOICE COMMAND PATTERNS — Swahili goal commands
# ═══════════════════════════════════════════════════════════════

GOAL_VOICE_PATTERNS = {
    # Goal creation
    "create_goal": [
        r"(?:lengo langu|nataka|ninataka|nina\s*hitaji)\s+(?:ni\s+)?(?:ku|kusave|kununua|kulipa|kupanua)\s+(.+)",
        r"(?:weka|unda|fanya)\s+lengo\s+(?:la|la\s+ku)\s+(.+)",
        r"(?:nataka|ninataka)\s+(.+?)(?:\s+ksh?\s*(\d+))?$",
    ],

    # Progress update
    "update_progress": [
        r"(?:nimefikia|nimeweka|nimekuwa)\s+(\d+)%?\s*(?:ya\s+lengo)?",
        r"(?:nimeweka|nimehifadhi|nimesave)\s+(?:ksh?\s*)?(\d+)\s*(?:leo|wiki\s+hii)?",
        r"(?:lengo\s+limefikia|progress\s+ni)\s+(\d+)%",
    ],

    # Goal report
    "goal_report": [
        r"(?:ripoti|report|hali)\s+ya\s+malengo",
        r"(?:malengo\s+yangu|goals?\s+zangu)",
        r"(?:nina\s+malengo\s+gani|what\s+are\s+my\s+goals)",
    ],

    # Time to goal
    "time_to_goal": [
        r"(?:muda|wakati)\s+wa\s+kufikia\s+lengo",
        r"(?:lengo\s+litafikiwa|goal\s+will\s+be\s+reached)\s+(?:lini|wakati\s+gani)",
        r"(?:nitafikia|nita\s*fikia)\s+lengo\s+(?:lini|wakati\s+gani)",
    ],

    # Goal adjustment
    "adjust_goal": [
        r"(?:badilisha|change|adjust)\s+lengo",
        r"(?:ongeza|punguza)\s+lengo\s+(?:hadi|kuwa)\s+(?:ksh?\s*)?(\d+)",
        r"(?:sogeza|extend)\s+tarehe\s+(?:ya\s+mwisho|deadline)",
    ],

    # Break-even analysis
    "break_even": [
        r"(?:faida|profit|break\s*even)\s+ya\s+(.+)",
        r"(?:vifaa|equipment)\s+(?:vitalipa|itanilipa)\s+(?:lini|wakati\s+gani)",
    ],

    # Encouragement
    "encouragement": [
        r"(?:nisaidie|encourage|motivation)\s+(?:na\s+)?lengo",
        r"(?:sijisikii|nimechoka|nimemotivation)\s+kufikia\s+lengo",
    ],
}

# Category detection keywords
GOAL_CATEGORY_KEYWORDS = {
    "EQUIPMENT": ["friji", "oven", "jiko", "mashine", "kifaa", "vifaa", "pikipiki", "boda", "gari", "nduthi"],
    "INVENTORY": ["stock", "hifadhi", "supply", "bidhaa", "za kununua", "ununuzi"],
    "SAVINGS": ["save", "kusave", "kusanya", "akiba", "hifadhi"],
    "DEBT_REDUCTION": ["deni", "debt", "mkopo", "loan", "kulipa"],
    "BUSINESS_EXPANSION": ["panua", "expand", "open", "fungua", "grow", "kua", "tawi"],
    "EDUCATION": ["shule", "school", "fees", "ada", "masomo", "elimu", "chuo"],
    "EMERGENCY_FUND": ["dharura", "emergency"],
    "ASSET": ["ardhi", "land", "nyumba", "house", "plot", "kiwanja", "mali"],
}


def detect_goal_category(text: str) -> str:
    """Auto-detect goal category from Swahili text."""
    text_lower = text.lower()
    for category, keywords in GOAL_CATEGORY_KEYWORDS.items():
        if any(kw in text_lower for kw in keywords):
            return category
    return "OTHER"


def extract_goal_amount(text: str) -> Optional[float]:
    """Extract KSh amount from Swahili text."""
    import re
    # Match "KSh 50,000" or "50000" or "elfu hamsini"
    patterns = [
        r"ksh?\s*([\d,]+(?:\.\d+)?)",
        r"(\d+(?:,\d{3})*(?:\.\d+)?)\s*(?:ksh|shilling)",
        r"(\d+(?:,\d{3})*)",
    ]
    for pattern in patterns:
        match = re.search(pattern, text.lower())
        if match:
            amount_str = match.group(1).replace(",", "")
            try:
                return float(amount_str)
            except ValueError:
                continue
    return None
