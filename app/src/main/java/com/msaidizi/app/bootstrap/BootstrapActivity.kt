package com.msaidizi.app.bootstrap

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.msaidizi.app.MainActivity
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

@Composable
fun BootstrapScreen(onComplete: (String, String, String) -> Unit) {
    var step by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    var businessType by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("sw") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (step) {
            0 -> {
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
            1 -> {
                Text("Poa $name! 👍", style = MaterialTheme.typography.headlineLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Biashara yako ni ipi?")
                Spacer(modifier = Modifier.height(16.dp))
                val businesses = listOf(
                    "Mama mboga" to "mboga",
                    "Boda boda" to "boda",
                    "Dukawallah" to "duka",
                    "Mama lishe" to "lishe",
                    "Fundi" to "fundi",
                    "Salon/Barber" to "salon",
                    "Nyingine" to "other"
                )
                businesses.forEach { (label, value) ->
                    Button(
                        onClick = { businessType = value; step = 2 },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors()
                    ) { Text(label) }
                }
            }
            2 -> {
                Text("Sawa! 🎉", style = MaterialTheme.typography.headlineLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Msaidizi wako tayari!")
                Spacer(modifier = Modifier.height(8.dp))
                Text("Jina: $name")
                Text("Biashara: $businessType")
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { onComplete(name, businessType, language) }) {
                    Text("Anza Kazi! 🚀")
                }
            }
        }
    }
}
