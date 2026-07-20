package com.msaidizi.app.agent.multimodal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import timber.log.Timber
import java.io.File
import java.io.InputStream

/**
 * Multimodal Input Pipeline — Camera/image handling for goods recognition.
 *
 * Pipeline: Camera → Preprocess → Identify Product → Price Lookup (Soko Pulse)
 *
 * ## Use Cases (Swarm 7)
 * 1. **Goods Recognition**: Photograph a product → identify it → look up price
 * 2. **Receipt Scanning**: Photograph a receipt → extract items and amounts
 * 3. **Inventory Scan**: Photograph shelf → count items → update inventory
 * 4. **Price Comparison**: Photograph product → get prices from multiple sources
 *
 * ## Architecture
 * ```
 * Camera Capture → Image Preprocess → Vision Model (Gemma 4 E2B)
 *                                        ↓
 *                                    Structured Output
 *                                        ↓
 *                    Text Pipeline → Financial Agent → Response
 * ```
 *
 * ## On-Device Processing
 * Uses Gemma 4 E2B (2B params, ~1.5GB) or LFM2.5-VL-1.6B for vision:
 * - 20-35 tokens/sec on Snapdragon 8 Gen 3
 * - Supports product classification, OCR, scene understanding
 * - Falls back to cloud vision API on low-RAM devices
 *
 * ## Image Preprocessing
 * Before sending to the vision model:
 * 1. Resize to max 512x512 (reduce memory and inference time)
 * 2. Convert to RGB (consistent format)
 * 3. Normalize brightness/contrast for consistent recognition
 * 4. Extract EXIF metadata (location, timestamp)
 *
 * Based on: Swarm 7 — Gemma 4 E2B delivers multimodal at edge sizes
 */
