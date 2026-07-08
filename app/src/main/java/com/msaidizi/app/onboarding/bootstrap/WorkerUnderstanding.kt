package com.msaidizi.app.onboarding.bootstrap

/**
 * Worker Understanding — what Msaidizi DERIVES from the conversation.
 *
 * This is NOT raw data (that's WorkerProfile). This is INTELLIGENCE.
 *
 * After 9 turns of conversation, Msaidizi doesn't just know "Mary sells mboga."
 * She understands:
 * - Mary is a MAMA archetype — experienced, knows her products
 * - She responds to WARM CASUAL tone — "Rafiki" not "Mshauri"
 * - She uses M-Pesa AND cash — MODERATE tech comfort
 * - Her biggest pain is STOCKOUT — she needs restock alerts
 * - She's HIGH STRESS about it — needs encouragement first
 * - She's on WhatsApp — can receive digital reports
 * - She works 12h/day — needs efficiency tips
 * - She has regulars — customer management features would help
 *
 * This understanding drives EVERYTHING after onboarding:
 * - What features to show first
 * - What reports to send
 * - How to phrase advice
 * - What tone to use
 * - What to prioritize
 *
 * ## Academic Foundations
 *
 * ### PSY 101 — Behavioral Psychology
 * Emotional state affects receptivity. Stressed workers need encouragement
 * before data. Confident workers want data first.
 *
 * ### ECO 201 — Producer Theory
 * Business maturity determines what help is most valuable:
 * - New → track sales (basic)
 * - Growing → manage stock (intermediate)
 * - Struggling → understand profit (urgent)
 * - Established → optimize operations (advanced)
 *
 * ### BCB 108 — Communication
 * Communication style determines how to phrase everything:
 * - Formal → proper Swahili, complete sentences
 * - Casual → Sheng mixed in, shorter sentences
 * - Minimal → bullet points, not paragraphs
 *
 * @see BootstrapConversation for the analysis that populates this
 */
data class WorkerUnderstanding(

    // ── WHO is this worker? ──
    /** The worker archetype — derived from all responses combined */
    var archetype: WorkerArchetype = WorkerArchetype.UNKNOWN,

    /** What kind of relationship does the worker want with their AI? */
    var relationshipType: RelationshipType = RelationshipType.FRIEND,

    // ── HOW to communicate ──
    /** Communication style — derived from how they speak */
    var communicationStyle: CommunicationStyle = CommunicationStyle(),

    // ── Business intelligence ──
    /** Business sophistication — derived from what they say about their business */
    var businessSophistication: BusinessSophistication = BusinessSophistication(),

    // ── Customer intelligence ──
    /** Customer profile — derived from how they describe their customers */
    var customerProfile: CustomerIntelligence = CustomerIntelligence(),

    // ── Tech intelligence ──
    /** Tech comfort — derived from payment methods, WhatsApp usage */
    var techProfile: TechIntelligence = TechIntelligence(),

    // ── Market intelligence ──
    /** Market context — derived from location description */
    var marketContext: MarketIntelligence = MarketIntelligence(),

    // ── Work intelligence ──
    /** Work patterns — derived from hours description */
    var workPatterns: WorkIntelligence = WorkIntelligence(),

    // ── Emotional intelligence ──
    /** How the worker feels about their business */
    var emotionalState: EmotionalState = EmotionalState.CALM,

    /** How the worker approaches problems */
    var problemSolvingStyle: ProblemSolving = ProblemSolving.DESCRIPTIVE,

    // ── Pain points ──
    /** What's hurting the most — drives help priorities */
    var painPoints: List<PainPoint> = emptyList(),

    // ── Help priorities ──
    /** What to focus on — the actionable output of understanding */
    var helpPriority: HelpPriority = HelpPriority(),

    // ── Greeting style ──
    /** How to greet this worker in future conversations */
    var greetingStyle: String = ""
)

// ═══════════════════════════════════════════════════════════════
// COMMUNICATION STYLE
// ═══════════════════════════════════════════════════════════════

/**
 * How the worker communicates — determines how Msaidizi should respond.
 */
