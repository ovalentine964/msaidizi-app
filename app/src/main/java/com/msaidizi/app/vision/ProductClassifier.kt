package com.msaidizi.app.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Product Classifier — MobileNetV3 via ONNX Runtime.
 *
 * Classifies images of Kenyan produce into 10 categories:
 * nyanya, vitunguu, sukuma, mboga, viazi, ndizi, embe, parachichi, limau, pilipili
 *
 * ## Model Details
 * - Architecture: MobileNetV3-Small (fine-tuned on Kenyan produce)
 * - Input: 224×224×3 RGB image, normalized to [0, 1]
 * - Output: 10-class softmax probabilities
 * - Model size: ~6MB (quantized INT8) — fits 2GB phones easily
 * - Inference: ~15ms on Helio G25, ~8ms on Snapdragon 680
 *
 * ## Memory Budget
 * - Model: ~6MB
 * - Input tensor: 224×224×3×4 = ~588KB (float32)
 * - ORT overhead: ~10MB
 * - Total: ~17MB — well within 2GB phone limits
 *
 * ## Image Preprocessing
 * 1. Resize to 224×224 (bilinear)
 * 2. Convert to float32 RGB
 * 3. Normalize: pixel / 255.0
 * 4. Apply ImageNet mean/std normalization:
 *    mean = [0.485, 0.456, 0.406]
 *    std  = [0.229, 0.224, 0.225]
 *
 * ## Usage
 * ```kotlin
 * val classifier = ProductClassifier(context)
 * classifier.loadModel()
 * val result = classifier.classify(bitmap)
 * // result.productSwahili = "nyanya"
 * // result.confidence = 0.92
 * ```
 */
@Singleton
class ProductClassifier @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    companion object {
        private const val MODEL_FILENAME = "mobilenetv3_kenyan_produce.onnx"
        private const val TAG = "ProductClassifier"

