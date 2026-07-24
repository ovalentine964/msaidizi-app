package com.msaidizi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.msaidizi.app.superagent.harness.SuperagentHarness
import com.msaidizi.app.voice.VoicePipeline
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var harness: SuperagentHarness
    @Inject lateinit var voicePipeline: VoicePipeline

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MsaidiziTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MsaidiziApp(harness, voicePipeline)
                }
            }
        }
    }
}

@Composable
fun MsaidiziApp(harness: SuperagentHarness, voicePipeline: VoicePipeline) {
    var userInput by remember { mutableStateOf("") }
    var response by remember { mutableStateOf("Habari! Mimi ni Msaidizi — CFO wako wa biashara. Ungependa kufanya nini?") }
    var isListening by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(16.dp)) {
        // Header
        Text(
            text = "Msaidizi CFO",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Response card
        Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Text(
                text = response,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Voice button
        Button(
            onClick = {
                isListening = !isListening
                if (isListening) {
                    voicePipeline.startListening { spokenText ->
                        userInput = spokenText
                        response = harness.processInput(spokenText, isVoice = true)
                        isListening = false
                    }
                } else {
                    voicePipeline.stopListening()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isListening) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (isListening) "🔴 Listening..." else "🎤 Speak to Your CFO")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Text input fallback
        OutlinedTextField(
            value = userInput,
            onValueChange = { userInput = it },
            label = { Text("Or type here...") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                if (userInput.isNotBlank()) {
                    response = harness.processInput(userInput)
                    userInput = ""
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Send")
        }
    }
}

@Composable
fun MsaidiziTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF1B4965),
            secondary = androidx.compose.ui.graphics.Color(0xFFE8A838),
            tertiary = androidx.compose.ui.graphics.Color(0xFFE8853D)
        ),
        content = content
    )
}
