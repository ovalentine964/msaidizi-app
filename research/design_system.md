# Msaidizi Design System

> Production-ready visual design system for informal workers in Africa.
> Optimized for outdoor use, low-literacy, 5" screens, and voice-first interaction.

---

## 1. Color Palette

### Primary Colors

| Role | Name | Hex | Usage |
|------|------|-----|-------|
| **Primary** | Warm Orange | `#FF6B35` | CTAs, active states, brand identity |
| **Secondary** | Deep Teal | `#004E64` | Headers, navigation, trust elements |
| **Tertiary** | Amber | `#FFA500` | Accent, highlights, secondary actions |

### Semantic Colors

| Role | Name | Hex | Usage |
|------|------|-----|-------|
| **Success** | Forest Green | `#2E8B57` | Profit, completed, positive |
| **Warning** | Amber | `#FFA500` | Low stock, attention needed |
| **Error** | Crimson | `#DC143C` | Loss, danger, critical alerts |
| **Info** | Teal | `#26A69A` | Informational, tips, advice |

### Surface Colors

| Role | Name | Hex | Usage |
|------|------|-----|-------|
| **Background** | Warm White | `#FFF8F0` | Screen background |
| **Surface** | Cream | `#FFFDF7` | Cards, sheets |
| **Surface Variant** | Warm Gray 100 | `#F5F3EF` | Input fields, chips |
| **Divider** | Warm Gray 200 | `#E8E4DD` | Horizontal rules |

### Voice State Colors

| State | Color | Hex | Meaning |
|-------|-------|-----|---------|
| Idle | Deep Teal | `#004E64` | Ready to listen |
| Listening | Forest Green | `#2E8B57` | Actively recording |
| Processing | Amber | `#FFA500` | Thinking / processing |
| Speaking | Blue | `#1565C0` | AI responding |
| Error | Crimson | `#DC143C` | Something went wrong |

### Color Guidelines

- **Contrast ratio**: Minimum 4.5:1 for text, 3:1 for large text and UI elements
- **High-contrast mode**: All colors shift to darker/more saturated variants for outdoor sunlight readability
- **Colorblind-safe**: Green/red pairs always accompanied by icons and text labels
- **Warm whites**: Background uses `#FFF8F0` instead of pure white to reduce glare

---

## 2. Typography Scale

All sizes are 2sp larger than Material 3 defaults. Line height is 1.5× for readability.

| Style | Size | Weight | Line Height | Usage |
|-------|------|--------|-------------|-------|
| Display Large | 34sp | Bold | 51sp | Hero text, splash |
| Display Medium | 28sp | Bold | 42sp | Amount displays |
| Display Small | 24sp | Bold | 36sp | Page titles |
| Headline Large | 26sp | Bold | 39sp | Section headers |
| Headline Medium | 22sp | SemiBold | 33sp | Card titles |
| Title Large | 22sp | SemiBold | 33sp | Top bar title |
| Title Medium | 18sp | Medium | 27sp | Section labels |
| Body Large | 18sp | Normal | 27sp | Primary body text |
| Body Medium | 16sp | Normal | 24sp | Secondary body |
| Label Large | 18sp | Medium | 27sp | Button labels |
| Label Medium | 16sp | Medium | 24sp | Chips, tags |
| Caption | 16sp | Normal | 24sp | Timestamps, hints |

### Custom Semantic Styles

| Style | Size | Weight | Usage |
|-------|------|--------|-------|
| Voice Transcript | 20sp | Medium | What the user said |
| Amount Display | 28sp | Bold | KES values on screen |
| Amount Inline | 18sp | SemiBold | Amounts in cards |
| Button Label | 18sp | SemiBold | Primary action buttons |

### Dynamic Type Scaling

| Scale | Factor | Use Case |
|-------|--------|----------|
| Small | 0.85× | Compact displays |
| Default | 1.0× | Standard |
| Large | 1.15× | Accessibility |
| Extra Large | 1.3× | Vision impaired |

---

## 3. Spacing & Touch Targets

### Spacing Scale

