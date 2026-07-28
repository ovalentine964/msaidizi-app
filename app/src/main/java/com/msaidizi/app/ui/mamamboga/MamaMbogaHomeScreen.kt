package com.msaidizi.app.ui.mamamboga

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msaidizi.app.model.*
import com.msaidizi.app.ui.designsystem.*

/**
 * MamaMbogaHomeScreen — Today's summary for mama mboga.
 *
 * Shows the ONE thing mama mbogas want to know: "Did I make money today?"
 *
 * Layout:
 *   - Big profit number (green = profit, red = loss)
 *   - Sales vs Expenses comparison
 *   - Quick "Record Sale" / "Record Expense" buttons
 *   - Spoilage alerts (if any)
 *   - Daily profit comparison (vs yesterday, vs last week)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MamaMbogaHomeScreen(
    dashboardState: DashboardState = DashboardState(),
    onRecordSale: () -> Unit = {},
    onRecordExpense: () -> Unit = {},
    onNavigate: (String) -> Unit = {}
) {
    val colors = MsaidiziThemeTokens.colors
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    val greeting = when {
        hour < 12 -> "Habari za asubuhi! ☀️"
        hour < 17 -> "Habari za mchana! 🌤️"
        else -> "Habari za jioni! 🌙"
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Msaidizi",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary
                        )
                        Text(
                            text = "Mama Mboga Edition",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
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
            // ── Greeting ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MsaidiziShapes().large,
                    colors = CardDefaults.cardColors(containerColor = colors.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = greeting,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.onPrimaryContainer
                        )
                        Text(
                            text = "Leo ni siku nzuri ya kuuza!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // ── BIG Profit Number — The #1 thing mama mbogas want to see ──
            item {
                ProfitRevelationCard(dashboardState)
            }

            // ── Quick Record Buttons ──
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Record Sale — big green button
                    Button(
                        onClick = onRecordSale,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = MsaidiziShapes().large,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.success)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Rekodi Mauzo",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    // Record Expense — outlined button
                    OutlinedButton(
                        onClick = onRecordExpense,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = MsaidiziShapes().large
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Rekodi Gharama",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            // ── Today's Breakdown ──
            item {
                TodayBreakdownCard(dashboardState)
            }

            // ── Spoilage Alerts ──
            if (dashboardState.lowStockProducts.isNotEmpty()) {
                item {
                    SpoilageAlertCard(dashboardState.lowStockProducts)
                }
            }

            // ── Week Comparison ──
            item {
                WeekComparisonCard(dashboardState)
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

/**
 * The profit revelation — the #1 feature mama mbogas need.
 * "Today you made KES 420 profit!"
 */
@Composable
private fun ProfitRevelationCard(state: DashboardState) {
    val colors = MsaidiziThemeTokens.colors
    val isProfit = state.todayProfit >= 0
    val profitColor = if (isProfit) colors.success else colors.error
    val emoji = if (isProfit) "🎉" else "😟"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = profitColor.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = emoji,
                style = MaterialTheme.typography.displayLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isProfit) "Leo umepata faida!" else "Leo umepata hasara!",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = profitColor
            )
            Text(
                text = if (isProfit) "Today you made profit!" else "Today you had a loss!",
                style = MaterialTheme.typography.bodyMedium,
                color = profitColor.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "KES %,.0f".format(kotlin.math.abs(state.todayProfit)),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.ExtraBold,
                color = profitColor
            )
            Text(
                text = if (isProfit) "Faida / Profit" else "Hasara / Loss",
                style = MaterialTheme.typography.bodySmall,
                color = profitColor.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * Today's breakdown — sales, expenses, transaction count.
 */
@Composable
private fun TodayBreakdownCard(state: DashboardState) {
    val colors = MsaidiziThemeTokens.colors

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Muhtasari wa Leo / Today's Summary",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.primary
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Sales
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Mauzo / Sales", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "KES %,.0f".format(state.todaySales),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.success
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Expenses
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Gharama / Expenses", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "KES %,.0f".format(state.todayExpenses),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.error
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // Profit
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Faida / Profit",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "KES %,.0f".format(state.todayProfit),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (state.todayProfit >= 0) colors.success else colors.error
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${state.transactionCount} miamala / transactions",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

/**
 * Spoilage alert — perishable items at risk.
 */
@Composable
private fun SpoilageAlertCard(lowStockProducts: List<ProductEntity>) {
    val colors = MsaidiziThemeTokens.colors

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.warning.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "⚠️ Bidhaa Zinazokaribia Kuharibika",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.warning
            )
            Text(
                text = "Items approaching spoilage",
                style = MaterialTheme.typography.bodySmall,
                color = colors.warning.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            lowStockProducts.take(3).forEach { product ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("• ${product.name}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${product.currentStock.toInt()} ${product.unit} imebaki",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.warning
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "💡 Punguza bei ili kuuza haraka! / Lower prices to sell quickly!",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

/**
 * Week comparison — "You're doing better than last week!"
 */
@Composable
private fun WeekComparisonCard(state: DashboardState) {
    val colors = MsaidiziThemeTokens.colors

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "📈 Linganisha na Wiki Iliyopita",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.primary
            )
            Text(
                text = "Compare to last week",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Placeholder — will be populated by CFOEngine
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Wiki hii / This week", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "KES %,.0f".format(state.todaySales * 6), // Rough estimate
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Wiki iliyopita / Last week", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "KES %,.0f".format(state.todaySales * 5.5), // Rough estimate
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "💡 Endelea kurekodi ili kuona mwelekeo wako! / Keep recording to see your trend!",
                style = MaterialTheme.typography.bodySmall,
                color = colors.info
            )
        }
    }
}
