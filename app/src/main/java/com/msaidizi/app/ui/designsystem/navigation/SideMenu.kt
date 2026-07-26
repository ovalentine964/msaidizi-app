package com.msaidizi.app.ui.designsystem.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msaidizi.app.ui.designsystem.MsaidiziThemeTokens
import com.msaidizi.app.ui.designsystem.MsaidiziShapes
import com.msaidizi.app.ui.designsystem.TouchTarget

// ──────────────────────────────────────────────
// Side Menu Item
// ──────────────────────────────────────────────

data class SideMenuItem(
    val icon: ImageVector,
    val labelSw: String,
    val labelEn: String,
    val route: String,
    val badge: Int = 0
)

// ──────────────────────────────────────────────
// Side Menu / Drawer
// Settings, help, profile
// ──────────────────────────────────────────────

@Composable
fun MsaidiziSideMenu(
    userName: String,
    businessName: String,
    currentRoute: String,
    onNavigate: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MsaidiziThemeTokens.colors

    val menuItems = listOf(
        SideMenuItem(Icons.Default.Person, "Wasifu", "Profile", "profile"),
        SideMenuItem(Icons.Default.Business, "Biashara", "Business", "business"),
        SideMenuItem(Icons.Default.BarChart, "Ripoti", "Reports", "reports"),
        SideMenuItem(Icons.Default.Group, "Wateja", "Customers", "customers"),
        SideMenuItem(Icons.Default.Savings, "Akiba", "Savings", "savings"),
        SideMenuItem(Icons.Default.Settings, "Mipangilio", "Settings", "settings"),
        SideMenuItem(Icons.Default.HelpOutline, "Msaada", "Help", "help")
    )

    ModalDrawerSheet(
        modifier = modifier.width(300.dp),
        drawerContainerColor = colors.surface
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.primaryContainer)
                .padding(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(colors.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = userName.take(1).uppercase(),
                    style = MsaidiziThemeTokens.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.onPrimary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = userName,
                style = MsaidiziThemeTokens.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colors.onPrimaryContainer
            )
            Text(
                text = businessName,
                style = MsaidiziThemeTokens.typography.bodyMedium,
                color = colors.onPrimaryContainer.copy(alpha = 0.8f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Menu items
        menuItems.forEach { item ->
            val selected = currentRoute == item.route

            NavigationDrawerItem(
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.labelSw,
                            style = MsaidiziThemeTokens.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = item.labelEn,
                            style = MsaidiziThemeTokens.typography.caption,
                            color = colors.onSurfaceVariant
                        )
                    }
                },
                selected = selected,
                onClick = {
                    onNavigate(item.route)
                    onClose()
                },
                icon = {
                    BadgedBox(
                        badge = {
                            if (item.badge > 0) {
                                Badge(containerColor = colors.error) {
                                    Text("${item.badge}")
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.labelSw
                        )
                    }
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = colors.primaryContainer,
                    selectedIconColor = colors.primary,
                    selectedTextColor = colors.onPrimaryContainer
                ),
                shape = MsaidiziShapes().medium
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Version info
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            HorizontalDivider(color = colors.divider)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Msaidizi v1.0",
                style = MsaidiziThemeTokens.typography.caption,
                color = colors.onSurfaceVariant
            )
        }
    }
}
