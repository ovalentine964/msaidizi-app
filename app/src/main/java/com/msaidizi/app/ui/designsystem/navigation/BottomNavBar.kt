package com.msaidizi.app.ui.designsystem.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msaidizi.app.ui.designsystem.MsaidiziThemeTokens

// ──────────────────────────────────────────────
// Navigation Items
// ──────────────────────────────────────────────

data class MsaidiziNavItem(
    val route: String,
    val labelSw: String,
    val labelEn: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val badge: Int = 0 // 0 = no badge
)

// ──────────────────────────────────────────────
// Default Nav Items
// ──────────────────────────────────────────────

val defaultNavItems = listOf(
    MsaidiziNavItem(
        route = "voice",
        labelSw = "Sema",
        labelEn = "Speak",
        selectedIcon = Icons.Filled.Mic,
        unselectedIcon = Icons.Outlined.Mic
    ),
    MsaidiziNavItem(
        route = "dashboard",
        labelSw = "Dashibodi",
        labelEn = "Home",
        selectedIcon = Icons.Filled.Dashboard,
        unselectedIcon = Icons.Outlined.Dashboard
    ),
    MsaidiziNavItem(
        route = "transactions",
        labelSw = "Miamala",
        labelEn = "Sales",
        selectedIcon = Icons.Filled.Receipt,
        unselectedIcon = Icons.Outlined.Receipt
    ),
    MsaidiziNavItem(
        route = "inventory",
        labelSw = "Stock",
        labelEn = "Stock",
        selectedIcon = Icons.Filled.Inventory2,
        unselectedIcon = Icons.Outlined.Inventory2
    ),
    MsaidiziNavItem(
        route = "more",
        labelSw = "Zaidi",
        labelEn = "More",
        selectedIcon = Icons.Filled.MoreHoriz,
        unselectedIcon = Icons.Outlined.MoreHoriz
    )
)

// ──────────────────────────────────────────────
// Bottom Navigation Bar
// 5 tabs with icons + badges
// ──────────────────────────────────────────────

@Composable
fun MsaidiziBottomNavBar(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    items: List<MsaidiziNavItem> = defaultNavItems
) {
    val colors = MsaidiziThemeTokens.colors

    NavigationBar(
        modifier = modifier,
        containerColor = colors.surface,
        tonalElevation = 8.dp
    ) {
        items.forEach { item ->
            val selected = currentRoute == item.route

            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(item.route) },
                icon = {
                    BadgedBox(
                        badge = {
                            if (item.badge > 0) {
                                Badge(
                                    containerColor = colors.error,
                                    contentColor = colors.onError
                                ) {
                                    Text(
                                        text = if (item.badge > 99) "99+" else "${item.badge}",
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                            contentDescription = item.labelSw
                        )
                    }
                },
                label = {
                    Text(
                        text = item.labelSw,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
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
