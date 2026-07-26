package com.msaidizi.app.ui.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// ──────────────────────────────────────────────
// Composition Locals
// ──────────────────────────────────────────────

val LocalMsaidiziColors = staticCompositionLocalOf<MsaidiziColors> {
    error("No MsaidiziColors provided")
}
val LocalMsaidiziTypography = staticCompositionLocalOf<MsaidiziTypography> {
    error("No MsaidiziTypography provided")
}
val LocalMsaidiziSpacing = staticCompositionLocalOf { MsaidiziSpacing() }
val LocalMsaidiziShapes = staticCompositionLocalOf { MsaidiziShapes() }

// ──────────────────────────────────────────────
// Theme Mode
// ──────────────────────────────────────────────

enum class MsaidiziThemeMode {
    LIGHT,          // Default — warm, easy on eyes
    HIGH_CONTRAST,  // Outdoor — maximum readability in sunlight
    DARK,           // Night mode — reduced eye strain
    SYSTEM          // Follow system setting
}

// ──────────────────────────────────────────────
// Spacing
// ──────────────────────────────────────────────

@Immutable
data class MsaidiziSpacing(
    val xs: Int = 4,
    val sm: Int = 8,
    val md: Int = 12,
    val lg: Int = 16,
    val xl: Int = 20,
    val xxl: Int = 24,
    val xxxl: Int = 32,
    val huge: Int = 48
)

// ──────────────────────────────────────────────
// Shapes
// ──────────────────────────────────────────────

@Immutable
data class MsaidiziShapes(
    val small: RoundedCornerShape = RoundedCornerShape(8.dp),
    val medium: RoundedCornerShape = RoundedCornerShape(12.dp),
    val large: RoundedCornerShape = RoundedCornerShape(16.dp),
    val extraLarge: RoundedCornerShape = RoundedCornerShape(24.dp),
    val full: RoundedCornerShape = RoundedCornerShape(percent = 50)
)

// ──────────────────────────────────────────────
// Touch Targets
// ──────────────────────────────────────────────

object TouchTarget {
    val minimum = 48.dp
    val comfortable = 56.dp
    val large = 64.dp
    val voiceButton = 80.dp
}

// ──────────────────────────────────────────────
// Theme Composable
// ──────────────────────────────────────────────

@Composable
fun MsaidiziTheme(
    themeMode: MsaidiziThemeMode = MsaidiziThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val resolvedMode = when (themeMode) {
        MsaidiziThemeMode.SYSTEM -> if (systemDark) MsaidiziThemeMode.DARK else MsaidiziThemeMode.LIGHT
        else -> themeMode
    }

    when (resolvedMode) {
        MsaidiziThemeMode.LIGHT -> MsaidiziLightTheme(content)
        MsaidiziThemeMode.HIGH_CONTRAST -> MsaidiziHighContrastTheme(content)
        MsaidiziThemeMode.DARK -> MsaidiziDarkTheme(content)
        else -> MsaidiziLightTheme(content)
    }
}

@Composable
private fun MsaidiziLightTheme(content: @Composable () -> Unit) {
    val colors = MsaidiziColors()
    ProvideMsaidiziTokens(colors, content)
}

@Composable
private fun MsaidiziHighContrastTheme(content: @Composable () -> Unit) {
    val hc = MsaidiziHighContrastColors()
    val colors = MsaidiziColors(
        primary = hc.primary,
        onPrimary = hc.onPrimary,
        primaryContainer = hc.primaryContainer,
        onPrimaryContainer = hc.onPrimaryContainer,
        secondary = hc.secondary,
        onSecondary = hc.onSecondary,
        secondaryContainer = hc.secondaryContainer,
        onSecondaryContainer = hc.onSecondaryContainer,
        tertiary = hc.tertiary,
        onTertiary = hc.onTertiary,
        tertiaryContainer = hc.tertiaryContainer,
        onTertiaryContainer = hc.onTertiaryContainer,
        success = hc.success,
        onSuccess = hc.onSuccess,
        successContainer = hc.successContainer,
        warning = hc.warning,
        onWarning = hc.onWarning,
        warningContainer = hc.warningContainer,
        error = hc.error,
        onError = hc.onError,
        errorContainer = hc.errorContainer,
        info = hc.info,
        onInfo = hc.onInfo,
        infoContainer = hc.infoContainer,
        background = hc.background,
        onBackground = hc.onBackground,
        surface = hc.surface,
        onSurface = hc.onSurface,
        surfaceVariant = hc.surfaceVariant,
        onSurfaceVariant = hc.onSurfaceVariant,
        outline = hc.outline,
        outlineVariant = hc.outlineVariant,
        voiceIdle = hc.voiceIdle,
        voiceListening = hc.voiceListening,
        voiceProcessing = hc.voiceProcessing,
        voiceSpeaking = hc.voiceSpeaking,
        voiceError = hc.voiceError,
        cardBackground = hc.cardBackground,
        cardElevation = hc.cardElevation,
        divider = hc.divider,
        chartPositive = hc.chartPositive,
        chartNegative = hc.chartNegative,
        chartNeutral = hc.chartNeutral,
        chartAccent = hc.chartAccent
    )
    ProvideMsaidiziTokens(colors, content)
}

