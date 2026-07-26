# Accessibility & Low-Literacy Design System

**Version:** 1.0  
**Date:** 2026-07-27  
**Status:** Design Specification  
**Target:** Users who cannot read or write (40%+ of informal workers in Africa)

---

## Design Principles

1. **Zero literacy required** — Every interaction must be completable without reading a single word
2. **Sunlight-readable** — Must work outdoors in direct equatorial sun
3. **Dirty-hands usable** — Large touch targets, forgiving gestures
4. **One-handed** — All critical actions reachable with thumb on 5" screen
5. **Voice-first** — Audio feedback and voice input are primary, not supplementary
6. **Universal** — Must work with TalkBack for blind users

---

## 1. Icon-First Navigation System

### Bottom Navigation Bar (5 Tabs)

The bottom bar uses **only icons + background colors**. No text labels are displayed by default. Each tab has:
- A **distinctive filled icon** (selected state)
- A **distinctive outlined icon** (unselected state)
- A **unique color** for the selected state indicator
- A **content description** for TalkBack (never visible on screen)

| # | Tab Name (Swahili) | English | Selected Icon | Unselected Icon | Selected Color | Color Hex |
|---|---|---|---|---|---|---|
| 1 | Mauzo | Sales | `Icons.Filled.PointOfSale` | `Icons.Outlined.PointOfSale` | Green | `#2E7D32` |
| 2 | Stock | Stock | `Icons.Filled.Inventory2` | `Icons.Outlined.Inventory2` | Blue | `#1565C0` |
| 3 | Ripoti | Reports | `Icons.Filled.BarChart` | `Icons.Outlined.BarChart` | Orange | `#E8853D` |
| 4 | Wateja | People | `Icons.Filled.People` | `Icons.Outlined.People` | Purple | `#7B1FA2` |
| 5 | Mipangilio | Settings | `Icons.Filled.Settings` | `Icons.Outlined.Settings` | Gray | `#616161` |

### Icon Design Requirements

Each icon MUST be:
- **Minimum 28dp** rendered size (within 56dp touch target)
- **Thick strokes** (2dp minimum) — visible at arm's length
- **Distinct silhouette** — no two tabs share similar shapes
- **Recognizable at 1dp/1px** — works on lowest-end screens
- **Color-independent** — shape alone identifies the tab (for colorblind users)

### Icon-to-Tab Mapping (Shape Language)

| Tab | Primary Shape | Mnemonic |
|---|---|---|
| Sales (Mauzo) | Coin/circle with KES symbol | Money = round coin |
| Stock (Stock) | Box with flap open | Warehouse box |
| Reports (Ripoti) | Tall bars (bar chart) | Growing bars |
| People (Wateja) | Two person silhouettes | People |
| Settings (Mipangilio) | Gear cog | Machine = settings |

### Navigation Bar Specifications

```
┌─────────────────────────────────────────────────────┐
│  [💰]     [📦]     [📊]     [👥]     [⚙️]          │
│  GREEN    BLUE     ORANGE   PURPLE   GRAY           │
│  56dp     56dp     56dp     56dp     56dp           │
└─────────────────────────────────────────────────────┘
```

- **Bar height:** 80dp (includes safe area)
- **Icon size:** 28dp
- **Touch target:** 56dp × 56dp per tab
- **Selected indicator:** Pill shape (RoundedCornerShape 16dp), filled with tab color at 12% opacity
- **Selected icon:** Filled variant, tab color
- **Unselected icon:** Outlined variant, `#616161` (gray)
- **No text labels** — icons only. TalkBack reads: "Mauzo, Sales tab, selected"

### Tab Color Accessibility (Colorblind Safety)

All tab colors pass WCAG 2.1 contrast ratios and are distinguishable by:
- **Shape** (primary differentiator)
- **Position** (consistent ordering)
- **Color** (supplementary only)

Deuteranopia/Protanopia safe: Green vs Blue vs Orange are distinguishable by luminance even without hue perception.

---

## 2. Color-Coded Status System

### Core Status Colors

