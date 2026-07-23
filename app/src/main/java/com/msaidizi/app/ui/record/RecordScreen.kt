package com.msaidizi.app.ui.record

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
 * RecordScreen — Manual transaction recording.
 * Voice-first: primary action is voice, text is fallback.
 */
@Composable
fun RecordScreen(
    viewModel: RecordViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var textInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "📝 Rekodi Miamala",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Voice input
        Button(
            onClick = { viewModel.toggleVoice() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (uiState.isListening)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = if (uiState.isListening) "🎤 Nasikiliza..." else "🎤 Ongea",
                fontSize = 18.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Text input fallback
        OutlinedTextField(
            value = textInput,
            onValueChange = { textInput = it },
            label = { Text("Au andika hapa...") },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Nimeuza nyanya kwa 500") }
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                if (textInput.isNotBlank()) {
                    viewModel.sendText(textInput)
                    textInput = ""
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Tuma")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Quick record buttons
        Text(
            text = "Rekodi Haraka",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        QuickRecordButton("💰 Rekodi Mauzo") { viewModel.quickRecord("sale") }
        QuickRecordButton("🛒 Rekodi Manunuzi") { viewModel.quickRecord("purchase") }
        QuickRecordButton("📉 Rekodi Gharama") { viewModel.quickRecord("expense") }
        QuickRecordButton("📱 Rekodi M-Pesa") { viewModel.quickRecord("mpesa") }

        Spacer(modifier = Modifier.height(16.dp))

        // Agent response
        if (uiState.lastResponse != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    text = uiState.lastResponse!!.text,
                    modifier = Modifier.padding(16.dp),
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
fun QuickRecordButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(text = label, fontSize = 14.sp)
    }
}
