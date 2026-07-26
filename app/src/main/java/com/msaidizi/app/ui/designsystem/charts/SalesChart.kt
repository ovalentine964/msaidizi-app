package com.msaidizi.app.ui.designsystem.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msaidizi.app.ui.designsystem.MsaidiziThemeTokens
import com.msaidizi.app.ui.designsystem.MsaidiziShapes

// ──────────────────────────────────────────────
// Chart Data Models
// ──────────────────────────────────────────────

data class BarChartData(
    val label: String,
    val value: Double,
    val color: Color? = null // null = use default
)

enum class ChartPeriod {
    DAILY, WEEKLY, MONTHLY
}

// ──────────────────────────────────────────────
// Sales Chart
// Bar chart for daily/weekly/monthly sales
// ──────────────────────────────────────────────

@Composable
fun SalesChart(
    data: List<BarChartData>,
    modifier: Modifier = Modifier,
    period: ChartPeriod = ChartPeriod.DAILY,
    barColor: Color = MsaidiziThemeTokens.colors.primary,
    showValues: Boolean = true
) {
    val colors = MsaidiziThemeTokens.colors
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = colors.onSurfaceVariant)
    val valueStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)

    if (data.isEmpty()) return

    val maxValue = data.maxOf { it.value }.coerceAtLeast(1.0)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = when (period) {
                    ChartPeriod.DAILY -> "Mauzo ya Wiki"
                    ChartPeriod.WEEKLY -> "Mauzo ya Mwezi"
                    ChartPeriod.MONTHLY -> "Mauzo ya Mwaka"
                },
                style = MsaidiziThemeTokens.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                val barCount = data.size
                val totalWidth = size.width
                val chartHeight = size.height - 30.dp.toPx() // space for labels
                val barWidth = (totalWidth / barCount) * 0.6f
                val gap = (totalWidth / barCount) * 0.4f

                data.forEachIndexed { index, item ->
                    val barHeight = (item.value / maxValue * chartHeight).toFloat()
                    val x = index * (barWidth + gap) + gap / 2
                    val y = chartHeight - barHeight

                    // Bar
                    drawRoundRect(
                        color = item.color ?: barColor,
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )

                    // Label
                    val labelResult = textMeasurer.measure(item.label, labelStyle)
                    drawText(
                        labelResult,
                        topLeft = Offset(
                            x + (barWidth - labelResult.size.width) / 2,
                            chartHeight + 4.dp.toPx()
                        )
                    )

                    // Value on top
                    if (showValues && item.value > 0) {
                        val valueText = formatShortKes(item.value)
                        val valueResult = textMeasurer.measure(valueText, valueStyle)
                        drawText(
                            valueResult,
                            topLeft = Offset(
                                x + (barWidth - valueResult.size.width) / 2,
                                y - valueResult.size.height - 2.dp.toPx()
                            )
                        )
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
// Profit Trend Chart
// Line chart with green/red zones
// ──────────────────────────────────────────────

@Composable
fun ProfitTrendChart(
    data: List<BarChartData>,
    modifier: Modifier = Modifier,
    positiveColor: Color = MsaidiziThemeTokens.colors.chartPositive,
    negativeColor: Color = MsaidiziThemeTokens.colors.chartNegative,
    neutralColor: Color = MsaidiziThemeTokens.colors.chartNeutral
) {
    val colors = MsaidiziThemeTokens.colors
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = colors.onSurfaceVariant)

    if (data.isEmpty()) return

    val maxValue = data.maxOf { kotlin.math.abs(it.value) }.coerceAtLeast(1.0)
    val minValue = -maxValue

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Faida / Hasara",
                style = MsaidiziThemeTokens.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Legend
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem(color = positiveColor, label = "Faida")
                LegendItem(color = negativeColor, label = "Hasara")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                val chartHeight = size.height - 20.dp.toPx()
                val zeroY = chartHeight / 2
                val stepX = size.width / (data.size - 1).coerceAtLeast(1)

                // Zero line
                drawLine(
                    color = neutralColor,
                    start = Offset(0f, zeroY),
                    end = Offset(size.width, zeroY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        floatArrayOf(6.dp.toPx(), 4.dp.toPx())
                    )
                )

                // Line path
                val path = Path()
                data.forEachIndexed { index, item ->
                    val x = index * stepX
                    val normalizedY = ((item.value - minValue) / (maxValue - minValue)).toFloat()
                    val y = chartHeight * (1f - normalizedY)

                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                drawPath(
                    path = path,
                    color = positiveColor,
                    style = Stroke(width = 2.5.dp.toPx())
                )

                // Points
                data.forEachIndexed { index, item ->
                    val x = index * stepX
                    val normalizedY = ((item.value - minValue) / (maxValue - minValue)).toFloat()
                    val y = chartHeight * (1f - normalizedY)
                    val pointColor = if (item.value >= 0) positiveColor else negativeColor

                    drawCircle(
                        color = pointColor,
                        radius = 4.dp.toPx(),
                        center = Offset(x, y)
                    )
                    drawCircle(
                        color = colors.cardBackground,
                        radius = 2.dp.toPx(),
                        center = Offset(x, y)
                    )
                }

                // Labels
                data.forEachIndexed { index, item ->
                    if (index % 2 == 0 || data.size <= 7) {
                        val x = index * stepX
                        val result = textMeasurer.measure(item.label, labelStyle)
                        drawText(
                            result,
                            topLeft = Offset(
                                x - result.size.width / 2,
                                chartHeight + 4.dp.toPx()
                            )
                        )
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
// Stock Level Chart
// Horizontal progress bars per product
// ──────────────────────────────────────────────

@Composable
fun StockLevelChart(
    products: List<StockLevel>,
    modifier: Modifier = Modifier
) {
    val colors = MsaidiziThemeTokens.colors

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Hali ya Stock",
                style = MsaidiziThemeTokens.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            products.forEach { product ->
                val ratio = if (product.maxStock > 0) {
                    (product.currentStock / product.maxStock).coerceIn(0.0, 1.0)
                } else 0.0

                val barColor = when {
                    ratio <= 0.2 -> colors.error
                    ratio <= 0.5 -> colors.warning
                    else -> colors.success
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = product.name,
                        style = MsaidiziThemeTokens.typography.labelMedium,
                        modifier = Modifier.width(80.dp),
                        maxLines = 1
                    )

                    LinearProgressIndicator(
                        progress = { ratio.toFloat() },
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = barColor,
                        trackColor = barColor.copy(alpha = 0.15f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = "${product.currentStock.toInt()}/${product.maxStock.toInt()}",
                        style = MsaidiziThemeTokens.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.width(48.dp)
                    )
                }
            }
        }
    }
}

data class StockLevel(
    val name: String,
    val currentStock: Double,
    val maxStock: Double,
    val unit: String = ""
)

// ── Helpers ──────────────────────────────────

@Composable
private fun LegendItem(color: Color, label: String) {
    val colors = MsaidiziThemeTokens.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MsaidiziThemeTokens.typography.labelSmall,
            color = colors.onSurfaceVariant
        )
    }
}

private fun formatShortKes(value: Double): String {
    return when {
        value >= 1_000_000 -> "${"%.1f".format(value / 1_000_000)}M"
        value >= 1_000 -> "${"%.0f".format(value / 1_000)}K"
        else -> "${"%.0f".format(value)}"
    }
}
