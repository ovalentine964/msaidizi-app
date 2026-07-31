package com.msaidizi.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.msaidizi.core.model.BusinessType
import com.msaidizi.core.model.Language
import com.msaidizi.app.ui.components.*
import com.msaidizi.app.ui.designsystem.*

// ──────────────────────────────────────────────
// Onboarding Screen
// First-time setup: business type, language, voice calibration
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onComplete: (businessType: BusinessType, language: Language, name: String) -> Unit = { _, _, _ -> }
) {
    val colors = MsaidiziThemeTokens.colors
    var currentStep by remember { mutableIntStateOf(0) }
    var selectedBusinessType by remember { mutableStateOf<BusinessType?>(null) }
    var selectedLanguage by remember { mutableStateOf(Language.KISWAHILI) }
    var userName by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Karibu Msaidizi",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ── Progress Indicator ──
            LinearProgressIndicator(
                progress = { (currentStep + 1).toFloat() / 4 },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = colors.primary,
                trackColor = colors.primary.copy(alpha = 0.15f)
            )

            // ── Step Content ──
            when (currentStep) {
                0 -> WelcomeStep(
                    onNext = { currentStep = 1 }
                )
                1 -> LanguageStep(
                    selectedLanguage = selectedLanguage,
                    onLanguageSelected = { selectedLanguage = it },
                    onNext = { currentStep = 2 },
                    onBack = { currentStep = 0 }
                )
                2 -> BusinessTypeStep(
                    selectedType = selectedBusinessType,
                    selectedCategory = selectedCategory,
                    onCategorySelected = { selectedCategory = it },
                    onTypeSelected = { selectedBusinessType = it },
                    onNext = { if (selectedBusinessType != null) currentStep = 3 },
                    onBack = { currentStep = 1 }
                )
                3 -> VoiceCalibrationStep(
                    userName = userName,
                    onNameChange = { userName = it },
                    onComplete = {
                        onComplete(
                            selectedBusinessType ?: BusinessType.OTHER,
                            selectedLanguage,
                            userName
                        )
                    },
                    onBack = { currentStep = 2 }
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
// Step 0: Welcome
// ──────────────────────────────────────────────

@Composable
private fun WelcomeStep(
    onNext: () -> Unit
) {
    val colors = MsaidiziThemeTokens.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("👋", style = MaterialTheme.typography.displayLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Karibu Msaidizi!",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = colors.primary,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Welcome to Msaidizi!",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Msaidizi ni msaidizi wako wa biashara. Nitakusaidia kurekodi mauzo, kufuatilia faida, na kupata ushauri.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = colors.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Msaidizi is your business assistant. I'll help you record sales, track profit, and get advice.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = colors.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "Sema au bonyeza — unaweza kutumia sauti au skrini",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = colors.onSurfaceVariant
        )
        Text(
            text = "Speak or tap — you can use voice or the screen",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = colors.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(48.dp))
        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = MsaidiziShapes().large,
            colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
        ) {
            Text("Anza — Start", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

// ──────────────────────────────────────────────
// Step 1: Language Selection
// ──────────────────────────────────────────────

@Composable
private fun LanguageStep(
    selectedLanguage: Language,
    onLanguageSelected: (Language) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val colors = MsaidiziThemeTokens.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = "Chagua Lugha Yako",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = colors.primary
        )
        Text(
            text = "Choose Your Language",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(Language.entries.toList()) { language ->
                LanguageOption(
                    language = language,
                    selected = selectedLanguage == language,
                    onClick = { onLanguageSelected(language) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = MsaidiziShapes().large
            ) {
                Text("Rudi — Back")
            }
            Button(
                onClick = onNext,
                modifier = Modifier
                    .weight(2f)
                    .height(56.dp),
                shape = MsaidiziShapes().large,
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
            ) {
                Text("Endelea — Continue", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LanguageOption(
    language: Language,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = MsaidiziThemeTokens.colors

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = MsaidiziShapes().medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) colors.primaryContainer else colors.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (selected) 4.dp else 1.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = colors.primary)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = language.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) colors.onPrimaryContainer else colors.onSurface
            )
        }
    }
}

// ──────────────────────────────────────────────
// Step 2: Business Type Selection
// ──────────────────────────────────────────────

@Composable
private fun BusinessTypeStep(
    selectedType: BusinessType?,
    selectedCategory: String?,
    onCategorySelected: (String) -> Unit,
    onTypeSelected: (BusinessType) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val colors = MsaidiziThemeTokens.colors
    val categories = listOf("Biashara" to "Trade", "Usafiri" to "Transport", "Chakula" to "Food", "Huduma" to "Services", "Kilimo" to "Agriculture", "Ujenzi" to "Construction", "Dijitali" to "Digital", "Sanaa" to "Artisans")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = "Biashara Yako ni Nini?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = colors.primary
        )
        Text(
            text = "What is Your Business?",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Category chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(categories) { (sw, en) ->
                CategoryChip(
                    label = sw,
                    selected = selectedCategory == en,
                    onClick = { onCategorySelected(en) }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // Business types in selected category
        val filteredTypes = if (selectedCategory != null) {
            BusinessType.entries.filter { it.category == selectedCategory }
        } else {
            BusinessType.entries.toList()
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(filteredTypes) { type ->
                BusinessTypeOption(
                    type = type,
                    selected = selectedType == type,
                    onClick = { onTypeSelected(type) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = MsaidiziShapes().large
            ) {
                Text("Rudi — Back")
            }
            Button(
                onClick = onNext,
                modifier = Modifier
                    .weight(2f)
                    .height(56.dp),
                enabled = selectedType != null,
                shape = MsaidiziShapes().large,
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
            ) {
                Text("Endelea — Continue", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun BusinessTypeOption(
    type: BusinessType,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = MsaidiziThemeTokens.colors

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = MsaidiziShapes().medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) colors.primaryContainer else colors.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (selected) 4.dp else 1.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = colors.primary)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = type.swahiliName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) colors.onPrimaryContainer else colors.onSurface
                )
                Text(
                    text = type.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) colors.onPrimaryContainer.copy(alpha = 0.7f) else colors.onSurfaceVariant
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
// Step 3: Voice Calibration & Name
// ──────────────────────────────────────────────

@Composable
private fun VoiceCalibrationStep(
    userName: String,
    onNameChange: (String) -> Unit,
    onComplete: () -> Unit,
    onBack: () -> Unit
) {
    val colors = MsaidiziThemeTokens.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = "Jina Lako",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = colors.primary
        )
        Text(
            text = "Your Name",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = userName,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Andika jina lako... — Enter your name...") },
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = "Jina la mtumiaji") },
            shape = MsaidiziShapes().large,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.primary,
                unfocusedBorderColor = colors.outlineVariant
            )
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MsaidiziShapes().large,
            colors = CardDefaults.cardColors(containerColor = colors.infoContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("🎤 Sauti", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onInfoContainer)
                Text("Voice Setup", style = MaterialTheme.typography.bodySmall, color = colors.onInfoContainer.copy(alpha = 0.7f))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Msaidizi anaweza kusikia sauti yako. Jaribu kusema:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onInfoContainer
                )
                Text(
                    text = "Msaidizi can hear your voice. Try saying:",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onInfoContainer.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "\"Habari, mimi ni ${userName.ifBlank { "___" }}\"",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.onInfoContainer
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = MsaidiziShapes().large
            ) {
                Text("Rudi — Back")
            }
            Button(
                onClick = onComplete,
                modifier = Modifier
                    .weight(2f)
                    .height(56.dp),
                enabled = userName.isNotBlank(),
                shape = MsaidiziShapes().large,
                colors = ButtonDefaults.buttonColors(containerColor = colors.success)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Imekamilika")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Anza Kuuza! — Start Selling!", fontWeight = FontWeight.Bold)
            }
        }
    }
}
