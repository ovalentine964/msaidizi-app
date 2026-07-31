package com.msaidizi.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msaidizi.core.model.SaleEntity
import com.msaidizi.app.ui.components.*
import com.msaidizi.app.ui.designsystem.*

// ──────────────────────────────────────────────
// Transaction List Screen
// Scrollable list with voice search, category filters,
// daily/weekly/monthly views
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionListScreen(
    transactions: List<SaleEntity> = emptyList(),
    onVoiceSearch: () -> Unit = {},
    onAddTransaction: () -> Unit = {},
    onNavigate: (String) -> Unit = {}
) {
    val colors = MsaidiziThemeTokens.colors
    var selectedPeriod by remember { mutableIntStateOf(0) }
    var selectedCategory by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    val periods = listOf("Leo" to "Today", "Wiki" to "Week", "Mwezi" to "Month", "Zote" to "All")
    val categories = listOf("Zote" to "All", "Mauzo" to "Sales", "Gharama" to "Expenses", "Deni" to "Debts")

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Miamala", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.primary)
                        Text("Transactions", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = onVoiceSearch) {
                        Icon(Icons.Default.Mic, contentDescription = "Tafuta kwa sauti — Voice search", tint = colors.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddTransaction,
                containerColor = colors.tertiary,
                contentColor = colors.onTertiary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Ongeza")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Rekodi Mpya", fontWeight = FontWeight.SemiBold)
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
            // ── Search Bar ──
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Tafuta miamala... — Search transactions...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Tafuta") },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Futa")
                            }
                        }
                    },
                    shape = MsaidiziShapes().full,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.outlineVariant
                    )
                )
            }

            // ── Period Filter ──
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

            // ── Category Filter ──
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEachIndexed { index, (sw, _) ->
                        CategoryChip(
                            label = sw,
                            selected = selectedCategory == index,
                            onClick = { selectedCategory = index }
                        )
                    }
                }
            }

            // ── Summary Row ──
            item {
                TransactionSummaryRow(transactions)
            }

            // ── Transaction List ──
            if (transactions.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Receipt,
                        titleSw = "Hakuna miamala",
                        titleEn = "No transactions",
                        subtitle = "Anza kurekodi mauzo yako",
                        actionText = "Rekodi Mauzo",
                        onAction = onAddTransaction
                    )
                }
            } else {
                val filtered = transactions.filter { txn ->
                    if (searchQuery.isBlank()) true
                    else txn.productName.contains(searchQuery, ignoreCase = true)
                }

                // Group by date
                val grouped = filtered.groupBy { txn ->
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                        .format(java.util.Date(txn.timestamp))
                }

                grouped.forEach { (date, txns) ->
                    item {
                        Text(
                            text = formatDateLabel(date),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.primary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    items(txns) { txn ->
                        TransactionItem(txn)
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun TransactionSummaryRow(transactions: List<SaleEntity>) {
    val colors = MsaidiziThemeTokens.colors
    val total = transactions.sumOf { it.totalPrice }
    val count = transactions.size

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().medium,
        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Jumla", style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer)
                Text("Total", style = MaterialTheme.typography.labelSmall, color = colors.onPrimaryContainer.copy(alpha = 0.7f))
                Text(
                    formatKes(total),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.onPrimaryContainer
                )
            }
            Column {
                Text("Miamala", style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer)
                Text("Count", style = MaterialTheme.typography.labelSmall, color = colors.onPrimaryContainer.copy(alpha = 0.7f))
                Text(
                    "$count",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun TransactionItem(txn: SaleEntity) {
    val colors = MsaidiziThemeTokens.colors
    val isCredit = txn.paymentMethod == "credit"

    ListItemCard(
        title = txn.productName,
        subtitle = "${txn.quantity} × KES ${"%,.0f".format(txn.unitPrice)} • ${txn.paymentMethod.uppercase()}",
        trailing = formatKes(txn.totalPrice),
        icon = if (isCredit) Icons.Default.CreditCard else Icons.Default.PointOfSale,
        statusColor = if (isCredit) colors.warning else colors.success
    )
}

private fun formatDateLabel(dateStr: String): String {
    return try {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val date = sdf.parse(dateStr) ?: return dateStr
        val today = sdf.format(java.util.Date())
        val yesterday = sdf.format(java.util.Date(System.currentTimeMillis() - 86400000))
        when (dateStr) {
            today -> "Leo — Today"
            yesterday -> "Jana — Yesterday"
            else -> {
                val cal = java.util.Calendar.getInstance()
                cal.time = date
                val days = listOf("Jumapili", "Jumatatu", "Jumanne", "Jumatano", "Alhamisi", "Ijumaa", "Jumamosi")
                "${days[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]}, ${cal.get(java.util.Calendar.DAY_OF_MONTH)}"
            }
        }
    } catch (e: Exception) {
        dateStr
    }
}
