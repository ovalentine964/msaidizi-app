package com.msaidizi.app.ui.mamamboga

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
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.msaidizi.app.model.*
import com.msaidizi.app.ui.screens.*

/**
 * SimplifiedNavigation — 5-screen navigation for mama mboga mode.
 *
 * Research showed 52 tools is overwhelming for mama mbogas.
 * This navigation exposes only 5 core screens:
 *   1. Home — today's summary (profit, sales, expenses)
 *   2. Record — quick transaction recording (voice or tap)
 *   3. Market — price lookup and demand forecasting
 *   4. Credit — Alama Score and credit readiness
 *   5. Reports — daily/weekly/monthly reports
 *
 * All other features are accessible from "Zaidi" (More) within each screen.
 */
@Composable
fun SimplifiedAppNavigation(
    dashboardState: DashboardState = DashboardState(),
    transactions: List<SaleEntity> = emptyList(),
    products: List<ProductEntity> = emptyList(),
    onRecordSale: () -> Unit = {},
    onRecordExpense: () -> Unit = {},
    onNavigate: (String) -> Unit = {}
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            MamaMbogaBottomBar(
                currentRoute = currentRoute ?: SimplifiedTab.HOME.route,
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
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = SimplifiedTab.HOME.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            // ── 1. HOME — Today's Summary ──
            composable(SimplifiedTab.HOME.route) {
                MamaMbogaHomeScreen(
                    dashboardState = dashboardState,
                    onRecordSale = onRecordSale,
                    onRecordExpense = onRecordExpense,
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            // ── 2. RECORD — Quick Transaction Recording ──
            composable(SimplifiedTab.RECORD.route) {
                MamaMbogaRecordScreen(
                    onRecordSale = onRecordSale,
                    onRecordExpense = onRecordExpense,
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            // ── 3. MARKET — Price Lookup & Demand ──
            composable(SimplifiedTab.MARKET.route) {
                MamaMbogaMarketScreen(
                    products = products,
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            // ── 4. CREDIT — Alama Score ──
            composable(SimplifiedTab.CREDIT.route) {
                CreditScreen(
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            // ── 5. REPORTS — Daily/Weekly/Monthly ──
            composable(SimplifiedTab.REPORTS.route) {
                MamaMbogaReportsScreen(
                    dashboardState = dashboardState,
                    transactions = transactions,
                    onNavigate = { route -> navController.navigate(route) }
                )
            }
        }
    }
}

/**
 * Bottom navigation bar with 5 tabs for mama mboga mode.
 * Large, touch-friendly icons with Swahili labels.
 */
@Composable
fun MamaMbogaBottomBar(
    currentRoute: String,
    onNavigate: (String) -> Unit
) {
    val colors = com.msaidizi.app.ui.designsystem.MsaidiziThemeTokens.colors

    NavigationBar(
        containerColor = colors.surface,
        tonalElevation = 8.dp
    ) {
        SimplifiedTab.entries.forEach { tab ->
            val selected = currentRoute == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(tab.route) },
                icon = {
                    Text(
                        text = tab.iconSw,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                label = {
                    Text(
                        text = tab.labelSw,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.primary,
                    selectedTextColor = colors.primary,
                    indicatorColor = colors.primaryContainer,
                    unselectedIconColor = colors.onSurfaceVariant,
                    unselectedTextColor = colors.onSurfaceVariant
                )
            )
        }
    }
}
