package com.msaidizi.app.ui.vision

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import com.google.android.material.card.MaterialCardView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.msaidizi.app.R
import com.msaidizi.app.vision.ProductRecognition
import com.msaidizi.app.vision.ProductRecognitionHandler
import com.msaidizi.app.vision.RecognitionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Camera Capture Fragment — Real-time product recognition via CameraX.
 *
 * ## User Experience
 * 1. "Piga picha bidhaa yako" — Camera preview opens
 * 2. Worker points camera at product
 * 3. Real-time classification: "Hii ni nyanya — bei ya soko ni KSh 50"
 * 4. "Nimeona nyanya 1 — niongeze kwenye stock?"
 * 5. Worker confirms: "Ndio" → adds to inventory
 *    OR corrects: "Siyo, ni pilipili" → learns from correction
 *
 * ## Voice Commands (via SpeechRecognizer)
 * - "Piga picha" → Capture and classify
 * - "Ndio" / "Sawa" → Confirm and add to stock
 * - "Hapana" → Reject and ask for correction
 * - "Ongeza [N]" → Add N items to stock
 *
 * ## Memory Budget
 * - CameraX preview: ~3MB
 * - Image capture buffer: ~2MB
 * - Analysis frame: ~1MB (downscaled)
 * - Total: ~6MB
 *
 * ## Permissions
 * - android.permission.CAMERA
 */
@AndroidEntryPoint
class CameraCaptureFragment : Fragment() {

    companion object {
        private const val TAG = "CameraCapture"
    }

    @Inject
    lateinit var recognitionHandler: ProductRecognitionHandler

    // ── Views ──
    private lateinit var cameraPreview: androidx.camera.view.PreviewView
    private lateinit var statusBar: LinearLayout
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var statusIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var btnClose: ImageButton
    private lateinit var resultCard: MaterialCardView
    private lateinit var productName: TextView
    private lateinit var confidenceText: TextView
    private lateinit var priceText: TextView
    private lateinit var voicePrompt: TextView
    private lateinit var btnConfirm: MaterialButton
    private lateinit var btnCorrect: MaterialButton
    private lateinit var quantitySelector: LinearLayout
    private lateinit var btnQtyMinus: MaterialButton
    private lateinit var quantityValue: TextView
    private lateinit var btnQtyPlus: MaterialButton
    private lateinit var btnAddQuantity: MaterialButton
    private lateinit var voiceInstruction: TextView
    private lateinit var btnCapture: FloatingActionButton
    private lateinit var stockSummary: TextView

    // ── Camera ──
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private lateinit var cameraExecutor: ExecutorService

    // ── State ──
    private var currentRecognition: ProductRecognition? = null
    private var selectedQuantity = 1
    private var isRealTimeEnabled = true

