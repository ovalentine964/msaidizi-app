# Transport & Logistics Validation — Msaidizi Superagent Architecture

**Validator:** Transport & Logistics Domain Validator
**Date:** 2026-07-24
**Architecture Version:** v1.1.0
**Scope:** All 20 transport & logistics informal worker types

---

## Executive Summary

The Msaidizi architecture was designed primarily around **product-based micro-entrepreneurs** (mama mboga, mama fua, dukawallah). This validation assesses whether the architecture can adequately serve **transport & logistics workers**, whose income is fundamentally **service-based** (per trip, per fare, distance-based, time-based) rather than product-based.

**Overall Verdict: CONDITIONAL PASS — Significant Adaptations Required**

| Category | Status | Detail |
|----------|--------|--------|
| Voice Input | ✅ PASS | Strong — multilingual, dialect-aware, offline STT/TTS |
| Financial Recording | ⚠️ PARTIAL | Data model needs service-transaction extensions |
| CFO Features | ⚠️ PARTIAL | Alerts need transport-specific triggers (fuel, maintenance, routes) |
| Alama Score | ⚠️ PARTIAL | Pillars need recalibration for variable daily income |
| Proactive Alerts | ❌ GAP | Missing fuel, route, weather, passenger demand alerts |
| Route Optimization | ❌ GAP | Not present in architecture at all |
| Language | ✅ PASS | Excellent — Sheng, Swahili, local dialects covered |
| Service-Based Income | ⚠️ PARTIAL | Core Transaction model assumes product sales; needs adaptation |

---

## Part 1: Per-Worker-Type Validation

### 1. Boda Boda Rider (Motorcycle Taxi)

**Profile:** Most common transport worker in Kenya (~1.5M riders). Income from passenger fares, goods delivery. Variable daily income: KSh 500–2,500/day.

| Feature | Status | Notes |
|---------|--------|-------|
| Voice Input | ✅ | "Nimepata abiria tatu leo, mia mbili kila moja" works well |
| Financial Recording | ⚠️ | Transaction model has `item` field — needs "trip" as a service type, not a product |
| CFO Features | ⚠️ | Needs fuel expense tracking, daily target vs actual fares |
| Alama Score | ⚠️ | Income is highly variable (rain = no customers). Pillar 2 (Revenue Trends) penalizes volatility unfairly |
| Proactive Alerts | ❌ | **NEEDS:** Fuel price alerts, rain/weather warnings (kills demand), peak hour reminders |
| Route Optimization | ❌ | **NEEDS:** Best routes during rush hour, hotspot locations for passengers |
| Language | ✅ | Sheng-heavy. Architecture handles this via dialect detection |

**Critical Issue — Service Transaction Model:**
```
Current: "Nimeuziwa mandazi 10, mia mbili" → SALE, item=mandazi, qty=10, price=200
Needed:  "Nimepata abiria 3, mia mbili" → SERVICE_TRIP, service="passenger", qty=3, fare=200
```
The `TransactionType` enum only has `SALE, PURCHASE, EXPENSE`. It needs `SERVICE_TRIP, SERVICE_DELIVERY, SERVICE_HIRE`.

**Boda-Specific Alama Score Adjustment:**
- Pillar 4 (Product Diversity) makes no sense — diversifying means doing delivery AND passenger trips
- Pillar 5 (Regularity) should weight time-of-day patterns (morning rush, evening rush)
- Weather impact on income should be a recognized factor, not penalized as volatility

---

### 2. Tuk Tuk Driver (Three-Wheeler)

**Profile:** Urban passenger/goods transport. Fixed routes in some cities. Income: KSh 800–2,000/day.

| Feature | Status | Notes |
|---------|--------|-------|
| Voice Input | ✅ | Works well |
| Financial Recording | ⚠️ | Same service-transaction gap. Also needs route-based recording: "Nimefanya Route ya Town-CBD, trip 8" |
| CFO Features | ⚠️ | Needs daily trip count targets, per-route profitability |
| Alama Score | ⚠️ | More stable than boda (fixed routes). But still needs service-income model |
| Proactive Alerts | ❌ | **NEEDS:** Route demand alerts, competitor pricing awareness |
| Route Optimization | ❌ | **NEEDS:** Optimal route selection, passenger density mapping |
| Language | ✅ | Covered |

**Unique Need — Batch Trip Recording:**
Tuk tuk drivers often run the same route repeatedly. Architecture should support: "Nimefanya trip 8 leo, kila moja 50" (8 trips at 50 each) rather than requiring 8 separate voice recordings.

---

### 3. Matatu Driver (Minibus)

**Profile:** Kenya's primary public transport. 14-33 seat vehicles. Income shared with sacco/conductor. Daily revenue: KSh 5,000–15,000 (driver's share: KSh 1,000–3,000).

| Feature | Status | Notes |
|---------|--------|-------|
| Voice Input | ✅ | Works. Some matatu slang ("nganya", "kanjo") needs dialect mapping |
| Financial Recording | ⚠️ | **PROBLEM:** Revenue is shared with sacco, conductor, owner. Transaction model has no concept of revenue sharing or splits |
| CFO Features | ⚠️ | Needs sacco payment tracking, fuel cost per trip, daily target |
| Alama Score | ⚠️ | Income depends on route, season, competition. Not purely self-controlled |
| Proactive Alerts | ❌ | **NEEDS:** Traffic alerts, fuel prices, route disruptions, police checkpoints |
| Route Optimization | ❌ | **NEEDS:** Optimal timing for route, passenger demand patterns |
| Language | ✅ | Covered |

**Critical Issue — Revenue Splitting:**
Matatu economics: Gross fare → Sacco commission (30-40%) → Fuel (25-30%) → Driver pay. The architecture's `Transaction` model records gross amounts but has no concept of:
- Revenue sharing (`splitWith: "sacco_name"`, `driverShare: 0.35`)
- Operational costs that are mandatory (sacco fees, parking, kanjo fines)
- Net income vs gross revenue

---

### 4. Matatu Conductor (Fare Collector)

**Profile:** Collects fares, manages passengers. Paid by driver or percentage. Income: KSh 500–1,500/day.

