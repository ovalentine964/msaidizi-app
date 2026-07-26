package com.msaidizi.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.msaidizi.app.model.DailySummaryEntity
import com.msaidizi.app.ui.components.*
import com.msaidizi.app.ui.designsystem.*

// ──────────────────────────────────────────────
// Reports Screen
// Daily/weekly/monthly business reports with charts
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    dailySummaries: List<DailySummaryEntity> = emptyList(),
    onNavigate: (String) -> Unit = {}
) {
    val colors = MsaidiziThemeTokens.colors
    var selectedPeriod by remember { mutableIntStateOf(0) }
    val periods = listOf("Wiki" to "Week", "Mwezi" to "Month", "Miezi 3" to "3 Months")

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Ripoti", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.primary)
                        Text("Business Reports", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Period Selector ──
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    periods.forEachIndexed { index, (sw, _) ->
                        CategoryChip(
                            label = sw,
                            selected = selectedPeriod == index,
                            onClick = { selectedPeriod = index }
                        )
                    }
                }
            }

            // ── Summary Cards ──
            item {
                ReportSummaryRow(dailySummaries)
            }

            // ── Sales Chart ──
            item {
                SalesChartCard(dailySummaries)
            }

            // ── Profit/Loss Breakdown ──
            item {
                ProfitBreakdownCard(dailySummaries)
            }

            // ── Top Products ──
            item {
                TopProductsReportCard(dailySummaries)
            }

            // ── Payment Method Split ──
            item {
                PaymentMethodCard(dailySummaries)
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ReportSummaryRow(summaries: List<DailySummaryEntity>) {
    val colors = MsaidiziThemeTokens.colors
    val totalSales = summaries.sumOf { it.totalSales }
    val totalExpenses = summaries.sumOf { it.totalExpenses }
    val totalProfit = summaries.sumOf { it.profit }
    val avgDaily = if (summaries.isNotEmpty()) totalSales / summaries.size else 0.0

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatusCard(
            modifier = Modifier.weight(1f),
            label = "Mauzo",
            sublabel = "Sales",
            value = formatKes(totalSales),
            icon = Icons.Default.TrendingUp,
            statusColor = colors.success
        )
        StatusCard(
            modifier = Modifier.weight(1f),
            label = "Gharama",
            sublabel = "Expenses",
            value = formatKes(totalExpenses),
            icon = Icons.Default.MoneyOff,
            statusColor = colors.error
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatusCard(
            modifier = Modifier.weight(1f),
            label = "Faida",
            sublabel = "Profit",
            value = formatKes(totalProfit),
            icon = Icons.Default.CheckCircle,
            statusColor = if (totalProfit >= 0) colors.success else colors.error
        )
        StatusCard(
            modifier = Modifier.weight(1f),
            label = "Wastani",
            sublabel = "Avg/Day",
            value = formatKes(avgDaily),
            icon = Icons.Default.CalendarToday,
            statusColor = colors.info
        )
    }
}

@Composable
private fun SalesChartCard(summaries: List<DailySummaryEntity>) {
    val colors = MsaidiziThemeTokens.colors
    val chartData = if (summaries.isNotEmpty()) {
        summaries.takeLast(7).map { it.totalSales }
    } else {
        listOf(5000.0, 8000.0, 6500.0, 12000.0, 9000.0, 15000.0, 11000.0)
    }
    val dayLabels = listOf("Jum", "Jtn", "Jrb", "Alh", "Iju", "Jma", "Leo")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Mwenendo wa Mauzo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.primary)
            Text("Sales Trend", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Spacer(modifier = Modifier.height(16.dp))

            val maxVal = chartData.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            ) {
                val w = size.width
                val h = size.height
                val stepX = w / (chartData.size - 1).coerceAtLeast(1)
                val padding = 16f

                val linePath = Path()
                val fillPath = Path()
                val points = chartData.mapIndexed { i, v ->
                    val x = i * stepX
                    val y = h - padding - ((v / maxVal) * (h - 2 * padding)).toFloat()
                    Offset(x, y)
                }

                points.forEachIndexed { i, pt ->
                    if (i == 0) {
                        linePath.moveTo(pt.x, pt.y)
                        fillPath.moveTo(pt.x, h)
                        fillPath.lineTo(pt.x, pt.y)
                    } else {
                        linePath.lineTo(pt.x, pt.y)
                        fillPath.lineTo(pt.x, pt.y)
                    }
                }
                fillPath.lineTo(points.last().x, h)
                fillPath.close()

                drawPath(path = fillPath, color = colors.success.copy(alpha = 0.1f))
                drawPath(path = linePath, color = colors.success, style = Stroke(width = 3f, cap = StrokeCap.Round))
                points.forEach { pt ->
                    drawCircle(color = colors.success, radius = 5f, center = pt)
                    drawCircle(color = Color.White, radius = 3f, center = pt)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                dayLabels.take(chartData.size).forEach { label ->
                    Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ProfitBreakdownCard(summaries: List<DailySummaryEntity>) {
    val colors = MsaidiziThemeTokens.colors
    val profitDays = summaries.count { it.profit > 0 }
    val lossDays = summaries.count { it.profit < 0 }
    val total = summaries.size.coerceAtLeast(1)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Matokeo ya Faida", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.primary)
            Text("Profit Breakdown", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Spacer(modifier = Modifier.height(12.dp))

            LabeledProgressBar(
                label = "Siku za Faida — Profit Days",
                progress = profitDays.toFloat() / total,
                progressText = "$profitDays / $total",
                color = colors.success
            )
            Spacer(modifier = Modifier.height(8.dp))
            LabeledProgressBar(
                label = "Siku za Hasara — Loss Days",
                progress = lossDays.toFloat() / total,
                progressText = "$lossDays / $total",
                color = colors.error
            )
        }
    }
}

@Composable
private fun TopProductsReportCard(summaries: List<DailySummaryEntity>) {
    val colors = MsaidiziThemeTokens.colors
    val topProduct = summaries.mapNotNull { it.topProduct }.groupingBy { it }.eachCount().maxByOrNull { it.value }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Bidhaa Bora", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.primary)
            Text("Top Product", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Spacer(modifier = Modifier.height(12.dp))

            if (topProduct != null) {
                ListItemCard(
                    title = topProduct.key,
                    subtitle = "Imeuzwa siku ${topProduct.value}",
                    trailing = "#1",
                    icon = Icons.Default.Star,
                    statusColor = colors.secondary
                )
            } else {
                Text("Hakuna data bado — No data yet", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PaymentMethodCard(summaries: List<DailySummaryEntity>) {
    val colors = MsaidiziThemeTokens.colors
    val cashTotal = summaries.sumOf { it.cashSales }
    val mpesaTotal = summaries.sumOf { it.mpesaSales }
    val creditTotal = summaries.sumOf { it.creditSales }
    val total = (cashTotal + mpesaTotal + creditTotal).coerceAtLeast(1.0)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Njia za Malipo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.primary)
            Text("Payment Methods", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Spacer(modifier = Modifier.height(12.dp))

            LabeledProgressBar(label = "💵 Cash", progress = (cashTotal / total).toFloat(), progressText = formatKes(cashTotal), color = colors.success)
            Spacer(modifier = Modifier.height(8.dp))
            LabeledProgressBar(label = "📱 M-Pesa", progress = (mpesaTotal / total).toFloat(), progressText = formatKes(mpesaTotal), color = colors.info)
            Spacer(modifier = Modifier.height(8.dp))
            LabeledProgressBar(label = "📋 Deni — Credit", progress = (creditTotal / total).toFloat(), progressText = formatKes(creditTotal), color = colors.warning)
        }
    }
}
