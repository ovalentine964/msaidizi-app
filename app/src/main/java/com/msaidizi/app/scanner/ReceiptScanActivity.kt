package com.msaidizi.app.scanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.msaidizi.app.R
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Receipt Scan Activity — CameraX capture for receipt scanning.
 *
 * Flow:
 * 1. Open camera with live preview
 * 2. User takes photo of receipt
 * 3. Run ML Kit OCR on captured image
 * 4. Parse receipt → extract items, prices, total
 * 5. Return parsed data to caller via result Intent
 *
 * ## Accessibility
 * - Large capture button for easy tapping
 * - Voice feedback for scan progress
 * - Clear visual overlay showing where to point camera
 * - Works on 2GB phones (CameraX + ML Kit are lightweight)
 *
 * ## Usage
 * ```kotlin
 * // Launch scanner
 * val intent = ReceiptScanActivity.newIntent(context)
 * startActivityForResult(intent, REQUEST_RECEIPT_SCAN)
 *
 * // Handle result
 * val receiptData = ReceiptScanActivity.parseResult(data)
 * ```
 */
class ReceiptScanActivity : AppCompatActivity() {

    private lateinit var cameraPreview: PreviewView
    private lateinit var captureButton: MaterialButton
    private lateinit var closeButton: ImageButton
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var instructionText: TextView

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private val receiptScanner = ReceiptScanner()

    companion object {
        private const val CAMERA_PERMISSION_CODE = 1002
        const val EXTRA_RECEIPT_DATA = "receipt_data"
        const val EXTRA_RAW_OCR_TEXT = "raw_ocr_text"
        const val REQUEST_CODE = 1001

        fun newIntent(context: Context): Intent {
            return Intent(context, ReceiptScanActivity::class.java)
        }

        /**
         * Parse the result from a ReceiptScanActivity result intent.
         */
        fun parseResult(data: Intent?): ReceiptData? {
            val items = data?.getParcelableArrayListExtra<ReceiptItemParcel>(EXTRA_RECEIPT_DATA)
            val rawText = data?.getStringExtra(EXTRA_RAW_OCR_TEXT) ?: ""
            if (items.isNullOrEmpty() && rawText.isBlank()) return null

            return ReceiptData(
                items = items?.map { it.toReceiptItem() } ?: emptyList(),
                rawOcrText = rawText,
                total = items?.sumOf { it.totalPrice } ?: 0.0
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receipt_scan)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupViews()
        checkCameraPermission()
    }

    private fun setupViews() {
        cameraPreview = findViewById(R.id.camera_preview)
        captureButton = findViewById(R.id.capture_button)
        closeButton = findViewById(R.id.close_button)
        statusText = findViewById(R.id.status_text)
        progressBar = findViewById(R.id.progress_bar)
        instructionText = findViewById(R.id.instruction_text)

        // Content descriptions for accessibility
        captureButton.contentDescription = "Piga picha risiti (Take receipt photo)"
        closeButton.contentDescription = "Funga (Close)"

        // Capture button — take photo
        captureButton.setOnClickListener {
            takePhoto()
        }

        // Close button
        closeButton.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        // Instruction text
        instructionText.text = "📸 Weka risiti ndani ya fremu\n📸 Place receipt inside the frame"
    }

    /**
     * Check camera permission and start camera if granted.
     */
    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Kamera inahitajika kwa skani ya risiti", Toast.LENGTH_LONG).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }

    /**
     * Start CameraX with preview and image capture use cases.
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .setTargetResolution(Size(1280, 720))
                .build()
                .also {
                    it.surfaceProvider = cameraPreview.surfaceProvider
                }

            // Image Capture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(Size(1280, 720))
                .build()

            // Use back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )
                Timber.d("ReceiptScanActivity: Camera started")
            } catch (e: Exception) {
                Timber.e(e, "ReceiptScanActivity: Camera bind failed")
                Toast.makeText(this, "Kamera imeshindwa kuanza", Toast.LENGTH_SHORT).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Take a photo and process it for receipt scanning.
     */
    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // Show processing state
        showProcessing(true)
        captureButton.isEnabled = false

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = imageProxyToBitmap(image)
                    image.close()

                    if (bitmap == null) {
                        showProcessing(false)
                        captureButton.isEnabled = true
                        Toast.makeText(
                            this@ReceiptScanActivity,
                            "Picha imeshindikana. Jaribu tena.",
                            Toast.LENGTH_SHORT
                        ).show()
                        return
                    }

                    // Process receipt in background
                    lifecycleScope.launch {
                        processReceipt(bitmap)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Timber.e(exception, "ReceiptScanActivity: Capture failed")
                    showProcessing(false)
                    captureButton.isEnabled = true
                    Toast.makeText(
                        this@ReceiptScanActivity,
                        "Piga picha imeshindikana. Jaribu tena.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    /**
     * Process captured receipt image through ML Kit OCR + parsing.
     */
    private suspend fun processReceipt(bitmap: Bitmap) {
        statusText.text = "Nasoma risiti... (Scanning receipt...)"

        val receiptData = receiptScanner.scanReceipt(bitmap)

        if (receiptData == null || !receiptData.isValid) {
            showProcessing(false)
            captureButton.isEnabled = true
            statusText.text = "Sikuona maandishi. Piga picha tena."
            Toast.makeText(
                this,
                "Haijapata maandishi. Hakikisha risiti iko wazi.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Success — return result
        statusText.text = "Nimesoma! ${receiptData.items.size} bidhaa"

        val resultIntent = Intent().apply {
            putParcelableArrayListExtra(
                EXTRA_RECEIPT_DATA,
                ArrayList(receiptData.items.map { ReceiptItemParcel.fromReceiptItem(it) })
            )
            putExtra(EXTRA_RAW_OCR_TEXT, receiptData.rawOcrText)
            putExtra("merchant_name", receiptData.merchantName)
            putExtra("date", receiptData.date)
            putExtra("total", receiptData.total)
            putExtra("payment_method", receiptData.paymentMethod)
        }

        setResult(RESULT_OK, resultIntent)
        finish()
    }

    /**
     * Convert ImageProxy to Bitmap, handling rotation.
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            // Apply rotation
            val rotation = imageProxy.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotation.toFloat())
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Timber.e(e, "ReceiptScanActivity: Bitmap conversion failed")
            null
        }
    }

    /**
     * Show/hide processing state.
     */
    private fun showProcessing(processing: Boolean) {
        progressBar.visibility = if (processing) View.VISIBLE else View.GONE
        captureButton.isEnabled = !processing
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        receiptScanner.close()
    }
}