| Token | Value | Usage |
|-------|-------|-------|
| xs | 4dp | Tight gaps |
| sm | 8dp | Inline spacing |
| md | 12dp | Card padding |
| lg | 16dp | Section padding |
| xl | 20dp | Large gaps |
| xxl | 24dp | Screen margins |
| xxxl | 32dp | Section dividers |
| huge | 48dp | Hero spacing |

### Touch Targets

| Size | Value | Usage |
|------|-------|-------|
| Minimum | 48dp | Android minimum |
| Comfortable | 56dp | Standard buttons (recommended) |
| Large | 64dp | Quick actions, icon buttons |
| Voice Button | 80dp | Main microphone FAB |

---

## 4. Component Catalog

### 4.1 Cards

#### TransactionCard
Displays a single business transaction (sale, expense, purchase, credit, refund).

```kotlin
TransactionCard(
    type = TransactionType.SALE,
    amount = 2500.0,
    product = "Nyanya 5kg",
    timeAgo = "Saa 2 zilizopita",
    customerName = "Mama Amina",
    paymentMethod = "M-Pesa",
    onClick = { /* navigate */ }
)
```

**Features:**
- Color-coded by transaction type (green=sale, red=expense, amber=purchase, blue=credit)
- Icon + status label + amount
- Supports customer name and payment method
- 56dp minimum touch target

#### StockCard
Product inventory card with restock alert indicator.

```kotlin
StockCard(
    productName = "Sukari",
    currentStock = 3.0,
    minStock = 10.0,
    unit = "kg",
    category = "Chakula",
    sellPrice = 180.0,
    onClick = { /* edit stock */ }
)
```

**Features:**
- Progress bar showing stock level relative to minimum
- Color-coded: green (sufficient), amber (low), red (critical)
- Warning icon when stock is below minimum
- Shows sell price and unit

#### GoalCard
Savings or business goal with progress tracking.

```kotlin
GoalCard(
    goalName = "Kununua Bajaj",
    currentAmount = 45000.0,
    targetAmount = 120000.0,
    daysRemaining = 45,
    icon = Icons.Default.DirectionsBike,
    onClick = { /* view goal */ }
)
```

**Features:**
- Animated progress bar with percentage
- Days remaining countdown
- Color transitions based on progress (red→amber→orange→green)
- Checkmark icon when complete

#### CustomerCard
Customer info with debt tracking and segmentation.

```kotlin
CustomerCard(
    name = "Mama Grace",
    phone = "+254712345678",
    debtAmount = 1500.0,
    lastVisit = "Jana",
    segment = "VIP",
    onClick = { /* customer detail */ }
)
```

**Features:**
- Initial avatar with first letter
- Segment chip (VIP, Regular, Occasional, New)
- Debt amount highlighted in red
- Last visit timestamp

#### AdviceCard
AI-generated business advice with follow/dismiss actions.

```kotlin
AdviceCard(
    title = "Ushauri wa Biashara",
    adviceText = "Bei ya nyanya imepanda. Nunua sasa na uhifadhi kwa wiki ijayo.",
    icon = Icons.Default.Lightbulb,
    onFollow = { /* apply advice */ },
    onDismiss = { /* dismiss */ }
)
```

#### AlertCard
Color-coded urgency alerts with action buttons.

```kotlin
AlertCard(
    title = "Stock ya chini!",
    message = "Sukari imesalia 3kg tu. Nunua zaidi leo.",
    urgency = AlertUrgency.WARNING,
    actionLabel = "Nunua Sasa",
    onAction = { /* navigate to purchase */ }
)
```

**Urgency levels:** CRITICAL (red), WARNING (amber), INFO (teal), SUCCESS (green)

---

### 4.2 Buttons

#### VoiceFAB (in SharedComponents)
Floating action button with animation states for voice interaction.

**States:** Idle → Listening → Processing → Speaking → Error

**Animations:**
- Idle: Static teal button with mic icon
- Listening: Green button with pulsing outer ring
- Processing: Amber button with spinning loader
- Speaking: Blue button with volume icon
- Error: Red button with error icon

#### ActionButton
Large, high-contrast button with icon + text for primary actions.

```kotlin
ActionButton(
    label = "Rekodi Mauzo",
    icon = Icons.Default.Add,
    onClick = { /* record sale */ },
    containerColor = MsaidiziThemeTokens.colors.primary
)
```