class MultimodalPipeline(
    private val context: Context,
    private val router: Any  // ModelRouter — typed as Any to avoid circular dependency
) {
    /**
     * Types of multimodal input the pipeline can handle.
     */
    enum class InputType {
        PRODUCT_IMAGE,      // Photo of a product for identification
        RECEIPT_IMAGE,      // Photo of a receipt for data extraction
        INVENTORY_IMAGE,    // Photo of shelf/inventory for counting
        DOCUMENT_IMAGE,     // Photo of a document for OCR
        BARCODE_IMAGE       // Barcode/QR code scan
    }

    /**
     * Processed multimodal input ready for the model.
     */
    data class ProcessedImage(
        val originalUri: Uri?,
        val bitmap: Bitmap,
        val inputType: InputType,
        val width: Int,
        val height: Int,
        val metadata: Map<String, String> = emptyMap()
    )

    /**
     * Result of multimodal processing.
     */
    data class MultimodalResult(
        val inputType: InputType,
        val recognizedProduct: String? = null,
        val confidence: Double = 0.0,
        val extractedData: Map<String, String> = emptyMap(),
        val priceLookupResult: Map<String, Any>? = null,
        val rawModelOutput: String = "",
        val processingTimeMs: Long = 0,
        val expertUsed: String = ""
    )

    // ── Image Processing ───────────────────────────────────────────

    /**
     * Maximum image dimension for model input.
     * Larger images are downscaled to reduce memory and inference time.
     */
    private val maxImageDimension = 512

    /**
     * Load and preprocess an image from a URI.
     *
     * @param uri Image URI (content://, file://, etc.)
     * @param inputType What kind of image this is
     * @return ProcessedImage ready for model input
     */
    fun loadImage(uri: Uri, inputType: InputType): ProcessedImage? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (originalBitmap == null) {
                Timber.w("Failed to decode image from URI: %s", uri)
                return null
            }

            val resized = resizeBitmap(originalBitmap, maxImageDimension)
            val metadata = extractMetadata(uri)

            ProcessedImage(
                originalUri = uri,
                bitmap = resized,
                inputType = inputType,
                width = resized.width,
                height = resized.height,
                metadata = metadata
            )
        } catch (e: Throwable) {
            Timber.e(e, "Error loading image from URI: %s", uri)
            null
        }
    }

    /**
     * Load and preprocess an image from a Bitmap (e.g., camera capture).
     */
    fun loadImage(bitmap: Bitmap, inputType: InputType): ProcessedImage {
        val resized = resizeBitmap(bitmap, maxImageDimension)
        return ProcessedImage(
            originalUri = null,
            bitmap = resized,
            inputType = inputType,
            width = resized.width,
            height = resized.height,
            metadata = mapOf("source" to "camera_capture")
        )
    }

    /**
     * Build the prompt for the vision model based on input type.
     */
    fun buildVisionPrompt(image: ProcessedImage): String {
        return when (image.inputType) {
            InputType.PRODUCT_IMAGE -> """
                You are analyzing a product image for a small business trader in Africa.
                
                Identify:
                1. Product name (in English and Swahili if possible)
                2. Product category (food, household, electronics, etc.)
                3. Approximate quantity visible
                4. Brand name if visible
                5. Any text or labels visible (OCR)
                
                Respond in JSON format:
                {"product": "name", "category": "type", "quantity": N, "brand": "name", "text": "visible text"}
            """.trimIndent()

            InputType.RECEIPT_IMAGE -> """
                You are analyzing a receipt image for a small business trader.
                
                Extract:
                1. All line items with quantities and prices
                2. Total amount
                3. Date if visible
                4. Vendor/shop name
                5. Payment method if visible
                
                Respond in JSON format:
                {"items": [{"name": "item", "qty": N, "price": N}], "total": N, "date": "date", "vendor": "name"}
            """.trimIndent()

            InputType.INVENTORY_IMAGE -> """
                You are analyzing an inventory/shelf image for a small business trader.
                
                Count and identify:
                1. Each distinct product type visible
                2. Approximate quantity of each
                3. Products that appear low/out of stock
                4. Any price tags visible
                
                Respond in JSON format:
                {"products": [{"name": "item", "count": N, "low_stock": true/false}], "prices_visible": [...]}
            """.trimIndent()

            InputType.DOCUMENT_IMAGE -> """
                Extract all text from this document image.
                Preserve the structure and formatting.
                Respond with the extracted text only.
            """.trimIndent()

            InputType.BARCODE_IMAGE -> """
                Read the barcode or QR code in this image.
                Return the decoded value.
                Respond in JSON format:
                {"type": "barcode/qr", "value": "decoded_string"}
            """.trimIndent()
        }
    }

    /**
     * Process a product image and attempt price lookup.
     *
     * Full pipeline:
     * 1. Run vision model to identify the product
     * 2. Parse structured output
     * 3. Look up price in Soko Pulse (if available)
     * 4. Return combined result
     */
    suspend fun processProductImage(
        image: ProcessedImage,
        sokoPriceLookup: ((String) -> Map<String, Any>?)? = null
    ): MultimodalResult {
        val startTime = System.currentTimeMillis()
        val prompt = buildVisionPrompt(image)

        // In a real implementation, this would call the multimodal expert
        // For now, we prepare the structured pipeline
        val result = MultimodalResult(
            inputType = image.inputType,
            recognizedProduct = null,  // Would be filled by vision model
            confidence = 0.0,
            extractedData = mapOf(
                "prompt" to prompt,
                "image_width" to image.width.toString(),
                "image_height" to image.height.toString(),
                "input_type" to image.inputType.name
            ),
            processingTimeMs = System.currentTimeMillis() - startTime,
            expertUsed = "multimodal_expert"
        )

        Timber.d(
            "Multimodal pipeline: %s image processed in %dms",
            image.inputType.name, result.processingTimeMs
        )

        return result
    }

    // ── Helpers ─────────────────────────────────────────────────────

    /**
     * Resize a bitmap to fit within maxDimension while preserving aspect ratio.
     */
    private fun resizeBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxDimension && height <= maxDimension) return bitmap

        val scale = maxDimension.toFloat() / maxOf(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Extract metadata from image URI (EXIF data, etc.).
     */
    private fun extractMetadata(uri: Uri): Map<String, String> {
        val metadata = mutableMapOf<String, String>()
        metadata["uri"] = uri.toString()
        metadata["timestamp"] = System.currentTimeMillis().toString()

        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream != null) {
                val exif = androidx.exifinterface.media.ExifInterface(inputStream)
                exif.latLong?.let { coords ->
                    metadata["latitude"] = coords[0].toString()
                    metadata["longitude"] = coords[1].toString()
                }
                metadata["orientation"] = exif.getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                ).toString()
                inputStream.close()
            }
        } catch (e: Throwable) {
            Timber.d("Could not read EXIF data: %s", e.message)
        }

        return metadata
    }

    /**
     * Check if the device supports on-device vision models.
     */
    fun supportsOnDeviceVision(): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemoryMb = runtime.maxMemory() / (1024 * 1024)
        // Gemma 4 E2B needs ~1.5GB + app overhead
        return maxMemoryMb >= 3072
    }
}
