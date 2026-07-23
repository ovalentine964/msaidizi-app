package com.msaidizi.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.msaidizi.app.ui.dashboard.DashboardScreen
import com.msaidizi.app.ui.record.RecordScreen
import com.msaidizi.app.ui.history.HistoryScreen
import com.msaidizi.app.ui.settings.SettingsScreen
import com.msaidizi.app.ui.gamification.GamificationScreen
import com.msaidizi.app.ui.goals.GoalsScreen
import com.msaidizi.app.ui.loans.LoansScreen
import com.msaidizi.app.ui.tithe.TitheScreen
import com.msaidizi.app.ui.mindset.MindsetScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MsaidiziTheme {
                MsaidiziNavHost()
            }
        }
    }
}

@Composable
fun MsaidiziNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Bottom nav items (4 main tabs + More)
    val bottomNavItems = listOf(
        Triple("dashboard", "📊", "Nyumbani"),
        Triple("record", "📝", "Rekodi"),
        Triple("history", "📋", "Historia"),
        Triple("settings", "⚙️", "Mipangilio")
    )

    // Extended destinations accessible via More menu
    val extendedDestinations = listOf(
        Triple("goals", "🎯", "Malengo"),
        Triple("loans", "💳", "Mikopo"),
        Triple("gamification", "🏆", "Mafanikio"),
        Triple("tithe", "🤝", "Zaka"),
        Triple("mindset", "🧠", "Mtazamo")
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomNavItems.forEach { (route, icon, label) ->
                    NavigationBarItem(
                        icon = { Text(icon) },
                        label = {
                            Text(
                                label,
                                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                                maxLines = 1,
                                textAlign = TextAlign.Center
                            )
                        },
                        selected = currentRoute == route,
                        onClick = {
                            if (currentRoute != route) {
                                navController.navigate(route) {
                                    popUpTo("dashboard") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    )
                }
                // More menu for extended destinations
                NavigationBarItem(
                    icon = { Text("⋯") },
                    label = {
                        Text(
                            "Zaidi",
                            fontSize = MaterialTheme.typography.labelSmall.fontSize,
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                    },
                    selected = currentRoute in extendedDestinations.map { it.first },
                    onClick = { navController.navigate("more") }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(padding)
        ) {
            // Main bottom nav destinations
            composable("dashboard") { DashboardScreen(navController = navController) }
            composable("record") { RecordScreen() }
            composable("history") { HistoryScreen() }
            composable("settings") { SettingsScreen() }

            // Extended destinations
            composable("goals") { GoalsScreen() }
            composable("loans") { LoansScreen() }
            composable("gamification") { GamificationScreen() }
            composable("tithe") { TitheScreen() }
            composable("mindset") { MindsetScreen() }
            composable("more") {
                MoreScreen(
                    destinations = extendedDestinations,
                    onNavigate = { route -> navController.navigate(route) }
                )
            }
        }
    }
}

@Composable
fun MoreScreen(
    destinations: List<Triple<String, String, String>>,
    onNavigate: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "🔗 Zaidi",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        destinations.forEach { (route, icon, label) ->
            TextButton(
                onClick = { onNavigate(route) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text("$icon  $label", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
fun MsaidiziTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF1B4965),     // Navy Blue — brand primary
            secondary = androidx.compose.ui.graphics.Color(0xFFE8A838),   // Gold — brand accent
            tertiary = androidx.compose.ui.graphics.Color(0xFFE8853D),    // African Orange — energy
            onPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),   // White on navy
            onSecondary = androidx.compose.ui.graphics.Color(0xFF1B4965), // Navy on gold
            surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
            background = androidx.compose.ui.graphics.Color(0xFFF8F9FA),
            onSurface = androidx.compose.ui.graphics.Color(0xFF1a1a1a),
            error = androidx.compose.ui.graphics.Color(0xFFF44336)
        ),
        content = content
    )
}
