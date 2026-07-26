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
// Harvest Screen
// Harvest tracking, yield prediction, market prices
// For farmers (Mkulima), fishermen (Mvuvi), livestock (Mfugaji)
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarvestScreen(
    onNavigate: (String) -> Unit = {}
) {
    val colors = MsaidiziThemeTokens.colors
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Mavuno" to "Harvest", "Bei" to "Prices", "Ushauri" to "Advice")

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Mavuno na Kilimo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.primary)
                        Text("Harvest & Farming", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { /* Record harvest */ },
                containerColor = colors.tertiary,
                contentColor = colors.onTertiary
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Rekodi Mavuno", fontWeight = FontWeight.SemiBold)
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
                    // ── Harvest Tracking ──
                    item {
                        SectionHeader(titleSw = "Mavuno ya Leo", titleEn = "Today's Harvest")
                    }
                    item {
                        HarvestSummaryCard()
                    }

                    item {
                        SectionHeader(titleSw = "Historia ya Mavuno", titleEn = "Harvest History")
                    }
                    item { HarvestItem("Nyanya", "50 kg", "KES 3,000", "Leo") }
                    item { HarvestItem("Sukuma Wiki", "30 bunches", "KES 1,500", "Jana") }
                    item { HarvestItem("Mahindi", "100 kg", "KES 4,000", "Wiki jana") }
                    item { HarvestItem("Samaki", "20 kg", "KES 5,000", "Jumatatu") }
                }
                1 -> {
                    // ── Market Prices ──
                    item {
                        AlertBanner(
                            messageSw = "Bei ya nyanya imepanda 15% wiki hii!",
                            messageEn = "Tomato prices increased 15% this week!",
                            severity = AlertSeverity.INFO
                        )
                    }

                    item {
                        SectionHeader(titleSw = "Bei za Soko", titleEn = "Market Prices")
                    }
                    item { PriceItem("Nyanya", "KES 80/kg", "↑ 15%", colors.success) }
                    item { PriceItem("Sukuma Wiki", "KES 30/bunch", "→ Sawa", colors.info) }
                    item { PriceItem("Mahindi", "KES 45/kg", "↓ 5%", colors.error) }
                    item { PriceItem("Samaki (Tilapia)", "KES 350/kg", "↑ 10%", colors.success) }
                    item { PriceItem("Nyama ya Ng'ombe", "KES 600/kg", "→ Sawa", colors.info) }
                    item { PriceItem("Maziwa", "KES 60/litre", "↑ 8%", colors.success) }
                }
                2 -> {
                    // ── Farming/Fishing Advice ──
                    item {
                        AdviceCard(
                            iconSw = "🌧️",
                            titleSw = "Mvua inatarajiwa wiki ijayo",
                            titleEn = "Rain expected next week",
                            bodySw = "Andaa shamba kwa kupanda. Ni wakati mzuri wa mahindi!",
                            bodyEn = "Prepare your farm for planting. Good time for maize!",
                            severity = AlertSeverity.INFO
                        )
                    }
                    item {
                        AdviceCard(
                            iconSw = "🐛",
                            titleSw = "Hatari ya wadudu kwa nyanya",
                            titleEn = "Pest risk for tomatoes",
                            bodySw = "Angalia majani kila siku. Tumia dawa mapema.",
                            bodyEn = "Check leaves daily. Apply pesticide early.",
                            severity = AlertSeverity.WARNING
                        )
                    }
                    item {
                        AdviceCard(
                            iconSw = "💰",
                            titleSw = "Bei ya soko ni nzuri sasa",
                            titleEn = "Market prices are good now",
                            bodySw = "Uza mavuno yako sasa kabla bei haijashuka.",
                            bodyEn = "Sell your harvest now before prices drop.",
                            severity = AlertSeverity.SUCCESS
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun HarvestSummaryCard() {
    val colors = MsaidiziThemeTokens.colors

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.successContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Mavuno ya Wiki", style = MaterialTheme.typography.labelMedium, color = colors.success)
                Text("Weekly Harvest", style = MaterialTheme.typography.labelSmall, color = colors.success.copy(alpha = 0.7f))
                Text("KES 13,500", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.success)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Jumla ya Mzigo", style = MaterialTheme.typography.labelMedium, color = colors.success)
                Text("Total Weight", style = MaterialTheme.typography.labelSmall, color = colors.success.copy(alpha = 0.7f))
                Text("200 kg", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.success)
            }
        }
    }
}

@Composable
private fun HarvestItem(crop: String, quantity: String, value: String, date: String) {
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
                Text(crop, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("$quantity • $date", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.success)
        }
    }
}

@Composable
private fun PriceItem(
    product: String,
    price: String,
    change: String,
    changeColor: androidx.compose.ui.graphics.Color
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
            Text(product, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(price, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.primary)
            Spacer(modifier = Modifier.width(12.dp))
            Text(change, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = changeColor)
        }
    }
}
