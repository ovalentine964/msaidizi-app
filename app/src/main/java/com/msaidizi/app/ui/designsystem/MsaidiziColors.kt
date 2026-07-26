package com.msaidizi.app.ui.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// ──────────────────────────────────────────────
// Msaidizi Color System
// Warm, high-contrast, outdoor-readable
// Inspired by African sunset, earth, and growth
// ──────────────────────────────────────────────

// ── Raw Palette ──────────────────────────────
object MsaidiziPalette {
    // Primary — Warm Orange (energy, warmth, African sunset)
    val Orange50 = Color(0xFFFFF3E0)
    val Orange100 = Color(0xFFFFE0B2)
    val Orange200 = Color(0xFFFFCC80)
    val Orange400 = Color(0xFFFFA726)
    val Orange600 = Color(0xFFFF6B35)  // Main primary
    val Orange800 = Color(0xFFE65100)
    val Orange900 = Color(0xFFBF360C)

    // Secondary — Deep Teal (trust, stability)
    val Teal50 = Color(0xFFE0F2F1)
    val Teal100 = Color(0xFFB2DFDB)
    val Teal200 = Color(0xFF80CBC4)
    val Teal400 = Color(0xFF26A69A)
    val Teal600 = Color(0xFF00897B)
    val Teal800 = Color(0xFF004E64)  // Main secondary
    val Teal900 = Color(0xFF003344)

    // Success — Forest Green (growth, profit)
    val Green50 = Color(0xFFE8F5E9)
    val Green100 = Color(0xFFC8E6C9)
    val Green400 = Color(0xFF66BB6A)
    val Green600 = Color(0xFF2E8B57)  // Main success
    val Green800 = Color(0xFF1B5E20)

    // Warning — Amber (attention)
    val Amber50 = Color(0xFFFFF8E1)
    val Amber100 = Color(0xFFFFECB3)
    val Amber400 = Color(0xFFFFCA28)
    val Amber600 = Color(0xFFFFA500)  // Main warning
    val Amber800 = Color(0xFFFF8F00)

    // Error — Crimson (danger)
    val Red50 = Color(0xFFFFEBEE)
    val Red100 = Color(0xFFFFCDD2)
    val Red400 = Color(0xFFEF5350)
    val Red600 = Color(0xFFDC143C)  // Main error
    val Red800 = Color(0xFFB71C1C)

    // Neutrals — Warm whites and grays
    val WarmWhite = Color(0xFFFFF8F0)     // Background
    val Cream = Color(0xFFFFFDF7)         // Surface
    val WarmGray50 = Color(0xFFFAF9F7)
    val WarmGray100 = Color(0xFFF5F3EF)
    val WarmGray200 = Color(0xFFE8E4DD)
    val WarmGray300 = Color(0xFFD5D0C8)
    val WarmGray400 = Color(0xFFB0A99E)
    val WarmGray600 = Color(0xFF78716C)
    val WarmGray800 = Color(0xFF44403C)
    val WarmGray900 = Color(0xFF1C1917)

    // Voice state colors
    val VoiceIdle = Teal800
    val VoiceListening = Green600
    val VoiceProcessing = Amber600
    val VoiceSpeaking = Color(0xFF1565C0)
    val VoiceError = Red600
}

