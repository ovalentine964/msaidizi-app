package com.msaidizi.app.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.msaidizi.app.agent.AgentOutput

/**
 * DashboardScreen — Main screen showing business overview.
 * Voice-first: tap the mic button to speak.
 * Shows today's summary, quick actions, and recent activity.
 */
@Composable
fun DashboardScreen(
    navController: NavController? = null,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "Msaidizi",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Habari! Leo ni siku nzuri ya biashara ☀️",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Voice Button (primary interaction)
        VoiceInputButton(
            isListening = uiState.isListening,
            onClick = { viewModel.toggleVoice() }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Today's Summary Card
        SummaryCard(
            sales = uiState.todaySales,
            expenses = uiState.todayExpenses,
            profit = uiState.todayProfit,
            transactionCount = uiState.todayTransactions
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Quick Actions
        Text(
            text = "Haraka",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            QuickActionButton("💰", "Mauzo") { viewModel.quickAction("sale") }
            QuickActionButton("📉", "Gharama") { viewModel.quickAction("expense") }
            QuickActionButton("📊", "Salio") { viewModel.quickAction("balance") }
            QuickActionButton("🎯", "Malengo") { viewModel.quickAction("goals") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Features — Navigate to dedicated screens
        Text(
            text = "⭐ Vipengele",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            QuickActionButton("🎯", "Malengo") { navController?.navigate("goals") }
            QuickActionButton("🏦", "Mikopo") { navController?.navigate("loans") }
            QuickActionButton("🙏", "Zaka") { navController?.navigate("tithe") }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            QuickActionButton("🎮", "Zawadi") { navController?.navigate("gamification") }
            QuickActionButton("🧠", "Tabia") { navController?.navigate("mindset") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Recent Activity
        Text(
            text = "Miamala ya Hivi Karibuni",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.recentTransactions.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "Bado hujarekodi miamala yoyote.\nSema: \"Nimeuza nyanya kwa 500\"",
                    modifier = Modifier.padding(16.dp),
                    fontSize = 14.sp
                )
            }
        } else {
            uiState.recentTransactions.forEach { tx ->
                TransactionRow(tx)
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Agent Response
        if (uiState.lastResponse != null) {
            AgentResponseCard(uiState.lastResponse!!)
        }
    }
}

@Composable
fun VoiceInputButton(isListening: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isListening)
                MaterialTheme.colorScheme.error
            else
                MaterialTheme.colorScheme.primary
        )
    ) {
        Text(
            text = if (isListening) "🎤 Nasikiliza..." else "🎤 Ongea na Msaidizi",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SummaryCard(sales: Double, expenses: Double, profit: Double, transactionCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "📋 Muhtasari wa Leo",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SummaryItem("Mauzo", "KSh ${"%,.0f".format(sales)}", "💰")
                SummaryItem("Matumizi", "KSh ${"%,.0f".format(expenses)}", "📉")
                SummaryItem("Faida", "KSh ${"%,.0f".format(profit)}", if (profit >= 0) "📈" else "📉")
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Miamala: $transactionCount",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SummaryItem(label: String, value: String, emoji: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = emoji, fontSize = 20.sp)
        Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun QuickActionButton(emoji: String, label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.width(80.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = emoji, fontSize = 20.sp)
            Text(text = label, fontSize = 10.sp)
        }
    }
}

@Composable
fun TransactionRow(tx: TransactionRowData) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = tx.item, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(text = tx.type, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = "KSh ${"%,.0f".format(tx.amount)}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (tx.type == "SALE") MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun AgentResponseCard(response: AgentOutput) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "🤖 Msaidizi",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = response.text, fontSize = 14.sp)
        }
    }
}

data class TransactionRowData(
    val item: String,
    val type: String,
    val amount: Double,
    val timestamp: Long
)
