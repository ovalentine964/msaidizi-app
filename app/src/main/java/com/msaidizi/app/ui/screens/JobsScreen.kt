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
// Job Listing Data
// ──────────────────────────────────────────────

data class JobListing(
    val titleSw: String,
    val titleEn: String,
    val location: String,
    val pay: String,
    val type: String,
    val postedAgo: String
)

// ──────────────────────────────────────────────
// Jobs Screen
// Job matching, marketplace (for fundis, househelps,
// construction workers, etc.)
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen(
    onNavigate: (String) -> Unit = {}
) {
    val colors = MsaidiziThemeTokens.colors
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Kazi" to "Jobs", "Zangu" to "My Jobs", "Portfolio" to "Portfolio")

    // Sample job data
    val jobs = listOf(
        JobListing("Kujenga Ukuta", "Build a Wall", "Westlands, Nairobi", "KES 5,000/day", "Mjengo", "Saa 2 zilizopita"),
        JobListing("Kupaka Rangi", "Paint House", "Kasarani, Nairobi", "KES 8,000 total", "Mjengo", "Saa 5 zilizopita"),
        JobListing("Kusuka Nywele", "Braiding Hair", "CBD, Nairobi", "KES 1,500", "Salon", "Jana"),
        JobListing("Kunyoa", "Shaving/Barber", "Eastlands", "KES 200/mtu", "Kinyozi", "Jana"),
        JobListing("Kufundisha Kompyuta", "Computer Lessons", "Thika Road", "KES 3,000/mwezi", "Fundi", "Siku 2"),
        JobListing("Kusafisha Nyumba", "House Cleaning", "Karen, Nairobi", "KES 2,000/day", "Mama Fuo", "Leo")
    )

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Kazi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.primary)
                        Text("Jobs & Marketplace", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Tabs ──
            item {
                MsaidiziTabRow(tabs = tabs, selectedTab = selectedTab, onTabSelected = { selectedTab = it })
            }

            when (selectedTab) {
                0 -> {
                    // ── Available Jobs ──
                    item {
                        AlertBanner(
                            messageSw = "Kazi 6 mpya karibu nawe!",
                            messageEn = "6 new jobs near you!",
                            severity = AlertSeverity.INFO
                        )
                    }

                    // Category filters
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val cats = listOf("Zote", "Mjengo", "Fundi", "Salon", "Usafi")
                            var selectedCat by remember { mutableIntStateOf(0) }
                            cats.forEachIndexed { i, cat ->
                                CategoryChip(label = cat, selected = selectedCat == i, onClick = { selectedCat = i })
                            }
                        }
                    }

                    items(jobs) { job ->
                        JobCard(job)
                    }
                }
                1 -> {
                    // ── My Jobs ──
                    item {
                        SectionHeader(titleSw = "Kazi Zangu", titleEn = "My Active Jobs")
                    }
                    item {
                        ActiveJobItem("Kujenga Ukuta — Westlands", "Inaendelea", "Siku 3/7", colors.warning)
                    }
                    item {
                        ActiveJobItem("Kupaka Rangi — Kasarani", "Imekamilika", "KES 8,000", colors.success)
                    }
                    item {
                        ActiveJobItem("Kusuka Nywele — CBD", "Inasubiri malipo", "KES 1,500", colors.info)
                    }
                }
                2 -> {
                    // ── Portfolio ──
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MsaidiziShapes().large,
                            colors = CardDefaults.cardColors(containerColor = colors.primaryContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Portfolio Yako", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
                                Text("Your Portfolio", style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer.copy(alpha = 0.7f))
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    PortfolioStat("Kazi", "12", "Jobs Done")
                                    PortfolioStat("Ukadiriaji", "4.8⭐", "Rating")
                                    PortfolioCount("Wateja", "8", "Clients")
                                }
                            }
                        }
                    }
                    item {
                        Text("Kazi Zilizokamilika", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.primary)
                        Text("Completed Jobs", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                    item { CompletedJobItem("Kujenga Ukuta — Westlands", "KES 35,000", "⭐ 5.0") }
                    item { CompletedJobItem("Kupaka Rangi — Kasarani", "KES 8,000", "⭐ 4.5") }
                    item { CompletedJobItem("Kufunga Dirisha — CBD", "KES 3,000", "⭐ 5.0") }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun JobCard(job: JobListing) {
    val colors = MsaidiziThemeTokens.colors

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().medium,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(job.titleSw, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(job.titleEn, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Text("${job.location} · ${job.pay}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text(job.postedAgo, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant.copy(alpha = 0.7f))
                }
                FilledTonalButton(onClick = { /* Apply */ }) {
                    Text("Omba", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ActiveJobItem(title: String, status: String, detail: String, statusColor: androidx.compose.ui.graphics.Color) {
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
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(status, style = MaterialTheme.typography.bodySmall, color = statusColor)
            }
            Text(detail, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = statusColor)
        }
    }
}

@Composable
private fun PortfolioStat(labelSw: String, value: String, labelEn: String) {
    val colors = MsaidiziThemeTokens.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
        Text(labelSw, style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer)
        Text(labelEn, style = MaterialTheme.typography.labelSmall, color = colors.onPrimaryContainer.copy(alpha = 0.7f))
    }
}

@Composable
private fun PortfolioCount(labelSw: String, value: String, labelEn: String) {
    val colors = MsaidiziThemeTokens.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
        Text(labelSw, style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer)
        Text(labelEn, style = MaterialTheme.typography.labelSmall, color = colors.onPrimaryContainer.copy(alpha = 0.7f))
    }
}

@Composable
private fun CompletedJobItem(title: String, payment: String, rating: String) {
    val colors = MsaidiziThemeTokens.colors

    ListItemCard(
        title = title,
        subtitle = rating,
        trailing = payment,
        icon = Icons.Default.CheckCircle,
        statusColor = colors.success
    )
}