// ── Light Theme Colors ───────────────────────
@Immutable
data class MsaidiziColors(
    // Primary
    val primary: Color = MsaidiziPalette.Orange600,
    val onPrimary: Color = Color.White,
    val primaryContainer: Color = MsaidiziPalette.Orange100,
    val onPrimaryContainer: Color = MsaidiziPalette.Orange900,

    // Secondary
    val secondary: Color = MsaidiziPalette.Teal800,
    val onSecondary: Color = Color.White,
    val secondaryContainer: Color = MsaidiziPalette.Teal100,
    val onSecondaryContainer: Color = MsaidiziPalette.Teal900,

    // Tertiary — warm gold accent
    val tertiary: Color = MsaidiziPalette.Amber600,
    val onTertiary: Color = Color.White,
    val tertiaryContainer: Color = MsaidiziPalette.Amber100,
    val onTertiaryContainer: Color = MsaidiziPalette.Amber800,

    // Semantic
    val success: Color = MsaidiziPalette.Green600,
    val onSuccess: Color = Color.White,
    val successContainer: Color = MsaidiziPalette.Green100,
    val warning: Color = MsaidiziPalette.Amber600,
    val onWarning: Color = Color.White,
    val warningContainer: Color = MsaidiziPalette.Amber100,
    val error: Color = MsaidiziPalette.Red600,
    val onError: Color = Color.White,
    val errorContainer: Color = MsaidiziPalette.Red100,
    val info: Color = MsaidiziPalette.Teal400,
    val onInfo: Color = Color.White,
    val infoContainer: Color = MsaidiziPalette.Teal50,

    // Surfaces
    val background: Color = MsaidiziPalette.WarmWhite,
    val onBackground: Color = MsaidiziPalette.WarmGray900,
    val surface: Color = MsaidiziPalette.Cream,
    val onSurface: Color = MsaidiziPalette.WarmGray900,
    val surfaceVariant: Color = MsaidiziPalette.WarmGray100,
    val onSurfaceVariant: Color = MsaidiziPalette.WarmGray600,
    val outline: Color = MsaidiziPalette.WarmGray300,
    val outlineVariant: Color = MsaidiziPalette.WarmGray200,

    // Voice states
    val voiceIdle: Color = MsaidiziPalette.VoiceIdle,
    val voiceListening: Color = MsaidiziPalette.VoiceListening,
    val voiceProcessing: Color = MsaidiziPalette.VoiceProcessing,
    val voiceSpeaking: Color = MsaidiziPalette.VoiceSpeaking,
    val voiceError: Color = MsaidiziPalette.VoiceError,

    // Card
    val cardBackground: Color = Color.White,
    val cardElevation: Color = Color(0x1A000000),
    val divider: Color = MsaidiziPalette.WarmGray200,

    // Chart
    val chartPositive: Color = MsaidiziPalette.Green600,
    val chartNegative: Color = MsaidiziPalette.Red600,
    val chartNeutral: Color = MsaidiziPalette.WarmGray300,
    val chartAccent: Color = MsaidiziPalette.Orange600
)

// ── High Contrast Theme (Outdoor) ────────────
@Immutable
data class MsaidiziHighContrastColors(
    val primary: Color = Color(0xFFE65100),          // Darker orange
    val onPrimary: Color = Color.White,
    val primaryContainer: Color = Color(0xFFFFCC80),
    val onPrimaryContainer: Color = Color(0xFF3E1500),

    val secondary: Color = Color(0xFF002233),         // Deeper teal
    val onSecondary: Color = Color.White,
    val secondaryContainer: Color = Color(0xFF80CBC4),
    val onSecondaryContainer: Color = Color(0xFF001320),

    val tertiary: Color = Color(0xFFE65100),
    val onTertiary: Color = Color.White,
    val tertiaryContainer: Color = Color(0xFFFFCC80),
    val onTertiaryContainer: Color = Color(0xFF3E1500),

    val success: Color = Color(0xFF1B5E20),
    val onSuccess: Color = Color.White,
    val successContainer: Color = Color(0xFFC8E6C9),
    val warning: Color = Color(0xFFE65100),
    val onWarning: Color = Color.White,
    val warningContainer: Color = Color(0xFFFFCC80),
    val error: Color = Color(0xFFB71C1C),
    val onError: Color = Color.White,
    val errorContainer: Color = Color(0xFFFFCDD2),
    val info: Color = Color(0xFF0D47A1),
    val onInfo: Color = Color.White,
    val infoContainer: Color = Color(0xFFBBDEFB),

    val background: Color = Color.White,
    val onBackground: Color = Color.Black,
    val surface: Color = Color(0xFFF5F5F5),
    val onSurface: Color = Color.Black,
    val surfaceVariant: Color = Color(0xFFE0E0E0),
    val onSurfaceVariant: Color = Color(0xFF212121),
    val outline: Color = Color(0xFF424242),
    val outlineVariant: Color = Color(0xFF9E9E9E),

    val voiceIdle: Color = Color(0xFF002233),
    val voiceListening: Color = Color(0xFF1B5E20),
    val voiceProcessing: Color = Color(0xFFE65100),
    val voiceSpeaking: Color = Color(0xFF0D47A1),
    val voiceError: Color = Color(0xFFB71C1C),

    val cardBackground: Color = Color.White,
    val cardElevation: Color = Color(0x33000000),
    val divider: Color = Color(0xFF9E9E9E),

    val chartPositive: Color = Color(0xFF1B5E20),
    val chartNegative: Color = Color(0xFFB71C1C),
    val chartNeutral: Color = Color(0xFF757575),
    val chartAccent: Color = Color(0xFFE65100)
)

