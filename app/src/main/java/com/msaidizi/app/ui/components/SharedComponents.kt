package com.msaidizi.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msaidizi.app.ui.designsystem.*

// ──────────────────────────────────────────────
// Voice Floating Action Button
// Always-visible, never hidden, primary interaction
// ──────────────────────────────────────────────

enum class VoiceState {
    IDLE, LISTENING, PROCESSING, SPEAKING, ERROR
}

@Composable
fun VoiceFAB(
    voiceState: VoiceState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MsaidiziThemeTokens.colors

    val bgColor = when (voiceState) {
        VoiceState.IDLE -> colors.voiceIdle
        VoiceState.LISTENING -> colors.voiceListening
        VoiceState.PROCESSING -> colors.voiceProcessing
        VoiceState.SPEAKING -> colors.voiceSpeaking
        VoiceState.ERROR -> colors.voiceError
    }

    // Pulsing animation for listening state
    val infiniteTransition = rememberInfiniteTransition(label = "voicePulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (voiceState == VoiceState.LISTENING) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Rotation for processing state
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (voiceState == VoiceState.PROCESSING) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Outer pulse ring (listening only)
        if (voiceState == VoiceState.LISTENING) {
            Box(
                modifier = Modifier
                    .size((80 * pulseScale).dp)
                    .clip(CircleShape)
                    .background(bgColor.copy(alpha = 0.2f))
            )
        }

        // Main button
        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier.size(TouchTarget.voiceButton),
            containerColor = bgColor,
            contentColor = Color.White,
            shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 8.dp,
                pressedElevation = 12.dp
            )
        ) {
            when (voiceState) {
                VoiceState.IDLE -> {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Sema — Tap to speak",
                        modifier = Modifier.size(36.dp)
                    )
                }
                VoiceState.LISTENING -> {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Nasikiliza — Listening",
                        modifier = Modifier.size(36.dp)
                    )
                }
                VoiceState.PROCESSING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = Color.White,
                        strokeWidth = 3.dp
                    )
                }
                VoiceState.SPEAKING -> {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Nasema — Speaking",
                        modifier = Modifier.size(36.dp)
                    )
                }
                VoiceState.ERROR -> {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Kosa — Error",
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
// Status Card — Color-coded business metric
// ──────────────────────────────────────────────

@Composable
fun StatusCard(
    label: String,
    sublabel: String? = null,
    value: String,
    icon: ImageVector,
    statusColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val colors = MsaidiziThemeTokens.colors

    Card(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = MsaidiziShapes().large,
        colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = statusColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.primary,
                textAlign = TextAlign.Center
            )
            if (sublabel != null) {
                Text(
                    text = sublabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = statusColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ──────────────────────────────────────────────
// List Item Card — For transaction/inventory lists
// ──────────────────────────────────────────────

@Composable
fun ListItemCard(
    title: String,
    subtitle: String,
    trailing: String,
    icon: ImageVector,
    statusColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val colors = MsaidiziThemeTokens.colors

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = MsaidiziShapes().medium,
        colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(MsaidiziShapes().medium)
                    .background(statusColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = trailing,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = statusColor
            )
        }
    }
}

// ──────────────────────────────────────────────
// Progress Bar with Label
// ──────────────────────────────────────────────

@Composable
fun LabeledProgressBar(
    label: String,
    progress: Float, // 0.0 to 1.0
    progressText: String,
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = progressText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp)),
            color = color,
            trackColor = color.copy(alpha = 0.15f)
        )
    }
}

// ──────────────────────────────────────────────
// Section Header
// ──────────────────────────────────────────────

@Composable
fun SectionHeader(
    titleSw: String,
    titleEn: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    val colors = MsaidiziThemeTokens.colors

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = titleSw,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.primary
            )
            Text(
                text = titleEn,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        if (actionText != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(
                    text = actionText,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.tertiary
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
// Empty State
// ──────────────────────────────────────────────

@Composable
fun EmptyState(
    icon: ImageVector,
    titleSw: String,
    titleEn: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    val colors = MsaidiziThemeTokens.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = colors.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = titleSw,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            text = titleEn,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionText != null && onAction != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = colors.tertiary)
            ) {
                Text(actionText)
            }
        }
    }
}

