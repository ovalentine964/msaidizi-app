package com.msaidizi.app.ui.mamamboga

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msaidizi.app.ui.designsystem.*

/**
 * MamaMbogaRecordScreen — Quick transaction recording for mama mboga.
 *
 * Designed for speed: tap a product icon → enter amount → done.
 * Voice is optional (tap the mic button).
 *
 * Key design decisions from research:
 *   - Default to TAP, not voice (voice fails in noisy markets)
 *   - Show common products as quick-select tiles
 *   - Big, touch-friendly buttons (minimum 48dp touch targets)
 *   - Confirmation after every recording
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MamaMbogaRecordScreen(
    onRecordSale: () -> Unit = {},
    onRecordExpense: () -> Unit = {},
    onNavigate: (String) -> Unit = {}
) {
    val colors = MsaidiziThemeTokens.colors
    var selectedTab by remember { mutableIntStateOf(0) } // 0=Sale, 1=Expense, 2=Purchase

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Rekodi",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary
                        )
                        Text(
                            text = "Record Transaction",
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
            // ── Transaction Type Selector ──
            item {
                TransactionTypeSelector(
                    selectedIndex = selectedTab,
                    onSelect = { selectedTab = it }
                )
            }

            // ── Quick Product Grid (for sales) ──
            if (selectedTab == 0) {
                item {
                    Text(
                        text = "Chagua Bidhaa / Select Product",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary
                    )
                }
                item {
                    QuickProductGrid(
                        onProductSelected = { product ->
                            // Navigate to amount entry with pre-selected product
                            onRecordSale()
                        }
                    )
                }
            }

            // ── Quick Expense Categories ──
            if (selectedTab == 1) {
                item {
                    Text(
                        text = "Aina ya Gharama / Expense Type",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary
                    )
                }
                item {
                    QuickExpenseGrid(
                        onExpenseSelected = { category ->
                            onRecordExpense()
                        }
                    )
                }
            }

            // ── Voice Recording Option ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MsaidiziShapes().large,
                    colors = CardDefaults.cardColors(containerColor = colors.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Rekodi kwa Sauti",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Record by Voice",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                            Text(
                                text = "Sema: \"Nimeuza nyanya mia tano\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        FilledIconButton(
                            onClick = { /* Voice recording */ },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = colors.primary
                            )
                        ) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = "Record",
                                tint = colors.onPrimary
                            )
                        }
                    }
                }
            }

            // ── M-Pesa Auto-Import Status ──
            item {
                MpesaAutoImportCard()
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

/**
 * Transaction type selector — Sale, Expense, Purchase.
 */
@Composable
private fun TransactionTypeSelector(
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    val colors = MsaidiziThemeTokens.colors
    val types = listOf(
        Triple("💰", "Mauzo", "Sale"),
        Triple("💸", "Gharama", "Expense"),
        Triple("📦", "Ununuzi", "Purchase")
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        types.forEachIndexed { index, (emoji, sw, en) ->
            val selected = selectedIndex == index
            Card(
                modifier = Modifier.weight(1f),
                onClick = { onSelect(index) },
                shape = MsaidiziShapes().large,
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) colors.primaryContainer else colors.surface
                ),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = if (selected) 4.dp else 1.dp
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = emoji, style = MaterialTheme.typography.headlineMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = sw,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) colors.primary else colors.onSurface
                    )
                    Text(
                        text = en,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Quick product grid — common mama mboga products as tap tiles.
 */
@Composable
private fun QuickProductGrid(
    onProductSelected: (String) -> Unit
) {
    val colors = MsaidiziThemeTokens.colors

    data class QuickProduct(val emoji: String, val nameSw: String, val nameEn: String)

    val products = listOf(
        QuickProduct("🍅", "Nyanya", "Tomatoes"),
        QuickProduct("🥬", "Sukuma Wiki", "Kale"),
        QuickProduct("🧅", "Vitunguu", "Onions"),
        QuickProduct("🥕", "Karoti", "Carrots"),
        QuickProduct("🌶️", "Pilipili", "Chilli"),
        QuickProduct("🥔", "Viazi", "Potatoes"),
        QuickProduct("🥒", "Matango", "Cucumbers"),
        QuickProduct("🍌", "Ndizi", "Bananas"),
        QuickProduct("🥑", "Parachichi", "Avocado"),
        QuickProduct("🌽", "Mahindi", "Maize"),
        QuickProduct("🫑", "Hoho", "Peppers"),
        QuickProduct("🧄", "Kitunguu Saumu", "Garlic")
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (row in products.chunked(4)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { product ->
                    Card(
                        modifier = Modifier.weight(1f),
                        onClick = { onProductSelected(product.nameSw) },
                        shape = MsaidiziShapes().medium,
                        colors = CardDefaults.cardColors(containerColor = colors.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(text = product.emoji, style = MaterialTheme.typography.titleLarge)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = product.nameSw,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }
                // Fill remaining space
                repeat(4 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Quick expense grid — common expense categories.
 */
@Composable
private fun QuickExpenseGrid(
    onExpenseSelected: (String) -> Unit
) {
    val colors = MsaidiziThemeTokens.colors

    data class QuickExpense(val emoji: String, val nameSw: String, val nameEn: String, val category: String)

    val expenses = listOf(
        QuickExpense("🚚", "Usafiri", "Transport", "transport"),
        QuickExpense("🏠", "Kodi", "Rent", "rent"),
        QuickExpense("🍱", "Chakula", "Food", "food"),
        QuickExpense("💡", "Umeme", "Electricity", "utilities"),
        QuickExpense("📱", "Airtime", "Airtime", "airtime"),
        QuickExpense("📦", "Stock", "Stock Purchase", "stock")
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        expenses.take(4).forEach { expense ->
            Card(
                modifier = Modifier.weight(1f),
                onClick = { onExpenseSelected(expense.category) },
                shape = MsaidiziShapes().medium,
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = expense.emoji, style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = expense.nameSw,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = expense.nameEn,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * M-Pesa auto-import status card.
 * Shows that M-Pesa SMS is being automatically parsed.
 */
@Composable
private fun MpesaAutoImportCard() {
    val colors = MsaidiziThemeTokens.colors

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.info.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "📱", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "M-Pesa Auto-Import",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.info
                )
                Text(
                    text = "M-Pesa SMS zinarekodiwa moja kwa moja",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Text(
                    text = "M-Pesa SMS are auto-recorded",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Switch(
                checked = true,
                onCheckedChange = { /* Toggle M-Pesa auto-import */ },
                colors = SwitchDefaults.colors(checkedTrackColor = colors.info)
            )
        }
    }
}
