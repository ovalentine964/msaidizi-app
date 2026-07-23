package com.msaidizi.app.ui.gamification

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
fun GamificationScreen(
    viewModel: GamificationViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "🎮 Zawadi",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Stats
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
                    Text(text = "⭐", fontSize = 24.sp)
                    Text(text = "${uiState.points}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(text = "Pointi", fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "📊", fontSize = 24.sp)
                    Text(text = "Level ${uiState.level}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(text = "Ngazi", fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "🔥", fontSize = 24.sp)
                    Text(text = "${uiState.streak}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(text = "Siku Mfululizo", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // How to earn
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "💡 Jinsi ya Kupata Pointi", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "• Rekodi mauzo kila siku — 10 pointi", fontSize = 12.sp)
                Text(text = "• Rekodi matumizi — 5 pointi", fontSize = 12.sp)
                Text(text = "• Fikia lengo — 100 pointi", fontSize = 12.sp)
                Text(text = "• Streak ya siku 7 — 200 pointi", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(text = "🏆 Badges", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn {
            items(uiState.badges) { badge ->
                BadgeCard(badge)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun BadgeCard(badge: Badge) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (badge.earned)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = badge.emoji,
                fontSize = 28.sp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = badge.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = badge.description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (badge.earned) {
                Text(text = "✅", fontSize = 20.sp)
            } else {
                Text(text = "🔒", fontSize = 20.sp)
            }
        }
    }
}