**Features:**
- 56dp minimum height
- Full-width by default
- Icon + text layout
- Customizable colors

#### QuickAction
Circular icon button for common dashboard actions.

```kotlin
QuickAction(
    icon = Icons.Default.PointOfSale,
    label = "Uza",
    onClick = { /* sell */ },
    badge = "3" // optional notification badge
)
```

#### OutlinedActionButton
Secondary action button with border outline.

---

### 4.3 Inputs

#### VoiceInput
Full microphone button with waveform visualization.

```kotlin
VoiceInput(
    state = VoiceInputState.LISTENING,
    onToggle = { /* toggle listening */ },
    transcript = "Nimeuza nyanya tano",
    hint = "Gusa kusema"
)
```

**Features:**
- Animated waveform when listening
- Transcript display card
- State-driven color changes
- Pulse animation during recording

#### AmountInput
Large number pad optimized for cash amounts.

```kotlin
AmountInput(
    value = "2500",
    onValueChange = { /* update value */ },
    currency = "KES",
    onConfirm = { /* confirm amount */ }
)
```

**Features:**
- Large circular number keys (56dp touch targets)
- Formatted display with comma separators
- Decimal support
- Backspace key
- Confirm button

#### ProductSelector
Grid of product icons with text/voice search.

```kotlin
ProductSelector(
    products = productList,
    onProductSelected = { /* select product */ },
    selectedId = 42L,
    onVoiceSearch = { /* voice search */ },
    searchQuery = "nya",
    onSearchQueryChange = { /* update search */ }
)
```

**Features:**
- 3-column grid layout
- Icon + name + price per item
- Search bar with voice search button
- Selected state with border highlight
- Out-of-stock indicator

---

### 4.4 Charts

#### SalesChart
Bar chart for daily/weekly/monthly sales data.

```kotlin
SalesChart(
    data = listOf(
        BarChartData("Jumatatu", 3200.0),
        BarChartData("Jumanne", 4100.0),
        BarChartData("Jumatano", 2800.0),
        // ...
    ),
    period = ChartPeriod.DAILY,
    barColor = MsaidiziThemeTokens.colors.primary
)
```

#### ProfitTrendChart
Line chart showing profit/loss with green/red zones.

```kotlin
ProfitTrendChart(
    data = profitData,
    positiveColor = MsaidiziThemeTokens.colors.chartPositive,
    negativeColor = MsaidiziThemeTokens.colors.chartNegative
)
```

**Features:**
- Zero line with dashed indicator
- Green points for profit, red for loss
- Legend with color key
- Smooth line path

#### StockLevelChart
Horizontal progress bars showing stock levels per product.

```kotlin
StockLevelChart(
    products = listOf(
        StockLevel("Nyanya", 15.0, 50.0, "kg"),
        StockLevel("Sukari", 3.0, 20.0, "kg"),
        // ...
    )
)
```

---

### 4.5 Navigation

#### BottomNavBar
5-tab navigation with icons, labels, and badge support.

**Tabs:** Sema (Voice) · Dashibodi (Home) · Miamala (Sales) · Stock · Zaidi (More)

```kotlin
MsaidiziBottomNavBar(
    currentRoute = "dashboard",
    onNavigate = { route -> /* navigate */ }
)
```

**Features:**
- Swahili-first labels
- Badge count support
- Selected state with indicator
- 56dp touch targets per tab

#### TopBar
App bar with back button, title, and voice action.

```kotlin
MsaidiziTopBar(
    title = "Dashibodi",
    subtitle = "Leo: 27 Julai 2026",
    showBack = true,
    onBack = { /* navigate back */ },
    showVoice = true,
    onVoice = { /* activate voice */ }
)
```

#### SideMenu
Navigation drawer with profile, settings, and help.

```kotlin
MsaidiziSideMenu(
    userName = "John Kamau",
    businessName = "Kamau Duka",
    currentRoute = "dashboard",
    onNavigate = { route -> /* navigate */ },
    onClose = { /* close drawer */ }
)
```

**Menu items:** Profile · Business · Reports · Customers · Savings · Settings · Help

---

## 5. Theme System

### Theme Modes

