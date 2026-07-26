package com.msaidizi.app.ui.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ──────────────────────────────────────────────
// Msaidizi Typography Scale
// Optimized for low-literacy users and outdoor use
// All sizes 2sp larger than Material 3 defaults
// Line height 1.5x for readability
// ──────────────────────────────────────────────

@Immutable
data class MsaidiziTypography(
    // Display — Hero text, splash screens
    val displayLarge: TextStyle = TextStyle(
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 51.sp,     // 1.5x
        letterSpacing = 0.sp
    ),
    val displayMedium: TextStyle = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 42.sp,
        letterSpacing = 0.sp
    ),
    val displaySmall: TextStyle = TextStyle(
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),

    // Headlines — Page titles
    val headlineLarge: TextStyle = TextStyle(
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 39.sp,
        letterSpacing = 0.sp
    ),
    val headlineMedium: TextStyle = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 33.sp,
        letterSpacing = 0.sp
    ),
    val headlineSmall: TextStyle = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 30.sp,
        letterSpacing = 0.sp
    ),

    // Titles — Section headers
    val titleLarge: TextStyle = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 33.sp,
        letterSpacing = 0.sp
    ),
    val titleMedium: TextStyle = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 27.sp,
        letterSpacing = 0.15.sp
    ),
    val titleSmall: TextStyle = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    ),

    // Body — Primary reading text (larger for accessibility)
    val bodyLarge: TextStyle = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 27.sp,
        letterSpacing = 0.5.sp
    ),
    val bodyMedium: TextStyle = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp,
        letterSpacing = 0.25.sp
    ),
    val bodySmall: TextStyle = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 21.sp,
        letterSpacing = 0.25.sp
    ),

    // Labels — Buttons, chips, badges
    val labelLarge: TextStyle = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 27.sp,
        letterSpacing = 0.1.sp
    ),
    val labelMedium: TextStyle = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    val labelSmall: TextStyle = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 21.sp,
        letterSpacing = 0.5.sp
    ),

    // ── Custom semantic styles ───────────────

    // Voice transcript — what the user said
    val voiceTranscript: TextStyle = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 30.sp,
        letterSpacing = 0.25.sp
    ),

    // Amount display — KES values
    val amountDisplay: TextStyle = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 42.sp,
        letterSpacing = 0.sp
    ),

    // Amount inline — smaller amounts in cards
    val amountInline: TextStyle = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 27.sp,
        letterSpacing = 0.sp
    ),

    // Button label — primary action buttons
    val buttonLabel: TextStyle = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 27.sp,
        letterSpacing = 0.5.sp
    ),

    // Caption — secondary info, timestamps
    val caption: TextStyle = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp,
        letterSpacing = 0.25.sp
    ),

    // Overline — category labels, tags
    val overline: TextStyle = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 21.sp,
        letterSpacing = 1.sp
    )
)

// ── Typography scale constants ───────────────
object MsaidiziTypeScale {
    const val DISPLAY_LARGE = 34
    const val DISPLAY_MEDIUM = 28
    const val DISPLAY_SMALL = 24
    const val HEADLINE_LARGE = 26
    const val HEADLINE_MEDIUM = 22
    const val HEADLINE_SMALL = 20
    const val TITLE_LARGE = 22
    const val TITLE_MEDIUM = 18
    const val TITLE_SMALL = 16
    const val BODY_LARGE = 18
    const val BODY_MEDIUM = 16
    const val BODY_SMALL = 14
    const val LABEL_LARGE = 18
    const val LABEL_MEDIUM = 16
    const val LABEL_SMALL = 14
    const val VOICE_TRANSCRIPT = 20
    const val AMOUNT_DISPLAY = 28
    const val BUTTON_LABEL = 18
    const val CAPTION = 16

    // Dynamic type scaling factors
    val SCALE_FACTORS = mapOf(
        "small" to 0.85f,
        "default" to 1.0f,
        "large" to 1.15f,
        "extra_large" to 1.3f
    )
}