Every status indicator in the app uses this exact color mapping:

| Status | Color Name | Hex | RGB | Use Cases | Icon |
|---|---|---|---|---|---|
| 🟢 Good | StatusGreen | `#2E7D32` | 46, 125, 50 | Profit, in-stock, paid, confirmed | ✓ checkmark |
| 🟡 Attention | StatusYellow | `#F9A825` | 249, 168, 37 | Low stock (<20%), pending payment, due soon | ⚠ warning triangle |
| 🔴 Urgent | StatusRed | `#C62828` | 198, 40, 40 | Out of stock, overdue debt, loss | ✕ cross |
| 🔵 Info | StatusBlue | `#1565C0` | 21, 101, 192 | Reports, data, settings, help | ℹ info circle |
| ⚪ Disabled | StatusGray | `#9E9E9E` | 158, 158, 158 | Inactive, archived, unavailable | — dash |

### Status Color Application Rules

#### Sales & Money
| State | Color | Icon | Example |
|---|---|---|---|
| Profitable day | 🟢 Green | ↑ arrow up | "Today: +KES 3,200" |
| Break-even | 🔵 Blue | → arrow right | "Today: KES 0" |
| Loss day | 🔴 Red | ↓ arrow down | "Today: -KES 500" |
| Pending payment | 🟡 Yellow | ⏳ clock | "John owes KES 1,200" |
| Paid in full | 🟢 Green | ✓ checkmark | "Settled" |

#### Inventory / Stock
| State | Color | Icon | Threshold |
|---|---|---|---|
| In stock | 🟢 Green | Full box icon | > 20% of typical stock |
| Low stock | 🟡 Yellow | Half-empty box | ≤ 20% of typical stock |
| Out of stock | 🔴 Red | Empty box with X | 0 units |
| New shipment | 🔵 Blue | Box with + | Just arrived |

#### Debts & Credit
| State | Color | Icon | Threshold |
|---|---|---|---|
| No debt | 🟢 Green | ✓ checkmark | KES 0 owed |
| Due soon | 🟡 Yellow | ⏳ clock | Due within 7 days |
| Overdue | 🔴 Red | ! exclamation | Past due date |
| Partially paid | 🔵 Blue | ½ half circle | Some payment received |

### Background Color Coding (Cards & Rows)

Status colors are applied as **left border accent** (4dp wide stripe) on list items, NOT as background fills. This preserves readability in sunlight.

```
┌──────────────────────────────────┐
│🟢│ [Box icon] Sugar  50 bags     │  ← Green left border = in stock
│  │ KES 4,500                     │
└──────────────────────────────────┘
┌──────────────────────────────────┐
│🟡│ [Box icon] Cooking Oil  3L    │  ← Yellow left border = low stock
│  │ Only 2 left!                  │
└──────────────────────────────────┘
┌──────────────────────────────────┐
│🔴│ [Box icon] Rice  25kg         │  ← Red left border = out of stock
│  │ EMPTY                         │
└──────────────────────────────────┘
```

### Number Color Rules

- **Positive numbers:** Green text (`#2E7D32`), with `+` prefix
- **Negative numbers:** Red text (`#C62828`), with `-` prefix
- **Zero/neutral:** Blue text (`#1565C0`)
- **Numbers always use `formatKes()`** — never raw digits

---

## 3. Gesture-Based Interactions

### Primary Gestures

| Gesture | Action | Visual Feedback | Audio Feedback |
|---|---|---|---|
| **Swipe Right** → | Confirm / Approve | Green flash + ✓ icon slides in | Success chime |
| **Swipe Left** ← | Delete / Undo | Red flash + trash icon slides in | Warning tone |
| **Long Press** (500ms) | Edit / More options | Haptic buzz + context menu rises | Subtle click |
| **Pull Down** ↓ | Refresh | Spinner appears, content reloads | Soft whoosh |
| **Double Tap** | Quick action (context-dependent) | Ripple effect on item | Confirmation click |
| **Single Tap** | Select / Open | Highlight state change | Light tap sound |

