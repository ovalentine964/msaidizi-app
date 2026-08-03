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
import com.msaidizi.core.model.ServiceMenuEntity
import com.msaidizi.core.model.ServiceTransactionEntity
import com.msaidizi.app.ui.components.*
import com.msaidizi.app.ui.designsystem.*

// ──────────────────────────────────────────────
// Services Screen
// Service menu, bookings, ratings (for service workers)
// Fundi, salon, barber, tailor, car wash, etc.
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesScreen(
    serviceMenu: List<ServiceMenuEntity> = emptyList(),
    recentServices: List<ServiceTransactionEntity> = emptyList(),
    onAddService: () -> Unit = {},
    onRecordService: () -> Unit = {},
    onNavigate: (String) -> Unit = {}
) {
    val colors = MsaidiziThemeTokens.colors
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Orodha" to "Menu", "Hivi Karibuni" to "Recent", "Nafasi" to "Bookings")

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Huduma", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.primary)
                        Text("Services", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onRecordService,
                containerColor = colors.tertiary,
                contentColor = colors.onTertiary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Ongeza")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Rekodi Huduma", fontWeight = FontWeight.SemiBold)
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
            // ── Tabs ──
            item {
                MsaidiziTabRow(tabs = tabs, selectedTab = selectedTab, onTabSelected = { selectedTab = it })
            }

            when (selectedTab) {
                0 -> {
                    // ── Service Menu ──
                    item {
                        SectionHeader(
                            titleSw = "Orodha ya Huduma",
                            titleEn = "Service Menu",
                            actionText = "Ongeza",
                            onAction = onAddService
                        )
                    }

                    if (serviceMenu.isEmpty()) {
                        item {
                            EmptyState(
                                icon = Icons.Outlined.DesignServices,
                                titleSw = "Hakuna huduma bado",
                                titleEn = "No services yet",
                                subtitle = "Ongeza huduma unazotoa",
                                actionText = "Ongeza Huduma",
                                onAction = onAddService
                            )
                        }
                    } else {
                        val grouped = serviceMenu.groupBy { it.category }
                        grouped.forEach { (category, services) ->
                            item {
                                Text(
                                    text = category.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.primary,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                            items(services) { service ->
                                ServiceMenuItem(service)
                            }
                        }
                    }
                }
                1 -> {
                    // ── Recent Services ──
                    item {
                        SectionHeader(titleSw = "Huduma za Hivi Karibuni", titleEn = "Recent Services")
                    }

                    if (recentServices.isEmpty()) {
                        item {
                            EmptyState(
                                icon = Icons.Outlined.History,
                                titleSw = "Hakuna huduma bado",
                                titleEn = "No services recorded",
                                subtitle = "Rekodi huduma yako ya kwanza"
                            )
                        }
                    } else {
                        items(recentServices.take(20)) { txn ->
                            ServiceTransactionItem(txn)
                        }
                    }
                }
                2 -> {
                    // ── Bookings ──
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MsaidiziShapes().large,
                            colors = CardDefaults.cardColors(containerColor = colors.infoContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("📅 Nafasi za Wiki Hii", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onSecondaryContainer)
                                Text("This Week's Bookings", style = MaterialTheme.typography.bodySmall, color = colors.onSecondaryContainer.copy(alpha = 0.7f))
                                Spacer(modifier = Modifier.height(12.dp))
                                BookingItem("Jumatatu 10:00", "Kupiga Nywele — Mama Njeri", "KES 500")
                                BookingItem("Jumanne 14:00", "Kusuka — Amina", "KES 1,200")
                                BookingItem("Alhamisi 09:00", "Kunyoa — Bwana Otieno", "KES 200")
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun ServiceMenuItem(service: ServiceMenuEntity) {
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
                Text(service.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(service.category, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("KES ${"%,.0f".format(service.basePrice)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.primary)
                Text("${service.usageCount}x imeuzwa", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ServiceTransactionItem(txn: ServiceTransactionEntity) {
    val colors = MsaidiziThemeTokens.colors

    ListItemCard(
        title = txn.serviceName,
        subtitle = "${txn.customerName ?: "Mteja"} • ${txn.paymentMethod.uppercase()}",
        trailing = formatKes(txn.totalCharged),
        icon = Icons.Default.DesignServices,
        statusColor = colors.tertiary
    )
}

@Composable
private fun BookingItem(time: String, service: String, price: String) {
    val colors = MsaidiziThemeTokens.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(time, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.onSecondaryContainer, modifier = Modifier.width(100.dp))
        Text(service, style = MaterialTheme.typography.bodyMedium, color = colors.onSecondaryContainer, modifier = Modifier.weight(1f))
        Text(price, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.onSecondaryContainer)
    }
}