    // ── Permission launcher ──
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(
                requireContext(),
                "Kamera inahitajika kutambua bidhaa",
                Toast.LENGTH_LONG
            ).show()
            parentFragmentManager.popBackStack()
        }
    }

    // ────────────────── Lifecycle ──────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_camera_capture, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        setupClickListeners()

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Initialize recognition handler
        viewLifecycleOwner.lifecycleScope.launch {
            recognitionHandler.initialize()
        }

        // Check camera permission
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // Observe recognition state
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                recognitionHandler.state.collect { state ->
                    updateUI(state)
                }
            }
        }

        // Observe last recognition
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                recognitionHandler.lastRecognition.collect { recognition ->
                    if (recognition != null) {
                        showRecognitionResult(recognition)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        recognitionHandler.cleanup()
        cameraProvider?.unbindAll()
    }

    // ────────────────── View Binding ──────────────────

    private fun bindViews(view: View) {
        cameraPreview = view.findViewById(R.id.camera_preview)
        statusBar = view.findViewById(R.id.status_bar)
        loadingIndicator = view.findViewById(R.id.loading_indicator)
        statusIcon = view.findViewById(R.id.status_icon)
        statusText = view.findViewById(R.id.status_text)
        btnClose = view.findViewById(R.id.btn_close)
        resultCard = view.findViewById(R.id.result_card)
        productName = view.findViewById(R.id.product_name)
        confidenceText = view.findViewById(R.id.confidence_text)
        priceText = view.findViewById(R.id.price_text)
        voicePrompt = view.findViewById(R.id.voice_prompt)
        btnConfirm = view.findViewById(R.id.btn_confirm)
        btnCorrect = view.findViewById(R.id.btn_correct)
        quantitySelector = view.findViewById(R.id.quantity_selector)
        btnQtyMinus = view.findViewById(R.id.btn_qty_minus)
        quantityValue = view.findViewById(R.id.quantity_value)
        btnQtyPlus = view.findViewById(R.id.btn_qty_plus)
        btnAddQuantity = view.findViewById(R.id.btn_add_quantity)
        voiceInstruction = view.findViewById(R.id.voice_instruction)
        btnCapture = view.findViewById(R.id.btn_capture)
        stockSummary = view.findViewById(R.id.stock_summary)
    }

    private fun setupClickListeners() {
        btnClose.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        btnCapture.setOnClickListener {
            captureAndClassify()
        }

        btnConfirm.setOnClickListener {
            confirmRecognition()
        }

        btnCorrect.setOnClickListener {
            promptCorrection()
        }

        btnQtyMinus.setOnClickListener {
            if (selectedQuantity > 1) {
                selectedQuantity--
                quantityValue.text = selectedQuantity.toString()
            }
        }

        btnQtyPlus.setOnClickListener {
            if (selectedQuantity < 999) {
                selectedQuantity++
                quantityValue.text = selectedQuantity.toString()
            }
        }

        btnAddQuantity.setOnClickListener {
            addQuantityToStock()
        }
    }

    // ────────────────── Camera Setup ──────────────────

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(cameraPreview.surfaceProvider) }

            // Image capture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            // Image analysis for real-time classification
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(320, 240))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (isRealTimeEnabled && recognitionHandler.state.value is RecognitionState.Ready) {
                            analyzeFrame(imageProxy)
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            // Select back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalysis
                )
                Timber.d(TAG, "Camera started")
            } catch (e: Throwable) {
                Timber.e(e, "Camera bind failed")
                statusText.text = "Kamera haikuanza. Jaribu tena."
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /**
     * Analyze a camera frame for real-time classification.
     * Runs on cameraExecutor thread.
     */
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val bitmap = imageProxyToBitmap(imageProxy)
        imageProxy.close()

        if (bitmap == null) return

        viewLifecycleOwner.lifecycleScope.launch {
            val result = recognitionHandler.classifyFrame(bitmap)
            if (result != null && result.isReliable) {
                // Announce on first reliable recognition
                if (currentRecognition?.productSwahili != result.productSwahili) {
                    recognitionHandler.announceRecognition(result)
                }
            }
            bitmap.recycle()
        }
    }

    /**
     * Capture a photo and classify it (one-shot).
     */
    private fun captureAndClassify() {
        val capture = imageCapture ?: return

        // Disable real-time during capture
        isRealTimeEnabled = false

        capture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = imageProxyToBitmap(image)
                    image.close()

                    if (bitmap != null) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            val result = recognitionHandler.classifyFrame(bitmap)
                            if (result != null) {
                                recognitionHandler.announceRecognition(result)
                            } else {
                                statusText.text = "Sikuweza kutambua bidhaa. Jaribu tena."
                            }
                            bitmap.recycle()
                            isRealTimeEnabled = true
                        }
                    } else {
                        isRealTimeEnabled = true
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Timber.e(exception, "Image capture failed")
                    statusText.text = "Picha haikupatikana. Jaribu tena."
                    isRealTimeEnabled = true
                }
            }
        )
    }

    // ────────────────── Recognition UI ──────────────────

    private fun showRecognitionResult(recognition: ProductRecognition) {
        currentRecognition = recognition

        productName.text = recognition.productSwahili.replaceFirstChar { it.uppercase() }
        confidenceText.text = "${(recognition.confidence * 100).toInt()}%"
        priceText.text = "Bei ya soko: KSh ${recognition.suggestedPriceKSh.toInt()}"
        voicePrompt.text = recognition.inventoryPrompt

        resultCard.visibility = View.VISIBLE
        quantitySelector.visibility = View.GONE
        selectedQuantity = 1
        quantityValue.text = "1"

        // Update stock summary
        viewLifecycleOwner.lifecycleScope.launch {
            val stock = recognitionHandler.getStock(recognition.productSwahili)
            stockSummary.text = if (stock > 0) {
                "Stock ya sasa: ${recognition.productSwahili} ${stock.toInt()}"
            } else {
                "${recognition.productSwahili} — haijaingizwa stock"
            }
        }
    }

    private fun confirmRecognition() {
        val recognition = currentRecognition ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val action = recognitionHandler.confirmAndAddToInventory(recognition)
            Timber.i(TAG, "Inventory: ${action.action} ${action.productSwahili} ×${action.quantity}")

            // Show confirmation briefly, then hide
            voicePrompt.text = action.confirmation
            btnConfirm.isEnabled = false
            btnConfirm.text = "Imeongezwa ✓"

            kotlinx.coroutines.delay(2000)
            resultCard.visibility = View.GONE
            btnConfirm.isEnabled = true
            btnConfirm.text = "Ndio — Ongeza"
        }
    }

    private fun promptCorrection() {
        quantitySelector.visibility = View.GONE
        voicePrompt.text = "Sema jina sahihi la bidhaa au ubadilishe kwenye orodha"

        // In full implementation, this would start speech recognition
        // For now, show a text input or dropdown
        showCorrectionDialog()
    }

    private fun showCorrectionDialog() {
        val products = com.msaidizi.app.vision.ProductDatabase.allSwahiliNames()
        val productArray = products.toTypedArray()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Bidhaa ni ipi?")
            .setItems(productArray) { _, which ->
                val corrected = products[which]
                viewLifecycleOwner.lifecycleScope.launch {
                    recognitionHandler.applyCorrection(
                        currentRecognition ?: return@launch,
                        corrected
                    )
                    resultCard.visibility = View.GONE
                }
            }
            .setNegativeButton("Ghairi", null)
            .show()
    }

    private fun addQuantityToStock() {
        val recognition = currentRecognition ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val action = recognitionHandler.addQuantityToInventory(recognition, selectedQuantity)
            Timber.i(TAG, "Added ${recognition.productSwahili} ×$selectedQuantity")

            voicePrompt.text = action.confirmation
            btnAddQuantity.isEnabled = false

            kotlinx.coroutines.delay(2000)
            resultCard.visibility = View.GONE
            quantitySelector.visibility = View.GONE
            btnAddQuantity.isEnabled = true
            selectedQuantity = 1
            quantityValue.text = "1"
        }
    }

    // ────────────────── State Updates ──────────────────

    private fun updateUI(state: RecognitionState) {
        when (state) {
            is RecognitionState.Uninitialized -> {
                statusText.text = "Inapakia mfumo..."
                loadingIndicator.visibility = View.VISIBLE
                statusIcon.visibility = View.GONE
            }
            is RecognitionState.Loading -> {
                statusText.text = "Inapakia modeli ya bidhaa..."
                loadingIndicator.visibility = View.VISIBLE
                statusIcon.visibility = View.GONE
            }
            is RecognitionState.Ready -> {
                statusText.text = "Piga picha bidhaa yako"
                loadingIndicator.visibility = View.GONE
                statusIcon.visibility = View.VISIBLE
                voiceInstruction.text = "Sema 'Piga picha' au bonyeza kitufe"
            }
            is RecognitionState.Classifying -> {
                statusText.text = "Inatambua bidhaa..."
                loadingIndicator.visibility = View.VISIBLE
                statusIcon.visibility = View.GONE
            }
            is RecognitionState.Result -> {
                statusText.text = "Nimeona: ${state.recognition.productSwahili}"
                loadingIndicator.visibility = View.GONE
                statusIcon.visibility = View.VISIBLE
                voiceInstruction.text = "Sema 'Ndio' kuongeza stock au 'Hapana' kusahihisha"
            }
            is RecognitionState.AwaitingConfirmation -> {
                statusText.text = "Subiri — ${state.recognition.productSwahili}"
                loadingIndicator.visibility = View.GONE
                statusIcon.visibility = View.VISIBLE
                voiceInstruction.text = "Ndio au Hapana?"
            }
            is RecognitionState.Error -> {
                statusText.text = state.message
                loadingIndicator.visibility = View.GONE
                statusIcon.visibility = View.VISIBLE
            }
        }
    }

    // ────────────────── Helpers ──────────────────

    /**
     * Convert ImageProxy to Bitmap.
     * Handles YUV_420_888 → RGB conversion.
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = android.graphics.YuvImage(
                nv21,
                android.graphics.ImageFormat.NV21,
                imageProxy.width,
                imageProxy.height,
                null
            )

            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height),
                85,
                out
            )

            val bytes = out.toByteArray()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Throwable) {
            Timber.e(e, "ImageProxy to Bitmap conversion failed")
            null
        }
    }
}