data class CommunicationStyle(
    /** Primary language detected */
    val primaryLanguage: String = "sw",

    /** How formal is the worker? */
    val formality: Formality = Formality.STANDARD,

    /** How much does the worker say? */
    val verbosity: CommunicationComfort = CommunicationComfort.MODERATE,

    /** What tone should Msaidizi use? */
    val preferredTone: Tone = Tone.WARM_CASUAL,

    /** How likely is the worker to use Sheng? */
    val shengLikelihood: ShengLikelihood = ShengLikelihood.MODERATE
)

enum class Formality {
    FORMAL,     // "Jina langu ni Mary Wanjiku" — educated, proper
    STANDARD,   // "Naitwa Mary" — normal Swahili
    CASUAL      // "Mary" — direct, confident
}

enum class CommunicationComfort {
    MINIMAL,    // One-word answers — may be shy or just efficient
    MODERATE,   // Normal responses
    VERBOSE     // Storyteller — comfortable, detailed
}

enum class Tone {
    WARM_CASUAL,      // "Habari Mary! Rafiki hapa."
    PROFESSIONAL,     // "Habari Mary. Ripoti yako ya leo:"
    RESULTS_FOCUSED,  // "Mary, hapa kile kilichotokea leo:"
    NURTURING,        // "Habari mpendwa! Leo imekuwaje?"
    DIRECT,           // "Ripoti ya leo:"
    PLAYFUL,          // "Heeey Mary! Rafiki ana update! 🎉"
    ENCOURAGING       // "Mary, usijali — kila kitu kinakuwa vizuri. Hapa kile..."
}

enum class ShengLikelihood {
    LOW,      // Pure Swahili or English speaker
    MODERATE, // Some Sheng mixed in
    HIGH      // Heavy Sheng user — needs simplified language
}

// ═══════════════════════════════════════════════════════════════
// BUSINESS SOPHISTICATION
// ═══════════════════════════════════════════════════════════════

/**
 * How sophisticated is the worker's business operation?
 */
data class BusinessSophistication(
    /** How specific is their business description? */
    val specificity: BusinessSpecificity = BusinessSpecificity.VAGUE,

    /** How mature is the business? */
    val maturity: BusinessMaturity = BusinessMaturity.STABLE,

    /** How does the worker see herself? */
    val selfIdentity: WorkerIdentity = WorkerIdentity.UNDEFINED,

    /** Number of products (complexity indicator) */
    val productCount: Int = 0,

    /** Sells perishable goods? */
    val isPerishable: Boolean = false,

    /** Sells high-value items? */
    val isHighValue: Boolean = false,

    /** Sells services (not products)? */
    val isService: Boolean = false
)

enum class BusinessSpecificity {
    VAGUE,      // "Biashara" — doesn't elaborate
    MODERATE,   // "Nauza mboga sokoni" — basic description
    DETAILED    // "Nauza nyanya, sukuma, vitunguu — sokoni Gikomba" — knows her business
}

enum class BusinessMaturity {
    NEW,         // "Nimeanza tu" — just started
    STABLE,      // Normal description — running steadily
    GROWING,     // "Nataka kukua" — growth mindset
    STRUGGLING,  // "Ni ngumu" — facing challenges
    ESTABLISHED  // "Nimekuwa nafanya miaka mingi" — years of experience
}

enum class WorkerIdentity {
    MAMA,           // "Mama mboga" — sees herself as a mama
    FUNDI,          // "Fundi" — craftsperson/tradesperson
    BUSINESS_OWNER, // "Biashara yangu" — sees herself as a business owner
    WORKER,         // "Kazi yangu" — sees it as work, not business
    HUSTLER,        // "Hustler" — self-identified hustler
    UNDEFINED       // No clear self-identification
}

// ═══════════════════════════════════════════════════════════════
// CUSTOMER INTELLIGENCE
// ═══════════════════════════════════════════════════════════════

/**
 * Understanding of the worker's customer base.
 */
