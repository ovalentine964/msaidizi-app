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
import com.msaidizi.app.model.ChamaEntity
import com.msaidizi.app.model.ChamaMemberEntity
import com.msaidizi.app.model.ChamaContributionEntity
import com.msaidizi.app.ui.components.*
import com.msaidizi.app.ui.designsystem.*

// ──────────────────────────────────────────────
// Chama Screen
// Group savings management, contributions, meetings
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChamaScreen(
    chamas: List<ChamaEntity> = emptyList(),
    members: List<ChamaMemberEntity> = emptyList(),
    contributions: List<ChamaContributionEntity> = emptyList(),
    onCreateChama: () -> Unit = {},
    onNavigate: (String) -> Unit = {}
) {
    val colors = MsaidiziThemeTokens.colors
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Chama" to "Groups", "Mchango" to "Contributions", "Mkutano" to "Meetings")

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chama", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.primary)
                        Text("Savings Groups", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateChama,
                containerColor = colors.tertiary,
                contentColor = colors.onTertiary
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Chama Jipya", fontWeight = FontWeight.SemiBold)
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
            item {
                MsaidiziTabRow(tabs = tabs, selectedTab = selectedTab, onTabSelected = { selectedTab = it })
            }

            when (selectedTab) {
                0 -> {
                    // ── My Chamas ──
                    item {
                        SectionHeader(titleSw = "Chama Zangu", titleEn = "My Savings Groups")
                    }

                    // Sample chamas
                    item { ChamaCard("Jirani Chama", "Kila wiki", 3000.0, 8, 10, 24000.0) }
                    item { ChamaCard("Wamama wa Sokoni", "Kila mwezi", 5000.0, 5, 12, 60000.0) }
                    item { ChamaCard("Wafanyikazi wa Gikomba", "Kila wiki", 2000.0, 3, 8, 16000.0) }

                    item {
                        SectionHeader(titleSw = "Orodha ya Wanachama", titleEn = "Members — Jirani Chama")
                    }
                    item { MemberItem("Mama Njeri (Wewe)", 1, true) }
                    item { MemberItem("Bwana Otieno", 2, true) }
                    item { MemberItem("Mama Amina", 3, false) }
                    item { MemberItem("Bwana Kamau", 4, true) }
                    item { MemberItem("Mama Wanjiku", 5, false) }
                }
                1 -> {
                    // ── Contributions ──
                    item {
                        AlertBanner(
                            messageSw = "Mchango wako ujao: KES 3,000 — Jumatatu",
                            messageEn = "Your next contribution: KES 3,000 — Monday",
                            severity = AlertSeverity.INFO
                        )
                    }

                    item {
                        SectionHeader(titleSw = "Mchango wa Hivi Karibuni", titleEn = "Recent Contributions")
                    }
                    item { ContributionItem("Mama Njeri", "KES 3,000", "Leo", true) }
                    item { ContributionItem("Bwana Otieno", "KES 3,000", "Jana", true) }
                    item { ContributionItem("Mama Amina", "KES 3,000", "Jumatatu", false) }
                    item { ContributionItem("Bwana Kamau", "KES 3,000", "Jumapili", true) }
                    item { ContributionItem("Mama Wanjiku", "KES 3,000", "—", false) }

                    item {
                        SectionHeader(titleSw = "Mzunguko wa Malipo", titleEn = "Payout Rotation")
                    }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MsaidiziShapes().large,
                            colors = CardDefaults.cardColors(containerColor = colors.primaryContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Mzunguko Ujao", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
                                Text("Next Payout", style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer.copy(alpha = 0.7f))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Bwana Otieno — KES 30,000", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = colors.onPrimaryContainer)
                                Text("Wiki ijayo — Next week", style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
                2 -> {
                    // ── Meetings ──
                    item {
                        SectionHeader(titleSw = "Mikutano Ijayo", titleEn = "Upcoming Meetings")
                    }
                    item {
                        MeetingCard("Jumatatu, Agosti 3", "10:00 AM", "Mama Njeri's Home", "Kawaida — Regular")
                    }
                    item {
                        MeetingCard("Jumatatu, Agosti 10", "10:00 AM", "Bwana Kamau's Home", "Malipo — Payout")
                    }

                    item {
                        SectionHeader(titleSw = "Vidokezo vya Mkutano", titleEn = "Meeting Reminders")
                    }
                    item {
                        AdviceCard(
                            iconSw = "📝",
                            titleSw = "Andaa risiti za mchango",
                            titleEn = "Prepare contribution receipts",
                            bodySw = "Kumbuka kuleta risiti za M-Pesa kwa mkutano ujao.",
                            bodyEn = "Remember to bring M-Pesa receipts to the next meeting.",
                            severity = AlertSeverity.INFO
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun ChamaCard(
    name: String,
    frequency: String,
    contribution: Double,
    currentCycle: Int,
    totalCycles: Int,
    totalSaved: Double
) {
    val colors = MsaidiziThemeTokens.colors
    val progress = currentCycle.toFloat() / totalCycles

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
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("$frequency • KES ${"%,.0f".format(contribution)}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatKes(totalSaved), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.success)
                    Text("Jumla", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            LabeledProgressBar(
                label = "Mzunguko $currentCycle/$totalCycles",
                progress = progress,
                progressText = "${(progress * 100).toInt()}%",
                color = colors.primary
            )
        }
    }
}

@Composable
private fun MemberItem(name: String, order: Int, hasPaid: Boolean) {
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
            Text("#$order", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.primary, modifier = Modifier.width(40.dp))
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (hasPaid) Icons.Default.CheckCircle else Icons.Default.Cancel,
                contentDescription = null,
                tint = if (hasPaid) colors.success else colors.error,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun ContributionItem(name: String, amount: String, date: String, paid: Boolean) {
    val colors = MsaidiziThemeTokens.colors

    ListItemCard(
        title = name,
        subtitle = date,
        trailing = if (paid) "✅ $amount" else "❌ Bado",
        icon = if (paid) Icons.Default.CheckCircle else Icons.Default.Schedule,
        statusColor = if (paid) colors.success else colors.error
    )
}

@Composable
private fun MeetingCard(date: String, time: String, location: String, type: String) {
    val colors = MsaidiziThemeTokens.colors

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().medium,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Event, contentDescription = null, tint = colors.primary, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(date, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("$time • $location", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Text(type, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.tertiary)
        }
    }
}
