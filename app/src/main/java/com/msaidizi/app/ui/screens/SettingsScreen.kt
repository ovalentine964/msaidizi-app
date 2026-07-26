package com.msaidizi.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msaidizi.app.model.Language
import com.msaidizi.app.ui.components.*
import com.msaidizi.app.ui.designsystem.*

// ──────────────────────────────────────────────
// Settings Screen
// Language, voice preferences, notifications, security
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentLanguage: String = "sw",
    voiceEnabled: Boolean = true,
    onLanguageChange: (String) -> Unit = {},
    onVoiceToggle: (Boolean) -> Unit = {},
    onNavigate: (String) -> Unit = {}
) {
    val colors = MsaidiziThemeTokens.colors
    var notificationsEnabled by remember { mutableStateOf(true) }
    var darkModeEnabled by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Mipangilio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.primary)
                        Text("Settings", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
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
            // ── Profile Section ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MsaidiziShapes().large,
                    colors = CardDefaults.cardColors(containerColor = colors.primaryContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = colors.onPrimaryContainer,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("Jina Lako", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
                            Text("Your Name", style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer.copy(alpha = 0.7f))
                            Text("Mama Mboga — Duka la Mboga", style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer.copy(alpha = 0.6f))
                        }
                    }
                }
            }

            // ── Language ──
            item {
                SettingsSection(titleSw = "Lugha", titleEn = "Language")
            }
            item {
                LanguageSelector(currentLanguage = currentLanguage, onLanguageChange = onLanguageChange)
            }

            // ── Voice Settings ──
            item {
                SettingsSection(titleSw = "Sauti", titleEn = "Voice")
            }
            item {
                SettingsToggleItem(
                    icon = Icons.Default.Mic,
                    titleSw = "Sauti Imewashwa",
                    titleEn = "Voice Enabled",
                    subtitleSw = "Amilisha amri za sauti",
                    subtitleEn = "Activate voice commands",
                    checked = voiceEnabled,
                    onCheckedChange = onVoiceToggle
                )
            }
            item {
                SettingsClickableItem(
                    icon = Icons.Default.RecordVoiceOver,
                    titleSw = "Sauti ya Msaidizi",
                    titleEn = "Assistant Voice",
                    subtitleSw = "Chagua sauti unayopenda",
                    subtitleEn = "Choose preferred voice"
                )
            }
            item {
                SettingsClickableItem(
                    icon = Icons.Default.Speed,
                    titleSw = "Kasi ya Sauti",
                    titleEn = "Voice Speed",
                    subtitleSw = "Kawaida — Normal",
                    subtitleEn = "Adjust speech rate"
                )
            }

            // ── Notifications ──
            item {
                SettingsSection(titleSw = "Arifa", titleEn = "Notifications")
            }
            item {
                SettingsToggleItem(
                    icon = Icons.Default.Notifications,
                    titleSw = "Arifa Zimewashwa",
                    titleEn = "Notifications Enabled",
                    subtitleSw = "Arifa za mauzo, deni, na chama",
                    subtitleEn = "Sales, debts, and chama alerts",
                    checked = notificationsEnabled,
                    onCheckedChange = { notificationsEnabled = it }
                )
            }
            item {
                SettingsToggleItem(
                    icon = Icons.Default.Restaurant,
                    titleSw = "Bidhaa Zinazoisha",
                    titleEn = "Low Stock Alerts",
                    subtitleSw = "Arifa wakati bidhaa inakaribia kuisha",
                    subtitleEn = "Alert when products are running low",
                    checked = true,
                    onCheckedChange = {}
                )
            }
            item {
                SettingsToggleItem(
                    icon = Icons.Default.CreditCard,
                    titleSw = "Deni Zinazokaribia",
                    titleEn = "Debt Reminders",
                    subtitleSw = "Kumbuka deni zinazodaiwa",
                    subtitleEn = "Remind about debts owed to you",
                    checked = true,
                    onCheckedChange = {}
                )
            }

            // ── Security ──
            item {
                SettingsSection(titleSw = "Usalama", titleEn = "Security")
            }
            item {
                SettingsClickableItem(
                    icon = Icons.Default.Lock,
                    titleSw = "Badilisha PIN",
                    titleEn = "Change PIN",
                    subtitleSw = "Weka PIN mpya ya usalama",
                    subtitleEn = "Set a new security PIN"
                )
            }
            item {
                SettingsToggleItem(
                    icon = Icons.Default.Fingerprint,
                    titleSw = "Sidirialo",
                    titleEn = "Fingerprint",
                    subtitleSw = "Fungua kwa kidole",
                    subtitleEn = "Unlock with fingerprint",
                    checked = false,
                    onCheckedChange = {}
                )
            }

            // ── Data ──
            item {
                SettingsSection(titleSw = "Data", titleEn = "Data")
            }
            item {
                SettingsClickableItem(
                    icon = Icons.Default.CloudUpload,
                    titleSw = "Safisha Data",
                    titleEn = "Backup Data",
                    subtitleSw = "Hifadhi data yako mtandaoni",
                    subtitleEn = "Save your data to the cloud"
                )
            }
            item {
                SettingsClickableItem(
                    icon = Icons.Default.Download,
                    titleSw = "Pakua Data",
                    titleEn = "Export Data",
                    subtitleSw = "Pakua ripoti za biashara",
                    subtitleEn = "Download business reports"
                )
            }

            // ── About ──
            item {
                SettingsSection(titleSw = "Kuhusu", titleEn = "About")
            }
            item {
                SettingsClickableItem(
                    icon = Icons.Default.Info,
                    titleSw = "Msaidizi v1.0.0",
                    titleEn = "Msaidizi v1.0.0",
                    subtitleSw = "Msaidizi wako wa biashara",
                    subtitleEn = "Your business assistant"
                )
            }
            item {
                SettingsClickableItem(
                    icon = Icons.Default.Help,
                    titleSw = "Msaada",
                    titleEn = "Help",
                    subtitleSw = "Amri za sauti na maswali",
                    subtitleEn = "Voice commands and FAQ"
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SettingsSection(titleSw: String, titleEn: String) {
    val colors = MsaidiziThemeTokens.colors
    Column {
        Text(titleSw, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.primary)
        Text(titleEn, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun SettingsToggleItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    titleSw: String,
    titleEn: String,
    subtitleSw: String,
    subtitleEn: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
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
            Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(titleSw, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitleSw, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onPrimary,
                    checkedTrackColor = colors.primary
                )
            )
        }
    }
}

@Composable
private fun SettingsClickableItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    titleSw: String,
    titleEn: String,
    subtitleSw: String,
    subtitleEn: String
) {
    val colors = MsaidiziThemeTokens.colors

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { },
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
            Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(titleSw, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitleSw, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun LanguageSelector(
    currentLanguage: String,
    onLanguageChange: (String) -> Unit
) {
    val colors = MsaidiziThemeTokens.colors
    val languages = listOf(
        "sw" to "Kiswahili",
        "en" to "English",
        "sheng" to "Sheng",
        "ki" to "Kikuyu",
        "luo" to "Dholuo",
        "kln" to "Kalenjin"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().medium,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            languages.forEach { (code, name) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = currentLanguage == code,
                        onClick = { onLanguageChange(code) },
                        colors = RadioButtonDefaults.colors(selectedColor = colors.primary)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
