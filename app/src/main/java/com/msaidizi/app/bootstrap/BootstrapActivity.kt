package com.msaidizi.app.bootstrap

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msaidizi.app.MainActivity
import com.msaidizi.app.model.BusinessType
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class BootstrapActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if bootstrap already completed
        val prefs = getSharedPreferences("msaidizi", MODE_PRIVATE)
        if (prefs.getBoolean("bootstrap_complete", false)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContent {
            BootstrapScreen(
                onComplete = { name, businessType, language ->
                    // Save bootstrap data
                    prefs.edit()
                        .putString("worker_name", name)
                        .putString("business_type", businessType)
                        .putString("language", language)
                        .putBoolean("bootstrap_complete", true)
                        .apply()

                    startActivity(Intent(this@BootstrapActivity, MainActivity::class.java))
                    finish()
                }
            )
        }
    }
}

/**
 * All business types grouped by category for the onboarding UI.
 * Each entry: display label → BusinessType enum name (stored as string key).
 */
private data class BusinessOption(val label: String, val key: String)

private val businessCategories: Map<String, List<BusinessOption>> = linkedMapOf(
    "🏪 Biashara / Trade" to listOf(
        BusinessOption("Mama mboga (Mboga)", BusinessType.MAMA_MBOGA.name),
        BusinessOption("Dukawallah (Duka)", BusinessType.DUKA.name),
        BusinessOption("Machinga (Hawker)", BusinessType.MACHINGA.name),
        BusinessOption("Mitumba (Nguo za mitumba)", BusinessType.MITUMBA.name),
        BusinessOption("Phone accessories", BusinessType.PHONE_ACCESSORIES.name),
        BusinessOption("Cosmetics (Urembo)", BusinessType.COSMETICS.name),
        BusinessOption("Hardware store (Duka la vifaa)", BusinessType.HARDWARE_STORE.name),
    ),
    "🚚 Usafiri / Transport" to listOf(
        BusinessOption("Boda boda", BusinessType.BODA_BODA.name),
        BusinessOption("Tuk-tuk", BusinessType.TUK_TUK.name),
        BusinessOption("Matatu (driver/conductor)", BusinessType.MATATU.name),
        BusinessOption("Mkokoteni (Cart pusher)", BusinessType.CART_PUSHER.name),
        BusinessOption("Truck driver", BusinessType.TRUCK_DRIVER.name),
    ),
    "🍽️ Chakula / Food" to listOf(
        BusinessOption("Mama lishe (Food vendor)", BusinessType.MAMA_LISHE.name),
        BusinessOption("Hoteli (Restaurant)", BusinessType.HOTELI.name),
        BusinessOption("Chapati/bread seller", BusinessType.CHAPATI_SELLER.name),
        BusinessOption("Muuza maji (Water seller)", BusinessType.WATER_SELLER.name),
        BusinessOption("Traditional brewer", BusinessType.TRADITIONAL_BREWER.name),
    ),
    "🔧 Huduma / Services" to listOf(
        BusinessOption("Fundi (Repair technician)", BusinessType.FUNDI.name),
        BusinessOption("Salon owner", BusinessType.SALON.name),
        BusinessOption("Kinyozi (Barber)", BusinessType.BARBER.name),
        BusinessOption("Mama fuo (Laundry)", BusinessType.MAMA_FUO.name),
        BusinessOption("Fundi nguo (Tailor)", BusinessType.TAILOR.name),
        BusinessOption("Shoe shiner", BusinessType.SHOE_SHINER.name),
        BusinessOption("Car wash", BusinessType.CAR_WASH.name),
    ),
    "🌾 Kilimo / Agriculture" to listOf(
        BusinessOption("Mkulima (Farmer)", BusinessType.MKULIMA.name),
        BusinessOption("Mvuvi (Fisherman)", BusinessType.MVUVI.name),
        BusinessOption("Mfugaji (Livestock keeper)", BusinessType.MFUGAJI.name),
        BusinessOption("Dalali (Produce broker)", BusinessType.PRODUCE_BROKER.name),
    ),
    "🏗️ Ujenzi / Construction" to listOf(
        BusinessOption("Mjengo (Construction worker)", BusinessType.MJENGO.name),
        BusinessOption("Mason", BusinessType.MASON.name),
        BusinessOption("Plumber", BusinessType.PLUMBER.name),
        BusinessOption("Electrician (Mfundi umeme)", BusinessType.ELECTRICIAN.name),
    ),
    "📱 Dijitali / Digital" to listOf(
        BusinessOption("M-Pesa agent", BusinessType.M_PESA.name),
        BusinessOption("Cyber cafe", BusinessType.CYBER_CAFE.name),
        BusinessOption("Fundi simu (Phone repair)", BusinessType.PHONE_REPAIR.name),
        BusinessOption("Social media reseller", BusinessType.SOCIAL_MEDIA_RESELLER.name),
    ),
    "🔨 Fundi / Artisans" to listOf(
        BusinessOption("Jua kali artisan", BusinessType.JUA_KALI.name),
        BusinessOption("Basket weaver (Mfumaji kikapu)", BusinessType.BASKET_WEAVER.name),
        BusinessOption("Potter (Mfinyanzi)", BusinessType.POTTER.name),
        BusinessOption("Welder", BusinessType.WELDER.name),
    ),
    "📦 Nyingine / Other" to listOf(
        BusinessOption("Nyingine (Other)", BusinessType.OTHER.name),
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BootstrapScreen(onComplete: (String, String, String) -> Unit) {
    var step by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    var businessType by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("sw") }

    // Track which category is expanded (only one at a time)
    var expandedCategory by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        when (step) {
            // ── Step 0: Name ──
            0 -> {
                Spacer(modifier = Modifier.height(48.dp))
                Text("Habari! 👋", style = MaterialTheme.typography.headlineLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Mimi ni Msaidizi — CFO wako wa biashara")
                Spacer(modifier = Modifier.height(24.dp))
                Text("Jina lako nani?")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Jina lako") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { if (name.isNotBlank()) step = 1 }) {
                    Text("Endelea →")
                }
            }

            // ── Step 1: Business type selection ──
            1 -> {
                Spacer(modifier = Modifier.height(32.dp))
                Text("Poa $name! 👍", style = MaterialTheme.typography.headlineLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Biashara yako ni ipi?")
                Spacer(modifier = Modifier.height(16.dp))

                businessCategories.forEach { (categoryTitle, options) ->
                    val isExpanded = expandedCategory == categoryTitle
                    val hasSelected = options.any { it.key == businessType }

                    // Category header card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (hasSelected)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column {
                            // Category header row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expandedCategory = if (isExpanded) null else categoryTitle
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = categoryTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                if (hasSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Icon(
                                    imageVector = if (isExpanded)
                                        Icons.Default.ArrowDropDown
                                    else
                                        Icons.Default.KeyboardArrowRight,
                                    contentDescription = if (isExpanded) "Collapse" else "Expand"
                                )
                            }

                            // Expandable options list
                            AnimatedVisibility(visible = isExpanded) {
                                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                                    HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                                    options.forEach { option ->
                                        val isSelected = businessType == option.key
                                        OutlinedButton(
                                            onClick = {
                                                businessType = option.key
                                                step = 2
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 3.dp),
                                            colors = if (isSelected)
                                                ButtonDefaults.outlinedButtonColors(
                                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                                )
                                            else
                                                ButtonDefaults.outlinedButtonColors()
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = option.label,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                if (isSelected) {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = "Selected",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── Step 2: Confirmation ──
            2 -> {
                Spacer(modifier = Modifier.height(48.dp))
                Text("Sawa! 🎉", style = MaterialTheme.typography.headlineLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Msaidizi wako tayari!")
                Spacer(modifier = Modifier.height(16.dp))

                // Find the display label for the selected business type
                val selectedLabel = businessCategories.values
                    .flatten()
                    .firstOrNull { it.key == businessType }
                    ?.label ?: businessType

                // Find the category
                val selectedCategory = businessCategories.entries
                    .firstOrNull { (_, options) -> options.any { it.key == businessType } }
                    ?.key ?: ""

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("👤 Jina: $name", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("🏪 Biashara: $selectedLabel", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("📂 Aina: $selectedCategory", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { onComplete(name, businessType, language) }) {
                    Text("Anza Kazi! 🚀")
                }
            }
        }
    }
}
