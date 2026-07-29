package com.msaidizi.app.ui.mamamboga

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msaidizi.core.model.ProductEntity
import com.msaidizi.app.ui.designsystem.*

/**
 * MamaMbogaMarketScreen — Market prices and demand forecasting.
 *
 * Shows:
 *   1. Current wholesale prices for common vegetables
 *   2. Demand forecast for today ("Buy less tomatoes — low demand day")
 *   3. Spoilage risk alerts
 *   4. Price trends
 *
 * Design: Simple cards, big text, actionable advice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MamaMbogaMarketScreen(
    products: List<ProductEntity> = emptyList(),
    onNavigate: (String) -> Unit = {}
) {
    val colors = MsaidiziThemeTokens.colors
    var selectedProduct by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Soko",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary
                        )
                        Text(
                            text = "Market Intelligence",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Demand Forecast for Today ──
            item {
                DemandForecastCard()
            }

            // ── Wholesale Price Grid ──
            item {
                Text(
                    text = "Bei za Jumla Leo / Wholesale Prices Today",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary
                )
            }
            item {
                WholesalePriceGrid(
                    onProductClick = { selectedProduct = it }
                )
            }

            // ── Spoilage Risk ──
            item {
                SpoilageRiskCard(products)
            }

            // ── Price Trends ──
            item {
                PriceTrendCard()
            }

            // ── Markdown Suggestions ──
            item {
                MarkdownSuggestionCard(products)
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

/**
 * Demand forecast — "Buy less tomatoes today — low demand day"
 * Based on day of week, market day patterns, and historical sales.
 */
