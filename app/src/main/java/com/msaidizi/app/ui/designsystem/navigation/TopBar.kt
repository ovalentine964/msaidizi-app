package com.msaidizi.app.ui.designsystem.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msaidizi.app.ui.designsystem.MsaidiziThemeTokens
import com.msaidizi.app.ui.designsystem.TouchTarget

// ──────────────────────────────────────────────
// Top Bar
// Back button, title, voice button
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MsaidiziTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showBack: Boolean = false,
    onBack: (() -> Unit)? = null,
    showVoice: Boolean = true,
    onVoice: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val colors = MsaidiziThemeTokens.colors

    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    style = MsaidiziThemeTokens.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MsaidiziThemeTokens.typography.caption,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        },
        modifier = modifier,
        navigationIcon = {
            if (showBack && onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(TouchTarget.minimum)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Rudi",
                        tint = colors.onSurface
                    )
                }
            }
        },
        actions = {
            actions()
            if (showVoice && onVoice != null) {
                IconButton(
                    onClick = onVoice,
                    modifier = Modifier.size(TouchTarget.minimum)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Sema",
                        tint = colors.primary
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.surface,
            titleContentColor = colors.onSurface,
            navigationIconContentColor = colors.onSurface,
            actionIconContentColor = colors.onSurface
        )
    )
}
