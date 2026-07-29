package com.msaidizi.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoneyOff
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msaidizi.core.model.DashboardState
import com.msaidizi.core.model.ProductEntity

// ──────────────────────────────────────────────
// Theme Colors
// ──────────────────────────────────────────────

private val MsaidiziPrimary = Color(0xFF1B4965)
private val MsaidiziSecondary = Color(0xFFE8A838)
private val MsaidiziTertiary = Color(0xFFE8853D)
private val MsaidiziGreen = Color(0xFF2E7D32)
private val MsaidiziRed = Color(0xFFC62828)
private val MsaidiziBg = Color(0xFFF5F5F5)

// ──────────────────────────────────────────────
// Main Dashboard Screen
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    dashboardState: DashboardState = DashboardState(),
    onRecordSale: () -> Unit = {},
    onCheckInventory: () -> Unit = {},
    onViewDebts: () -> Unit = {}
) {
    Scaffold(
        containerColor = MsaidiziBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Msaidizi CFO",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MsaidiziPrimary
                        )
                        Text(
                            text = dashboardState.greeting.ifBlank { "Habari! Karibu." },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White
                )
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
            // ── Section: Today's Summary Cards ──
            item {
                TodaySummaryCards(dashboardState)
            }

            // ── Section: 7-Day Trend ──
            item {
                WeeklyTrendCard(dashboardState)
            }

            // ── Section: Top Products ──
            item {
                TopProductsCard(dashboardState)
            }

            // ── Section: Quick Actions ──
            item {
                QuickActionsSection(
                    onRecordSale = onRecordSale,
                    onCheckInventory = onCheckInventory,
                    onViewDebts = onViewDebts
                )
            }

            // Bottom spacer for nav bar
            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

// ──────────────────────────────────────────────
// Today's Summary Cards
// ──────────────────────────────────────────────

@Composable
private fun TodaySummaryCards(state: DashboardState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryCard(
            modifier = Modifier.weight(1f),
            label = "Mauzo",
            sublabel = "Today's Sales",
            amount = state.todaySales,
            icon = Icons.Default.TrendingUp,
            iconColor = MsaidiziGreen,
            cardColor = Color.White
        )
        SummaryCard(
            modifier = Modifier.weight(1f),
            label = "Gharama",
            sublabel = "Today's Expenses",
            amount = state.todayExpenses,
            icon = Icons.Default.MoneyOff,
            iconColor = MsaidiziRed,
            cardColor = Color.White
        )
        SummaryCard(
            modifier = Modifier.weight(1f),
            label = "Faida",
            sublabel = "Today's Profit",
            amount = state.todayProfit,
            icon = Icons.Default.CheckCircle,
            iconColor = if (state.todayProfit >= 0) MsaidiziGreen else MsaidiziRed,
            cardColor = if (state.todayProfit >= 0)
                MsaidiziGreen.copy(alpha = 0.08f)
            else
                MsaidiziRed.copy(alpha = 0.08f)
        )
    }
}

