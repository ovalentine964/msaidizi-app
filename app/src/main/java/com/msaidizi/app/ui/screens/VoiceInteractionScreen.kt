package com.msaidizi.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.msaidizi.core.model.ChatMessage
import com.msaidizi.core.model.MessageRole
import com.msaidizi.core.model.VoiceState
import com.msaidizi.app.ui.components.VoiceFAB
import com.msaidizi.app.ui.components.VoiceState as VoiceStateEnum
import com.msaidizi.app.ui.components.VoiceWaveform
import com.msaidizi.app.ui.components.AlertBanner
import com.msaidizi.app.ui.components.AlertSeverity
import com.msaidizi.app.ui.designsystem.*

// ──────────────────────────────────────────────
// Voice Interaction Screen — THE PRIMARY SCREEN
// Voice-first, always-on, the heart of Msaidizi
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceInteractionScreen(
    voiceState: VoiceState = VoiceState(),
    messages: List<ChatMessage> = emptyList(),
    onVoiceToggle: () -> Unit = {},
    onTextSubmit: (String) -> Unit = {},
    onQuickAction: (String) -> Unit = {},
    onNavigate: (String) -> Unit = {}
) {
    val colors = MsaidiziThemeTokens.colors
    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(colors.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SmartToy,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Msaidizi",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = colors.primary
                            )
                            Text(
                                text = voiceStatusText(voiceState),
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    voiceState.isListening -> colors.success
                                    voiceState.isProcessing -> colors.warning
                                    voiceState.isSpeaking -> colors.info
                                    voiceState.error != null -> colors.error
                                    else -> colors.onSurfaceVariant
                                }
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { onNavigate("settings") }) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "Mipangilio — Settings",
                            tint = colors.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ── Chat Messages ──
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Welcome message if empty
                if (messages.isEmpty()) {
                    item {
                        WelcomeCard(onQuickAction = onQuickAction)
                    }
                }

                items(messages) { message ->
                    ChatBubble(message = message)
                }
            }

            // ── Waveform (when listening/speaking) ──
            AnimatedVisibility(
                visible = voiceState.isListening || voiceState.isSpeaking,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                VoiceWaveform(
                    isActive = voiceState.isListening,
                    color = if (voiceState.isListening) colors.voiceListening else colors.voiceSpeaking,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                )
            }

            // ── Transcript Display ──
            if (voiceState.partialText.isNotBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = MsaidiziShapes().medium,
                    colors = CardDefaults.cardColors(
                        containerColor = colors.surfaceVariant
                    )
                ) {
                    Text(
                        text = voiceState.partialText,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(12.dp),
                        color = colors.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ── Quick Action Chips ──
            QuickActionRow(onQuickAction = onQuickAction)

            // ── Voice / Text Input Bar ──
            VoiceInputBar(
                voiceState = voiceState,
                onVoiceToggle = onVoiceToggle,
                onTextSubmit = onTextSubmit
            )
        }
    }
}

// ──────────────────────────────────────────────
// Welcome Card (first-time / empty state)
// ──────────────────────────────────────────────

@Composable
private fun WelcomeCard(
    onQuickAction: (String) -> Unit
) {
    val colors = MsaidiziThemeTokens.colors

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "👋 Karibu!",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Welcome!",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Bonyeza mikrofoni na uniambie unachohitaji",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = colors.onPrimaryContainer
            )
            Text(
                text = "Tap the microphone and tell me what you need",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = colors.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Suggested first commands
            val suggestions = listOf(
                "Nimeuza nyanya" to "I sold tomatoes",
                "Nataka kuona faida" to "Show me profit",
                "Bei ya sukuma ni ngapi?" to "What's the price of sukuma?"
            )
            suggestions.forEach { (sw, en) ->
                SuggestionChip(
                    onClick = { onQuickAction(sw) },
                    label = {
                        Column {
                            Text(sw, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(en, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    shape = MsaidiziShapes().medium,
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = colors.surface
                    )
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
// Chat Bubble
// ──────────────────────────────────────────────

@Composable
private fun ChatBubble(message: ChatMessage) {
    val colors = MsaidiziThemeTokens.colors
    val isUser = message.role == MessageRole.USER

    val bubbleColor = if (isUser) colors.primary else colors.surface
    val textColor = if (isUser) colors.onPrimary else colors.onSurface
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Card(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (message.isProcessing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = textColor
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Nafikiri... — Thinking...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor.copy(alpha = 0.7f)
                        )
                    }
                } else {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor
                    )
                }

                if (message.isVoice) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = textColor.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Sauti — Voice",
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
// Quick Action Row
// ──────────────────────────────────────────────

@Composable
private fun QuickActionRow(
    onQuickAction: (String) -> Unit
) {
    val colors = MsaidiziThemeTokens.colors

    val actions = listOf(
        Triple(Icons.Default.PointOfSale, "Uza", "Sell"),
        Triple(Icons.Default.Inventory2, "Hifadhi", "Stock"),
        Triple(Icons.Default.TrendingUp, "Faida", "Profit"),
        Triple(Icons.Default.CreditCard, "Deni", "Debts"),
        Triple(Icons.Default.Groups, "Chama", "Chama")
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        actions.forEach { (icon, sw, en) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(MsaidiziShapes().medium)
                    .clickable { onQuickAction(sw) }
                    .padding(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(colors.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = sw,
                        tint = colors.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(sw, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Text(en, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = colors.onSurfaceVariant)
            }
        }
    }
}

// ──────────────────────────────────────────────
// Voice Input Bar
// ──────────────────────────────────────────────

@Composable
private fun VoiceInputBar(
    voiceState: VoiceState,
    onVoiceToggle: () -> Unit,
    onTextSubmit: (String) -> Unit
) {
    val colors = MsaidiziThemeTokens.colors
    var textInput by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp,
        color = colors.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Text input field
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = "Andika hapa... — Type here...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                shape = MsaidiziShapes().full,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.primary,
                    unfocusedBorderColor = colors.outlineVariant
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Send button (if text entered)
            if (textInput.isNotBlank()) {
                FloatingActionButton(
                    onClick = {
                        onTextSubmit(textInput)
                        textInput = ""
                    },
                    modifier = Modifier.size(56.dp),
                    containerColor = colors.tertiary,
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Tuma — Send",
                        tint = Color.White
                    )
                }
            } else {
                // Voice FAB
                VoiceFAB(
                    voiceState = if (voiceState.isListening) VoiceStateEnum.LISTENING
                    else if (voiceState.isProcessing) VoiceStateEnum.PROCESSING
                    else if (voiceState.isSpeaking) VoiceStateEnum.SPEAKING
                    else if (voiceState.error != null) VoiceStateEnum.ERROR
                    else VoiceStateEnum.IDLE,
                    onClick = onVoiceToggle
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────

private fun voiceStatusText(state: VoiceState): String {
    return when {
        state.isListening -> "Nasikiliza... — Listening..."
        state.isProcessing -> "Nafikiri... — Processing..."
        state.isSpeaking -> "Nasema... — Speaking..."
        state.error != null -> "Kosa: ${state.error}"
        else -> "Tayari — Ready"
    }
}