@Composable
private fun DemandForecastCard() {
    val colors = MsaidiziThemeTokens.colors
    val calendar = remember { java.util.Calendar.getInstance() }
    val dayOfWeek = remember { calendar.get(java.util.Calendar.DAY_OF_WEEK) }
    val dayName = remember {
        when (dayOfWeek) {
            java.util.Calendar.MONDAY -> "Jumatatu / Monday"
            java.util.Calendar.TUESDAY -> "Jumanne / Tuesday"
            java.util.Calendar.WEDNESDAY -> "Jumatano / Wednesday"
            java.util.Calendar.THURSDAY -> "Alhamisi / Thursday"
            java.util.Calendar.FRIDAY -> "Ijumaa / Friday"
            java.util.Calendar.SATURDAY -> "Jumamosi / Saturday"
            java.util.Calendar.SUNDAY -> "Jumapili / Sunday"
            else -> "Leo / Today"
        }
    }

    // Demand patterns by day of week (typical Nairobi mama mboga patterns)
    // Higher = more demand
    val demandLevel = when (dayOfWeek) {
        java.util.Calendar.MONDAY -> "HIGH" // People restocking after weekend
        java.util.Calendar.TUESDAY -> "MEDIUM"
        java.util.Calendar.WEDNESDAY -> "MEDIUM"
        java.util.Calendar.THURSDAY -> "HIGH" // Pre-weekend shopping
        java.util.Calendar.FRIDAY -> "VERY HIGH" // Weekend prep
        java.util.Calendar.SATURDAY -> "VERY HIGH" // Peak market day
        java.util.Calendar.SUNDAY -> "LOW" // Church day, less shopping
        else -> "MEDIUM"
    }

    val demandEmoji = when (demandLevel) {
        "VERY HIGH" -> "🔥"
        "HIGH" -> "📈"
        "MEDIUM" -> "➡️"
        "LOW" -> "📉"
        else -> "➡️"
    }

    val demandColor = when (demandLevel) {
        "VERY HIGH" -> colors.success
        "HIGH" -> colors.success
        "MEDIUM" -> colors.info
        "LOW" -> colors.warning
        else -> colors.info
    }

    val advice = when (demandLevel) {
        "VERY HIGH" -> "Nunua zaidi leo! Mahitaji ni makubwa sana."
        "HIGH" -> "Mahitaji ni mazuri. Nunua kiasi cha kawaida."
        "MEDIUM" -> "Mahitaji ya kawaida. Usinunue sana."
        "LOW" -> "Nunua kidogo leo — mahitaji ni madogo."
        else -> "Rekodi mauzo yako ili kujifunza mzunguko wako."
    }
    val adviceEn = when (demandLevel) {
        "VERY HIGH" -> "Buy more today! Very high demand expected."
        "HIGH" -> "Good demand expected. Buy normal stock."
        "MEDIUM" -> "Normal demand. Don't over-stock."
        "LOW" -> "Buy less today — low demand expected."
        else -> "Record your sales to learn your patterns."
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = demandColor.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = demandEmoji, style = MaterialTheme.typography.headlineLarge)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Mahitaji ya Leo",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = demandColor
                    )
                    Text(
                        text = "Today's Demand — $dayName",
                        style = MaterialTheme.typography.bodySmall,
                        color = demandColor.copy(alpha = 0.7f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Demand level badge
            Card(
                shape = MsaidiziShapes().medium,
                colors = CardDefaults.cardColors(containerColor = demandColor.copy(alpha = 0.15f))
            ) {
                Text(
                    text = "  $demandLevel  ",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = demandColor,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Advice
            Text(text = advice, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = adviceEn,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

/**
 * Wholesale price grid — common vegetables with current prices.
 */
@Composable
private fun WholesalePriceGrid(
    onProductClick: (String) -> Unit
) {
    val colors = MsaidiziThemeTokens.colors

    data class PriceItem(
        val emoji: String,
        val nameSw: String,
        val wholesalePrice: String,
        val retailPrice: String,
        val trend: String // "up", "down", "stable"
    )

    val prices = listOf(
        PriceItem("🍅", "Nyanya", "KES 80/kg", "KES 120/kg", "up"),
        PriceItem("🥬", "Sukuma Wiki", "KES 30/bunch", "KES 50/bunch", "stable"),
        PriceItem("🧅", "Vitunguu", "KES 60/kg", "KES 100/kg", "down"),
        PriceItem("🥔", "Viazi", "KES 50/kg", "KES 80/kg", "stable"),
        PriceItem("🌶️", "Pilipili", "KES 100/kg", "KES 150/kg", "up"),
        PriceItem("🥕", "Karoti", "KES 70/kg", "KES 120/kg", "stable"),
        PriceItem("🥒", "Matango", "KES 40/kg", "KES 70/kg", "down"),
        PriceItem("🌽", "Mahindi", "KES 20/piece", "KES 40/piece", "stable")
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in prices.chunked(2)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { item ->
                    Card(
                        modifier = Modifier.weight(1f),
                        onClick = { onProductClick(item.nameSw) },
                        shape = MsaidiziShapes().medium,
                        colors = CardDefaults.cardColors(containerColor = colors.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = item.emoji, style = MaterialTheme.typography.titleLarge)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = item.nameSw,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val trendIcon = when (item.trend) {
                                        "up" -> "↑"
                                        "down" -> "↓"
                                        else -> "→"
                                    }
                                    val trendColor = when (item.trend) {
                                        "up" -> colors.error
                                        "down" -> colors.success
                                        else -> colors.onSurfaceVariant
                                    }
                                    Text(
                                        text = "$trendIcon ${item.trend}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = trendColor
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Jumla: ${item.wholesalePrice}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                            Text(
                                text = "Rejareja: ${item.retailPrice}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.primary
                            )
                        }
                    }
                }
                if (row.size < 2) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Spoilage risk card — items at risk of spoiling.
 */
@Composable
private fun SpoilageRiskCard(products: List<ProductEntity>) {
    val colors = MsaidiziThemeTokens.colors

    // Common perishable items with typical shelf life
    val perishables = listOf(
        Triple("🍅", "Nyanya", "2-3 siku / days"),
        Triple("🥬", "Sukuma Wiki", "1-2 siku / days"),
        Triple("🥒", "Matango", "3-4 siku / days"),
        Triple("🌶️", "Pilipili", "4-5 siku / days"),
        Triple("🍌", "Ndizi", "2-3 siku / days")
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.warning.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "⚠️ Bidhaa Zinazoharibika Haraka",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.warning
            )
            Text(
                text = "Items that spoil quickly",
                style = MaterialTheme.typography.bodySmall,
                color = colors.warning.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(12.dp))

            perishables.forEach { (emoji, name, shelfLife) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = emoji, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = shelfLife,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.warning
                    )
                }
            }
        }
    }
}

/**
 * Price trend card — weekly price movements.
 */
@Composable
private fun PriceTrendCard() {
    val colors = MsaidiziThemeTokens.colors

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "📈 Mwelekeo wa Bei Wiki Hii",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.primary
            )
            Text(
                text = "Price trends this week",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Placeholder trend data
            val trends = listOf(
                "Nyanya" to "+15% ↑",
                "Sukuma Wiki" to "-5% ↓",
                "Vitunguu" to "+8% ↑",
                "Viazi" to "0% →"
            )

            trends.forEach { (name, change) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = name, style = MaterialTheme.typography.bodyMedium)
                    val color = when {
                        change.startsWith("+") -> colors.error
                        change.startsWith("-") -> colors.success
                        else -> colors.onSurfaceVariant
                    }
                    Text(
                        text = change,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "💡 Rekodi bei unazonunua na kuuza ili kupata ushauri bora!",
                style = MaterialTheme.typography.bodySmall,
                color = colors.info
            )
            Text(
                text = "Record your buy/sell prices for better advice!",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

/**
 * Markdown suggestion card — suggest price reductions for aging stock.
 */
@Composable
private fun MarkdownSuggestionCard(products: List<ProductEntity>) {
    val colors = MsaidiziThemeTokens.colors

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.info.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "💡 Punguza Bei — Uuze Haraka",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.info
            )
            Text(
                text = "Markdown pricing to reduce spoilage",
                style = MaterialTheme.typography.bodySmall,
                color = colors.info.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Example markdown suggestions
            val suggestions = listOf(
                "🍅 Nyanya: Punguza kutoka KES 120 hadi KES 90/kg (siku 2 kabla ya kuharibika)",
                "🥬 Sukuma Wiki: Punguza kutoka KES 50 hadi KES 30/bunch (leo ni siku ya mwisho)",
                "🥒 Matango: Punguza kutoka KES 70 hadi KES 50/kg (bado ni freshi)"
            )

            suggestions.forEach { suggestion ->
                Text(
                    text = "• $suggestion",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Kuuza kwa bei ndogo ni bora kuliko kupoteza kabisa!",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.info
            )
            Text(
                text = "Selling cheap is better than losing everything!",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