| Mode | Description | When to Use |
|------|-------------|-------------|
| **Light** | Warm white background, standard contrast | Default, indoor use |
| **High Contrast** | Maximum contrast, darker colors, bold borders | Outdoor / sunlight |
| **Dark** | Dark surfaces, muted colors | Night mode, low light |
| **System** | Follows device system setting | Automatic |

### Using the Theme

```kotlin
// In your Activity or root composable:
MsaidiziTheme(themeMode = MsaidiziThemeMode.LIGHT) {
    // Your app content
    Scaffold(
        topBar = { MsaidiziTopBar(title = "Dashibodi") },
        bottomBar = { MsaidiziBottomNavBar(...) }
    ) { padding ->
        // Screen content
    }
}
```

### Accessing Design Tokens

```kotlin
// Colors
val primary = MsaidiziThemeTokens.colors.primary
val success = MsaidiziThemeTokens.colors.success

// Typography
val bodyStyle = MsaidiziThemeTokens.typography.bodyLarge
val amountStyle = MsaidiziThemeTokens.typography.amountDisplay

// Spacing
val padding = MsaidiziThemeTokens.spacing.lg.dp

// Shapes
val cardShape = MsaidiziThemeTokens.shapes.medium
```

### Dynamic Theme Switching

```kotlin
var themeMode by remember { mutableStateOf(MsaidiziThemeMode.SYSTEM) }

MsaidiziTheme(themeMode = themeMode) {
    // In settings screen:
    SegmentedButton(
        options = listOf("Light", "Outdoor", "Dark"),
        onSelectionChange = { index ->
            themeMode = when (index) {
                0 -> MsaidiziThemeMode.LIGHT
                1 -> MsaidiziThemeMode.HIGH_CONTRAST
                2 -> MsaidiziThemeMode.DARK
                else -> MsaidiziThemeMode.SYSTEM
            }
        }
    )
}
```

---

## 6. KES Formatting

The design system includes built-in KES (Kenyan Shilling) formatting:

```kotlin
formatKes(2500)      // "KES 2,500"
formatKes(15000)     // "KES 15K"
formatKes(150000)    // "KES 150K"
formatKes(1500000)   // "KES 1.5M"
formatKesFull(2500)  // "KES 2,500" (always full format)
```

---

## 7. File Structure

```
ui/designsystem/
├── MsaidiziDesignSystem.kt    # Entry point (re-exports)
├── MsaidiziColors.kt          # Color palette + 3 theme variants
├── MsaidiziTypography.kt      # Type scale + semantic styles
├── MsaidiziTheme.kt           # Theme composable + tokens
├── cards/
│   ├── TransactionCard.kt
│   ├── StockCard.kt
│   ├── GoalCard.kt
│   ├── CustomerCard.kt
│   ├── AdviceCard.kt
│   └── AlertCard.kt
├── buttons/
│   └── ActionButton.kt        # ActionButton + QuickAction + OutlinedActionButton
├── inputs/
│   ├── VoiceInput.kt          # Voice input + waveform
│   ├── AmountInput.kt         # Number pad
│   └── ProductSelector.kt     # Product grid + search
├── charts/
│   └── SalesChart.kt          # SalesChart + ProfitTrend + StockLevel
└── navigation/
    ├── BottomNavBar.kt         # 5-tab bottom nav
    ├── TopBar.kt               # App top bar
    └── SideMenu.kt             # Navigation drawer
```

---

## 8. Design Principles

1. **Voice-first**: Every screen has a voice button. Voice is the primary input.
2. **Large text**: Minimum 16sp body, 18sp buttons. Larger than standard.
3. **High contrast**: All text meets WCAG AA (4.5:1). Outdoor mode pushes to AAA.
4. **Big touch targets**: 56dp minimum. 80dp for the voice button.
5. **Warm palette**: Orange, teal, cream. Feels friendly, not corporate.
6. **Swahili-first**: All labels in Swahili with English subtitles.
7. **Color-coded status**: Green=good, amber=attention, red=danger. Always with icons.
8. **5" screen ready**: No horizontal overflow. Scrollable where needed.
9. **Offline-ready**: No network-dependent UI. Works fully offline.
10. **Accessible**: Supports dynamic type scaling up to 1.3×.
