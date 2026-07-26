package com.msaidizi.app.ui.designsystem.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
// Customer Card
// Customer info with debt tracking
// ──────────────────────────────────────────────

@Composable
fun CustomerCard(
    name: String,
    modifier: Modifier = Modifier,
    phone: String? = null,
    debtAmount: Double = 0.0,
    lastVisit: String? = null,
    totalPurchases: Double = 0.0,
    segment: String? = null, // VIP, Regular, Occasional, New
    onClick: (() -> Unit)? = null
) {
    val colors = MsaidiziThemeTokens.colors

    val hasDebt = debtAmount > 0
    val segmentColor = when (segment?.lowercase()) {
        "vip" -> colors.tertiary
        "regular" -> colors.primary
        "occasional" -> colors.onSurfaceVariant
        "new" -> colors.info
        else -> colors.onSurfaceVariant
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = MsaidiziShapes().medium,
        colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.take(1).uppercase(),
                    style = MsaidiziThemeTokens.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = MsaidiziThemeTokens.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (segment != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        SuggestionChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = segment,
                                    style = MsaidiziThemeTokens.typography.labelSmall
                                )
                            },
                            modifier = Modifier.height(24.dp),
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = segmentColor.copy(alpha = 0.12f),
                                labelColor = segmentColor
                            )
                        )
                    }
                }
                if (phone != null) {
                    Text(
                        text = phone,
                        style = MsaidiziThemeTokens.typography.caption,
                        color = colors.onSurfaceVariant
                    )
                }
                if (lastVisit != null) {
                    Text(
                        text = "Mara ya mwisho: $lastVisit",
                        style = MsaidiziThemeTokens.typography.caption,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            // Debt indicator
            if (hasDebt) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Deni",
                        style = MsaidiziThemeTokens.typography.labelSmall,
                        color = colors.error
                    )
                    Text(
                        text = formatKes(debtAmount),
                        style = MsaidiziThemeTokens.typography.amountInline,
                        fontWeight = FontWeight.Bold,
                        color = colors.error
                    )
                }
            } else if (totalPurchases > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Jumla",
                        style = MsaidiziThemeTokens.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                    Text(
                        text = formatKes(totalPurchases),
                        style = MsaidiziThemeTokens.typography.amountInline,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.success
                    )
                }
            }
        }
    }
}
