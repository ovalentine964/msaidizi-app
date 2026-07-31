package com.msaidizi.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msaidizi.core.model.*
import com.msaidizi.app.ui.components.*
import com.msaidizi.app.ui.designsystem.*

// ──────────────────────────────────────────────
// Dashboard Screen — Business Overview
// Color-coded: green=good, yellow=attention, red=urgent
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    dashboardState: DashboardState = DashboardState(),
    onRecordSale: () -> Unit = {},
    onCheckInventory: () -> Unit = {},
    onViewDebts: () -> Unit = {},
    onViewReports: () -> Unit = {},
    onViewCustomers: () -> Unit = {},
    onViewGoals: () -> Unit = {},
    onNavigate: (String) -> Unit = {}
) {
    val colors = MsaidiziThemeTokens.colors

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Dashibodi",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary
                        )
                        Text(
                            text = "Dashboard",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onNavigate("notifications") }) {
                        BadgedBox(
                            badge = {
                                Badge(containerColor = colors.error) {
                                    Text("3", color = colors.onError)
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Notifications,
                                contentDescription = "Arifa — Notifications",
                                tint = colors.onSurfaceVariant
                            )
                        }
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
            // ── Greeting + Date ──
            item {
                GreetingCard(greeting = dashboardState.greeting)
            }

            // ── Alerts (low stock, overdue debts) ──
            if (dashboardState.lowStockProducts.isNotEmpty()) {
                item {
                    AlertBanner(
                        messageSw = "${dashboardState.lowStockProducts.size} bidhaa zinahitaji kujazwa",
                        messageEn = "${dashboardState.lowStockProducts.size} products need restocking",
                        severity = AlertSeverity.WARNING,
                        onDismiss = null
                    )
                }
            }

            // ── Today's Summary Cards ──
            item {
                TodaySummaryRow(dashboardState)
            }

            // ── Profit Indicator ──
            item {
                ProfitIndicatorCard(dashboardState)
            }

            // ── Quick Actions Grid ──
            item {
                SectionHeader(
                    titleSw = "Haraka",
                    titleEn = "Quick Actions"
                )
            }
            item {
                QuickActionsGrid(
                    onRecordSale = onRecordSale,
                    onCheckInventory = onCheckInventory,
                    onViewDebts = onViewDebts,
                    onViewReports = onViewReports,
                    onViewCustomers = onViewCustomers,
                    onViewGoals = onViewGoals,
                    onNavigate = onNavigate
                )
            }

            // ── Recent Transactions ──
            item {
                SectionHeader(
                    titleSw = "Miamala ya Hivi Karibuni",
                    titleEn = "Recent Transactions",
                    actionText = "Ona Zote",
                    onAction = { onNavigate("transactions") }
                )
            }

            if (dashboardState.recentSales.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Receipt,
                        titleSw = "Hakuna miamala bado",
                        titleEn = "No transactions yet",
                        subtitle = "Anza kuuza na urekodi hapa",
                        actionText = "Rekodi Mauzo",
                        onAction = onRecordSale
                    )
                }
            } else {
                items(dashboardState.recentSales.take(5)) { sale ->
                    ListItemCard(
                        title = sale.productName,
                        subtitle = "${sale.quantity} × KES ${"%,.0f".format(sale.unitPrice)}",
                        trailing = formatKes(sale.totalPrice),
                        icon = Icons.Default.ShoppingCart,
                        statusColor = colors.success
                    )
                }
            }

            // ── Advice Cards ──
            item {
                SectionHeader(
                    titleSw = "Ushauri wa Leo",
                    titleEn = "Today's Advice"
                )
            }
            item {
                AdviceCard(
                    iconSw = "💡",
                    titleSw = "Bei ya nyanya imepanda",
                    titleEn = "Tomato prices have increased",
                    bodySw = "Ukiuza sasa, utapata faida zaidi ya 30%",
                    bodyEn = "If you sell now, you'll get 30% more profit",
                    severity = AlertSeverity.INFO
                )
            }
            item {
                AdviceCard(
                    iconSw = "⚠️",
                    titleSw = "Sukuma wiki inakaribia kuharibika",
                    titleEn = "Sukuma wiki is about to spoil",
                    bodySw = "Inua bei kidogo au uuze kwa hasara ndogo",
                    bodyEn = "Lower the price slightly or sell at a small loss",
                    severity = AlertSeverity.WARNING
                )
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

// ──────────────────────────────────────────────
// Greeting Card
// ──────────────────────────────────────────────

@Composable
private fun GreetingCard(greeting: String) {
    val colors = MsaidiziThemeTokens.colors
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    val timeGreeting = when {
        hour < 12 -> "Habari za asubuhi"
        hour < 17 -> "Habari za mchana"
        else -> "Habari za jioni"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = greeting.ifBlank { timeGreeting },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.onPrimaryContainer
                )
                Text(
                    text = "Leo ni siku nzuri ya kuuza!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onPrimaryContainer.copy(alpha = 0.8f)
                )
                Text(
                    text = "Today is a good day to sell!",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onPrimaryContainer.copy(alpha = 0.6f)
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
// Today's Summary Row
// ──────────────────────────────────────────────

@Composable
private fun TodaySummaryRow(state: DashboardState) {
    val colors = MsaidiziThemeTokens.colors

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatusCard(
            modifier = Modifier.weight(1f),
            label = "Mauzo",
            sublabel = "Sales",
            value = formatKes(state.todaySales),
            icon = Icons.Default.TrendingUp,
            statusColor = colors.success
        )
        StatusCard(
            modifier = Modifier.weight(1f),
            label = "Gharama",
            sublabel = "Expenses",
            value = formatKes(state.todayExpenses),
            icon = Icons.Default.MoneyOff,
            statusColor = colors.error
        )
        StatusCard(
            modifier = Modifier.weight(1f),
            label = "Faida",
            sublabel = "Profit",
            value = formatKes(state.todayProfit),
            icon = Icons.Default.CheckCircle,
            statusColor = if (state.todayProfit >= 0) colors.success else colors.error
        )
    }
}

// ──────────────────────────────────────────────
// Profit Indicator Card
// ──────────────────────────────────────────────

@Composable
private fun ProfitIndicatorCard(state: DashboardState) {
    val colors = MsaidiziThemeTokens.colors
    val profitColor = when {
        state.todayProfit > 0 -> colors.success
        state.todayProfit == 0.0 -> colors.warning
        else -> colors.error
    }
    val profitTextSw = when {
        state.todayProfit > 0 -> "Faida! Umeongea pesa leo"
        state.todayProfit == 0.0 -> "Hujapata faida bado"
        else -> "Hasara! Gharama zimezidi mauzo"
    }
    val profitTextEn = when {
        state.todayProfit > 0 -> "Profit! You made money today"
        state.todayProfit == 0.0 -> "No profit yet today"
        else -> "Loss! Expenses exceeded sales"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = profitColor.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Icon(
                imageVector = if (state.todayProfit >= 0) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                contentDescription = "Dashibodi icon",
                tint = profitColor,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = profitTextSw,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = profitColor
                )
                Text(
                    text = profitTextEn,
                    style = MaterialTheme.typography.bodySmall,
                    color = profitColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
// Quick Actions Grid
// ──────────────────────────────────────────────

@Composable
private fun QuickActionsGrid(
    onRecordSale: () -> Unit,
    onCheckInventory: () -> Unit,
    onViewDebts: () -> Unit,
    onViewReports: () -> Unit,
    onViewCustomers: () -> Unit,
    onViewGoals: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val colors = MsaidiziThemeTokens.colors

    data class Action(
        val iconSw: String,
        val labelSw: String,
        val labelEn: String,
        val color: Color,
        val onClick: () -> Unit
    )

    val actions = listOf(
        Action("💰", "Rekodi Mauzo", "Record Sale", colors.primary, onRecordSale),
        Action("📦", "Hifadhi", "Inventory", colors.tertiary, onCheckInventory),
        Action("💳", "Deni", "Debts", colors.warning, onViewDebts),
        Action("📊", "Ripoti", "Reports", colors.info, onViewReports),
        Action("👥", "Wateja", "Customers", colors.success, onViewCustomers),
        Action("🎯", "Malengo", "Goals", colors.secondary, onViewGoals),
        Action("🤝", "Chama", "Chama", colors.primary, { onNavigate("chama") }),
        Action("💹", "Bei", "Pricing", colors.tertiary, { onNavigate("pricing") })
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in actions.chunked(4)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { action ->
                    QuickActionChip(
                        modifier = Modifier.weight(1f),
                        iconSw = action.iconSw,
                        labelSw = action.labelSw,
                        labelEn = action.labelEn,
                        color = action.color,
                        onClick = action.onClick
                    )
                }
                // Fill remaining space if row < 4
                repeat(4 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun QuickActionChip(
    modifier: Modifier = Modifier,
    iconSw: String,
    labelSw: String,
    labelEn: String,
    color: Color,
    onClick: () -> Unit
) {
    val colors = MsaidiziThemeTokens.colors

    Card(
        modifier = modifier,
        onClick = onClick,
        shape = MsaidiziShapes().medium,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            Text(text = iconSw, style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = labelSw,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = color,
                maxLines = 1
            )
            Text(
                text = labelEn,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                fontSize = androidx.compose.ui.unit.sp(9)
            )
        }
    }
}

// ──────────────────────────────────────────────
// Advice Card
// ──────────────────────────────────────────────

@Composable
private fun AdviceCard(
    iconSw: String,
    titleSw: String,
    titleEn: String,
    bodySw: String,
    bodyEn: String,
    severity: AlertSeverity
) {
    val colors = MsaidiziThemeTokens.colors
    val borderColor = when (severity) {
        AlertSeverity.SUCCESS -> colors.success
        AlertSeverity.WARNING -> colors.warning
        AlertSeverity.ERROR -> colors.error
        AlertSeverity.INFO -> colors.info
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().medium,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Text(text = iconSw, style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = titleSw,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = titleEn,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = bodySw,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = bodyEn,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}