### Swipe Gesture Specifications

```
Swipe Right (Confirm):
┌─────────────────────────────────────┐
│ ████████░░░░░░░░░░░░░░░░░░░░░░░░░░│  ← Green background reveals
│ ✓  [Item content]                   │     as user swipes right
└─────────────────────────────────────┘
  → Swipe distance: 40% of row width to trigger
  → Animation: 200ms ease-out
  → Color: StatusGreen (#2E7D32) background
  → Icon: Checkmark, 24dp, white

Swipe Left (Delete):
┌─────────────────────────────────────┐
│░░░░░░░░░░░░░░░░░░░░░░░░░░░░████████│  ← Red background reveals
│          [Item content]       🗑     │     as user swipes left
└─────────────────────────────────────┘
  → Swipe distance: 40% of row width to trigger
  → Animation: 200ms ease-out
  → Color: StatusRed (#C62828) background
  → Icon: Trash can, 24dp, white
  → Confirmation: Undo snackbar appears for 5 seconds
```

### Gesture Conflict Resolution

- **Scrollable lists:** Swipe gestures only activate on horizontal swipe (>30° from vertical)
- **Long press vs scroll:** 500ms hold threshold before long-press activates
- **Double tap vs single tap:** 300ms window for second tap
- **Swipe vs tap:** Minimum 20dp movement to count as swipe

### Gesture Discoverability

For first-time users, gestures are taught via:
1. **Onboarding animation** — Shows swipe animations on a sample card
2. **Hint text** (with voice) — "Swipe right to confirm" appears once per gesture type
3. **Voice prompt** — After 3 seconds of inactivity on a list item: "Swipe right to confirm, or left to delete"

---

## 4. Audio Feedback Design

### Sound Effects

All sounds are short (< 500ms), non-intrusive, and work through phone speakers at any volume.

| Event | Sound Name | Duration | Character | File |
|---|---|---|---|---|
| **Success** (sale confirmed, payment received) | `success_chime` | 200ms | Rising two-tone (C5 → E5), warm | `res/raw/sfx_success.ogg` |
| **Error** (validation fail, network error) | `error_buzz` | 300ms | Low buzzer (A3), slightly harsh | `res/raw/sfx_error.ogg` |
| **Warning** (low stock, due payment) | `warning_ping` | 250ms | Single tone (G4), gentle | `res/raw/sfx_warning.ogg` |
| **Notification** (new message, sync complete) | `notify_chime` | 350ms | Three ascending tones (C5→E5→G5) | `res/raw/sfx_notify.ogg` |
| **Tap** (button press, selection) | `tap_click` | 50ms | Soft click | `res/raw/sfx_tap.ogg` |
| **Swipe** (gesture recognized) | `swipe_whoosh` | 150ms | Quick sweep | `res/raw/sfx_swipe.ogg` |
| **Long press** (hold recognized) | `hold_buzz` | 100ms | Haptic + soft thud | `res/raw/sfx_hold.ogg` |
| **Voice start** (mic activated) | `mic_on` | 200ms | Rising tone | `res/raw/sfx_mic_on.ogg` |
| **Voice stop** (mic deactivated) | `mic_off` | 200ms | Falling tone | `res/raw/sfx_mic_off.ogg` |
| **Delete** (item removed) | `delete_swoosh` | 250ms | Descending swoosh | `res/raw/sfx_delete.ogg` |

### Audio Feedback Rules

1. **Every action has audio** — No silent interactions (except scrolling)
2. **Voice always accompanies visual** — Critical state changes announce via TTS
3. **Respect system volume** — All sounds play at current media volume
4. **No sound in silent mode** — Unless TalkBack is active
5. **Sounds are additive, not replacing** — TTS narration plays ON TOP of sound effects

### TTS Narration Points

