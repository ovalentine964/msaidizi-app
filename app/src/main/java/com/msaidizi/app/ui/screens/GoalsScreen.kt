package com.msaidizi.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msaidizi.app.ui.components.*
import com.msaidizi.app.ui.designsystem.*

// ──────────────────────────────────────────────
// Goals Screen
// Savings goals, loan tracking, chama contributions
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsScreen(
    onNavigate: (String) -> Unit = {}
) {
    val colors = MsaidiziThemeTokens.colors
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Malengo" to "Goals", "Mikopo" to "Loans", "Chama" to "Chama")

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Malengo na Mikopo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.primary)
                        Text("Goals & Loans", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { /* Add goal */ },
                containerColor = colors.tertiary,
                contentColor = colors.onTertiary
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Lengo Jipya", fontWeight = FontWeight.SemiBold)
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Tabs ──
            item {
                MsaidiziTabRow(tabs = tabs, selectedTab = selectedTab, onTabSelected = { selectedTab = it })
            }

            when (selectedTab) {
                0 -> {
                    // ── Savings Goals ──
                    item { GoalCard("Biashara Mpya", "New Business", 15000.0, 50000.0, colors.primary) }
                    item { GoalCard("Pikipiki", "Motorcycle", 35000.0, 80000.0, colors.info) }
                    item { GoalCard("Duka", "Shop", 8000.0, 200000.0, colors.secondary) }
                    item { GoalCard("Elimu ya Mtoto", "Child Education", 12000.0, 60000.0, colors.success) }
                }
                1 -> {
                    // ── Loans ──
                    item {
                        AlertBanner(
                            messageSw = "Una mkopo wa KES 5,000 unaodaiwa",
                            messageEn = "You have a KES 5,000 loan due",
                            severity = AlertSeverity.WARNING
                        )
                    }
                    item { LoanItem("M-Shwari", 5000.0, 15.0, "2026-08-15") }
                    item { LoanItem("Fuliza", 2000.0, 0.0, "2026-07-30") }
                    item { LoanItem("KCB M-Pesa", 10000.0, 8.5, "2026-09-01") }
                }
                2 -> {
                    // ── Chama Quick View ──
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onNavigate("chama") },
                            shape = MsaidiziShapes().large,
                            colors = CardDefaults.cardColors(containerColor = colors.primaryContainer)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Groups, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(40.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Chama Yako", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
                                    Text("Your Savings Group", style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer.copy(alpha = 0.7f))
                                    Text("Bonyeza kuona mchango wako", style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer.copy(alpha = 0.6f))
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.onPrimaryContainer)
                            }
                        }
                    }
                    item { ChamaQuickItem("Jirani Chama", 3000.0, "Kila wiki", 8, 10) }
                    item { ChamaQuickItem("Wamama wa Sokoni", 5000.0, "Kila mwezi", 5, 12) }
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun GoalCard(
    nameSw: String,
    nameEn: String,
    saved: Double,
    target: Double,
    color: androidx.compose.ui.graphics.Color
) {
    val colors = MsaidiziThemeTokens.colors
    val progress = (saved / target).coerceIn(0.0, 1.0).toFloat()
    val remaining = target - saved

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(nameSw, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(nameEn, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            LabeledProgressBar(
                label = formatKes(saved),
                progress = progress,
                progressText = "ya ${formatKes(target)}",
                color = color
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Imebaki ${formatKes(remaining)} — Remaining",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LoanItem(
    lender: String,
    amount: Double,
    interestRate: Double,
    dueDate: String
) {
    val colors = MsaidiziThemeTokens.colors

    ListItemCard(
        title = lender,
        subtitle = "Riba: ${"%.1f".format(interestRate)}% • Inakomea: $dueDate",
        trailing = formatKes(amount),
        icon = Icons.Default.AccountBalance,
        statusColor = colors.warning
    )
}

@Composable
private fun ChamaQuickItem(
    name: String,
    contribution: Double,
    frequency: String,
    members: Int,
    totalMembers: Int
) {
    val colors = MsaidiziThemeTokens.colors

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().medium,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Groups, contentDescription = null, tint = colors.primary, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("$frequency • ${members}/${totalMembers} wanachama", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Text(formatKes(contribution), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.primary)
        }
    }
}