data class CustomerIntelligence(
    /** Does the worker have repeat customers? */
    val hasRegulars: Boolean = false,

    /** How do customers find the worker? */
    val acquisitionMethod: CustomerAcquisition = CustomerAcquisition.PASSIVE,

    /** How do customers pay? */
    val paymentMethod: PaymentType = PaymentType.CASH
)

enum class CustomerAcquisition {
    PASSIVE,    // "Wanakuja mwenyewe" — waits for customers
    REFERRAL,   // "Marafiki wanakuja" — word of mouth
    DIGITAL,    // "WhatsApp, Instagram" — online presence
    DELIVERY    // "Ninawapelekea" — goes to customers
}

// ═══════════════════════════════════════════════════════════════
// TECH INTELLIGENCE
// ═══════════════════════════════════════════════════════════════

/**
 * How comfortable is the worker with technology?
 */
data class TechIntelligence(
    /** Overall tech comfort level */
    val comfortLevel: TechComfort = TechComfort.LOW,

    /** Uses WhatsApp? */
    val usesWhatsApp: Boolean = false,

    /** Uses M-Pesa? */
    val usesMPesa: Boolean = false,

    /** Has any digital presence (social media, etc.)? */
    val hasDigitalPresence: Boolean = false
)

enum class TechComfort {
    LOW,       // Cash only, no smartphone features
    MODERATE,  // M-Pesa + basic smartphone
    HIGH       // WhatsApp, social media, comfortable with apps
}

// ═══════════════════════════════════════════════════════════════
// MARKET INTELLIGENCE
// ═══════════════════════════════════════════════════════════════

/**
 * Understanding of the worker's market environment.
 */
data class MarketIntelligence(
    /** Type of location */
    val locationType: String = "",

    /** Is this an urban area? */
    val isUrban: Boolean = false,

    /** Is this a rural area? */
    val isRural: Boolean = false,

    /** Does the worker have a named, established location? */
    val hasNamedLocation: Boolean = false,

    /** Is the worker mobile (moves around)? */
    val isMobile: Boolean = false
)

// ═══════════════════════════════════════════════════════════════
// WORK INTELLIGENCE
// ═══════════════════════════════════════════════════════════════

/**
 * Understanding of the worker's work patterns.
 */
data class WorkIntelligence(
    /** Total hours worked per day */
    val totalHours: Int = 8,

    /** Work intensity level */
    val intensity: WorkIntensity = WorkIntensity.NORMAL,

    /** Is the schedule consistent? */
    val isConsistent: Boolean = true,

    /** Does the worker know their peak hours? */
    val peakHourAwareness: Boolean = false,

    /** Days per week worked */
    val daysPerWeek: Int = 6
)

enum class WorkIntensity {
    LOW,       // Part-time, <6 hours
    NORMAL,    // Standard, 6-10 hours
    HIGH,      // Long hours, 10-14 hours
    EXTREME    // Overwork, 14+ hours — needs efficiency tips
}

// ═══════════════════════════════════════════════════════════════
// EMOTIONAL & PSYCHOLOGICAL
// ═══════════════════════════════════════════════════════════════

/**
 * How the worker feels about their business challenges.
 */
enum class EmotionalState {
    CALM,            // Describes challenges matter-of-factly
    REFLECTIVE,      // Long, thoughtful answer — processing
    MODERATE_STRESS, // Some stress indicators
    HIGH_STRESS,     // Multiple stress words — needs encouragement
    RESIGNED         // Short answer — accepted the situation
}

/**
 * How the worker approaches problem-solving.
 */
enum class ProblemSolving {
    VAGUE,          // "Ni ngumu tu" — doesn't elaborate
    DESCRIPTIVE,    // Describes the problem
    ANALYTICAL,     // Explains WHY ("kwa sababu...")
    ACTIVE,         // Has TRIED solutions ("nimejaribu...")
    PATTERN_AWARE   // Recognizes patterns ("kila wakati...")
}

/**
 * What's hurting the worker — drives help priorities.
 */
enum class PainPoint {
    STOCKOUT,          // Running out of stock
    PRICING,           // Pricing/margin problems
    CUSTOMER_SHORTAGE, // Not enough customers
    CASH_FLOW,         // Money problems
    KNOWLEDGE_GAP,     // Doesn't know how to manage
    EXTERNAL_SHOCK,    // Weather, police, competition
    GENERAL            // No specific pain identified
}

