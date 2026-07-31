package com.msaidizi.app.ui.designsystem.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.msaidizi.app.ui.designsystem.MsaidiziThemeTokens
import com.msaidizi.app.ui.designsystem.MsaidiziShapes
import com.msaidizi.app.ui.designsystem.formatKes

// ──────────────────────────────────────────────
// Goal Card
// Savings/business goal with progress
// ──────────────────────────────────────────────

@Composable
fun GoalCard(
    goalName: String,
    currentAmount: Double,
    targetAmount: Double,
    daysRemaining: Int,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Flag,
    onClick: (() -> Unit)? = null
) {
    val colors = MsaidiziThemeTokens.colors

    val progress = if (targetAmount > 0) (currentAmount / targetAmount).coerceIn(0.0, 1.0) else 0.0
    val progressPct = (progress * 100).toInt()
    val isComplete = progress >= 1.0

    val progressColor = when {
        isComplete -> colors.success
        progress >= 0.7 -> colors.primary
        progress >= 0.4 -> colors.warning
        else -> colors.error
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = MsaidiziShapes().medium,
        colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(MsaidiziShapes().medium)
                        .background(progressColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isComplete) Icons.Default.CheckCircle else icon,
                        contentDescription = if (isComplete) "Lengo limekamilika" else "Lengo icon",
                        tint = progressColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = goalName,
                        style = MsaidiziThemeTokens.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isComplete) "Imekamilika!" else "Siku $daysRemaining zimebaki",
                        style = MsaidiziThemeTokens.typography.caption,
                        color = if (isComplete) colors.success else colors.onSurfaceVariant
                    )
                }

                Text(
                    text = "$progressPct%",
                    style = MsaidiziThemeTokens.typography.amountInline,
                    fontWeight = FontWeight.Bold,
                    color = progressColor
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { progress.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
                color = progressColor,
                trackColor = progressColor.copy(alpha = 0.15f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Amount labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatKes(currentAmount),
                    style = MsaidiziThemeTokens.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Text(
                    text = "Lengo: ${formatKes(targetAmount)}",
                    style = MsaidiziThemeTokens.typography.labelMedium,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}