// ──────────────────────────────────────────────
// Waveform Visualization
// ──────────────────────────────────────────────

@Composable
fun VoiceWaveform(
    isActive: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 24,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")

    val barHeights = List(barCount) { index ->
        infiniteTransition.animateFloat(
            initialValue = 0.2f,
            targetValue = if (isActive) (0.3f + (index % 5) * 0.15f) else 0.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 300 + (index * 50),
                    easing = EaseInOutCubic
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar$index"
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        barHeights.forEach { height ->
            val h by height
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(h.coerceIn(0.1f, 1f))
                    .clip(RoundedCornerShape(2.dp))
                    .background(color.copy(alpha = if (isActive) 0.8f else 0.3f))
            )
        }
    }
}

// ──────────────────────────────────────────────
// Bottom Navigation Bar
// ──────────────────────────────────────────────

@Composable
fun MsaidiziBottomBar(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MsaidiziThemeTokens.colors

    data class NavItem(
        val route: String,
        val labelSw: String,
        val selectedIcon: ImageVector,
        val unselectedIcon: ImageVector
    )

    val items = listOf(
        NavItem("voice", "Sema", Icons.Filled.Mic, Icons.Outlined.Mic),
        NavItem("dashboard", "Dashibodi", Icons.Filled.Dashboard, Icons.Outlined.Dashboard),
        NavItem("transactions", "Miamala", Icons.Filled.Receipt, Icons.Outlined.Receipt),
        NavItem("more", "Zaidi", Icons.Filled.MoreHoriz, Icons.Outlined.MoreHoriz)
    )

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
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = item.labelSw
                    )
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
                    indicatorColor = colors.primaryContainer
                )
            )
        }
    }
}

// ──────────────────────────────────────────────
// Tab Row (Swahili-first)
// ──────────────────────────────────────────────

@Composable
fun MsaidiziTabRow(
    tabs: List<Pair<String, String>>, // (swahili, english)
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MsaidiziThemeTokens.colors

    ScrollableTabRow(
        selectedTabIndex = selectedTab,
        modifier = modifier,
        containerColor = colors.surface,
        contentColor = colors.primary,
        edgePadding = 16.dp
    ) {
        tabs.forEachIndexed { index, (sw, en) ->
            Tab(
                selected = selectedTab == index,
                onClick = { onTabSelected(index) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = sw,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium
                        )
                        Text(
                            text = en,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            )
        }
    }
}

// ──────────────────────────────────────────────
// Alert Banner
// ──────────────────────────────────────────────

@Composable
fun AlertBanner(
    messageSw: String,
    messageEn: String,
    severity: AlertSeverity,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null
) {
    val colors = MsaidiziThemeTokens.colors

    val (bgColor, iconColor, icon) = when (severity) {
        AlertSeverity.SUCCESS -> Quadruple(colors.successContainer, colors.success, Icons.Default.CheckCircle)
        AlertSeverity.WARNING -> Quadruple(colors.warningContainer, colors.warning, Icons.Default.Warning)
        AlertSeverity.ERROR -> Quadruple(colors.errorContainer, colors.error, Icons.Default.Error)
        AlertSeverity.INFO -> Quadruple(colors.infoContainer, colors.info, Icons.Default.Info)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MsaidiziShapes().medium,
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = messageSw,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = iconColor
                )
                Text(
                    text = messageEn,
                    style = MaterialTheme.typography.bodySmall,
                    color = iconColor.copy(alpha = 0.8f)
                )
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Funga",
                        tint = iconColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

enum class AlertSeverity { SUCCESS, WARNING, ERROR, INFO }

// Helper for 4-element destructuring
private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

// ──────────────────────────────────────────────
// Category Chip
// ──────────────────────────────────────────────

@Composable
fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MsaidiziThemeTokens.colors

    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = colors.primary,
            selectedLabelColor = colors.onPrimary,
            containerColor = colors.surfaceVariant,
            labelColor = colors.onSurfaceVariant
        ),
        shape = MsaidiziShapes().full
    )
}

// ──────────────────────────────────────────────
// Swipe-to-delete placeholder (simplified)
// ──────────────────────────────────────────────

@Composable
fun SwipeableCard(
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MsaidiziShapes().medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        content()
    }
}