These events trigger spoken audio (in user's selected language):

| Event | TTS Message (Swahili) | TTS Message (English) |
|---|---|---|
| Sale recorded | "Mauzo yamerekodiwa. KES [amount]" | "Sale recorded. KES [amount]" |
| Payment received | "Malipo yamepokelewa. KES [amount]" | "Payment received. KES [amount]" |
| Low stock alert | "Bidhaa ya [name] inakaribia kuisha" | "[name] is running low" |
| Out of stock | "[name] imeisha" | "[name] is out of stock" |
| Debt overdue | "Deni la [name] limechelewa" | "[name]'s payment is overdue" |
| Navigation | "Uko kwenye [tab name]" | "You are on [tab name]" |
| Error | "Kosa. Jaribu tena" | "Error. Try again" |
| Voice listening | "Sikiliza..." | "Listening..." |
| Voice processing | "Nafikiri..." | "Thinking..." |
| Delete confirmation | "[item] imefutwa. Gusa kutendua" | "[item] deleted. Tap to undo" |

### Volume & Sound Settings

Accessible via Settings tab:
- **Sound effects:** On / Off toggle (default: On)
- **Voice narration:** On / Off toggle (default: On)
- **Voice language:** Swahili / English / Sheng
- **Volume:** Uses system media volume

---

## 5. High-Contrast Outdoor Mode

### Theme: `MsaidiziOutdoorTheme`

Designed for direct sunlight on a 5" screen. Maximum readability.

#### Color Specifications

| Element | Light Mode (Indoor) | Outdoor Mode (Sunlight) | Notes |
|---|---|---|---|
| **Background** | `#F5F5F0` (warm white) | `#FFFFFF` (pure white) | Maximum reflectivity |
| **Surface/Card** | `#FFFFFF` | `#FFFFFF` | Pure white |
| **Text Primary** | `#1A1C1E` (near black) | `#000000` (pure black) | Maximum contrast |
| **Text Secondary** | `#44474F` (dark gray) | `#1A1C1E` (near black) | No gray text outdoors |
| **Borders** | `#C4C6CF` (light gray) | `#000000` (pure black), 2dp | Thick, visible borders |
| **Dividers** | `#E0E0DB` | `#000000`, 1dp | Solid black lines |
| **Shadows** | `0x1A000000` | Disabled | No subtle shadows outdoors |
| **Elevation** | Shadow-based | Border-based | Use borders instead of shadows |

#### Typography for Outdoor Mode

| Style | Indoor Size | Outdoor Size | Weight | Notes |
|---|---|---|---|---|
| Display | 32sp | 36sp | Bold | Dashboard numbers |
| Headline | 24sp | 28sp | Bold | Screen titles |
| Title | 20sp | 24sp | SemiBold | Section headers |
| Body | 16sp | 20sp | Medium | Content text |
| Label | 14sp | 18sp | SemiBold | Buttons, tags |
| Caption | 12sp | 16sp | Medium | Secondary info |

**Rule: No text below 16sp in outdoor mode.**

#### Touch Targets (Outdoor Mode)

All targets grow by 8dp in outdoor mode:

| Element | Indoor | Outdoor |
|---|---|---|
| Minimum target | 48dp | 56dp |
| Comfortable target | 56dp | 64dp |
| Primary action (FAB) | 64dp | 72dp |
| Voice button | 80dp | 88dp |

#### Outdoor Mode Activation

- **Manual:** Toggle in Settings → Display → Outdoor Mode
- **Auto:** Activates when ambient light sensor detects > 10,000 lux
- **Quick toggle:** Pull-down notification shade quick tile

#### Visual Indicators for Outdoor Mode

When active:
- Status bar shows ☀️ sun icon
- All gray text becomes black
- All subtle borders become 2dp black
- Shadows replaced with solid borders
- Background pure white
- Accent colors slightly increased in saturation (+10%)

---

## 6. One-Handed Operation Layout

### Thumb Zone Map (5" Screen)

```
┌─────────────────────────┐
│                         │
│    HARD TO REACH        │  ← Avoid critical actions here
│    (top 30%)            │
│                         │
├─────────────────────────┤
│                         │
│    MODERATE REACH       │  ← Secondary content OK here
│    (middle 40%)         │
│                         │
├─────────────────────────┤
│                         │
│    EASY REACH           │  ← ALL critical actions here
│    (bottom 30%)         │
│                         │
│    [Voice FAB]          │  ← Floating action button
│    [Bottom Nav]         │  ← Navigation bar
└─────────────────────────┘
```

### Critical Action Placement Rules

1. **Floating Action Button (Voice):**
   - Position: Bottom-right, 16dp from edges
   - Size: 64dp (indoor) / 72dp (outdoor)
   - Always visible on every screen
   - Always in thumb zone
   - Color: `#1B4965` (primary blue) with white microphone icon

2. **Primary Actions (Confirm, Save, Submit):**
   - Always at bottom of screen
   - Full-width button, 56dp height minimum
   - Rounded corners (16dp)
   - Color-coded: Green for confirm, Red for delete

3. **Bottom Sheets (not top menus):**
   - All action menus slide up from bottom
   - Maximum height: 60% of screen
   - Dismissible by swipe down or tap outside
   - Actions listed vertically, 64dp per row

4. **Top Bar:**
   - Contains ONLY: Back button (left) and optional title (center)
   - NO action buttons in top bar
   - No overflow menu (⋮) in top bar

### Bottom Sheet Action Design

All contextual actions use bottom sheets:

```
┌─────────────────────────────────────────┐
│  ─── (drag handle, 40dp × 4dp)         │
│                                         │
│  ┌─────────────────────────────────┐    │
│  │ ✏️  Edit sale                    │    │  ← 64dp height
│  └─────────────────────────────────┘    │
│  ┌─────────────────────────────────┐    │
│  │ 📋 Copy details                 │    │  ← 64dp height
│  └─────────────────────────────────┘    │
│  ┌─────────────────────────────────┐    │
│  │ 🗑️  Delete                       │    │  ← 64dp height, RED
│  └─────────────────────────────────┘    │
│  ┌─────────────────────────────────┐    │
│  │ ❌ Cancel                        │    │  ← 64dp height, GRAY
│  └─────────────────────────────────┘    │
└─────────────────────────────────────────┘
```

### Screen Layout Template

Every screen follows this structure:

```
┌─────────────────────────────────┐
│ [←] Title (optional)            │  ← Top bar: back + title only
├─────────────────────────────────┤
│                                 │
│  [Content Area]                 │  ← Scrollable content
│  [Cards / Lists / Data]         │
│                                 │
│                                 │
├─────────────────────────────────┤
│  [Primary Action Button]        │  ← Always at bottom
│  Full-width, green/blue         │
├─────────────────────────────────┤
│  [💰] [📦] [📊] [👥] [⚙️]     │  ← Bottom nav
└─────────────────────────────────┘
         ↑
    [🎤 FAB]                       │  ← Voice button overlay
```

### FAB (Floating Action Button) Specifications

- **Shape:** Circle, 64dp diameter (72dp outdoor)
- **Position:** Bottom-right, 16dp from right edge, 96dp from bottom (above nav bar)
- **Icon:** Filled microphone, white, 28dp
- **Background:** Primary blue (`#1B4965`)
- **Elevation:** 6dp (indoor) / 2dp black border (outdoor)
- **Behavior:**
  - Tap: Activate voice input
  - Long press: Show voice command help
  - Active state: Pulsing green ring animation
- **TalkBack:** "Record voice command, button"

---

## 7. TalkBack / Screen Reader Support

### Content Description Standards

Every interactive element MUST have:
- `contentDescription` — What the element is
- `stateDescription` — Current state (selected, expanded, etc.)
- `role` — What it does (button, tab, etc.)

### Content Description Examples

```kotlin
// Tab
contentDescription = "Mauzo, Sales tab"
stateDescription = if (selected) "selected" else "not selected"

// Status indicator
contentDescription = "Stock status: low, only 3 remaining"

// Swipeable item
contentDescription = "Sale to John for KES 500. Swipe right to confirm, swipe left to delete."

// Voice button
contentDescription = "Voice input"
stateDescription = when (voiceState) {
    Idle -> "tap to speak"
    Listening -> "listening, speak now"
    Processing -> "processing your request"
    Speaking -> "speaking response"
}

// Money amount
contentDescription = "Profit today: 3,200 Kenya shillings"

// Number with context
contentDescription = "Sugar: 50 bags in stock"
```

### Focus Order

Tab order follows logical reading order:
1. Top bar (back button)
2. Primary content (top to bottom)
3. Primary action button
4. Voice FAB
5. Bottom navigation (left to right)

### Gesture Overrides for TalkBack

When TalkBack is active:
- Swipe gestures are replaced with TalkBack's standard navigation
- Double-tap to activate (standard TalkBack behavior)
- Custom swipe actions are accessible via TalkBack's "Actions" menu
- Voice input remains available via FAB

---

## 8. Implementation Specifications

### Kotlin Constants File

```kotlin
// AccessibilityConstants.kt
object AccessibilityColors {
    val StatusGreen = Color(0xFF2E7D32)
    val StatusYellow = Color(0xFFF9A825)
    val StatusRed = Color(0xFFC62828)
    val StatusBlue = Color(0xFF1565C0)
    val StatusGray = Color(0xFF9E9E9E)

    // Tab colors
    val TabSales = Color(0xFF2E7D32)      // Green
    val TabStock = Color(0xFF1565C0)      // Blue
    val TabReports = Color(0xFFE8853D)    // Orange
    val TabPeople = Color(0xFF7B1FA2)     // Purple
    val TabSettings = Color(0xFF616161)   // Gray

    // Outdoor mode overrides
    val OutdoorBackground = Color(0xFFFFFFFF)
    val OutdoorText = Color(0xFF000000)
    val OutdoorBorder = Color(0xFF000000)
}

object AccessibilityTouchTargets {
    val Minimum = 48.dp
    val Comfortable = 56.dp
    val Large = 64.dp
    val VoiceButton = 80.dp
    val OutdoorMinimum = 56.dp
    val OutdoorComfortable = 64.dp
    val OutdoorVoiceButton = 88.dp
}

object AccessibilityTypography {
    val MinBodySize = 16.sp
    val OutdoorMinBodySize = 20.sp
    val MaxIconSize = 28.dp
}

object GestureThresholds {
    val SwipeDistance = 0.4f  // 40% of row width
    val LongPressMs = 500L
    val DoubleTapMs = 300L
    val SwipeAngleThreshold = 30  // degrees from horizontal
}
```

### Compose Modifier Extensions

```kotlin
// AccessibilityModifiers.kt
fun Modifier.accessibleSwipe(
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit,
    enabled: Boolean = true
): Modifier = this.then(
    Modifier.pointerInput(enabled) {
        // Swipe gesture detection with accessibility fallback
    }
)

fun Modifier.accessibleTouchTarget(
    outdoorMode: Boolean = false
): Modifier = this.then(
    Modifier.size(
        if (outdoorMode) AccessibilityTouchTargets.OutdoorComfortable
        else AccessibilityTouchTargets.Comfortable
    )
)

fun Modifier.accessibleContentDescription(
    description: String,
    state: String? = null,
    role: Role = Role.Button
): Modifier = this.then(
    Modifier.semantics {
        contentDescription = description
        state?.let { stateDescription = it }
        this.role = role
    }
)
```

### Navigation Bar Implementation

```kotlin
// AccessibleNavigationBar.kt
@Composable
fun AccessibleNavigationBar(
    selectedRoute: String,
    onNavigate: (String) -> Unit,
    outdoorMode: Boolean = false
) {
    NavigationBar(
        containerColor = if (outdoorMode) Color.White else MaterialTheme.colorScheme.surface,
        tonalElevation = if (outdoorMode) 0.dp else 8.dp,
        modifier = Modifier.height(80.dp)
    ) {
        accessibleNavItems.forEach { item ->
            val selected = selectedRoute == item.route
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(item.route) },
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = "${item.swahiliName}, ${item.englishName} tab",
                        modifier = Modifier.size(28.dp)
                    )
                },
                label = null, // No text labels - icon only
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = item.selectedColor,
                    unselectedIconColor = AccessibilityColors.StatusGray,
                    indicatorColor = item.selectedColor.copy(alpha = 0.12f)
                ),
                modifier = Modifier.semantics {
                    stateDescription = if (selected) "selected" else "not selected"
                }
            )
        }
    }
}
```

---

## 9. Testing Checklist

### No-Text-Readiness Tests

- [ ] Complete a full sale without reading any text
- [ ] Navigate between all 5 tabs using only icons
- [ ] Identify stock status using only colors
- [ ] Confirm/delete a transaction using only gestures
- [ ] Enter a sale using only voice
- [ ] Check today's profit using only audio feedback
- [ ] Find a customer using only voice search

### Outdoor Readability Tests

- [ ] All text readable at arm's length in direct sunlight
- [ ] All icons distinguishable in direct sunlight
- [ ] Status colors distinguishable in direct sunlight
- [ ] Touch targets accurate with sweaty/dirty fingers
- [ ] Screen readable with sunglasses on

### One-Handed Tests

- [ ] All primary actions reachable with thumb
- [ ] Voice button always in thumb zone
- [ ] No critical actions in top 30% of screen
- [ ] Bottom sheets dismissible with one hand
- [ ] Navigation possible with one hand

### TalkBack Tests

- [ ] Every element has contentDescription
- [ ] Tab navigation follows logical order
- [ ] Status changes announced automatically
- [ ] Voice input works alongside TalkBack
- [ ] Custom actions accessible via TalkBack menu

### Audio Tests

- [ ] Every action produces sound feedback
- [ ] TTS announces critical state changes
- [ ] Sounds work at minimum volume
- [ ] No sound conflicts with TalkBack
- [ ] Audio works through phone speaker (no headphones required)

---

## 10. Design Tokens Summary

### Colors

| Token | Hex | Usage |
|---|---|---|
| `status-green` | `#2E7D32` | Profit, in-stock, paid, confirmed |
| `status-yellow` | `#F9A825` | Low stock, pending, due soon |
| `status-red` | `#C62828` | Out of stock, overdue, loss |
| `status-blue` | `#1565C0` | Reports, info, settings |
| `status-gray` | `#9E9E9E` | Disabled, inactive |
| `tab-sales` | `#2E7D32` | Sales tab selected |
| `tab-stock` | `#1565C0` | Stock tab selected |
| `tab-reports` | `#E8853D` | Reports tab selected |
| `tab-people` | `#7B1FA2` | People tab selected |
| `tab-settings` | `#616161` | Settings tab selected |
| `bg-indoor` | `#F5F5F0` | Indoor background |
| `bg-outdoor` | `#FFFFFF` | Outdoor background |
| `text-primary` | `#1A1C1E` | Indoor primary text |
| `text-outdoor` | `#000000` | Outdoor primary text |
| `border-outdoor` | `#000000` | Outdoor borders |

### Spacing

| Token | Value | Usage |
|---|---|---|
| `touch-min` | 48dp | Minimum touch target |
| `touch-comfortable` | 56dp | Comfortable touch target |
| `touch-large` | 64dp | Primary actions |
| `touch-voice` | 80dp | Voice FAB |
| `icon-size` | 28dp | Navigation icons |
| `border-thick` | 2dp | Outdoor borders |
| `border-accent` | 4dp | Status left border |

### Typography

| Token | Indoor | Outdoor | Weight |
|---|---|---|---|
| `display` | 32sp | 36sp | Bold |
| `headline` | 24sp | 28sp | Bold |
| `title` | 20sp | 24sp | SemiBold |
| `body` | 16sp | 20sp | Medium |
| `label` | 14sp | 18sp | SemiBold |
| `caption` | 12sp | 16sp | Medium |

---

*This document is the authoritative accessibility specification for Msaidizi. All UI implementations must conform to these requirements. Any deviation requires explicit approval from the Accessibility & Low-Literacy Design Council.*
