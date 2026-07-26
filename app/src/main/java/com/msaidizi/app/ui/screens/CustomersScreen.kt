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
import com.msaidizi.app.model.CustomerEntity
import com.msaidizi.app.ui.components.*
import com.msaidizi.app.ui.designsystem.*

// ──────────────────────────────────────────────
// Customers Screen
// Customer list with debts, loyalty, visit history
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomersScreen(
    customers: List<CustomerEntity> = emptyList(),
    onAddCustomer: () -> Unit = {},
    onCustomerClick: (CustomerEntity) -> Unit = {},
    onNavigate: (String) -> Unit = {}
) {
    val colors = MsaidiziThemeTokens.colors
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    val tabs = listOf("Wote" to "All", "Wanaodaiwa" to "With Debt", "Wazuri" to "Top")

    val filtered = customers.filter { customer ->
        val matchesSearch = searchQuery.isBlank() || customer.name.contains(searchQuery, ignoreCase = true)
        val matchesTab = when (selectedTab) {
            1 -> customer.creditBalance > 0
            2 -> customer.totalPurchases > 5000
            else -> true
        }
        matchesSearch && matchesTab
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Wateja", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.primary)
                        Text("Customers", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddCustomer,
                containerColor = colors.tertiary,
                contentColor = colors.onTertiary
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Mteja Mpya", fontWeight = FontWeight.SemiBold)
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
            // ── Search ──
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Tafuta mteja... — Search customer...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    shape = MsaidiziShapes().full,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.outlineVariant
                    )
                )
            }

            // ── Tabs ──
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tabs.forEachIndexed { index, (sw, _) ->
                        CategoryChip(label = sw, selected = selectedTab == index, onClick = { selectedTab = index })
                    }
                }
            }

            // ── Summary ──
            item {
                CustomerSummaryCard(customers)
            }

            // ── Customer List ──
            if (filtered.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.People,
                        titleSw = "Hakuna wateja",
                        titleEn = "No customers",
                        subtitle = "Anza kuongeza wateja wako",
                        actionText = "Ongeza Mteja",
                        onAction = onAddCustomer
                    )
                }
            } else {
                items(filtered) { customer ->
                    CustomerItem(
                        customer = customer,
                        onClick = { onCustomerClick(customer) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun CustomerSummaryCard(customers: List<CustomerEntity>) {
    val colors = MsaidiziThemeTokens.colors
    val totalDebt = customers.sumOf { it.creditBalance }
    val withDebt = customers.count { it.creditBalance > 0 }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Wateja Wote", style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer)
                Text("Total Customers", style = MaterialTheme.typography.labelSmall, color = colors.onPrimaryContainer.copy(alpha = 0.7f))
                Text("${customers.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Deni Zote", style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer)
                Text("Total Debt", style = MaterialTheme.typography.labelSmall, color = colors.onPrimaryContainer.copy(alpha = 0.7f))
                Text(formatKes(totalDebt), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (totalDebt > 0) colors.error else colors.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun CustomerItem(
    customer: CustomerEntity,
    onClick: () -> Unit
) {
    val colors = MsaidiziThemeTokens.colors
    val hasDebt = customer.creditBalance > 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
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
            Box(
                modifier = Modifier.size(44.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (hasDebt) Icons.Default.Warning else Icons.Default.Person,
                    contentDescription = null,
                    tint = if (hasDebt) colors.warning else colors.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = customer.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (customer.phone != null) {
                    Text(
                        text = customer.phone,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                Text(
                    text = "Jumla: ${formatKes(customer.totalPurchases)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }
            if (hasDebt) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Anadaiwa",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.error
                    )
                    Text(
                        text = formatKes(customer.creditBalance),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.error
                    )
                }
            }
        }
    }
}
