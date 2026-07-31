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
import com.msaidizi.core.model.ProductEntity
import com.msaidizi.app.ui.components.*
import com.msaidizi.app.ui.designsystem.*

// ──────────────────────────────────────────────
// Inventory Screen
// Product list with stock levels, restock alerts, voice-add
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    products: List<ProductEntity> = emptyList(),
    onVoiceAdd: () -> Unit = {},
    onAddProduct: () -> Unit = {},
    onProductClick: (ProductEntity) -> Unit = {},
    onNavigate: (String) -> Unit = {}
) {
    val colors = MsaidiziThemeTokens.colors
    var selectedFilter by remember { mutableIntStateOf(0) }
    val filters = listOf("Zote" to "All", "Zinazoisha" to "Low Stock", "Zimeisha" to "Out of Stock")

    val filteredProducts = when (selectedFilter) {
        1 -> products.filter { it.currentStock in 0.1..it.minStock && it.isActive }
        2 -> products.filter { it.currentStock <= 0 && it.isActive }
        else -> products.filter { it.isActive }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Hifadhi ya Bidhaa", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.primary)
                        Text("Inventory", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = onVoiceAdd) {
                        Icon(Icons.Default.Mic, contentDescription = "Ongeza kwa sauti — Voice add", tint = colors.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddProduct,
                containerColor = colors.tertiary,
                contentColor = colors.onTertiary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Ongeza")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Bidhaa Mpya", fontWeight = FontWeight.SemiBold)
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
            // ── Stock Alerts ──
            val lowStock = products.filter { it.currentStock in 0.1..it.minStock && it.isActive }
            val outOfStock = products.filter { it.currentStock <= 0 && it.isActive }

            if (outOfStock.isNotEmpty()) {
                item {
                    AlertBanner(
                        messageSw = "${outOfStock.size} bidhaa zimeisha kabisa!",
                        messageEn = "${outOfStock.size} products are completely out of stock!",
                        severity = AlertSeverity.ERROR
                    )
                }
            }
            if (lowStock.isNotEmpty()) {
                item {
                    AlertBanner(
                        messageSw = "${lowStock.size} bidhaa zinakaribia kuisha",
                        messageEn = "${lowStock.size} products are running low",
                        severity = AlertSeverity.WARNING
                    )
                }
            }

            // ── Summary ──
            item {
                InventorySummaryCard(products)
            }

            // ── Filters ──
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filters.forEachIndexed { index, (sw, _) ->
                        CategoryChip(
                            label = sw,
                            selected = selectedFilter == index,
                            onClick = { selectedFilter = index }
                        )
                    }
                }
            }

            // ── Product List ──
            if (filteredProducts.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Inventory2,
                        titleSw = "Hakuna bidhaa",
                        titleEn = "No products",
                        subtitle = "Ongeza bidhaa zako za kwanza",
                        actionText = "Ongeza Bidhaa",
                        onAction = onAddProduct
                    )
                }
            } else {
                items(filteredProducts) { product ->
                    InventoryItem(
                        product = product,
                        onClick = { onProductClick(product) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun InventorySummaryCard(products: List<ProductEntity>) {
    val colors = MsaidiziThemeTokens.colors
    val totalValue = products.sumOf { it.currentStock * it.sellPrice }
    val totalItems = products.sumOf { it.currentStock }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Thamani ya Hifadhi", style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer)
                Text("Stock Value", style = MaterialTheme.typography.labelSmall, color = colors.onPrimaryContainer.copy(alpha = 0.7f))
                Text(formatKes(totalValue), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Jumla ya Bidhaa", style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer)
                Text("Total Items", style = MaterialTheme.typography.labelSmall, color = colors.onPrimaryContainer.copy(alpha = 0.7f))
                Text("${totalItems.toInt()}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun InventoryItem(
    product: ProductEntity,
    onClick: () -> Unit
) {
    val colors = MsaidiziThemeTokens.colors
    val stockStatus = when {
        product.currentStock <= 0 -> Triple("Imeisha", "Out", colors.error)
        product.currentStock <= product.minStock -> Triple("Inaisha", "Low", colors.warning)
        else -> Triple("Inatosha", "OK", colors.success)
    }
    val stockFraction = if (product.minStock > 0) {
        (product.currentStock / (product.minStock * 3)).coerceIn(0.0, 1.0).toFloat()
    } else 1f

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = MsaidiziShapes().medium,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = product.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = product.category,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${product.currentStock.toInt()} ${product.unit}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = stockStatus.third
                    )
                    Text(
                        text = "${stockStatus.first} / ${stockStatus.second}",
                        style = MaterialTheme.typography.labelSmall,
                        color = stockStatus.third
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { stockFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .then(
                        Modifier.padding(0.dp)
                    ),
                color = stockStatus.third,
                trackColor = stockStatus.third.copy(alpha = 0.15f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Nunua: KES ${"%,.0f".format(product.buyPrice)}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Text("Uza: KES ${"%,.0f".format(product.sellPrice)}", style = MaterialTheme.typography.labelSmall, color = colors.success)
            }
        }
    }
}
