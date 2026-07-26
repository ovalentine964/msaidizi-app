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
// Pricing Screen
// Service/product pricing advisor with market comparison
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PricingScreen(
    onNavigate: (String) -> Unit = {}
) {
    val colors = MsaidiziThemeTokens.colors
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Bei Zangu" to "My Prices", "Soko" to "Market", "Ushauri" to "Advice")

    // Sample pricing data
    data class PriceComparison(
        val product: String,
        val myPrice: Double,
        val marketAvg: Double,
        val lowestPrice: Double,
        val highestPrice: Double
    )

    val prices = listOf(
        PriceComparison("Nyanya (kg)", 80.0, 85.0, 60.0, 120.0),
        PriceComparison("Sukuma Wiki (bunch)", 30.0, 25.0, 20.0, 40.0),
        PriceComparison("Vitunguu (kg)", 100.0, 110.0, 80.0, 150.0),
        PriceComparison("Karoti (kg)", 120.0, 100.0, 80.0, 140.0),
        PriceComparison("Viazi (kg)", 60.0, 55.0, 40.0, 80.0)
    )

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Bei na Soko", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.primary)
                        Text("Pricing & Market", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
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
            item {
                MsaidiziTabRow(tabs = tabs, selectedTab = selectedTab, onTabSelected = { selectedTab = it })
            }

            when (selectedTab) {
                0 -> {
                    // ── My Prices vs Market ──
                    item {
                        AlertBanner(
                            messageSw = "Bei yako ya sukuma wiki ni ya chini kuliko soko!",
                            messageEn = "Your sukuma wiki price is below market rate!",
                            severity = AlertSeverity.WARNING
                        )
                    }

                    items(prices) { price ->
                        PriceComparisonCard(price)
                    }
                }
                1 -> {
                    // ── Market Overview ──
                    item {
                        SectionHeader(titleSw = "Bei za Sokoni Leo", titleEn = "Today's Market Prices")
                    }
                    item { MarketPriceItem("Wakulima Market", "Nyanya: KES 70/kg", "Sukuma: KES 20/bunch") }
                    item { MarketPriceItem("Gikomba Market", "Nyanya: KES 65/kg", "Sukuma: KES 18/bunch") }
                    item { MarketPriceItem("City Market", "Nyanya: KES 100/kg", "Sukuma: KES 35/bunch") }
                    item { MarketPriceItem("Eastlands", "Nyanya: KES 80/kg", "Sukuma: KES 25/bunch") }
                }
                2 -> {
                    // ── Pricing Advice ──
                    item {
                        AdviceCard(
                            iconSw = "📈",
                            titleSw = "Panda bei ya nyanya",
                            titleEn = "Increase tomato price",
                            bodySw = "Soko la jiji lina bei ya juu. Weka KES 90/kg badala ya KES 80.",
                            bodyEn = "City market has higher prices. Set KES 90/kg instead of KES 80.",
                            severity = AlertSeverity.INFO
                        )
                    }
                    item {
                        AdviceCard(
                            iconSw = "📉",
                            titleSw = "Shusha bei ya sukuma wiki",
                            titleEn = "Lower sukuma wiki price",
                            bodySw = "Sukuma wiki yako inakaribia kuharibika. Uza kwa KES 20 leo.",
                            bodyEn = "Your sukuma wiki is about to spoil. Sell at KES 20 today.",
                            severity = AlertSeverity.WARNING
                        )
                    }
                    item {
                        AdviceCard(
                            iconSw = "💡",
                            titleSw = "Nunua vitunguu sasa",
                            titleEn = "Buy onions now",
                            bodySw = "Bei ya vitunguu ni ya chini Gikomba. Nunua na uuze baadaye.",
                            bodyEn = "Onion prices are low at Gikomba. Buy now, sell later.",
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
private fun PriceComparisonCard(price: Any) {
    val colors = MsaidiziThemeTokens.colors
    // Simplified — in production use the actual data class
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().medium,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Bidhaa", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Bei Yako", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text("KES 80", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.primary)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Wastani wa Soko", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text("KES 85", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.info)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Ushauri", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text("Panda ↑", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.success)
                }
            }
        }
    }
}

@Composable
private fun MarketPriceItem(market: String, line1: String, line2: String) {
    val colors = MsaidiziThemeTokens.colors

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().medium,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(market, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(line1, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text(line2, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}
