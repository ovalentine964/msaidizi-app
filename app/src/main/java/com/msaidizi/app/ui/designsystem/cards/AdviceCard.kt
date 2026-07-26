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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msaidizi.app.ui.designsystem.MsaidiziThemeTokens
import com.msaidizi.app.ui.designsystem.MsaidiziShapes

// ──────────────────────────────────────────────
// Advice Card
// AI-generated business advice with actions
// ──────────────────────────────────────────────

@Composable
fun AdviceCard(
    adviceText: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Lightbulb,
    title: String? = null,
    onFollow: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null
) {
    val colors = MsaidiziThemeTokens.colors

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.infoContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.info.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colors.info,
                        modifier = Modifier.size(22.dp)
                    )
                }
                if (title != null) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = title,
                        style = MsaidiziThemeTokens.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.info
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = adviceText,
                style = MsaidiziThemeTokens.typography.bodyLarge,
                color = colors.onSurface
            )

            if (onFollow != null || onDismiss != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (onDismiss != null) {
                        TextButton(onClick = onDismiss) {
                            Text(
                                text = "Puuza",
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                    if (onFollow != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onFollow,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.info
                            ),
                            shape = MsaidiziShapes().full
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Fuata")
                        }
                    }
                }
            }
        }
    }
}
