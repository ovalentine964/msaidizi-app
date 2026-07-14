package com.msaidizi.app.ui.components

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.msaidizi.app.scanner.ReceiptData
import com.msaidizi.app.scanner.ReceiptItemParcel
import com.msaidizi.app.scanner.ReceiptScanActivity

/**
 * Scan Receipt Button — launches camera for receipt scanning.
 *
 * Usage in HomeScreen or RecordScreen:
 * ```kotlin
 * ScanReceiptButton(
 *     onReceiptScanned = { receiptData ->
 *         // Show confirmation UI or auto-create transactions
 *     }
 * )
 * ```
 */
@Composable
fun ScanReceiptButton(
    onReceiptScanned: (ReceiptData) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val items = data?.getParcelableArrayListExtra<ReceiptItemParcel>(
                ReceiptScanActivity.EXTRA_RECEIPT_DATA
            )
            val rawText = data?.getStringExtra(ReceiptScanActivity.EXTRA_RAW_OCR_TEXT) ?: ""
            val merchant = data?.getStringExtra("merchant_name") ?: ""
            val total = data?.getDoubleExtra("total", 0.0) ?: 0.0

            if (!items.isNullOrEmpty() || rawText.isNotBlank()) {
                val receiptData = ReceiptData(
                    merchantName = merchant,
                    items = items?.map { it.toReceiptItem() } ?: emptyList(),
                    total = total,
                    rawOcrText = rawText
                )
                onReceiptScanned(receiptData)
            }
        }
    }

    Button(
        onClick = {
            val intent = ReceiptScanActivity.newIntent(context)
            launcher.launch(intent)
        },
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary
        )
    ) {
        Icon(
            imageVector = Icons.Default.CameraAlt,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("📸 Scan Risiti")
    }
}

/**
 * Floating scan button for overlay on other screens.
 */
@Composable
fun ScanReceiptFab(
    onReceiptScanned: (ReceiptData) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val items = data?.getParcelableArrayListExtra<ReceiptItemParcel>(
                ReceiptScanActivity.EXTRA_RECEIPT_DATA
            )
            val rawText = data?.getStringExtra(ReceiptScanActivity.EXTRA_RAW_OCR_TEXT) ?: ""
            val merchant = data?.getStringExtra("merchant_name") ?: ""
            val total = data?.getDoubleExtra("total", 0.0) ?: 0.0

            if (!items.isNullOrEmpty() || rawText.isNotBlank()) {
                val receiptData = ReceiptData(
                    merchantName = merchant,
                    items = items?.map { it.toReceiptItem() } ?: emptyList(),
                    total = total,
                    rawOcrText = rawText
                )
                onReceiptScanned(receiptData)
            }
        }
    }

    FloatingActionButton(
        onClick = {
            val intent = ReceiptScanActivity.newIntent(context)
            launcher.launch(intent)
        },
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.secondary
    ) {
        Icon(
            imageVector = Icons.Default.CameraAlt,
            contentDescription = "Scan Receipt / Piga risiti"
        )
    }
}