// ═══════════════════════════════════════════════════════════════
// WORKER ARCHETYPE — The final classification
// ═══════════════════════════════════════════════════════════════

/**
 * The worker archetype — the FINAL understanding of who this person is.
 * Derived from ALL responses combined.
 *
 * Each archetype gets different:
 * - Report types
 * - Feature priorities
 * - Communication style
 * - Advice tone
 */
enum class WorkerArchetype {
    /** Just started, not tech-savvy → simple tracking, lots of encouragement */
    NEW_TRADITIONAL,

    /** Just started, tech-savvy → interactive features, growth tips */
    NEW_DIGITAL,

    /** Growing business, knows her stuff → detailed reports, optimization */
    GROWING_SOPHISTICATED,

    /** Growing business, still learning → daily reports with tips */
    GROWING_BASIC,

    /** Struggling, stressed → encouragement first, then actionable help */
    STRUGGLING_STRESSED,

    /** Struggling, resilient → direct actionable advice */
    STRUGGLING_RESILIENT,

    /** Established, experienced → comprehensive analytics */
    ESTABLISHED,

    /** Can't determine → default to simple, warm approach */
    UNKNOWN
}

// ═══════════════════════════════════════════════════════════════
// RELATIONSHIP TYPE — What the worker wants from their AI
// ═══════════════════════════════════════════════════════════════

/**
 * The type of relationship the worker wants with Msaidizi.
 * Derived from the naming choice and conversation tone.
 */
enum class RelationshipType {
    FRIEND,          // Named "Rafiki" — wants warmth
    ADVISOR,         // Named "Mshauri" — wants professional advice
    BUSINESS_PARTNER,// Named "Biashara Yangu" — wants results
    FAMILY,          // Named "Mama" — wants nurturing
    PRAGMATIC,       // Skipped naming — just wants help
    CREATIVE         // Named something creative — playful personality
}

// ═══════════════════════════════════════════════════════════════
// HELP PRIORITY — What to focus on
// ═══════════════════════════════════════════════════════════════

/**
 * What Msaidizi should prioritize for this specific worker.
 * This is the ACTIONABLE output of the understanding.
 */
data class HelpPriority(
    /** The #1 thing to help with */
    val primary: HelpFocus = HelpFocus.TRACK_SALES,

    /** The #2 thing to help with */
    val secondary: HelpFocus = HelpFocus.UNDERSTAND_PROFIT,

    /** Specific features to enable first */
    val featurePriorities: List<String> = emptyList(),

    /** What kind of report to send */
    val reportType: ReportType = ReportType.SIMPLE_DAILY,

    /** How to deliver reports */
    val reportDelivery: ReportDeliveryMethod = ReportDeliveryMethod.IN_APP,

    /** What to do IMMEDIATELY after onboarding */
    val immediateAction: String = "record_first_sale"
)

enum class HelpFocus {
    TRACK_SALES,        // Basic: record every sale
    MANAGE_STOCK,       // Intermediate: track inventory
    UNDERSTAND_PROFIT,  // Critical: know if making money
    MANAGE_CASH,        // Urgent: money in vs money out
    GROW_BUSINESS,      // Advanced: find opportunities
    OPTIMIZE_OPERATIONS // Expert: maximize efficiency
}

enum class ReportType {
    SIMPLE_DAILY,       // "Leo umepata KES 1,200" — basic daily
    DAILY_WITH_TIPS,    // Daily + one actionable tip
    ENCOURAGING_DAILY,  // Daily + emotional support
    ACTIONABLE_DAILY,   // Daily + specific action items
    INTERACTIVE,        // Daily + asks follow-up questions
    DETAILED_WEEKLY,    // Weekly deep dive
    COMPREHENSIVE       // Full analytics
}

enum class ReportDeliveryMethod {
    IN_APP,     // Show in app only
    WHATSAPP,   // Send via WhatsApp
    SMS,        // Send via SMS
    VOICE       // Speak the report
}
