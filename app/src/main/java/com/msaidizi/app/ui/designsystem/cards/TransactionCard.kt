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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.msaidizi.app.ui.designsystem.MsaidiziThemeTokens
import com.msaidizi.app.ui.designsystem.MsaidiziShapes
import com.msaidizi.app.ui.designsystem.formatKes

// ──────────────────────────────────────────────
// Transaction Type
// ──────────────────────────────────────────────

enum class TransactionType {
    SALE, EXPENSE, PURCHASE, CREDIT, REFUND
}

// ──────────────────────────────────────────────
// Transaction Card
// Displays a single business transaction
// ──────────────────────────────────────────────

@Composable
fun TransactionCard(
    type: TransactionType,
    amount: Double,
    product: String,
    timeAgo: String,
    modifier: Modifier = Modifier,
    customerName: String? = null,
    paymentMethod: String? = null,
    onClick: (() -> Unit)? = null
) {
    val colors = MsaidiziThemeTokens.colors

    val (statusColor, icon, typeLabel) = when (type) {
        TransactionType.SALE -> Triple(colors.success, Icons.Default.ShoppingCart, "Mauzo")
        TransactionType.EXPENSE -> Triple(colors.error, Icons.Default.Receipt, "Gharama")
        TransactionType.PURCHASE -> Triple(colors.warning, Icons.Default.Inventory, "Manunuzi")
        TransactionType.CREDIT -> Triple(colors.info, Icons.Default.CreditScore, "Mkopo")
        TransactionType.REFUND -> Triple(colors.onSurfaceVariant, Icons.Default.Undo, "Rudisha")
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
            // Status icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = typeLabel,
                    tint = statusColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = product,
                    style = MsaidiziThemeTokens.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = typeLabel,
                        style = MsaidiziThemeTokens.typography.caption,
                        color = statusColor,
                        fontWeight = FontWeight.Medium
                    )
                    if (paymentMethod != null) {
                        Text(
                            text = " · $paymentMethod",
                            style = MsaidiziThemeTokens.typography.caption,
                            color = colors.onSurfaceVariant
                        )
                    }
                    if (customerName != null) {
                        Text(
                            text = " · $customerName",
                            style = MsaidiziThemeTokens.typography.caption,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Amount + time
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatKes(amount),
                    style = MsaidiziThemeTokens.typography.amountInline,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
                Text(
                    text = timeAgo,
                    style = MsaidiziThemeTokens.typography.caption,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}