| Feature | Status | Notes |
|---------|--------|-------|
| Voice Input | ✅ | "Nimekusanya elfu tatu leo" works |
| Financial Recording | ⚠️ | Income is "collected" not "earned" — different model. Needs expense pass-through tracking |
| CFO Features | ⚠️ | Needs daily collection targets, reconciliation with driver |
| Alama Score | ⚠️ | Conductor has limited control over income. Score should reflect consistency of engagement, not just revenue |
| Proactive Alerts | ❌ | Not a primary need |
| Route Optimization | ❌ | Not applicable (follows driver's route) |
| Language | ✅ | Covered |

**Unique Need — Collection vs Earning:**
The conductor collects KSh 10,000 in fares but keeps KSh 1,000. The architecture needs to distinguish between:
- `collected_amount`: Total fares collected (for reconciliation)
- `earned_amount`: Actual income kept (for personal finance)

---

### 5. Taxi Driver (Private Car)

**Profile:** Traditional or app-based (Uber, Bolt). Income: KSh 2,000–5,000/day.

| Feature | Status | Notes |
|---------|--------|-------|
| Voice Input | ✅ | Works well |
| Financial Recording | ⚠️ | Needs per-trip recording with distance. "Nimepata trip ya airport, elfu mbili" |
| CFO Features | ✅ | Strong fit — expense tracking (fuel, insurance, maintenance) maps well to existing CFO engine |
| Alama Score | ⚠️ | App-based drivers have verifiable income (M-Pesa). Should be weighted higher. Variable income from surge pricing |
| Proactive Alerts | ❌ | **NEEDS:** Surge pricing zones, airport pickup queues, fuel prices |
| Route Optimization | ❌ | **NEEDS:** Optimal zones to wait for rides |
| Language | ✅ | Covered |

**Opportunity — M-Pesa Integration:**
App-based taxi drivers have M-Pesa records of every trip. The existing `MpesaSmsReceiver` and `MpesaStatementParser` in `:data` module can auto-import trip income. This is a **massive proof accelerator** for Alama Score.

---

### 6. Delivery Rider (Food/Package — Glovo, Bolt)

**Profile:** App-based delivery. Income: per delivery + tips. KSh 800–3,000/day.

| Feature | Status | Notes |
|---------|--------|-------|
| Voice Input | ✅ | "Nimefanya delivery tano leo, mia tatu kila moja" works |
| Financial Recording | ⚠️ | Needs per-delivery recording. Tips are separate from delivery fee |
| CFO Features | ⚠️ | **NEEDS:** Bike maintenance tracking (chains, tires, brakes — frequent costs) |
| Alama Score | ⚠️ | Highly variable. Rain = more orders but dangerous. Peak hours matter. Algorithm-driven income (app assigns orders) |
| Proactive Alerts | ❌ | **NEEDS:** Weather alerts (safety + demand), bike maintenance reminders, peak hour alerts |
| Route Optimization | ❌ | **NEEDS:** Delivery batching optimization (app does this, but rider awareness helps) |
| Language | ✅ | Covered |

**Unique Need — Tips vs Base Pay:**
Delivery riders distinguish between delivery fee (from app) and tips (from customer). Architecture needs:
- `base_pay`: App-confirmed delivery fee
- `tips`: Additional customer payment
- These have different reliability signals for Alama Score

---

### 7. Truck Driver (Long-Haul)

**Profile:** Long-distance goods transport. Income: KSh 2,000–8,000/day (employed) or per-trip contract (owner).

| Feature | Status | Notes |
|---------|--------|-------|
| Voice Input | ✅ | Works. May be in multiple languages (Swahili on Mombasa route, English for logistics terms) |
| Financial Recording | ⚠️ | **PROBLEM:** Long-haul has multi-day trips. A trip from Nairobi to Mombasa takes 2 days. Transaction model assumes same-day recording |
| CFO Features | ⚠️ | **NEEDS:** Per-trip profitability (fuel + food + lodging + tolls vs trip payment), vehicle maintenance schedule |
| Alama Score | ⚠️ | Very low frequency (maybe 2-3 trips/week). Pillar 1 (Frequency) penalizes this. Pillar 5 (Regularity) doesn't account for multi-day trips |
| Proactive Alerts | ❌ | **NEEDS:** Road conditions, border delays, fuel prices along route, rest stop recommendations, load availability for return trips |
| Route Optimization | ❌ | **NEEDS:** Optimal routes (time vs fuel cost), rest stop planning, border crossing timing |
| Language | ✅ | Covered |

**Critical Issue — Multi-Day Transactions:**
The architecture assumes transactions are recorded on the day they happen. Long-haul drivers need:
- Trip start/end spanning multiple days
- Expenses accumulated during trip (fuel at different stops, meals, lodging)
- Trip completion recording with total settlement

**Critical Issue — Return Load Optimization:**
A truck going Nairobi→Mombasa empty on return is a massive loss. Architecture should support:
- "Niko Mombasa, nataka kujaza" → Load matching suggestions
- This requires backend integration beyond current scope

---

### 8. Pickup Truck Owner (Goods Transport)

**Profile:** Local goods transport. Moving materials, construction supplies. KSh 1,500–5,000/day.

| Feature | Status | Notes |
|---------|--------|-------|
| Voice Input | ✅ | Works |
| Financial Recording | ⚠️ | Needs per-trip recording with goods type. "Nimebeba mchanga, trip 3, elfu moja kila moja" |
| CFO Features | ⚠️ | Vehicle maintenance is the #1 expense. Needs maintenance schedule + cost tracking |
| Alama Score | ⚠️ | Seasonal (construction season = more work). Should account for seasonality |
| Proactive Alerts | ❌ | **NEEDS:** Construction project locations, material demand, maintenance reminders |
| Route Optimization | ❌ | **NEEDS:** Optimal routes for heavy loads, fuel efficiency |
| Language | ✅ | Covered |

---

### 9. Cart Pusher (Handcart)

**Profile:** Ultra-low-cost urban goods transport. KSh 200–800/day. Most informal of transport workers.

| Feature | Status | Notes |
|---------|--------|-------|
| Voice Input | ✅ | Critical for this group — likely semi-literate. Voice-first design is perfect |
| Financial Recording | ⚠️ | Very small amounts. "Nimebeba mizigo, mia mbili" — currency formatting must handle small amounts |
| CFO Features | ⚠️ | Minimal expenses (food, cart repairs). Simple tracking sufficient |
| Alama Score | ⚠️ | Very low income = very slow proof accumulation. Tier progression will be painfully slow. **Risk: worker gives up before reaching useful tiers** |
| Proactive Alerts | ❌ | **NEEDS:** Market day schedules, busy location alerts |
| Route Optimization | ❌ | **NEEDS:** Shortest routes with goods (cart can't use highways) |
| Language | ✅ | Covered |

**Critical Issue — Tier Progression for Low-Income Workers:**
Current Alama Score tiers are based on proof point counts. A cart pusher earning KSh 300/day will accumulate proof points at the same rate as a taxi driver earning KSh 5,000/day, but their **financial capacity** is vastly different. The tiers should consider:
- Income level, not just proof count
- Or: separate tier tracks for different income levels
- Risk: A cart pusher at Tier 3 (MKUU) may only qualify for KSh 500 loan, while same tier taxi driver qualifies for KSh 10,000

---

### 10. Wheelbarrow Operator

**Profile:** Similar to cart pusher but even more basic. KSh 150–600/day.

| Feature | Status | Notes |
|---------|--------|-------|
| Voice Input | ✅ | Same as cart pusher — voice-first is essential |
| Financial Recording | ⚠️ | Same small-amount issues |
| CFO Features | ⚠️ | Minimal needs. Basic income/expense tracking sufficient |
| Alama Score | ⚠️ | Same low-income progression issue as cart pusher |
| Proactive Alerts | ❌ | Minimal need |
| Route Optimization | ❌ | Not applicable (stationary or very local) |
| Language | ✅ | Covered |

---

### 11. Boat Fisherman (Lake/Ocean)

**Profile:** Lake Victoria, Indian Ocean coast. Income seasonal, weather-dependent. KSh 500–5,000/day.

| Feature | Status | Notes |
|---------|--------|-------|
| Voice Input | ✅ | **CHALLENGE:** Fishermen may use Dholuo, Mijikenda, or other local languages heavily. Architecture supports multiple languages but needs specific dialect training data |
| Financial Recording | ⚠️ | **PROBLEM:** Fish is both product AND service (catching = service). Sale of catch is product. Architecture confuses these. Also: catch is often sold at beach, not market — different pricing dynamics |
| CFO Features | ⚠️ | **NEEDS:** Catch tracking (kg of fish, species), seasonal income patterns, boat maintenance costs |
| Alama Score | ⚠️ | **MAJOR ISSUE:** Highly seasonal. Lake fishing has peak seasons (June-Sept). Rainy season = no fishing. Architecture's consistency metrics will penalize natural seasonality |
| Proactive Alerts | ❌ | **NEEDS:** Weather/sea condition alerts (life safety!), fish market prices, season forecasts |
| Route Optimization | ❌ | **NEEDS:** Fishing zone recommendations based on catch reports, weather-safe routes |
| Language | ⚠️ | Dholuo fishermen need specific dialect support. Current dialect detection may not cover lake-specific fishing terminology |

**Critical Issue — Safety Alerts:**
Fishermen die on Lake Victoria every year from sudden storms. Architecture has no concept of **life-safety alerts**. This should be a priority feature:
- Storm warnings
- Wind speed alerts
- "Don't go out today" proactive voice message

---

### 12. Ferry Operator (Lake/River)

**Profile:** Operates ferries on Lake Victoria, rivers. Fixed routes, scheduled. Income: KSh 1,000–4,000/day.

| Feature | Status | Notes |
|---------|--------|-------|
| Voice Input | ✅ | Works |
| Financial Recording | ⚠️ | Per-trip passenger count and fare collection. Similar to matatu conductor model |
| CFO Features | ⚠️ | Fuel costs, maintenance, dock fees |
| Alama Score | ⚠️ | More stable than fishing but still weather-dependent |
| Proactive Alerts | ❌ | **NEEDS:** Water levels, weather, passenger demand forecasts |
| Route Optimization | ❌ | **NEEDS:** Schedule optimization based on demand |
| Language | ✅ | Covered |

---

### 13. Oxcart Driver (Rural)

**Profile:** Rural goods transport. Very low income. KSh 100–500/day.

| Feature | Status | Notes |
|---------|--------|-------|
| Voice Input | ✅ | **CRITICAL:** Likely speaks only local language. Voice-first is essential. May not understand Swahili prompts |
| Financial Recording | ⚠️ | Very small amounts, infrequent work |
| CFO Features | ⚠️ | Minimal. Animal feed costs, cart maintenance |
| Alama Score | ❌ | **MAJOR GAP:** Income so low and infrequent that proof accumulation is nearly impossible under current model. May take 6+ months to reach Tier 1 |
| Proactive Alerts | ❌ | Minimal need. Weather matters for road conditions |
| Route Optimization | ❌ | Not applicable |
| Language | ❌ | **GAP:** Needs support for rural languages (Kalenjin, Kamba, etc.) beyond current Swahili/Sheng focus |

**Critical Issue — Rural Viability:**
The architecture assumes daily use. Oxcart drivers may work 2-3 days/week. The entire M-KOPA proof model assumes **daily** interactions. For rural transport workers:
- Weekly recording should still count as "consistent"
- The time-to-tier needs to be longer but achievable
- Alternative proof mechanisms (weekly summary vs daily transactions)

---

### 14. Donkey Cart Operator

**Profile:** Similar to oxcart but in arid/semi-arid areas. KSh 100–400/day.

| Feature | Status | Notes |
|---------|--------|-------|
| Voice Input | ✅ | Same as oxcart — local languages essential |
| Financial Recording | ⚠️ | Same small-amount issues |
| CFO Features | ⚠️ | Animal care costs, cart maintenance |
| Alama Score | ❌ | Same viability issue as oxcart |
| Proactive Alerts | ❌ | **NEEDS:** Drought alerts (affects water/food for donkey, affects road conditions) |
| Route Optimization | ❌ | Not applicable |
| Language | ❌ | **GAP:** Pastoral communities (Turkana, Samburu, Marsabit) need specific language support |

---

### 15. Moving Services (Relocation)

**Profile:** Home/office relocation. Irregular income. KSh 3,000–15,000 per job.

| Feature | Status | Notes |
|---------|--------|-------|
| Voice Input | ✅ | Works |
| Financial Recording | ⚠️ | **PROBLEM:** One job = one large payment, not daily small transactions. "Nimehamisha nyumba, elfu kumi" — architecture expects more granular recording |
| CFO Features | ⚠️ | Vehicle costs, labor costs (hiring helpers), fuel per job |
| Alama Score | ⚠️ | **ISSUE:** Irregular frequency (maybe 2-4 jobs/week). Pillar 1 (Frequency) and Pillar 5 (Regularity) will score low even for successful movers |
| Proactive Alerts | ❌ | **NEEDS:** Demand forecasting (month-end = more moves), pricing intelligence |
| Route Optimization | ❌ | **NEEDS:** Multi-stop optimization for moving jobs |
| Language | ✅ | Covered |

**Unique Need — Job-Based Recording:**
Moving services are project-based, not transaction-based. Architecture needs:
- `TransactionType.JOB` or `SERVICE_PROJECT`
- Sub-items (labor cost, fuel cost, helper pay) within a job
- Job duration tracking

---

### 16. Courier (Documents/Packages)

**Profile:** Document and package delivery. KSh 500–2,500/day.

| Feature | Status | Notes |
|---------|--------|-------|
| Voice Input | ✅ | "Nimefanya delivery ya documents tatu leo" works |
| Financial Recording | ⚠️ | Per-delivery recording with pickup/dropoff locations |
| CFO Features | ⚠️ | Transport costs, phone costs (for coordination) |
| Alama Score | ⚠️ | Moderate variability. More stable than boda |
| Proactive Alerts | ❌ | **NEEDS:** Package demand zones, time-sensitive delivery alerts |
| Route Optimization | ❌ | **NEEDS:** Multi-package route optimization (TSP problem) |
| Language | ✅ | Covered |

**Opportunity — Route Optimization:**
Couriers delivering multiple packages need route optimization. This is a well-studied problem (Traveling Salesman). Even a simple nearest-neighbor heuristic would save significant fuel and time. **This is a high-value feature gap.**

---

### 17. Airport Taxi

**Profile:** Airport transfers. Higher fares, less frequent. KSh 2,000–8,000/day.

| Feature | Status | Notes |
|---------|--------|-------|
| Voice Input | ✅ | Works. May use English for international passengers |
| Financial Recording | ✅ | High-value, low-frequency transactions. Good fit for current model |
| CFO Features | ✅ | Strong fit — vehicle costs, parking fees, airport access fees |
| Alama Score | ✅ | Higher income = faster proof accumulation. Good fit |
| Proactive Alerts | ❌ | **NEEDS:** Flight arrival schedules, passenger demand at airport, parking availability |
| Route Optimization | ❌ | **NEEDS:** Optimal routes to/from airport, traffic avoidance |
| Language | ✅ | Covered |

**Best Fit:** Airport taxi is the transport worker type that best fits the current architecture. High income, clear transactions, M-Pesa integration possible.

---

### 18. Tour Driver (Safari/Tour)

**Profile:** Tourism transport. Seasonal, high-value. KSh 3,000–20,000/day during season.

| Feature | Status | Notes |
|---------|--------|-------|
| Voice Input | ✅ | May code-switch between Swahili and English frequently |
| Financial Recording | ⚠️ | **PROBLEM:** Multi-day tours. A 3-day Masai Mara safari is one "job" with daily expenses. Transaction model assumes daily granularity |
| CFO Features | ⚠️ | Park fees, fuel, accommodation, food — all passed through to client but need tracking |
| Alama Score | ❌ | **MAJOR ISSUE:** Extreme seasonality (peak: Jul-Oct, Dec-Jan). Off-season = almost no income. Architecture will show "declining" score during natural off-season |
| Proactive Alerts | ❌ | **NEEDS:** Tourist season forecasts, park condition updates, booking demand |
| Route Optimization | ❌ | **NEEDS:** Safari route planning, park circuit optimization |
| Language | ✅ | Covered |

**Critical Issue — Seasonal Income:**
Tour drivers may earn KSh 100,000/month in peak season and KSh 5,000/month off-season. The architecture's revenue trend analysis will interpret this as "volatile" or "declining" when it's actually **predictable seasonality**. The system needs:
- Seasonal pattern recognition
- Annual income view, not just monthly
- "Off-season" flagged as expected, not penalized

---

### 19. School Transport (Van/Bus)

**Profile:** School routes. Fixed schedule, fixed income. KSh 1,500–5,000/day.

| Feature | Status | Notes |
|---------|--------|-------|
| Voice Input | ✅ | Works |
| Financial Recording | ⚠️ | **PROBLEM:** Income is termly/monthly contracts, not daily fares. Parents pay per term. Architecture assumes per-transaction recording |
| CFO Features | ⚠️ | Vehicle maintenance, fuel for fixed routes, insurance |
| Alama Score | ✅ | Very stable income — good for Alama Score. But recording pattern is different (monthly bulk, not daily small) |
| Proactive Alerts | ❌ | **NEEDS:** Term start/end reminders, route optimization for fuel savings, vehicle inspection schedules |
| Route Optimization | ❌ | **NEEDS:** Optimal school routes (minimize fuel, maximize students) |
| Language | ✅ | Covered |

**Unique Need — Contract-Based Income:**
School transport is contract-based. Architecture needs:
- `TransactionType.CONTRACT_INCOME` — monthly/termly bulk income
- Contract period tracking
- Payment collection tracking (some parents pay late)

---

### 20. Ambulance Driver (Informal)

**Profile:** Community ambulance services. Emergency transport. Variable income, often subsidized. KSh 1,000–5,000/day.

| Feature | Status | Notes |
|---------|--------|-------|
| Voice Input | ✅ | Works. May need quick recording during stressful situations |
| Financial Recording | ⚠️ | Emergency services have unique payment patterns — some paid by family, some by community fund, some free |
| CFO Features | ⚠️ | Vehicle costs, fuel (urgent trips = no fuel economy), maintenance |
| Alama Score | ⚠️ | Highly variable and unpredictable. Emergency calls are random |
| Proactive Alerts | ❌ | **NEEDS:** Fuel availability, hospital route optimization, vehicle readiness checks |
| Route Optimization | ❌ | **NEEDS:** Fastest route to hospital, hospital capacity awareness |
| Language | ✅ | Covered |

**Unique Need — Emergency Recording:**
During emergencies, the driver can't stop to record transactions. Architecture needs:
- Deferred recording: "Niliwapeleka hospitali, elfu tatu" recorded after the fact
- Quick-preset: One-tap "emergency trip" recording with pre-filled defaults
- No follow-up questions during emergency use

---

## Part 2: Cross-Cutting Gap Analysis

### Gap 1: Service-Based Transaction Model (CRITICAL)

**Current:** Transaction model is product-centric (`item`, `quantity`, `unit`, `category`, `subcategory`).

**Needed:** Service-centric extensions for transport workers.

**Proposed Changes:**

```kotlin
// Extension to TransactionType enum
enum class TransactionType {
    SALE,           // Product sale (existing)
    PURCHASE,       // Stock purchase (existing)
    EXPENSE,        // Business expense (existing)
    SERVICE_TRIP,   // NEW: Per-trip income (boda, taxi, tuk tuk)
    SERVICE_DELIVERY, // NEW: Per-delivery income (courier, delivery rider)
    SERVICE_HIRE,   // NEW: Vehicle/equipment hire (pickup, moving)
    SERVICE_CONTRACT, // NEW: Contract-based income (school transport)
    JOB,            // NEW: Project-based work (moving services)
    FARE_COLLECTION, // NEW: Collecting fares on behalf of others (conductor)
}

// New fields for service transactions
data class ServiceDetails(
    val serviceType: String,         // "passenger", "delivery", "hire", "freight"
    val origin: String?,             // Pickup location
    val destination: String?,        // Drop-off location
    val distanceKm: Double?,         // Trip distance
    val durationMinutes: Int?,       // Trip duration
    val vehicleType: String?,        // "boda", "tuk_tuk", "matatu", "truck"
    val passengerCount: Int?,        // Number of passengers
    val goodsDescription: String?,   // What was transported
    val farePerUnit: Double?,        // Per-passenger or per-trip fare
    val sharedWith: String?,         // Sacco, owner, partner
    val sharedAmount: Double?,       // Amount paid to shared party
    val tips: Double?,               // Tips received separately
    val isReturnTrip: Boolean?,      // Return/load trip
    val contractId: String?,         // For contract-based income
)
```

**Impact:** This change touches `Transaction.kt`, `TransactionEngine.kt`, `TransactionEntity.kt`, and the voice parsing pipeline. It's a significant but localized change.

**Priority: P0 — Without this, transport workers cannot properly record income.**

---

### Gap 2: Variable Income Alama Score (HIGH)

**Current:** Alama Score pillars assume product-business income patterns:
- Pillar 2 (Revenue Trends) penalizes high variability
- Pillar 4 (Product Diversity) is product-centric
- Pillar 5 (Regularity) assumes daily operation
- Pillar 7 (Expense Patterns) assumes stock-based expenses

**Needed:** Transport-adapted scoring:

| Pillar | Current Assumption | Transport Reality | Adaptation |
|--------|-------------------|-------------------|------------|
| Frequency (15%) | Daily transactions | Varies: daily (boda) to weekly (truck) | Normalize by worker type's expected frequency |
| Revenue Trends (15%) | Stable/growing = good | Seasonal, weather-dependent, surge-based | Use coefficient of variation relative to worker type, not absolute |
| Margins (15%) | Product margin (revenue - COGS) | Service margin (fare - fuel - maintenance) | Add service-cost deductions |
| Diversity (10%) | Multiple products | Multiple service types OR routes | "Diversification" = having backup income streams |
| Regularity (10%) | Same hours daily | Rush hours, seasonal peaks | Recognize patterns within irregular schedules |
| Growth (10%) | 30d vs 90d comparison | Seasonal comparison (this month vs same month last year) | Add seasonal baseline |
| Expense Control (10%) | Stock purchasing discipline | Fuel + maintenance discipline | Different expense categories |
| Savings (15%) | Daily savings | May save weekly/monthly when big payments come | Allow irregular savings patterns |

**Proposed: Worker-Type-Calibrated Scoring**

```kotlin
// AlamaScoreEngine needs worker-type calibration
data class WorkerTypeProfile(
    val type: WorkerType,                    // BODA, TAXI, TRUCK, etc.
    val expectedFrequency: FrequencyPattern, // DAILY, WEEKLY, IRREGULAR
    val incomeVolatility: VolatilityLevel,   // LOW, MEDIUM, HIGH, SEASONAL
    val seasonalPattern: SeasonalPattern?,   // null or monthly pattern
    val primaryExpenseCategories: List<String>, // ["fuel", "maintenance", "sacco"]
)

enum class WorkerType {
    PRODUCT_SELLER,      // Current default (mama mboga, dukawallah)
    SERVICE_DAILY,       // Daily service workers (boda, tuk tuk, courier)
    SERVICE_IRREGULAR,   // Irregular service (moving, tour)
    SERVICE_SEASONAL,    // Seasonal service (fishing, tourism)
    SERVICE_CONTRACT,    // Contract-based (school transport)
    FARE_COLLECTOR,      // Collecting on behalf (matatu conductor)
    TRANSPORT_OPERATOR,  // Vehicle operators with crews (matatu driver, truck)
}
```

**Priority: P0 — Without this, Alama Score unfairly penalizes transport workers.**

---

### Gap 3: Proactive Alerts for Transport (HIGH)

**Current:** Proactive alerts focus on:
- Stock-out prediction
- Cash flow forecasting
- Financial anomaly detection

**Needed:** Transport-specific alerts:

| Alert Type | Example | Workers Who Need It |
|-----------|---------|-------------------|
| **Fuel Prices** | "Mafuta ya petrol yamepanda. Jaza tanki leo!" | All motorized transport |
| **Weather** | "Kuna mvua kubwa kesho. Andaa mapema." | Boda, tuk tuk, fishing |
| **Demand Surge** | "CBD kuna msongamano. Ongeza bei ya trip." | Taxi, boda, matatu |
| **Maintenance** | "Umefanya km 5,000. Fanya service ya gari." | All vehicle owners |
| **Road Conditions** | "Barabara ya Thika iko blocked. Tumia bypass." | Long-haul, courier |
| **Safety** | "Upepo mkali leo. Usitoke ziwa." | Fishermen (LIFE SAFETY) |
| **Peak Hours** | "Saa za usiku zina faida zaidi. Kuwa CBD." | Taxi, boda |
| **Season** | "Msimu wa watalii unaanza. Ongeza bookings." | Tour drivers |
| **Insurance** | "Bima ya gari inaisha wiki ijayo." | All vehicle owners |
| **SACCO** | "Malipo ya sacco ni kesho. Weka pesa tayari." | Matatu, some boda |

**Implementation:** New `TransportAlertEngine` in `:superagent:financial` module:

```kotlin
class TransportAlertEngine(
    private val contextEngine: ContextEngine,
    private val weatherApi: WeatherApi,
    private val fuelPriceApi: FuelPriceApi,
    private val trafficApi: TrafficApi
) {
    suspend fun checkAlerts(workerProfile: WorkerProfile): List<TransportAlert> {
        val alerts = mutableListOf<TransportAlert>()

        // Fuel price change detection
        val fuelChange = fuelPriceApi.getPriceChange(workerProfile.location)
        if (fuelChange.percentChange > 5) {
            alerts.add(TransportAlert(
                type = AlertType.FUEL_PRICE,
                urgency = if (fuelChange.percentChange > 10) HIGH else MEDIUM,
                message = "Mafuta yamepanda ${fuelChange.percentChange}%. Jaza tanki leo!"
            ))
        }

        // Weather alerts (life safety for fishermen)
        val weather = weatherApi.getForecast(workerProfile.location)
        if (weather.hasSevereWarning) {
            alerts.add(TransportAlert(
                type = AlertType.WEATHER_SAFETY,
                urgency = CRITICAL,
                message = "⚠️ ${weather.warningMessage}. Usitoke leo!"
            ))
        }

        // Vehicle maintenance reminders
        val vehicle = contextEngine.getVehicleInfo()
        if (vehicle != null) {
            val kmSinceService = vehicle.odometer - vehicle.lastServiceKm
            if (kmSinceService > 5000) {
                alerts.add(TransportAlert(
                    type = AlertType.MAINTENANCE,
                    urgency = MEDIUM,
                    message = "Gari yako imefanya km $kmSinceService tangu service. Panga service wiki hii."
                ))
            }
        }

        return alerts
    }
}
```

**Priority: P1 — Safety alerts for fishermen are P0.**

---

### Gap 4: Route Optimization (MEDIUM)

**Current:** No route optimization in architecture.

**Needed:** Basic route optimization for couriers, delivery riders, multi-stop trips.

**Assessment:** Route optimization is a specialized feature that significantly increases scope. However, even simple implementations add high value:

| Level | Complexity | Value | Recommendation |
|-------|-----------|-------|----------------|
| **L1: Nearest Neighbor** | Low | High for couriers | Implement in v1 |
| **L2: Google Maps Integration** | Medium | High for all | Implement in v2 |
| **L3: Custom TSP Solver** | High | Medium | Defer |

**Proposed:** Add a `RouteOptimizer` utility to `:superagent:financial`:

```kotlin
class RouteOptimizer(
    private val mapsApi: MapsApi
) {
    /**
     * Simple nearest-neighbor for multi-stop delivery.
     * Not optimal but good enough for informal workers.
     */
    suspend fun optimizeRoute(
        currentLocation: LatLng,
        stops: List<DeliveryStop>
    ): OptimizedRoute {
        // L1: Nearest neighbor heuristic
        val ordered = mutableListOf<DeliveryStop>()
        var current = currentLocation
        val remaining = stops.toMutableList()

        while (remaining.isNotEmpty()) {
            val nearest = remaining.minByOrNull { 
                haversineDistance(current, it.location) 
            }!!
            ordered.add(nearest)
            current = nearest.location
            remaining.remove(nearest)
        }

        return OptimizedRoute(
            stops = ordered,
            estimatedDistanceKm = calculateTotalDistance(currentLocation, ordered),
            estimatedTimeMinutes = estimateTime(ordered),
            estimatedFuelCost = estimateFuelCost(ordered)
        )
    }
}
```

**Priority: P2 — High value for couriers/delivery, but not blocking for other workers.**

---

### Gap 5: Revenue Sharing / Split Economics (MEDIUM)

**Current:** Transaction records one amount per transaction.

**Needed:** Transport economics often involve splits:

| Worker | Split Model |
|--------|------------|
| Matatu Driver | Gross fare → Sacco (35%) → Fuel (25%) → Driver (40%) |
| Matatu Conductor | Collects gross → Pays driver → Keeps commission |
| Truck Driver (employed) | Trip payment → Owner (70%) → Driver (30%) |
| Fishing Crew | Catch sale → Boat owner (50%) → Crew split (50%) |

**Proposed:** Add split fields to Transaction:

```kotlin
data class TransactionSplit(
    val grossAmount: Double,           // Total collected
    val splits: List<SplitEntry>,     // Who gets what
    val netAmount: Double,            // Worker's actual take-home
)

data class SplitEntry(
    val recipient: String,            // "sacco", "owner", "crew"
    val amount: Double,
    val percentage: Double?,
    val reason: String,               // "commission", "fuel_share", "profit_share"
)
```

**Priority: P2 — Important for matatu/truck workers, but can be approximated with current expense model initially.**

---

### Gap 6: Multi-Day / Trip-Based Recording (MEDIUM)

**Current:** Transactions are single-day, single-event.

**Needed:** Long-haul truck drivers, tour drivers, and moving services have multi-day jobs.

**Proposed:** Add trip/job context that groups transactions:

```kotlin
data class TripContext(
    val tripId: String,
    val tripType: TripType,          // LONG_HAUL, TOUR, MOVING_JOB
    val startDate: Long,
    val endDate: Long?,              // null if ongoing
    val origin: String,
    val destination: String?,
    val clientName: String?,
    val agreedPayment: Double?,
    val status: TripStatus,          // ACTIVE, COMPLETED, CANCELLED
    val transactions: List<Long>,    // Associated transaction IDs
)
```

**Priority: P2 — Important for truck/tour/moving workers.**

---

### Gap 7: Seasonal Income Patterns (MEDIUM)

**Current:** Alama Score uses 7d/30d/90d rolling windows.

**Needed:** Seasonal workers (fishing, tourism) need annual patterns:

| Worker Type | Peak Season | Off Season | Pattern |
|-----------|-------------|------------|---------|
| Fisherman | Jun-Sep (Nile Perch) | Mar-May (rain) | Predictable |
| Tour Driver | Jul-Oct, Dec-Jan | Mar-May, Nov | Predictable |
| School Transport | Jan-Apr, May-Jul, Sep-Dec | Apr, Aug (holidays) | Predictable |
| Construction Pickup | Jan-Mar, Jun-Aug | Apr-May (rain), Oct-Dec | Semi-predictable |

**Proposed:** Add seasonal baseline to Alama Score:

```kotlin
data class SeasonalBaseline(
    val workerType: WorkerType,
    val monthlyExpectedIncome: Map<Int, Double>,  // Month 1-12 → expected income
    val adjustmentFactor: Double,                  // How much to adjust score for season
)

// In AlamaScoreEngine:
fun adjustForSeason(score: AlamaScore, month: Int, baseline: SeasonalBaseline): AlamaScore {
    val expected = baseline.monthlyExpectedIncome[month] ?: return score
    val actual = getCurrentMonthIncome()
    val ratio = actual / expected
    // If worker is performing at or above seasonal expectation, don't penalize
    return if (ratio >= 0.8) score else score.copy(
        score = score.score * (ratio / 0.8)  // Gentle penalty only if significantly below expectation
    )
}
```

**Priority: P2 — Important for seasonal workers, but they're a smaller segment.**

---

### Gap 8: Rural Transport Worker Viability (HIGH)

**Current:** Architecture assumes daily smartphone use, internet connectivity for sync, and regular income.

**Needed:** Rural transport workers (oxcart, donkey cart) have:
- Infrequent work (2-3 days/week)
- No internet (sync is rare)
- Very low income (KSh 100-500/day)
- May not speak Swahili fluently

**Assessment:** The architecture's offline-first design handles connectivity. But the **proof accumulation model** breaks down for rural workers:

| Issue | Impact | Mitigation |
|-------|--------|-----------|
| Infrequent recording | Slow proof accumulation | Lower frequency threshold for rural worker types |
| Very low income | Alama Score products (loans) have minimums | Set micro-loan floor at KSh 200 |
| Language barrier | Voice prompts may not be understood | Add more local language support |
| No M-Pesa | Cannot verify income via mobile money | Accept voice-only proof with lower confidence weight |

**Proposed:** Rural Worker Calibration:

```kotlin
data class RuralWorkerConfig(
    val expectedWeeklyDays: Int = 3,        // vs 6 for urban
    val minLoanAmount: Int = 200,           // vs 500 for urban
    val proofWeightMultiplier: Double = 1.5, // Each proof point counts more
    val tierThresholdReduction: Double = 0.5, // Lower thresholds for tier progression
)
```

**Priority: P1 — Without this, rural transport workers are effectively excluded.**

---

## Part 3: Alama Score Pillar Recalibration for Transport

### Current Pillar Weights vs Proposed Transport Weights

| Pillar | Current Weight | Proposed (Urban Transport) | Proposed (Rural Transport) | Rationale |
|--------|---------------|---------------------------|---------------------------|-----------|
| Frequency | 15% | 12% | 8% | Transport workers have more variable schedules |
| Revenue Trends | 15% | 10% | 8% | Seasonal/weather volatility should not penalize |
| Margins | 15% | 18% | 15% | Fuel/maintenance costs are critical for transport |
| Diversity | 10% | 8% | 5% | Diversification means different things for transport |
| Regularity | 10% | 12% | 10% | Rush-hour patterns are valuable signals |
| Growth | 10% | 10% | 8% | Still important but harder to measure |
| Expense Control | 10% | 15% | 12% | Fuel discipline, maintenance scheduling critical |
| Savings | 15% | 15% | 14% | Universal signal, slightly reduced for rural |
| **NEW: Safety Compliance** | — | **—** | **10%** | Rural workers: vehicle/animal safety, insurance |
| **NEW: Trip Completion Rate** | — | **—** | **10%** | Did they complete trips reliably? |

### Transport-Specific Metrics

**For Pillar 2 (Revenue Trends) — Transport Adjustment:**
```
Instead of: revenue_trend = slope(daily_revenue)
Use: revenue_trend = slope(daily_revenue) * seasonal_adjustment_factor

Where seasonal_adjustment_factor = 
  1.0 if current_month_income >= 0.8 * same_month_last_year
  0.7 if no last_year_data
  actual/expected if below 0.8
```

**For Pillar 4 (Diversity) — Transport Adjustment:**
```
Instead of: diversity = entropy(product_categories)
Use: diversity = entropy(service_types) + entropy(routes) + entropy(client_types)

For boda: service_types=[passenger, delivery], routes=[CBD-Westlands, CBD-Eastlands]
For courier: client_types=[documents, food, packages]
```

**For Pillar 5 (Regularity) — Transport Adjustment:**
```
Instead of: regularity = entropy(hour_distribution) + entropy(day_distribution)
Use: regularity = pattern_match(actual_schedule, expected_schedule_for_type)

For boda: expected = bimodal (morning rush 6-9am, evening rush 5-8pm)
For matatu: expected = trimodal (morning, midday, evening)
For truck: expected = concentrated (departure day, arrival day)
```

---

## Part 4: Voice Parsing Adaptations

### Current Voice Parsing

Architecture assumes: "Nimeuziwa [item] [quantity], [price]"

### Transport Voice Patterns

| Worker | Typical Voice Input | Expected Parsing |
|--------|-------------------|------------------|
| Boda | "Nimepata abiria tatu, mia mbili" | SERVICE_TRIP, passengers=3, fare=200 |
| Tuk Tuk | "Nimefanya trip nane, hamsini kila moja" | SERVICE_TRIP, trips=8, fare_per=50 |
| Matatu | "Nimekusanya elfu tano, nimeshia elfu mbili" | FARE_COLLECTION, collected=5000, fuel=2000 |
| Taxi | "Nimepata trip ya airport, elfu tatu" | SERVICE_TRIP, destination="airport", fare=3000 |
| Delivery | "Nimefanya delivery tano, mia tatu, tips mia moja" | SERVICE_DELIVERY, deliveries=5, base=300, tips=100 |
| Truck | "Nimebeba mzigo Mombasa, elfu kumi" | SERVICE_TRIP, destination="Mombasa", payment=10000 |
| Fishing | "Nimevua samaki kilo ishirini, elfu mbili" | SALE, item="samaki", quantity=20, amount=2000 |
| Courier | "Nimefanya delivery tatu, mia tano kila moja" | SERVICE_DELIVERY, deliveries=3, fee_per=500 |
| Moving | "Nimehamisha nyumba, elfu kumi na tano" | JOB, type="moving", payment=15000 |
| School | "Nimepata malipo ya term, elfu tano" | SERVICE_CONTRACT, period="term", payment=5000 |

**Key Patterns to Add to IntentClassifier:**

```kotlin
// New intent patterns for transport
val transportPatterns = mapOf(
    // Passenger transport
    "nimepata abiria" to IntentType.SERVICE_TRIP,
    "nimebeba abiria" to IntentType.SERVICE_TRIP,
    "nimefanya trip" to IntentType.SERVICE_TRIP,
    "nimepata trip" to IntentType.SERVICE_TRIP,
    "nimechukua abiria" to IntentType.SERVICE_TRIP,
    
    // Fare collection
    "nimekusanya" to IntentType.FARE_COLLECTION,
    "nimekusanya nauli" to IntentType.FARE_COLLECTION,
    
    // Delivery
    "nimefanya delivery" to IntentType.SERVICE_DELIVERY,
    "nimepeleka" to IntentType.SERVICE_DELIVERY,
    "nimeleta package" to IntentType.SERVICE_DELIVERY,
    
    // Goods transport
    "nimebeba mzigo" to IntentType.SERVICE_TRIP,
    "nimebeba mchanga" to IntentType.SERVICE_TRIP,
    "nimebeba mali" to IntentType.SERVICE_TRIP,
    
    // Fishing
    "nimevua" to IntentType.SALE,
    "nimepata samaki" to IntentType.SALE,
    
    // Moving
    "nimehamisha" to IntentType.JOB,
    "nimesaidia kuhamia" to IntentType.JOB,
    
    // Contract income
    "nimepata malipo" to IntentType.SERVICE_CONTRACT,
    "malipo ya term" to IntentType.SERVICE_CONTRACT,
    "nimepata mshahara" to IntentType.SERVICE_CONTRACT,
)
```

---

## Part 5: Onboarding Adaptations for Transport Workers

### Current Onboarding

Architecture onboarding captures: name, business type, location, then first transaction.

### Transport Worker Onboarding

Transport workers need different onboarding:

```
STEP 1: First Trip Recording (30 sec)
  Msaidizi: "Habari! Mimi ni Msaidizi wako. Nitakusaidia kufuatilia
    mapato yako ya usafiri. Leo umefanya trip ngapi? Sema tu:
    'Nimepata abiria X, bei Y'"
  Worker: "Nimepata abiria watano, mia mbili"
  Msaidizi: "Hongera! Umepata KSh 1,000 leo kutoka abiria 5."

STEP 2: Vehicle Type Detection (15 sec)
  Msaidizi: "Unatumia gari gani? Boda boda? Tuk tuk? Gari?"
  Worker: "Boda boda"
  → WorkerType = BODA, VehicleType = MOTORCYCLE

STEP 3: Route Pattern (15 sec)
  Msaidizi: "Unafanya kazi wapi? CBD? Westlands? Route gani?"
  Worker: "Nafanya CBD hadi Westlands"
  → Primary route captured for route optimization later

STEP 4: Quick Expense (10 sec)
  Msaidizi: "Leo umetumia pesa gani? Mafuta? Chakula?"
  Worker: "Nimenunua mafuta mia tano"
  → First expense recorded, fuel tracking begins

STEP 5: The Hook
  Msaidizi: "Kesho sema tu mapato yako. Baada ya siku 30,
    nitakuambia faida yako halisi!"
```

**Key Difference:** Onboarding must detect **vehicle type** early, as it determines:
- Income patterns (daily vs irregular)
- Expense categories (fuel, maintenance, sacco fees)
- Alama Score calibration
- Relevant alerts (weather for boda, maintenance for truck)

---

## Part 6: Summary of Required Architecture Changes

### P0 — Must Have (Blocks Transport Worker Adoption)

| Change | Effort | Impact |
|--------|--------|--------|
| Extend TransactionType with service types | Medium | All transport workers can record income |
| Add ServiceDetails to Transaction model | Medium | Proper service transaction data capture |
| Add transport patterns to IntentClassifier | Low | Voice parsing for transport phrases |
| Recalibrate Alama Score for variable income | High | Fair scoring for transport workers |
| Add WorkerType calibration to scoring | High | Type-specific scoring thresholds |

### P1 — Should Have (Significantly Improves Experience)

| Change | Effort | Impact |
|--------|--------|--------|
| Transport-specific proactive alerts | Medium | Fuel, weather, maintenance alerts |
| Safety alerts for fishermen | Low | **Life safety** — highest priority |
| M-Pesa auto-import for app-based workers | Medium | Accelerated proof for taxi/delivery |
| Batch trip recording | Low | "8 trips at 50 each" instead of 8 recordings |
| Rural worker calibration | Medium | Viability for oxcart/donkey workers |
| Trip context for multi-day jobs | Medium | Truck/tour/moving workers |

### P2 — Nice to Have (Enhanced Experience)

| Change | Effort | Impact |
|--------|--------|--------|
| Basic route optimization (nearest neighbor) | Medium | Couriers, delivery riders |
| Revenue splitting model | Medium | Matatu, truck, fishing crews |
| Seasonal baseline for Alama Score | Medium | Fishing, tourism, school transport |
| Contract-based income model | Low | School transport, contracted drivers |
| Vehicle maintenance tracking | Low | All vehicle owners |
| Emergency quick-record mode | Low | Ambulance drivers |

---

## Part 7: Final Verdict

### Can the Architecture Handle Service-Based Income?

**Answer: Not out of the box, but it can be adapted.**

The core architecture (voice-first, offline-first, proof-based) is **fundamentally sound** for transport workers. The M-KOPA proof model works — transport workers building a verifiable history of income is just as valuable as product sellers doing the same.

However, the **data model** is product-centric and needs service-transaction extensions. The **Alama Score pillars** assume product-business patterns and need recalibration for variable service income. The **proactive alerts** miss transport-critical triggers (fuel, weather, maintenance, routes).

### Architecture Strengths for Transport

| Strength | Why It Matters |
|----------|---------------|
| Voice-first design | Transport workers often can't stop to type — they're driving |
| Offline-first | Rural routes, lake areas have no connectivity |
| Proof accumulation | Transport workers have NO financial history — this creates one |
| Progressive tiers | Encourages consistent tracking over months |
| Dialect support | Sheng for urban boda riders, Dholuo for fishermen |
| M-Pesa integration | App-based drivers have verifiable income streams |
| Shared Context Engine | Fuel expenses, trip income, maintenance all connected |
| Safety Guard | Prevents dangerous advice (e.g., "drive faster to earn more") |

### Architecture Weaknesses for Transport

| Weakness | Impact | Mitigation |
|----------|--------|-----------|
| Product-centric data model | Can't properly record trip income | Extend TransactionType + ServiceDetails |
| Daily-assumption proof model | Penalizes irregular workers | Worker-type calibration |
| Revenue stability assumption | Penalizes seasonal/weather-dependent workers | Seasonal adjustment factors |
| No route optimization | Missing high-value feature | Add basic nearest-neighbor |
| No vehicle tracking | Missing maintenance/fuel context | Add vehicle profile |
| No safety alerts | Fishermen risk their lives | Add weather/safety alerts (P0) |

### Recommendation

**Proceed with architecture adoption for transport workers, with the following phased approach:**

1. **Phase 1 (Weeks 1-2):** P0 changes — service transaction model, voice patterns, basic Alama calibration
2. **Phase 2 (Weeks 3-4):** P1 changes — transport alerts, safety alerts, M-Pesa auto-import, batch recording
3. **Phase 3 (Weeks 5-8):** P2 changes — route optimization, revenue splitting, seasonal patterns

The architecture's core design — one brain, voice-first, offline-first, proof-based — is exactly what transport informal workers need. The adaptations are in the **data model and scoring calibration**, not the fundamental architecture.

---

*Validation complete. 20 worker types assessed. Architecture: CONDITIONAL PASS.*
