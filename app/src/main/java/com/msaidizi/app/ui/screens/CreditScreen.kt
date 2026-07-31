package com.msaidizi.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msaidizi.app.ui.components.*
import com.msaidizi.app.ui.designsystem.*

// ──────────────────────────────────────────────
// Credit Screen
// Alama Score (credit readiness), loan comparison,
// insurance matching
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditScreen(
    onNavigate: (String) -> Unit = {}
) {
    val colors = MsaidiziThemeTokens.colors
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Alama" to "Score", "Mikopo" to "Loans", "Bima" to "Insurance")

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Mkopo na Bima", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.primary)
                        Text("Credit & Insurance", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
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
            item {
                MsaidiziTabRow(tabs = tabs, selectedTab = selectedTab, onTabSelected = { selectedTab = it })
            }

            when (selectedTab) {
                0 -> {
                    // ── Alama Score ──
                    item {
                        AlamaScoreCard(score = 62, maxScore = 100)
                    }

                    item {
                        SectionHeader(titleSw = "Jinsi ya Kupata Alama Zaidi", titleEn = "How to Improve Your Score")
                    }
                    item { ScoreTip("Rekodi mauzo kila siku", "Record sales daily", true) }
                    item { ScoreTip("Tumia M-Pesa kwa malipo", "Use M-Pesa for payments", true) }
                    item { ScoreTip("Lipa deni kwa wakati", "Pay debts on time", false) }
                    item { ScoreTip("Jiunge na chama", "Join a savings group", true) }
                    item { ScoreTip("Ongeza wateja", "Grow your customer base", false) }

                    item {
                        SectionHeader(titleSw = "Faida za Alama Nzuri", titleEn = "Benefits of a Good Score")
                    }
                    item { ScoreBenefit("Mikopo ya riba nafisi", "Low interest loans", "10-15% badala ya 200%+") }
                    item { ScoreBenefit("Bima nafuu", "Affordable insurance", "KES 200/mwezi") }
                    item { ScoreBenefit("Kiwango cha juu cha mkopo", "Higher loan limits", "KES 50,000+") }
                }
                1 -> {
                    // ── Loan Comparison ──
                    item {
                        SectionHeader(titleSw = "Mikopo Inayopatikana", titleEn = "Available Loans")
                    }
                    item { LoanComparisonItem("M-Shwari", "15%", "KES 500-50,000", "Saa 1", colors.info) }
                    item { LoanComparisonItem("KCB M-Pesa", "8.5%", "KES 1,000-100,000", "Siku 1", colors.success) }
                    item { LoanComparisonItem("Fuliza", "0-1%", "KES 100-70,000", "Papo hapo", colors.warning) }
                    item { LoanComparisonItem("Tala", "15-25%", "KES 500-30,000", "Dakika 5", colors.tertiary) }
                    item { LoanComparisonItem("Branch", "14-22%", "KES 500-50,000", "Siku 1", colors.info) }
                    item { LoanComparisonItem("SACCO", "10-12%", "KES 10,000-500,000", "Wiki 1", colors.success) }

                    item {
                        AlertBanner(
                            messageSw = "Ukishapata Alama 70+, utapata mikopo ya SACCO!",
                            messageEn = "Once you reach 70+ score, you qualify for SACCO loans!",
                            severity = AlertSeverity.INFO
                        )
                    }
                }
                2 -> {
                    // ── Insurance ──
                    item {
                        SectionHeader(titleSw = "Bima Zinazopatikana", titleEn = "Available Insurance")
                    }
                    item { InsuranceItem("Afya — Health", "KES 300/mo", "Hospitali, dawa, upasuaji", colors.success) }
                    item { InsuranceItem("Mali — Property", "KES 200/mo", "Duka, bidhaa, moto", colors.info) }
                    item { InsuranceItem("Biashara — Business", "KES 250/mo", "Hasara, wizi, moto", colors.tertiary) }
                    item { InsuranceItem("Pikipiki — Motorcycle", "KES 400/mo", "Ajali, wibi, majeruhi", colors.warning) }
                    item { InsuranceItem("Mazao — Crop", "KES 150/mo", "Mvua, wadudu, ukame", colors.primary) }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun AlamaScoreCard(score: Int, maxScore: Int) {
    val colors = MsaidiziThemeTokens.colors
    val fraction = score.toFloat() / maxScore
    val (label, color) = when {
        score >= 80 -> "Nzuri Sana! — Excellent!" to colors.success
        score >= 60 -> "Nzuri — Good" to colors.info
        score >= 40 -> "Wastani — Average" to colors.warning
        else -> "Inahitaji Kuboreshwa — Needs Improvement" to colors.error
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().extraLarge,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Alama Yako", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
            Text("Your Credit Score", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant.copy(alpha = 0.7f))
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "$score",
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = "/ $maxScore",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp),
                color = color,
                trackColor = color.copy(alpha = 0.15f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

@Composable
private fun ScoreTip(tipSw: String, tipEn: String, done: Boolean) {
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
            Icon(
                imageVector = if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = "Alama ya mkopo",
                tint = if (done) colors.success else colors.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(tipSw, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(tipEn, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ScoreBenefit(benefitSw: String, benefitEn: String, detail: String) {
    val colors = MsaidiziThemeTokens.colors

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MsaidiziShapes().medium,
        colors = CardDefaults.cardColors(containerColor = colors.successContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Star, contentDescription = "Alama", tint = colors.success, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(benefitSw, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = colors.success)
                Text(benefitEn, style = MaterialTheme.typography.bodySmall, color = colors.success.copy(alpha = 0.7f))
            }
            Text(detail, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.success)
        }
    }
}

@Composable
private fun LoanComparisonItem(
    lender: String,
    rate: String,
    range: String,
    speed: String,
    color: androidx.compose.ui.graphics.Color
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
            Column(modifier = Modifier.weight(1f)) {
                Text(lender, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("Riba: $rate • $range", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(speed, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
                Text("Kasi", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun InsuranceItem(
    type: String,
    cost: String,
    coverage: String,
    color: androidx.compose.ui.graphics.Color
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
            Icon(Icons.Default.Shield, contentDescription = "Ulinzi", tint = color, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(type, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(coverage, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(cost, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color)
                Text("Bima", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}
