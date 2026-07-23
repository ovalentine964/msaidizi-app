package com.msaidizi.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * SettingsScreen — App settings and preferences.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "⚙️ Mipangilio",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Language setting
        Text(text = "Lugha", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = uiState.language == "sw",
                onClick = { viewModel.setLanguage("sw") },
                label = { Text("Kiswahili") }
            )
            FilterChip(
                selected = uiState.language == "en",
                onClick = { viewModel.setLanguage("en") },
                label = { Text("English") }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Voice settings
        Text(text = "Sauti", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Sauti ya Msaidizi", fontSize = 14.sp)
            Switch(
                checked = uiState.voiceEnabled,
                onCheckedChange = { viewModel.setVoiceEnabled(it) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Business type
        Text(text = "Aina ya Biashara", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        val businessTypes = listOf("Duka", "Kilimo", "Usafiri", "Huduma", "Kidijitali", "Nyingine")
        businessTypes.forEach { type ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = type, fontSize = 14.sp)
                RadioButton(
                    selected = uiState.businessType == type,
                    onClick = { viewModel.setBusinessType(type) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Metrics / Debug
        Text(text = "Viashiria", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { viewModel.showMetrics() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("📊 Onyesha Viashiria vya Utendaji")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = { viewModel.clearData() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("🗑️ Futa Data Yote")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Version info
        Text(
            text = "Msaidizi v2.0.0 — Super Agent",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
