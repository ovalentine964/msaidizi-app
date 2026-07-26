package com.msaidizi.app.ui.designsystem.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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

// ──────────────────────────────────────────────
// Alert Urgency Levels
// ──────────────────────────────────────────────

enum class AlertUrgency {
    CRITICAL,   // Red — immediate action needed
    WARNING,    // Amber — attention needed
    INFO,       // Teal — informational
    SUCCESS     // Green — positive outcome
}

// ──────────────────────────────────────────────
// Alert Card
// Color-coded urgency with action button
// ──────────────────────────────────────────────

@Composable
fun AlertCard(
    title: String,
    message: String,
    urgency: AlertUrgency,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null
) {
    val colors = MsaidiziThemeTokens.colors

    val (bgColor, accentColor, defaultIcon) = when (urgency) {
        AlertUrgency.CRITICAL -> Triple(
            colors.errorContainer,
            colors.error,
            Icons.Default.Error
        )
        AlertUrgency.WARNING -> Triple(
            colors.warningContainer,
            colors.warning,
            Icons.Default.Warning
        )
        AlertUrgency.INFO -> Triple(
            colors.infoContainer,
            colors.info,
            Icons.Default.Info
        )
        AlertUrgency.SUCCESS -> Triple(
            colors.successContainer,
            colors.success,
            Icons.Default.CheckCircle
        )
    }

    val displayIcon = icon ?: defaultIcon

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = displayIcon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MsaidiziThemeTokens.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message,
                    style = MsaidiziThemeTokens.typography.bodyMedium,
                    color = colors.onSurface
                )

                if (actionLabel != null && onAction != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onAction,
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        shape = MsaidiziShapes().full,
                        modifier = Modifier.height(TouchTarget.comfortable)
                    ) {
                        Text(
                            text = actionLabel,
                            style = MsaidiziThemeTokens.typography.buttonLabel
                        )
                    }
                }
            }

            if (onDismiss != null) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(TouchTarget.minimum)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Funga",
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

private val TouchTarget = com.msaidizi.app.ui.designsystem.TouchTarget
