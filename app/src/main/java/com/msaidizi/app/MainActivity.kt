package com.msaidizi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.msaidizi.app.ui.navigation.AppNavigation
import com.msaidizi.agent.harness.SuperagentHarness
import com.msaidizi.agent.tools.VoicePipeline
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
                    AppNavigation(
                        onRecordSale = {
                            // TODO: navigate to record sale
                            harness.processInput("Rekodi mpya ya mauzo")
                        },
                        onCheckInventory = {
                            // TODO: navigate to inventory
                            harness.processInput("Onyesha hifadhi ya bidhaa")
                        },
                        onViewDebts = {
                            // TODO: navigate to debts
                            harness.processInput("Onyesha deni zote")
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MsaidiziTheme(content: @Composable () -> Unit) {
    com.msaidizi.app.ui.designsystem.MsaidiziTheme(content = content)
}
