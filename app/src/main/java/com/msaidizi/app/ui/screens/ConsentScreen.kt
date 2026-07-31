package com.msaidizi.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msaidizi.app.ui.designsystem.*

/**
 * Consent Screen — Kenya DPA 2019 Compliant
 *
 * Presented during onboarding to obtain explicit, informed consent
 * for data processing activities. Each consent category is separate
 * and requires individual opt-in (no pre-ticked boxes).
 *
 * Compliance:
 * - Section 30 DPA 2019: Consent must be freely given, specific, informed
 * - ODPC Guidance Note on Consent (2021)
 * - Plain language in English and Kiswahili
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentScreen(
    onConsentComplete: (ConsentChoices) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val colors = MsaidiziThemeTokens.colors

    // Consent state — all unchecked by default (DPA compliant)
    var functionalConsent by remember { mutableStateOf(false) }
    var analyticsConsent by remember { mutableStateOf(false) }
    var federatedLearningConsent by remember { mutableStateOf(false) }
    var dataSyncConsent by remember { mutableStateOf(false) }
    var privacyPolicyRead by remember { mutableStateOf(false) }
    var termsRead by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Faragha ya Data",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary
                        )
                        Text(
                            text = "Data Privacy Consent",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // ── Header ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colors.primaryContainer),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Icon(
                            Icons.Outlined.Shield,
                            contentDescription = "Kibali icon",
                            tint = colors.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Data Yako, Uamuzi Wako",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary
                        )
                        Text(
                            text = "Your Data, Your Choice",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Chini ya Sheria ya Ulinzi wa Data ya Kenya (2019), una haki ya kuchagua jinsi data yako inavyotumika.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurface
                        )
                        Text(
                            text = "Under the Kenya Data Protection Act (2019), you have the right to choose how your data is used.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Required Consent ──
            item {
                Text(
                    text = "Required / Inahitajika",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary
                )
            }

            item {
                ConsentCard(
                    title = "Functional Data Processing",
                    titleSw = "Usindikaji wa Data ya Kazi",
                    description = "Process your voice input and financial transactions on your device. This data NEVER leaves your phone.",
                    descriptionSw = "Sindikiza sauti na miamala yako kwenye simu yako. Data hii HAITOKI kamwe kwenye simu yako.",
                    icon = Icons.Outlined.PhoneAndroid,
                    checked = functionalConsent,
                    onCheckedChange = { functionalConsent = it },
                    required = true,
                    colors = colors
                )
            }

            // ── Optional Consents ──
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Optional / Si Lazima",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
            }

            item {
                ConsentCard(
                    title = "Anonymous Analytics",
                    titleSw = "Takwimu Zisizojulikana",
                    description = "Share anonymized usage patterns to help us improve Msaidizi. No personal data is included.",
                    descriptionSw = "Shiriki mifumo ya matumizi isiyojulikana kutusaidia kuboresha Msaidizi. Hakuna data ya kibinafsi.",
                    icon = Icons.Outlined.Analytics,
                    checked = analyticsConsent,
                    onCheckedChange = { analyticsConsent = it },
                    required = false,
                    colors = colors
                )
            }

            item {
                ConsentCard(
                    title = "Federated Learning",
                    titleSw = "Kujifunza kwa Pamoja",
                    description = "Allow your device to contribute to AI model improvement using differential privacy (ε=0.1). Your raw data stays on your device.",
                    descriptionSw = "Ruhusu simu yako kuchangia kuboresha mfano wa AI kwa faragha tofauti (ε=0.1). Data yako mbichi inabaki kwenye simu yako.",
                    icon = Icons.Outlined.Psychology,
                    checked = federatedLearningConsent,
                    onCheckedChange = { federatedLearningConsent = it },
                    required = false,
                    colors = colors
                )
            }

            item {
                ConsentCard(
                    title = "Data Sync (Encrypted Backup)",
                    titleSw = "Kusambaza Data (Hifadhi iliyosimbwa)",
                    description = "Sync anonymized data to Angavu servers for backup. Protected with differential privacy and k-anonymity (k≥10).",
                    descriptionSw = "Sambaza data isiyojulikana kwa seva za Angavu kwa hifadhi. Imelindwa na faragha tofauti na k-ufananisho (k≥10).",
                    icon = Icons.Outlined.CloudSync,
                    checked = dataSyncConsent,
                    onCheckedChange = { dataSyncConsent = it },
                    required = false,
                    colors = colors
                )
            }

            // ── Privacy Policy & Terms ──
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Agreements / Makubaliano",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
            }

            item {
                ConsentCard(
                    title = "I have read the Privacy Policy",
                    titleSw = "Nimesoma Sera ya Faragha",
                    description = "Review how we collect, process, store, and protect your data under the Kenya DPA 2019.",
                    descriptionSw = "Angalia jinsi tunavyokusanya, kusindika, kuhifadhi, na kulinda data yako chini ya DPA 2019.",
                    icon = Icons.Outlined.PrivacyTip,
                    checked = privacyPolicyRead,
                    onCheckedChange = { privacyPolicyRead = it },
                    required = true,
                    link = true,
                    colors = colors
                )
            }

            item {
                ConsentCard(
                    title = "I accept the Terms of Service",
                    titleSw = "Ninakubali Masharti ya Huduma",
                    description = "Accept the terms governing your use of Msaidizi and Angavu Intelligence services.",
                    descriptionSw = "Kubali masharti yanayosimamia matumizi yako ya Msaidizi na huduma za Angavu Intelligence.",
                    icon = Icons.Outlined.Description,
                    checked = termsRead,
                    onCheckedChange = { termsRead = it },
                    required = true,
                    link = true,
                    colors = colors
                )
            }

            // ── Submit ──
            item {
                Spacer(modifier = Modifier.height(8.dp))

                val canProceed = functionalConsent && privacyPolicyRead && termsRead

                Button(
                    onClick = {
                        onConsentComplete(
                            ConsentChoices(
                                functional = functionalConsent,
                                analytics = analyticsConsent,
                                federatedLearning = federatedLearningConsent,
                                dataSync = dataSyncConsent,
                                privacyPolicyAccepted = privacyPolicyRead,
                                termsAccepted = termsRead,
                                consentTimestamp = System.currentTimeMillis(),
                                consentVersion = "1.0"
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = canProceed,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        disabledContainerColor = colors.surfaceVariant
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Imekamilika")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (canProceed) "Endelea / Continue" else "Jaza inahitajika / Fill required",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (!canProceed) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Required fields must be checked to continue / Sehemu zinazohitajika lazima zitiwe alama",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            // ── Rights Reminder ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Your Rights / Haki Zako",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "• Unaweza kubadilisha idhini yako wakati wowote kupitia Mipangilio\n" +
                                "• Unaweza kufuta data yako yote wakati wowote\n" +
                                "• Unaweza kuhamisha data yako katika muundo wa JSON/CSV\n" +
                                "• Wasiliana na DPO: dpo@angavuintelligence.com\n" +
                                "• ODPC: www.odpc.go.ke",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• You can change your consent anytime via Settings\n" +
                                "• You can delete all your data anytime\n" +
                                "• You can export your data in JSON/CSV format\n" +
                                "• Contact DPO: dpo@angavuintelligence.com\n" +
                                "• ODPC: www.odpc.go.ke",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsentCard(
    title: String,
    titleSw: String,
    description: String,
    descriptionSw: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    required: Boolean,
    link: Boolean = false,
    colors: MsaidiziColorTokens
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) colors.primaryContainer.copy(alpha = 0.3f)
            else colors.surface
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                icon,
                contentDescription = "Ridhaa",
                tint = if (checked) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    if (required) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Required",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = titleSw,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurface
                )
                Text(
                    text = descriptionSw,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(checkedColor = colors.primary)
            )
        }
    }
}

/**
 * Consent choices data class — stored with timestamp and version for audit trail.
 * Required by DPA 2019 Section 30 for demonstrating valid consent.
 */
data class ConsentChoices(
    val functional: Boolean,
    val analytics: Boolean,
    val federatedLearning: Boolean,
    val dataSync: Boolean,
    val privacyPolicyAccepted: Boolean,
    val termsAccepted: Boolean,
    val consentTimestamp: Long,
    val consentVersion: String
) {
    /**
     * Serialize to JSON for persistent storage.
     * Stored in EncryptedSharedPreferences for audit trail.
     */
    fun toJson(): String {
        return """{"functional":$functional,"analytics":$analytics,"federatedLearning":$federatedLearning,"dataSync":$dataSync,"privacyPolicyAccepted":$privacyPolicyAccepted,"termsAccepted":$termsAccepted,"consentTimestamp":$consentTimestamp,"consentVersion":"$consentVersion"}"""
    }

    /**
     * Minimum consent required for app to function.
     */
    fun hasMinimumConsent(): Boolean = functional && privacyPolicyAccepted && termsAccepted

    /**
     * Check if consent needs refresh (annual requirement per DPA).
     */
    fun needsRefresh(): Boolean {
        val oneYear = 365L * 24 * 60 * 60 * 1000
        return System.currentTimeMillis() - consentTimestamp > oneYear
    }
}
