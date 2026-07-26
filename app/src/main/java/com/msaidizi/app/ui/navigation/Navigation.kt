package com.msaidizi.app.ui.navigation

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.msaidizi.app.model.*
import com.msaidizi.app.ui.components.MsaidiziBottomBar
import com.msaidizi.app.ui.screens.*

// ──────────────────────────────────────────────
// Routes — All screen destinations
// ──────────────────────────────────────────────

object Routes {
    // Core
    const val VOICE = "voice"
    const val DASHBOARD = "dashboard"
    const val TRANSACTIONS = "transactions"
    const val MORE = "more"

    // Business
    const val INVENTORY = "inventory"
    const val REPORTS = "reports"
    const val GOALS = "goals"
    const val CUSTOMERS = "customers"
    const val SERVICES = "services"
    const val JOBS = "jobs"
    const val HARVEST = "harvest"
    const val PRICING = "pricing"

    // Financial
    const val MPESA = "mpesa"
    const val CREDIT = "credit"
    const val CHAMA = "chama"

    // System
    const val SETTINGS = "settings"
    const val ONBOARDING = "onboarding"
    const val HELP = "help"
    const val NOTIFICATIONS = "notifications"
}

// ──────────────────────────────────────────────
// More Menu Items (for the "Zaidi" bottom tab)
// ──────────────────────────────────────────────

data class MoreMenuItem(
    val route: String,
    val iconSw: String,
    val labelSw: String,
    val labelEn: String
)

val moreMenuItems = listOf(
    MoreMenuItem(Routes.INVENTORY, "📦", "Hifadhi ya Bidhaa", "Inventory"),
    MoreMenuItem(Routes.REPORTS, "📊", "Ripoti za Biashara", "Business Reports"),
    MoreMenuItem(Routes.GOALS, "🎯", "Malengo na Mikopo", "Goals & Loans"),
    MoreMenuItem(Routes.CUSTOMERS, "👥", "Wateja", "Customers"),
    MoreMenuItem(Routes.SERVICES, "🔧", "Huduma", "Services"),
    MoreMenuItem(Routes.JOBS, "💼", "Kazi", "Jobs"),
    MoreMenuItem(Routes.HARVEST, "🌾", "Mavuno", "Harvest"),
    MoreMenuItem(Routes.PRICING, "💹", "Bei na Soko", "Pricing"),
    MoreMenuItem(Routes.CREDIT, "💳", "Mkopo na Bima", "Credit & Insurance"),
    MoreMenuItem(Routes.CHAMA, "🤝", "Chama", "Savings Groups"),
    MoreMenuItem(Routes.SETTINGS, "⚙️", "Mipangilio", "Settings")
)

// ──────────────────────────────────────────────
// Main App Navigation
// ──────────────────────────────────────────────

@Composable
fun AppNavigation(
    isOnboarded: Boolean = true,
    voiceState: VoiceState = VoiceState(),
    dashboardState: DashboardState = DashboardState(),
    messages: List<ChatMessage> = emptyList(),
    transactions: List<SaleEntity> = emptyList(),
    products: List<ProductEntity> = emptyList(),
    customers: List<CustomerEntity> = emptyList(),
    chamas: List<ChamaEntity> = emptyList(),
    chamaMembers: List<ChamaMemberEntity> = emptyList(),
    chamaContributions: List<ChamaContributionEntity> = emptyList(),
    onVoiceToggle: () -> Unit = {},
    onTextSubmit: (String) -> Unit = {},
    onQuickAction: (String) -> Unit = {},
    onRecordSale: () -> Unit = {},
    onCheckInventory: () -> Unit = {},
    onViewDebts: () -> Unit = {},
    onOnboardingComplete: (BusinessType, Language, String) -> Unit = { _, _, _ -> }
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Show onboarding if not completed
    val startDestination = if (isOnboarded) Routes.VOICE else Routes.ONBOARDING

    // Routes that show bottom bar
    val bottomBarRoutes = setOf(Routes.VOICE, Routes.DASHBOARD, Routes.TRANSACTIONS, Routes.MORE)
    val showBottomBar = currentRoute in bottomBarRoutes

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                MsaidiziBottomBar(
                    currentRoute = currentRoute ?: Routes.VOICE,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            // ── Core Screens ──
            composable(Routes.VOICE) {
                VoiceInteractionScreen(
                    voiceState = voiceState,
                    messages = messages,
                    onVoiceToggle = onVoiceToggle,
                    onTextSubmit = onTextSubmit,
                    onQuickAction = onQuickAction,
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    dashboardState = dashboardState,
                    onRecordSale = onRecordSale,
                    onCheckInventory = onCheckInventory,
                    onViewDebts = onViewDebts,
                    onViewReports = { navController.navigate(Routes.REPORTS) },
                    onViewCustomers = { navController.navigate(Routes.CUSTOMERS) },
                    onViewGoals = { navController.navigate(Routes.GOALS) },
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            composable(Routes.TRANSACTIONS) {
                TransactionListScreen(
                    transactions = transactions,
                    onVoiceSearch = { /* Voice search */ },
                    onAddTransaction = onRecordSale,
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            // ── More Menu ──
            composable(Routes.MORE) {
                MoreMenuScreen(
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            // ── Business Screens ──
            composable(Routes.INVENTORY) {
                InventoryScreen(
                    products = products,
                    onVoiceAdd = { /* Voice add */ },
                    onAddProduct = { /* Add product dialog */ },
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            composable(Routes.REPORTS) {
                ReportsScreen(
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            composable(Routes.GOALS) {
                GoalsScreen(
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            composable(Routes.CUSTOMERS) {
                CustomersScreen(
                    customers = customers,
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            composable(Routes.SERVICES) {
                ServicesScreen(
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            composable(Routes.JOBS) {
                JobsScreen(
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            composable(Routes.HARVEST) {
                HarvestScreen(
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            composable(Routes.PRICING) {
                PricingScreen(
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            // ── Financial Screens ──
            composable(Routes.CREDIT) {
                CreditScreen(
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            composable(Routes.CHAMA) {
                ChamaScreen(
                    chamas = chamas,
                    members = chamaMembers,
                    contributions = chamaContributions,
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            // ── System Screens ──
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onComplete = { businessType, language, name ->
                        onOnboardingComplete(businessType, language, name)
                        navController.navigate(Routes.VOICE) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
// More Menu Screen
// Grid of all secondary screens
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreMenuScreen(
    onNavigate: (String) -> Unit
) {
    val colors = com.msaidizi.app.ui.designsystem.MsaidiziThemeTokens.colors

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Zaidi",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary
                        )
                        Text(
                            "More",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface)
            )
        }
    ) { innerPadding ->
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            val chunked = moreMenuItems.chunked(2)
            items(chunked.size) { rowIndex ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                ) {
                    chunked[rowIndex].forEach { item ->
                        MoreMenuCard(
                            item = item,
                            onClick = { onNavigate(item.route) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (chunked[rowIndex].size < 2) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreMenuCard(
    item: MoreMenuItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = com.msaidizi.app.ui.designsystem.MsaidiziThemeTokens.colors

    Card(
        modifier = modifier,
        onClick = onClick,
        shape = com.msaidizi.app.ui.designsystem.MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            Text(text = item.iconSw, style = MaterialTheme.typography.headlineLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = item.labelSw,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.primary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Text(
                text = item.labelEn,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
