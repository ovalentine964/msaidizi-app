package com.msaidizi.app.ui.goals

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

@Composable
fun GoalsScreen(
    viewModel: GoalsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "🎯 Malengo",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Summary
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "🟢", fontSize = 20.sp)
                    Text(text = "${uiState.activeCount}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(text = "Inayofanya kazi", fontSize = 11.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "✅", fontSize = 20.sp)
                    Text(text = "${uiState.completedCount}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(text = "Imekamilika", fontSize = 11.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "💰", fontSize = 20.sp)
                    Text(text = "KSh ${"%,.0f".format(uiState.totalSaved)}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(text = "Jumla Akiba", fontSize = 11.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.goals.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Bado huna malengo.\nSema: \"Nataka kusave KSh 10,000\"",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn {
                items(uiState.goals) { goal ->
                    GoalCard(goal)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun GoalCard(goal: GoalRow) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = goal.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (goal.status == "completed") "✅" else "🟢",
                    fontSize = 16.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (goal.progress.coerceIn(0, 100)) / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "KSh ${"%,.0f".format(goal.currentAmount)} / ${"%,.0f".format(goal.targetAmount)}",
                    fontSize = 13.sp
                )
                Text(text = "${goal.progress}%", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            if (goal.deadline != null) {
                Text(
                    text = "⏱ Tarehe: ${goal.deadline}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