@Composable
private fun SummaryCard(
    modifier: Modifier = Modifier,
    label: String,
    sublabel: String,
    amount: Double,
    icon: ImageVector,
    iconColor: Color,
    cardColor: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(iconColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MsaidiziPrimary
            )
            Text(
                text = sublabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatKes(amount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (amount >= 0) MsaidiziPrimary else MsaidiziRed,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ──────────────────────────────────────────────
// 7-Day Trend Card
// ──────────────────────────────────────────────

@Composable
private fun WeeklyTrendCard(state: DashboardState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Mwenendo wa Siku 7",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MsaidiziPrimary
            )
            Text(
                text = "7-Day Trend",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Simple line chart using Canvas
            val trendData = remember {
                listOf(12000.0, 8500.0, 15000.0, 9800.0, 18000.0, 14500.0, state.todaySales.coerceAtLeast(1.0))
            }
            val dayLabels = listOf("Jum", "Jtn", "Jrb", "Alh", "Iju", "Jma", "Leo")

            SimpleLineChart(
                data = trendData,
                labels = dayLabels,
                lineColor = MsaidiziPrimary,
                fillColor = MsaidiziPrimary.copy(alpha = 0.08f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            )
        }
    }
}

@Composable
private fun SimpleLineChart(
    data: List<Double>,
    labels: List<String>,
    lineColor: Color,
    fillColor: Color,
    modifier: Modifier = Modifier
) {
    if (data.isEmpty()) return

    val maxVal = data.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val w = size.width
            val h = size.height
            val stepX = w / (data.size - 1).coerceAtLeast(1)
            val padding = 8f

            // Build path
            val linePath = Path()
            val fillPath = Path()
            val points = data.mapIndexed { i, v ->
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

            // Fill area under curve
            drawPath(
                path = fillPath,
                color = fillColor
            )

            // Draw line
            drawPath(
                path = linePath,
                color = lineColor,
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )

            // Draw dots
            points.forEach { pt ->
                drawCircle(
                    color = lineColor,
                    radius = 5f,
                    center = pt
                )
                drawCircle(
                    color = Color.White,
                    radius = 3f,
                    center = pt
                )
            }
        }

        // Day labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            labels.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
// Top Products Card
// ──────────────────────────────────────────────

@Composable
private fun TopProductsCard(state: DashboardState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Bidhaa Bora kwa Faida",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MsaidiziPrimary
            )
            Text(
                text = "Top Products by Profit",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (state.lowStockProducts.isEmpty() && state.recentSales.isEmpty()) {
                // Placeholder data when no sales yet
                TopProductRow(
                    rank = 1,
                    name = "Hakuna mauzo bado",
                    profit = 0.0,
                    barFraction = 0f
                )
            } else {
                // Derive top products from recent sales (simplified)
                val topProducts = state.recentSales
                    .groupBy { it.productName }
                    .map { (name, sales) ->
                        name to sales.sumOf { it.totalPrice }
                    }
                    .sortedByDescending { it.second }
                    .take(3)

                val maxProfit = topProducts.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0

                topProducts.forEachIndexed { index, (name, profit) ->
                    TopProductRow(
                        rank = index + 1,
                        name = name,
                        profit = profit,
                        barFraction = (profit / maxProfit).toFloat()
                    )
                    if (index < topProducts.lastIndex) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TopProductRow(
    rank: Int,
    name: String,
    profit: Double,
    barFraction: Float
) {
    val animatedFraction by animateFloatAsState(
        targetValue = barFraction,
        animationSpec = tween(durationMillis = 800),
        label = "barAnim"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Rank badge
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    when (rank) {
                        1 -> MsaidiziSecondary
                        2 -> MsaidiziPrimary.copy(alpha = 0.6f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$rank",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MsaidiziGreen.copy(alpha = 0.2f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MsaidiziGreen)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = formatKes(profit),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MsaidiziGreen
        )
    }
}

// ──────────────────────────────────────────────
// Quick Actions Section
// ──────────────────────────────────────────────

@Composable
private fun QuickActionsSection(
    onRecordSale: () -> Unit,
    onCheckInventory: () -> Unit,
    onViewDebts: () -> Unit
) {
    Column {
        Text(
            text = "Haraka",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MsaidiziPrimary
        )
        Text(
            text = "Quick Actions",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            QuickActionButton(
                modifier = Modifier.weight(1f),
                label = "Rekodi Mauzo",
                sublabel = "Record Sale",
                icon = Icons.Default.Add,
                containerColor = MsaidiziPrimary,
                onClick = onRecordSale
            )
            QuickActionButton(
                modifier = Modifier.weight(1f),
                label = "Hifadhi",
                sublabel = "Check Inventory",
                icon = Icons.Default.Inventory2,
                containerColor = MsaidiziTertiary,
                onClick = onCheckInventory
            )
            QuickActionButton(
                modifier = Modifier.weight(1f),
                label = "Deni",
                sublabel = "View Debts",
                icon = Icons.Default.CreditCard,
                containerColor = MsaidiziSecondary,
                onClick = onViewDebts
            )
        }
    }
}

@Composable
private fun QuickActionButton(
    modifier: Modifier = Modifier,
    label: String,
    sublabel: String,
    icon: ImageVector,
    containerColor: Color,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(80.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = sublabel,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ──────────────────────────────────────────────
// Utility
// ──────────────────────────────────────────────

private fun formatKes(amount: Double): String {
    val abs = kotlin.math.abs(amount)
    val prefix = if (amount < 0) "-" else ""
    return when {
        abs >= 1_000_000 -> "${prefix}KES ${"%.1f".format(abs / 1_000_000)}M"
        abs >= 10_000 -> "${prefix}KES ${"%.0f".format(abs / 1_000)}K"
        else -> "${prefix}KES ${"%,.0f".format(abs)}"
    }
}
