package com.msaidizi.app.ui.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * HistoryScreen — Transaction history with filtering.
 */
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "📋 Historia ya Miamala",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Filter buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FilterChip("Zote", uiState.filter == "all") { viewModel.setFilter("all") }
            FilterChip("Mauzo", uiState.filter == "SALE") { viewModel.setFilter("SALE") }
            FilterChip("Manunuzi", uiState.filter == "PURCHASE") { viewModel.setFilter("PURCHASE") }
            FilterChip("Gharama", uiState.filter == "EXPENSE") { viewModel.setFilter("EXPENSE") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Transaction list
        if (uiState.transactions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Hakuna miamala bado.\nAnza kurekodi mauzo yako!",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn {
                items(uiState.transactions) { tx ->
                    HistoryRow(
                        item = tx.item,
                        type = tx.type,
                        amount = tx.amount,
                        date = tx.date
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                           else MaterialTheme.colorScheme.surface
        )
    ) {
        Text(text = label, fontSize = 12.sp)
    }
}

@Composable
fun HistoryRow(item: String, type: String, amount: Double, date: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = item, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(text = "$type • $date", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = "KSh ${"%,.0f".format(amount)}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (type == "SALE") MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.error
            )
        }
    }
}
