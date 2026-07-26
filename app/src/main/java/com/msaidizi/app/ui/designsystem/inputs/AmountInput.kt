package com.msaidizi.app.ui.designsystem.inputs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.msaidizi.app.ui.designsystem.MsaidiziThemeTokens
import com.msaidizi.app.ui.designsystem.MsaidiziShapes
import com.msaidizi.app.ui.designsystem.TouchTarget

// ──────────────────────────────────────────────
// Amount Input
// Large number pad optimized for cash amounts
// ──────────────────────────────────────────────

@Composable
fun AmountInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    currency: String = "KES",
    maxLength: Int = 10,
    onConfirm: (() -> Unit)? = null
) {
    val colors = MsaidiziThemeTokens.colors

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Display
        Card(
            shape = MsaidiziShapes().large,
            colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = currency,
                    style = MsaidiziThemeTokens.typography.labelLarge,
                    color = colors.onSurfaceVariant
                )
                Text(
                    text = if (value.isEmpty()) "0" else formatAmountDisplay(value),
                    style = MsaidiziThemeTokens.typography.amountDisplay,
                    fontWeight = FontWeight.Bold,
                    color = if (value.isEmpty()) colors.onSurfaceVariant else colors.onSurface,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Number pad
        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf(".", "0", "⌫")
        )

        keys.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { key ->
                    NumberKey(
                        key = key,
                        onClick = {
                            when (key) {
                                "⌫" -> {
                                    if (value.isNotEmpty()) {
                                        onValueChange(value.dropLast(1))
                                    }
                                }
                                "." -> {
                                    if (!value.contains(".") && value.length < maxLength) {
                                        onValueChange(value + ".")
                                    }
                                }
                                else -> {
                                    if (value.length < maxLength) {
                                        // Prevent multiple leading zeros
                                        if (value == "0" && key != ".") {
                                            onValueChange(key)
                                        } else {
                                            onValueChange(value + key)
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Confirm button
        if (onConfirm != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .heightIn(min = TouchTarget.comfortable),
                enabled = value.isNotEmpty() && value != "0",
                shape = MsaidiziShapes().large,
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
            ) {
                Text(
                    text = "Thibitisha",
                    style = MsaidiziThemeTokens.typography.buttonLabel,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun NumberKey(
    key: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MsaidiziThemeTokens.colors
    val isDelete = key == "⌫"

    Box(
        modifier = modifier
            .size(TouchTarget.comfortable)
            .clip(CircleShape)
            .background(
                if (isDelete) colors.errorContainer else colors.surface
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isDelete) {
            Icon(
                imageVector = Icons.Default.Backspace,
                contentDescription = "Futa",
                tint = colors.error,
                modifier = Modifier.size(24.dp)
            )
        } else {
            Text(
                text = key,
                style = MsaidiziThemeTokens.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
        }
    }
}

private fun formatAmountDisplay(value: String): String {
    val parts = value.split(".")
    val intPart = parts[0].reversed().chunked(3).joinToString(",").reversed()
    return if (parts.size > 1) "$intPart.${parts[1]}" else intPart
}