@Composable
private fun MsaidiziDarkTheme(content: @Composable () -> Unit) {
    val dc = MsaidiziDarkColors()
    val colors = MsaidiziColors(
        primary = dc.primary,
        onPrimary = dc.onPrimary,
        primaryContainer = dc.primaryContainer,
        onPrimaryContainer = dc.onPrimaryContainer,
        secondary = dc.secondary,
        onSecondary = dc.onSecondary,
        secondaryContainer = dc.secondaryContainer,
        onSecondaryContainer = dc.onSecondaryContainer,
        tertiary = dc.tertiary,
        onTertiary = dc.onTertiary,
        tertiaryContainer = dc.tertiaryContainer,
        onTertiaryContainer = dc.onTertiaryContainer,
        success = dc.success,
        onSuccess = dc.onSuccess,
        successContainer = dc.successContainer,
        warning = dc.warning,
        onWarning = dc.onWarning,
        warningContainer = dc.warningContainer,
        error = dc.error,
        onError = dc.onError,
        errorContainer = dc.errorContainer,
        info = dc.info,
        onInfo = dc.onInfo,
        infoContainer = dc.infoContainer,
        background = dc.background,
        onBackground = dc.onBackground,
        surface = dc.surface,
        onSurface = dc.onSurface,
        surfaceVariant = dc.surfaceVariant,
        onSurfaceVariant = dc.onSurfaceVariant,
        outline = dc.outline,
        outlineVariant = dc.outlineVariant,
        voiceIdle = dc.voiceIdle,
        voiceListening = dc.voiceListening,
        voiceProcessing = dc.voiceProcessing,
        voiceSpeaking = dc.voiceSpeaking,
        voiceError = dc.voiceError,
        cardBackground = dc.cardBackground,
        cardElevation = dc.cardElevation,
        divider = dc.divider,
        chartPositive = dc.chartPositive,
        chartNegative = dc.chartNegative,
        chartNeutral = dc.chartNeutral,
        chartAccent = dc.chartAccent
    )
    ProvideMsaidiziTokens(colors, content)
}

@Composable
private fun ProvideMsaidiziTokens(
    colors: MsaidiziColors,
    content: @Composable () -> Unit
) {
    val typography = MsaidiziTypography()
    val spacing = MsaidiziSpacing()
    val shapes = MsaidiziShapes()

    val m3ColorScheme = lightColorScheme(
        primary = colors.primary,
        onPrimary = colors.onPrimary,
        primaryContainer = colors.primaryContainer,
        onPrimaryContainer = colors.onPrimaryContainer,
        secondary = colors.secondary,
        onSecondary = colors.onSecondary,
        secondaryContainer = colors.secondaryContainer,
        onSecondaryContainer = colors.onSecondaryContainer,
        tertiary = colors.tertiary,
        onTertiary = colors.onTertiary,
        tertiaryContainer = colors.tertiaryContainer,
        onTertiaryContainer = colors.onTertiaryContainer,
        error = colors.error,
        onError = colors.onError,
        errorContainer = colors.errorContainer,
        background = colors.background,
        onBackground = colors.onBackground,
        surface = colors.surface,
        onSurface = colors.onSurface,
        surfaceVariant = colors.surfaceVariant,
        onSurfaceVariant = colors.onSurfaceVariant,
        outline = colors.outline,
        outlineVariant = colors.outlineVariant,
        inverseSurface = colors.onBackground,
        inverseOnSurface = colors.background,
        surfaceTint = colors.primary
    )

    val m3Typography = Typography(
        displayLarge = typography.displayLarge,
        displayMedium = typography.displayMedium,
        displaySmall = typography.displaySmall,
        headlineLarge = typography.headlineLarge,
        headlineMedium = typography.headlineMedium,
        headlineSmall = typography.headlineSmall,
        titleLarge = typography.titleLarge,
        titleMedium = typography.titleMedium,
        titleSmall = typography.titleSmall,
        bodyLarge = typography.bodyLarge,
        bodyMedium = typography.bodyMedium,
        bodySmall = typography.bodySmall,
        labelLarge = typography.labelLarge,
        labelMedium = typography.labelMedium,
        labelSmall = typography.labelSmall
    )

    val m3Shapes = Shapes(
        small = shapes.small,
        medium = shapes.medium,
        large = shapes.large,
        extraLarge = shapes.extraLarge
    )

    CompositionLocalProvider(
        LocalMsaidiziColors provides colors,
        LocalMsaidiziTypography provides typography,
        LocalMsaidiziSpacing provides spacing,
        LocalMsaidiziShapes provides shapes
    ) {
        MaterialTheme(
            colorScheme = m3ColorScheme,
            typography = m3Typography,
            shapes = m3Shapes,
            content = content
        )
    }
}

// ──────────────────────────────────────────────
// Theme Access Object
// ──────────────────────────────────────────────

object MsaidiziThemeTokens {
    val colors: MsaidiziColors @Composable get() = LocalMsaidiziColors.current
    val typography: MsaidiziTypography @Composable get() = LocalMsaidiziTypography.current
    val spacing: MsaidiziSpacing @Composable get() = LocalMsaidiziSpacing.current
    val shapes: MsaidiziShapes @Composable get() = LocalMsaidiziShapes.current
}

// ──────────────────────────────────────────────
// KES Formatting Utilities
// ──────────────────────────────────────────────

fun formatKes(amount: Double): String {
    val abs = kotlin.math.abs(amount)
    val prefix = if (amount < 0) "-" else ""
    return when {
        abs >= 1_000_000 -> "${prefix}KES ${"%.1f".format(abs / 1_000_000)}M"
        abs >= 100_000 -> "${prefix}KES ${"%.0f".format(abs / 1_000)}K"
        abs >= 10_000 -> "${prefix}KES ${"%.1f".format(abs / 1_000)}K"
        else -> "${prefix}KES ${"%,.0f".format(abs)}"
    }
}

fun formatKesFull(amount: Double): String {
    val prefix = if (amount < 0) "-" else ""
    return "${prefix}KES ${"%,.0f".format(kotlin.math.abs(amount))}"
}
