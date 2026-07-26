package com.msaidizi.app.ui.designsystem.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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

// ──────────────────────────────────────────────
// Stock Card
// Product inventory with restock alert
// ──────────────────────────────────────────────

@Composable
fun StockCard(
    productName: String,
    currentStock: Double,
    minStock: Double,
    unit: String,
    modifier: Modifier = Modifier,
    category: String? = null,
    sellPrice: Double? = null,
    onClick: (() -> Unit)? = null
) {
    val colors = MsaidiziThemeTokens.colors

    val stockRatio = if (minStock > 0) (currentStock / minStock).coerceIn(0.0, 3.0) else 1.0
    val isLow = currentStock <= minStock
    val isCritical = currentStock <= minStock * 0.5

    val statusColor = when {
        isCritical -> colors.error
        isLow -> colors.warning
        else -> colors.success
    }

    val statusLabel = when {
        isCritical -> "Inahitajika haraka!"
        isLow -> "Stock ya chini"
        else -> "Inatosha"
    }

    val progressColor = when {
        isCritical -> colors.error
        isLow -> colors.warning
        else -> colors.success
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
                // Product icon
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(MsaidiziShapes().medium)
                        .background(colors.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Inventory2,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = productName,
                        style = MsaidiziThemeTokens.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (category != null) {
                        Text(
                            text = category,
                            style = MsaidiziThemeTokens.typography.caption,
                            color = colors.onSurfaceVariant
                        )
                    }
                }

                // Restock alert indicator
                if (isLow) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isCritical) Icons.Default.Warning else Icons.Default.Info,
                            contentDescription = statusLabel,
                            tint = statusColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Stock level bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${currentStock.toInt()} $unit",
                    style = MsaidiziThemeTokens.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
                Spacer(modifier = Modifier.width(8.dp))
                LinearProgressIndicator(
                    progress = { (stockRatio / 3.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = progressColor,
                    trackColor = progressColor.copy(alpha = 0.15f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Min: ${minStock.toInt()}",
                    style = MsaidiziThemeTokens.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }

            if (sellPrice != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Bei: KES ${"%,.0f".format(sellPrice)} / $unit",
                    style = MsaidiziThemeTokens.typography.caption,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}