// ── Dark Theme Colors ────────────────────────
@Immutable
data class MsaidiziDarkColors(
    val primary: Color = MsaidiziPalette.Orange400,
    val onPrimary: Color = MsaidiziPalette.Orange900,
    val primaryContainer: Color = MsaidiziPalette.Orange800,
    val onPrimaryContainer: Color = MsaidiziPalette.Orange100,

    val secondary: Color = MsaidiziPalette.Teal200,
    val onSecondary: Color = MsaidiziPalette.Teal900,
    val secondaryContainer: Color = MsaidiziPalette.Teal800,
    val onSecondaryContainer: Color = MsaidiziPalette.Teal100,

    val tertiary: Color = MsaidiziPalette.Amber400,
    val onTertiary: Color = MsaidiziPalette.Amber800,
    val tertiaryContainer: Color = MsaidiziPalette.Amber800,
    val onTertiaryContainer: Color = MsaidiziPalette.Amber100,

    val success: Color = MsaidiziPalette.Green400,
    val onSuccess: Color = MsaidiziPalette.Green800,
    val successContainer: Color = Color(0xFF1B5E20),
    val warning: Color = MsaidiziPalette.Amber400,
    val onWarning: Color = MsaidiziPalette.Amber800,
    val warningContainer: Color = Color(0xFF5C3D00),
    val error: Color = MsaidiziPalette.Red400,
    val onError: Color = Color.White,
    val errorContainer: Color = MsaidiziPalette.Red800,
    val info: Color = Color(0xFF64B5F6),
    val onInfo: Color = Color(0xFF0D47A1),
    val infoContainer: Color = Color(0xFF1565C0),

    val background: Color = Color(0xFF1C1917),
    val onBackground: Color = Color(0xFFE8E4DD),
    val surface: Color = Color(0xFF292524),
    val onSurface: Color = Color(0xFFE8E4DD),
    val surfaceVariant: Color = Color(0xFF44403C),
    val onSurfaceVariant: Color = Color(0xFFD5D0C8),
    val outline: Color = MsaidiziPalette.WarmGray600,
    val outlineVariant: Color = MsaidiziPalette.WarmGray800,

    val voiceIdle: Color = MsaidiziPalette.Teal200,
    val voiceListening: Color = MsaidiziPalette.Green400,
    val voiceProcessing: Color = MsaidiziPalette.Amber400,
    val voiceSpeaking: Color = Color(0xFF64B5F6),
    val voiceError: Color = MsaidiziPalette.Red400,

    val cardBackground: Color = Color(0xFF292524),
    val cardElevation: Color = Color(0x33FFFFFF),
    val divider: Color = MsaidiziPalette.WarmGray800,

    val chartPositive: Color = MsaidiziPalette.Green400,
    val chartNegative: Color = MsaidiziPalette.Red400,
    val chartNeutral: Color = MsaidiziPalette.WarmGray600,
    val chartAccent: Color = MsaidiziPalette.Orange400
)
