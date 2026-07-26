package com.msaidizi.app.ui.designsystem.inputs

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.msaidizi.app.ui.designsystem.MsaidiziThemeTokens
import com.msaidizi.app.ui.designsystem.MsaidiziShapes
import com.msaidizi.app.ui.designsystem.TouchTarget

// ──────────────────────────────────────────────
// Voice Input States
// ──────────────────────────────────────────────

enum class VoiceInputState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING,
    ERROR
}

// ──────────────────────────────────────────────
// Voice Input Component
// Microphone button with waveform visualization
// ──────────────────────────────────────────────

@Composable
fun VoiceInput(
    state: VoiceInputState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    transcript: String = "",
    errorMessage: String? = null,
    hint: String = "Gusa kusema"
) {
    val colors = MsaidiziThemeTokens.colors

    val buttonColor = when (state) {
        VoiceInputState.IDLE -> colors.voiceIdle
        VoiceInputState.LISTENING -> colors.voiceListening
        VoiceInputState.PROCESSING -> colors.voiceProcessing
        VoiceInputState.SPEAKING -> colors.voiceSpeaking
        VoiceInputState.ERROR -> colors.voiceError
    }

    // Pulse animation for listening
    val infiniteTransition = rememberInfiniteTransition(label = "voicePulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = if (state == VoiceInputState.LISTENING) 0.8f else 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Waveform (when listening)
        if (state == VoiceInputState.LISTENING || state == VoiceInputState.SPEAKING) {
            WaveformVisualization(
                isActive = state == VoiceInputState.LISTENING,
                color = buttonColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 32.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Microphone button
        Box(contentAlignment = Alignment.Center) {
            // Pulse ring
            if (state == VoiceInputState.LISTENING) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(buttonColor.copy(alpha = pulseAlpha * 0.3f))
                )
            }

            FloatingActionButton(
                onClick = onToggle,
                modifier = Modifier.size(TouchTarget.voiceButton),
                containerColor = buttonColor,
                contentColor = Color.White,
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 8.dp,
                    pressedElevation = 12.dp
                )
            ) {
                when (state) {
                    VoiceInputState.IDLE -> Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Sema",
                        modifier = Modifier.size(36.dp)
                    )
                    VoiceInputState.LISTENING -> Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Nasikiliza",
                        modifier = Modifier.size(36.dp)
                    )
                    VoiceInputState.PROCESSING -> CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = Color.White,
                        strokeWidth = 3.dp
                    )
                    VoiceInputState.SPEAKING -> Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Nasema",
                        modifier = Modifier.size(36.dp)
                    )
                    VoiceInputState.ERROR -> Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Kosa",
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Status text
        val statusText = when (state) {
            VoiceInputState.IDLE -> hint
            VoiceInputState.LISTENING -> "Nasikiliza..."
            VoiceInputState.PROCESSING -> "Nafikiria..."
            VoiceInputState.SPEAKING -> "Nasema..."
            VoiceInputState.ERROR -> errorMessage ?: "Kosa limetokea"
        }

        Text(
            text = statusText,
            style = MsaidiziThemeTokens.typography.caption,
            color = if (state == VoiceInputState.ERROR) colors.error else colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        // Transcript display
        if (transcript.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                shape = MsaidiziShapes().medium,
                colors = CardDefaults.cardColors(
                    containerColor = colors.surfaceVariant
                )
            ) {
                Text(
                    text = transcript,
                    style = MsaidiziThemeTokens.typography.voiceTranscript,
                    color = colors.onSurface,
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
// Waveform Visualization
// ──────────────────────────────────────────────

@Composable
private fun WaveformVisualization(
    isActive: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 32
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")

    val amplitudes = List(barCount) { index ->
        infiniteTransition.animateFloat(
            initialValue = 0.15f,
            targetValue = if (isActive) {
                0.3f + (kotlin.math.sin(index * 0.5) * 0.3f + 0.3f) * 0.4f
            } else 0.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 200 + (index * 30),
                    easing = EaseInOutCubic
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "amp$index"
        )
    }

    Canvas(modifier = modifier) {
        val barWidth = size.width / (barCount * 2)
        val centerY = size.height / 2

        amplitudes.forEachIndexed { index, animatable ->
            val amp = animatable.value
            val x = (index * 2 + 1) * barWidth
            val barHeight = size.height * amp

            drawLine(
                color = color.copy(alpha = if (isActive) 0.8f else 0.3f),
                start = Offset(x, centerY - barHeight / 2),
                end = Offset(x, centerY + barHeight / 2),
                strokeWidth = barWidth * 0.8f,
                cap = StrokeCap.Round
            )
        }
    }
}