        // ImageNet normalization constants
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }

    // ── ONNX Runtime state ──
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isModelLoaded = false

    // ── Inference stats ──
    private var totalInferences = 0L
    private var totalInferenceTimeMs = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Load the MobileNetV3 ONNX model.
     *
     * Looks for the model in:
     * 1. Internal storage: files/models/mobilenetv3_kenyan_produce.onnx
     * 2. Assets: models/mobilenetv3_kenyan_produce.onnx
     *
     * @return true if model loaded successfully
     */
    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        if (isModelLoaded) return@withContext true

        try {
            val startTime = System.currentTimeMillis()

            // Find model file
            val modelFile = findModelFile()
            if (modelFile == null) {
                Timber.w(TAG, "Model file not found: %s", MODEL_FILENAME)
                return@withContext false
            }

            // Check available memory
            val runtime = Runtime.getRuntime()
            val freeMB = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / (1024 * 1024)
            if (freeMB < 50) {
                Timber.e(TAG, "Insufficient memory to load classifier: %dMB free", freeMB)
                return@withContext false
            }

            ortEnvironment = OrtEnvironment.getEnvironment()

            val sessionOptions = OrtSession.SessionOptions().apply {
                // Use 2 threads — conservative for 2GB phones
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                // Enable memory pattern optimization
                setOptimizedModelFilePath(
                    File(context.cacheDir, "mobilenetv3_optimized.onnx").absolutePath
                )
            }

            ortSession = requireNotNull(ortEnvironment).createSession(
                modelFile.absolutePath,
                sessionOptions
            )

            isModelLoaded = true
            val elapsed = System.currentTimeMillis() - startTime
            Timber.i(TAG, "Product classifier loaded in %dms (%s)", elapsed, MODEL_FILENAME)
            true

        } catch (e: OutOfMemoryError) {
            Timber.e(TAG, "OOM loading product classifier")
            cleanup()
            System.gc()
            false
        } catch (e: Throwable) {
            Timber.e(e, "Failed to load product classifier")
            cleanup()
            false
        }
    }

    /**
     * Unload model to free ~17MB RAM.
     */
    fun unloadModel() {
        cleanup()
        Timber.d(TAG, "Product classifier unloaded")
    }

    fun isModelReady(): Boolean = isModelLoaded

    /**
     * Classify a bitmap image and return the top product prediction.
     *
     * @param bitmap Input image (any size — will be resized to 224×224)
     * @param topK Number of top predictions to return (default 3)
     * @return Top product recognition result, or null if classification failed
     */
    suspend fun classify(bitmap: Bitmap, topK: Int = 3): ProductRecognition? =
        withContext(Dispatchers.Default) {
            if (!isModelLoaded) {
                val loaded = loadModel()
                if (!loaded) return@withContext null
            }

            try {
                val startTime = System.currentTimeMillis()

                // 1. Preprocess: resize + normalize
                val inputTensor = preprocessImage(bitmap)
                    ?: return@withContext null

                // 2. Run inference
                val env = requireNotNull(ortEnvironment)
                val session = requireNotNull(ortSession)

                val results = session.run(mapOf("input" to inputTensor))
                val outputTensor = results.get("output") as OnnxTensor
                val probabilities = (outputTensor.value as Array<FloatArray>)[0]

                // 3. Get top-K predictions
                val topPredictions = getTopK(probabilities, topK)
                val bestClass = topPredictions.first()
                val product = ProductDatabase.getByIndex(bestClass.first)

                // 4. Cleanup
                inputTensor.close()
                outputTensor.close()
                results.close()

                val elapsed = System.currentTimeMillis() - startTime
                totalInferences++
                totalInferenceTimeMs += elapsed

                if (product == null) {
                    Timber.w(TAG, "Unknown class index: %d", bestClass.first)
                    return@withContext null
                }

                Timber.d(
                    TAG, "Classified: %s (%.1f%%) in %dms",
                    product.swahiliName, bestClass.second * 100, elapsed
                )

                ProductRecognition(
                    productSwahili = product.swahiliName,
                    productEnglish = product.englishName,
                    category = product.category,
                    confidence = bestClass.second.toDouble(),
                    suggestedPriceKSh = product.currentPriceKSh,
                    quantityEstimate = 1,
                    processingTimeMs = elapsed,
                    modelUsed = "mobilenetv3-small",
                    classIndex = bestClass.first
                )

            } catch (e: OutOfMemoryError) {
                Timber.e(TAG, "OOM during classification")
                cleanup()
                System.gc()
                null
            } catch (e: Throwable) {
                Timber.e(e, "Classification failed")
                null
            }
        }

    /**
     * Classify and return ALL class probabilities (for confidence calibration).
     *
     * @param bitmap Input image
     * @return FloatArray of probabilities (length = NUM_CLASSES), or null on failure
     */
    suspend fun classifyRaw(bitmap: Bitmap): FloatArray? =
        withContext(Dispatchers.Default) {
            if (!isModelLoaded) {
                val loaded = loadModel()
                if (!loaded) return@withContext null
            }

            try {
                val inputTensor = preprocessImage(bitmap) ?: return@withContext null
                val env = requireNotNull(ortEnvironment)
                val session = requireNotNull(ortSession)

                val results = session.run(mapOf("input" to inputTensor))
                val outputTensor = results.get("output") as OnnxTensor
                val probabilities = (outputTensor.value as Array<FloatArray>)[0].copyOf()

                inputTensor.close()
                outputTensor.close()
                results.close()

                probabilities
            } catch (e: Throwable) {
                Timber.e(e, "Raw classification failed")
                null
            }
        }

    /**
     * Get average inference time in milliseconds.
     */
    fun getAverageInferenceTimeMs(): Long {
        return if (totalInferences > 0) totalInferenceTimeMs / totalInferences else 0
    }

    // ── Image Preprocessing ────────────────────────────────────────

    /**
     * Preprocess bitmap for MobileNetV3 input.
     *
     * Steps:
     * 1. Resize to 224×224 (bilinear interpolation)
     * 2. Convert pixels to float32 RGB [0, 1]
     * 3. Apply ImageNet normalization: (pixel - mean) / std
     *
     * @return ONNX tensor [1, 3, 224, 224] (NCHW format), or null on error
     */
    private fun preprocessImage(bitmap: Bitmap): OnnxTensor? {
        return try {
            val w = ProductDatabase.INPUT_WIDTH
            val h = ProductDatabase.INPUT_HEIGHT

            // Resize bitmap
            val resized = Bitmap.createScaledBitmap(bitmap, w, h, true)

            // Create float buffer in NCHW format: [batch, channels, height, width]
            val buffer = FloatArray(1 * 3 * h * w)

            var pixelIdx = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val pixel = resized.getPixel(x, y)

                    // Extract RGB channels, normalize to [0, 1]
                    val r = Color.red(pixel) / 255.0f
                    val g = Color.green(pixel) / 255.0f
                    val b = Color.blue(pixel) / 255.0f

                    // ImageNet normalization: (x - mean) / std
                    // NCHW layout: R channel first, then G, then B
                    buffer[0 * h * w + pixelIdx] = (r - MEAN[0]) / STD[0]
                    buffer[1 * h * w + pixelIdx] = (g - MEAN[1]) / STD[1]
                    buffer[2 * h * w + pixelIdx] = (b - MEAN[2]) / STD[2]

                    pixelIdx++
                }
            }

            // Recycle resized bitmap if it's different from input
            if (resized !== bitmap) {
                resized.recycle()
            }

            val env = requireNotNull(ortEnvironment)
            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(buffer),
                longArrayOf(1, 3, h.toLong(), w.toLong())
            )

        } catch (e: OutOfMemoryError) {
            Timber.e(TAG, "OOM during image preprocessing")
            null
        } catch (e: Throwable) {
            Timber.e(e, "Image preprocessing failed")
            null
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    /**
     * Find the model file in internal storage or assets.
     */
    private fun findModelFile(): File? {
        // 1. Check internal storage (downloaded model)
        val internalFile = File(context.filesDir, "models/$MODEL_FILENAME")
        if (internalFile.exists()) return internalFile

        // 2. Check app-specific external storage
        val externalFile = File(context.getExternalFilesDir(null), "models/$MODEL_FILENAME")
        if (externalFile.exists()) return externalFile

        // 3. Copy from assets to internal storage
        return try {
            context.assets.open("models/$MODEL_FILENAME").use { input ->
                internalFile.parentFile?.mkdirs()
                internalFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            internalFile
        } catch (e: Throwable) {
            Timber.d(TAG, "Model not in assets: %s", e.message)
            null
        }
    }

    /**
     * Get top-K class indices and probabilities.
     * Returns list of (classIndex, probability) sorted by probability descending.
     */
    private fun getTopK(probabilities: FloatArray, k: Int): List<Pair<Int, Float>> {
        return probabilities.mapIndexed { index, prob -> Pair(index, prob) }
            .sortedByDescending { it.second }
            .take(k)
    }

    /**
     * Cleanup ONNX resources.
     */
    private fun cleanup() {
        try {
            ortSession?.close()
        } catch (e: Throwable) {
            Timber.d(TAG, "Error closing session: %s", e.message)
        }
        ortSession = null
        ortEnvironment = null
        isModelLoaded = false
    }

    /**
     * Shutdown coroutine scope.
     */
    fun shutdown() {
        scope.cancel()
        cleanup()
    }
}
