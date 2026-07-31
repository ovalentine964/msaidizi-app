package com.msaidizi.app.ui.designsystem.buttons

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msaidizi.app.ui.designsystem.MsaidiziThemeTokens
import com.msaidizi.app.ui.designsystem.MsaidiziShapes
import com.msaidizi.app.ui.designsystem.TouchTarget

// ──────────────────────────────────────────────
// Action Button
// Large, high-contrast, icon + text
// Primary action button for key workflows
// ──────────────────────────────────────────────

@Composable
fun ActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MsaidiziThemeTokens.colors.primary,
    contentColor: Color = MsaidiziThemeTokens.colors.onPrimary,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget.comfortable),
        enabled = enabled,
        shape = MsaidiziShapes().large,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = MsaidiziThemeTokens.colors.outline.copy(alpha = 0.3f),
            disabledContentColor = MsaidiziThemeTokens.colors.onSurfaceVariant
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 4.dp,
            pressedElevation = 2.dp
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MsaidiziThemeTokens.typography.buttonLabel,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ──────────────────────────────────────────────
// Quick Action
// Circular icon button for common actions
// ──────────────────────────────────────────────

@Composable
fun QuickAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MsaidiziThemeTokens.colors.primaryContainer,
    contentColor: Color = MsaidiziThemeTokens.colors.primary,
    badge: String? = null
) {
    val colors = MsaidiziThemeTokens.colors

    Column(
        modifier = modifier
            .widthIn(min = 72.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                modifier = Modifier
                    .size(TouchTarget.large)
                    .clip(CircleShape)
                    .background(containerColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = contentColor,
                    modifier = Modifier.size(28.dp)
                )
            }
            if (badge != null) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(colors.error),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = badge,
                        style = MsaidiziThemeTokens.typography.labelSmall,
                        color = colors.onError,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MsaidiziThemeTokens.typography.labelMedium,
            color = colors.onSurface,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

// ──────────────────────────────────────────────
// Outlined Action Button
// Secondary actions with border
// ──────────────────────────────────────────────

@Composable
fun OutlinedActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    borderColor: Color = MsaidiziThemeTokens.colors.primary,
    contentColor: Color = MsaidiziThemeTokens.colors.primary,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget.comfortable),
        enabled = enabled,
        shape = MsaidiziShapes().large,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) borderColor else borderColor.copy(alpha = 0.3f)
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MsaidiziThemeTokens.typography.buttonLabel,
            fontWeight = FontWeight.Medium
        )
    }
}
