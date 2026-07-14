# Msaidizi Computer Vision System — Complete Technical Design

**Date:** 2026-07-14  
**Author:** Architecture Subagent  
**Status:** Research & Design  
**Target:** 2GB Android phones (Tecno Pop 7, Infinix Smart 7, Nokia C21)

---

## Table of Contents

1. [Current State Audit](#1-current-state-audit)
2. [On-Device Vision Research (2026)](#2-on-device-vision-research-2026)
3. [Vision Pipeline Architecture](#3-vision-pipeline-architecture)
4. [Phase-by-Phase Implementation](#4-phase-by-phase-implementation)
5. [Academic Framework Integration](#5-academic-framework-integration)
6. [Daily Improvement System](#6-daily-improvement-system)
7. [Model Selection & Sizing](#7-model-selection--sizing)
8. [Implementation Plan](#8-implementation-plan)
9. [Code Examples](#9-code-examples)
10. [Cost, Battery & Storage Budget](#10-cost-battery--storage-budget)

---

## 1. Current State Audit

### 1.1 MultimodalPipeline.kt — What Exists

**Status: Scaffolded, not functional**

| Component | Status | Details |
|-----------|--------|---------|
| `InputType` enum | ✅ Defined | PRODUCT_IMAGE, RECEIPT_IMAGE, INVENTORY_IMAGE, DOCUMENT_IMAGE, BARCODE_IMAGE |
| `ProcessedImage` data class | ✅ Defined | Holds bitmap, URI, metadata, dimensions |
| `MultimodalResult` data class | ✅ Defined | Holds recognition result, confidence, extracted data |
| `loadImage(Uri)` | ✅ Implemented | Loads, resizes to 512×512, extracts EXIF metadata |
| `loadImage(Bitmap)` | ✅ Implemented | Camera capture variant |
| `buildVisionPrompt()` | ✅ Implemented | JSON-structured prompts per input type |
| `resizeBitmap()` | ✅ Implemented | Aspect-preserving resize |
| `extractMetadata()` | ✅ Implemented | EXIF lat/long, orientation |
| `processProductImage()` | ⚠️ Stub | Returns empty result — **no actual model inference** |
| `supportsOnDeviceVision()` | ✅ Implemented | Checks ≥3GB RAM (too strict for 2GB target) |
| **Actual vision model call** | ❌ Missing | No TFLite, ONNX, ML Kit, or any model integration |
| **CameraX integration** | ❌ Missing | No camera capture UI |
| **OCR pipeline** | ❌ Missing | No text extraction |
| **Object detection** | ❌ Missing | No bounding box or classification |

### 1.2 ModelRouter.kt — Vision Routing

**Status: Routing table defined, execution stubbed**

- ✅ `on-device-vision` provider registered (Gemma 4 E2B / LFM2.5-VL-1.6B)
- ✅ Task types: `GOODS_RECOGNITION`, `RECEIPT_SCANNING`, `INVENTORY_SCAN`, `PRICE_COMPARISON`
- ✅ Routing table maps multimodal tasks → `on-device-vision` primary
- ✅ MoE router routes image input → `MULTIMODAL_EXPERT`
- ⚠️ `callOnDevice()` only calls `LlmEngine` (text-only llama.cpp) — **no vision path**
- ❌ No image encoding for model input
- ❌ No vision-specific inference code

### 1.3 MoERouter.kt — Multimodal Expert

**Status: Expert defined, not wired**

- ✅ `MULTIMODAL_EXPERT` defined with `supportsVision = true`
- ✅ Model: `gemma-4-e2b`, provider: `on-device-vision`
- ✅ Routing: Image input → MULTIMODAL_EXPERT (confidence 0.95)
- ⚠️ Low RAM override skips multimodal expert entirely — **wrong for lightweight CV models**
- ❌ No actual expert implementation

### 1.4 Dependencies Present

- ✅ `onnxruntime-android:1.20.0` — already in build.gradle
- ✅ `exifinterface` — used in MultimodalPipeline
- ❌ No CameraX dependency
- ❌ No ML Kit dependency
- ❌ No MediaPipe dependency
- ❌ No LiteRT/TFLite dependency

### 1.5 What's Working vs Scaffolded

```
WORKING:
├── Image loading & preprocessing (Bitmap resize, EXIF extraction)
├── Prompt generation for vision tasks (JSON-structured)
├── Routing infrastructure (MoE, fallback chains, cost tracking)
├── Device capability detection (RAM tier, Android Go)
└── ONNX Runtime (used for Whisper ASR, not vision)

SCAFFOLDED (defined but not functional):
├── MultimodalPipeline.processProductImage() → returns empty result
├── ModelRouter "on-device-vision" provider → never called
├── MoERouter MULTIMODAL_EXPERT → routing works, execution doesn't
└── supportsOnDeviceVision() → threshold too high (3GB)

MISSING:
├── Actual vision model (TFLite/ONNX/MediaPipe)
├── CameraX integration for capture
├── ML Kit for OCR/barcode
├── Object detection pipeline
├── Image classification model
├── On-device fine-tuning
└── Training data collection pipeline
```

---

## 2. On-Device Vision Research (2026)

### 2.1 Runtime Landscape

| Runtime | Status 2026 | Size | NNAPI | Best For |
|---------|-------------|------|-------|----------|
| **LiteRT** (ex-TFLite) | Production-ready, Google's primary | ~1.5MB runtime | ✅ | Classification, detection, segmentation |
| **ONNX Runtime Mobile** | Stable, 1.20+ | ~3MB runtime | ✅ | Cross-platform models, existing .onnx |
| **MediaPipe Tasks** | Production-ready | ~5-15MB | ✅ | Pre-built CV pipelines (detection, segmentation) |
| **Gemma 4 E2B** | April 2026 release | ~1.5GB quantized | Partial | Multimodal understanding (vision+text) |
| **ML Kit** | Stable | ~260KB base | ✅ | Text recognition, barcode, face detection |

### 2.2 Model Sizing for 2GB Phones

**Critical constraint:** 2GB total RAM, ~800MB-1.2GB available for app after OS.

| Model | Size | RAM Peak | Inference | Use Case |
|-------|------|----------|-----------|----------|
| ML Kit Text Recognition v2 | 260KB | ~30MB | 50-200ms | OCR (receipts, price tags) |
| ML Kit Barcode Scanning | 260KB | ~20MB | 30-100ms | Barcode/QR |
| MobileNetV3-Small (INT8) | 2.4MB | ~15MB | 15-30ms | Image classification |
| MobileNetV3-Large (INT8) | 5.4MB | ~25MB | 20-50ms | Image classification |
| EfficientNet-Lite0 (INT8) | 4.7MB | ~30MB | 30-60ms | Higher accuracy classification |
| YOLOv8n (INT8, 320px) | 6MB | ~50MB | 80-150ms | Object detection |
| YOLOv8n (INT4, 320px) | 3.5MB | ~35MB | 60-120ms | Object detection |
| Gemma 4 E2B (Q4) | ~1.5GB | ~1.8GB | 500ms+/token | ❌ Too heavy for 2GB phones |

**Verdict for 2GB phones:**
- ✅ ML Kit + MobileNetV3 + YOLOv8n = **~10MB total, ~100MB RAM peak** — fits easily
- ❌ Gemma 4 E2B = **1.8GB RAM peak** — does NOT fit on 2GB phones
- ⚠️ Gemma 4 E2B fits on 4GB+ phones only (with 3GB+ available)

### 2.3 Key Finding: Dual-Track Strategy

**Track A (2GB phones — primary target):**
ML Kit OCR + MobileNetV3 classifier + YOLOv8n detector
- All run on CPU via LiteRT/ONNX
- Total model size: ~10MB
- Peak RAM: ~100MB
- Battery: minimal impact

**Track B (4GB+ phones — future):**
Gemma 4 E2B for full multimodal understanding
- Requires 3GB+ available RAM
- Vision + text in single model
- Can replace Track A for complex tasks

### 2.4 Available Datasets for African Produce

| Dataset | Content | Size | License |
|---------|---------|------|---------|
| **Lacuna Fund** (dsfsi.co.za) | Crop-type classification for Sub-Saharan Africa (Kenya, Mali, Togo, Rwanda, Uganda) | ~50K images | Open source |
| **iNaturalist** | Global species including African fruits/vegetables | 2.7M+ images | CC BY-NC |
| **Open Images V7** | General objects including food items | 9M images | CC BY |
| **Food-101** | 101 food categories | 101K images | Academic |
| **UEC-Food-256** | 256 food categories | ~31K images | Academic |
| **Custom collection** | Msaidizi workers photograph their own products | Growing | Owned |

**Best strategy:** Start with MobileNetV3 pretrained on ImageNet → fine-tune on a custom Kenyan produce dataset collected through Msaidizi itself.

---

## 3. Vision Pipeline Architecture

### 3.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    CAMERA CAPTURE (CameraX)                 │
│  Preview → Capture → Auto-focus → Flash control             │
└──────────────────────────┬──────────────────────────────────┘
                           │ Bitmap
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                  IMAGE PREPROCESSOR                          │
│  Resize (512px) → Normalize → Enhance contrast              │
│  Already implemented in MultimodalPipeline.kt               │
└──────────┬──────────────┬──────────────┬────────────────────┘
           │              │              │
           ▼              ▼              ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│  OCR ENGINE  │ │  CLASSIFIER  │ │  DETECTOR    │
│  ML Kit v2   │ │  MobileNetV3 │ │  YOLOv8n     │
│  (260KB)     │ │  (2.4MB)     │ │  (6MB)       │
│              │ │              │ │              │
│  Receipts    │ │  Products    │ │  Inventory   │
│  Price tags  │ │  Categories  │ │  Counting    │
│  Documents   │ │  Brand recog │ │  Shelf scan  │
└──────┬───────┘ └──────┬───────┘ └──────┬───────┘
       │                │                │
       ▼                ▼                ▼
┌─────────────────────────────────────────────────────────────┐
│                  RESULT AGGREGATOR                           │
│  Parse OCR → Match products → Count items → Confidence      │
│  Bayesian fusion of multiple signals                        │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                  FEEDBACK & LEARNING LOOP                    │
│  Worker correction → "Hii si nyanya, ni pilipili"           │
│  Store labeled pair → Queue for fine-tuning                 │
│  Nightly on-device training → Updated model                 │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 Model Routing for Vision (Updated MoE)

```kotlin
// Updated routing: lightweight CV models for 2GB, Gemma E2B for 4GB+
fun routeVisionTask(inputType: InputType, deviceTier: PerformanceTier): VisionRoute {
    return when {
        // 2GB phones: lightweight specialized models
        deviceTier == PerformanceTier.LOW -> when (inputType) {
            InputType.RECEIPT_IMAGE -> VisionRoute(
                primary = VisionEngine.ML_KIT_OCR,
                fallback = VisionEngine.CLOUD_OCR,
                maxImageSize = 640
            )
            InputType.PRODUCT_IMAGE -> VisionRoute(
                primary = VisionEngine.MOBILENET_V3,
                fallback = VisionEngine.CLOUD_VISION,
                maxImageSize = 512
            )
            InputType.INVENTORY_IMAGE -> VisionRoute(
                primary = VisionEngine.YOLO_V8N,
                fallback = VisionEngine.MOBILENET_V3,
                maxImageSize = 320  // Smaller for detection speed
            )
            InputType.BARCODE_IMAGE -> VisionRoute(
                primary = VisionEngine.ML_KIT_BARCODE,
                fallback = null,
                maxImageSize = 640
            )
            InputType.DOCUMENT_IMAGE -> VisionRoute(
                primary = VisionEngine.ML_KIT_OCR,
                fallback = VisionEngine.CLOUD_OCR,
                maxImageSize = 1024
            )
        }
        // 4GB+ phones: Gemma 4 E2B for complex understanding
        deviceTier == PerformanceTier.HIGH -> VisionRoute(
            primary = VisionEngine.GEMMA_E2B,
            fallback = VisionEngine.MOBILENET_V3,
            maxImageSize = 512
        )
        // 3-4GB: MobileNet + ML Kit (no Gemma)
        else -> VisionRoute(
            primary = VisionEngine.MOBILENET_V3,
            fallback = VisionEngine.CLOUD_VISION,
            maxImageSize = 512
        )
    }
}

enum class VisionEngine {
    ML_KIT_OCR,        // 260KB, ~50ms
    ML_KIT_BARCODE,    // 260KB, ~30ms
    MOBILENET_V3,      // 2.4MB, ~20ms
    YOLO_V8N,          // 6MB, ~100ms
    GEMMA_E2B,         // 1.5GB, ~500ms/token
    CLOUD_OCR,         // Fallback
    CLOUD_VISION       // Fallback
}

data class VisionRoute(
    val primary: VisionEngine,
    val fallback: VisionEngine?,
    val maxImageSize: Int
)
```

### 3.3 Integration with Existing MoE Router

The existing `MoERouter.MULTIMODAL_EXPERT` should be updated:

```kotlin
// In MoERouter.kt — update MULTIMODAL_EXPERT to reflect actual capabilities
put(ExpertType.MULTIMODAL_EXPERT, ExpertProfile(
    type = ExpertType.MULTIMODAL_EXPERT,
    providerId = "on-device-vision",
    modelId = "mobilenet-v3-mlkit-yolo",  // Ensemble, not single model
    costPer1kInput = 0.0,
    costPer1kOutput = 0.0,
    maxContextTokens = 0,  // Not applicable for vision
    supportsVision = true,
    supportsAudio = false,
    supportsFunctionCalling = false,
    avgLatencyMs = 100,
    capabilities = listOf(
        "goods_recognition",     // MobileNetV3
        "receipt_scanning",      // ML Kit OCR
        "inventory_scan",        // YOLOv8n
        "price_tag_reading",     // ML Kit OCR + parsing
        "barcode_scanning"       // ML Kit Barcode
    )
))
```

---

## 4. Phase-by-Phase Implementation

### Phase 1: Receipt Scanning (OCR) — Weeks 1-2

**Goal:** Photograph a receipt → extract items, prices, total, vendor.

**Model:** Google ML Kit Text Recognition v2 (on-device, 260KB)

**Why ML Kit first:**
- Smallest footprint (260KB download)
- Works on all Android devices (API 21+)
- No GPU/NPU required — CPU-only
- Production-tested by Google
- Supports Latin script (English) and can handle handwritten text
- Offline by default

**Architecture:**
```
Camera Capture → Image Preprocess (existing) → ML Kit TextRecognizer
                                                     │
                                                     ▼
                                              Raw Text Blocks
                                                     │
                                                     ▼
                                         Receipt Parser (regex + heuristics)
                                                     │
                                                     ▼
                                    Structured: items[], total, vendor, date
                                                     │
                                                     ▼
                                         Transaction Agent → Record sale
```

**Receipt Parser Design:**
```kotlin
data class ParsedReceipt(
    val vendor: String?,
    val date: String?,
    val items: List<ReceiptItem>,
    val total: Double?,
    val currency: String,
    val rawText: String,
    val confidence: Double
)

data class ReceiptItem(
    val name: String,
    val quantity: Int?,
    val unitPrice: Double?,
    val totalPrice: Double
)

class ReceiptParser {
    // Common Kenyan receipt patterns
    private val totalPattern = Regex(
        """(?:TOTAL|Total|JUMLAH|GRAND\s*TOTAL|Amount)\s*:?\s*(?:KSh|KES|ksh)?\s*([\d,]+\.?\d*)""",
        RegexOption.IGNORE_CASE
    )
    
    private val itemPattern = Regex(
        """(.+?)\s+(\d+)\s*(?:x|×|@)\s*(?:KSh|KES)?\s*([\d,]+\.?\d*)\s+(?:KSh|KES)?\s*([\d,]+\.?\d*)"""
    )
    
    private val datePattern = Regex(
        """(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4})"""
    )
    
    fun parse(textBlocks: List<TextBlock>): ParsedReceipt {
        val fullText = textBlocks.joinToString("\n") { it.text }
        
        // Extract total
        val total = totalPattern.find(fullText)
            ?.groupValues?.get(1)
            ?.replace(",", "")?.toDoubleOrNull()
        
        // Extract date
        val date = datePattern.find(fullText)?.value
        
        // Extract vendor (usually first text block)
        val vendor = textBlocks.firstOrNull()?.text
        
        // Extract line items
        val items = itemPattern.findAll(fullText).map { match ->
            ReceiptItem(
                name = match.groupValues[1].trim(),
                quantity = match.groupValues[2].toIntOrNull(),
                unitPrice = match.groupValues[3].replace(",", "").toDoubleOrNull(),
                totalPrice = match.groupValues[4].replace(",", "").toDoubleOrNull() ?: 0.0
            )
        }.toList()
        
        return ParsedReceipt(
            vendor = vendor,
            date = date,
            items = items,
            total = total,
            currency = "KES",
            rawText = fullText,
            confidence = calculateConfidence(items, total)
        )
    }
    
    private fun calculateConfidence(items: List<ReceiptItem>, total: Double?): Double {
        if (items.isEmpty()) return 0.3
        val itemSum = items.sumOf { it.totalPrice }
        val totalMatch = if (total != null) {
            1.0 - minOf(kotlin.math.abs(itemSum - total) / total, 1.0)
        } else 0.5
        return (0.4 + 0.6 * totalMatch).coerceIn(0.0, 1.0)
    }
}
```

### Phase 2: Product Recognition (Classification) — Weeks 3-6

**Goal:** Photograph a product → identify it (tomato, mango, soap, etc.)

**Model:** MobileNetV3-Small (INT8, 2.4MB) with custom Kenyan product head

**Training Strategy:**
1. Start with ImageNet-pretrained MobileNetV3
2. Replace classification head with 50 Kenyan product categories
3. Fine-tune on collected data

**Product Categories (initial 50):**
```
FOOD: nyanya (tomato), pilipili (pepper), vitunguu (onion), 
      ndizi (banana), embe (mango), parachichi (avocado),
      nyama (meat), samaki (fish), maziwa (milk), 
      mkate (bread), unga (flour), mchele (rice),
      sukari (sugar), chai (tea), kahawa (coffee),
      viazi (potato), karoti (carrot), kabichi (cabbage),
      spinachi (spinach), tende (dates), zabibu (grapes)

HOUSEHOLD: sabuni (soap), dawa (medicine), mafuta (oil),
           tishu (tissue), mswaki (toothbrush), shampoo

ELECTRONICS: simu (phone), chaja (charger), kipaza sauti (speaker)

CLOTHING: nguo (clothes), viatu (shoes), kofia (hat)
```

**On-Device Training Loop:**
```
1. User photographs product → "Hii ni nyanya" (This is a tomato)
2. Store image + label in local SQLite: vision_training(image_path, label, timestamp)
3. Nightly (when charging + WiFi): run 5 epochs of fine-tuning
4. Updated model saved to app storage
5. Next inference uses improved model
```

### Phase 3: Inventory Counting (Detection) — Weeks 7-12

**Goal:** Photograph shelf → count items per product type

**Model:** YOLOv8n (INT8, 6MB, 320×320 input)

**Why YOLOv8n:**
- 6MB model size (fits 2GB phones)
- 320×320 input → fast inference (~100ms on ARM Cortex-A53)
- Pre-trained on COCO → fine-tune for market products
- Produces bounding boxes + class labels

**Detection Pipeline:**
```
Camera → Resize 320×320 → YOLOv8n inference → Bounding boxes
                                                      │
                                                      ▼
                                          NMS (non-max suppression)
                                                      │
                                                      ▼
                                    Count per class + confidence
                                                      │
                                                      ▼
                                    Inventory diff (compared to last scan)
                                                      │
                                                      ▼
                                    "Umekuja na nyanya 12, pilipili 8"
```

### Phase 4: Price Tag Reading (OCR + Parsing) — Weeks 13-15

**Goal:** Photograph price tag → extract product name + price

**Model:** ML Kit OCR (reuse from Phase 1) + price-specific parser

**Price Tag Parser:**
```kotlin
data class PriceTagInfo(
    val productName: String?,
    val price: Double?,
    val currency: String,
    val unit: String?,  // "per kg", "per piece", "per litre"
    val confidence: Double
)

class PriceTagParser {
    private val pricePattern = Regex(
        """(?:KSh|KES|ksh|/=)\s*([\d,]+\.?\d*)|([\d,]+\.?\d*)\s*(?:KSh|KES|/=|bob)""",
        RegexOption.IGNORE_CASE
    )
    
    private val unitPattern = Regex(
        """(?:per|kwa|/)\s*(kg|g|l|ltr|litre|piece|pc|packet|pkt|dozen|dz)""",
        RegexOption.IGNORE_CASE
    )
    
    fun parse(ocrText: String): PriceTagInfo {
        val price = pricePattern.find(ocrText)
            ?.groupValues?.getOrNull(1)?.ifEmpty { null }
            ?.replace(",", "")?.toDoubleOrNull()
            ?: pricePattern.find(ocrText)
                ?.groupValues?.getOrNull(2)?.ifEmpty { null }
                ?.replace(",", "")?.toDoubleOrNull()
        
        val unit = unitPattern.find(ocrText)?.groupValues?.get(1)
        
        // Product name is everything that's not price or unit
        val productName = ocrText
            .replace(pricePattern, "")
            .replace(unitPattern, "")
            .trim()
            .ifEmpty { null }
        
        return PriceTagInfo(
            productName = productName,
            price = price,
            currency = "KES",
            unit = unit,
            confidence = if (price != null) 0.8 else 0.3
        )
    }
}
```

---

## 5. Academic Framework Integration

### 5.1 Valentine's Statistics Background → Computer Vision

The `AcademicFramework.kt` maps CS307 (Computer Vision) to `AgentType.MULTIMODAL`. Here's how the stats units apply:

| Academic Unit | CV Application |
|---------------|----------------|
| **STA 101** (Intro Stats) | Mean pixel intensity for image normalization; mode for dominant color detection |
| **STA 201** (Probability) | Bayesian confidence calibration: P(tomato \| features) using prior from training data |
| **STA 301** (Statistical Inference) | Confidence intervals on product recognition: "85% ± 5% this is nyanya" |
| **STA 302** (Regression) | Price prediction from product features; quantity estimation from pixel area |
| **STA 303** (Time Series) | Inventory tracking over time; seasonal demand prediction from scan frequency |
| **STA 304** (Multivariate) | Multi-feature classification: color histogram + shape + texture → product class |
| **STA 341** (Estimation Theory) | MLE for model calibration; MAP estimation for few-shot product recognition |
| **STA 342** (Hypothesis Testing) | A/B testing vision model improvements; convergence detection in fine-tuning |
| **MTH 201** (Linear Algebra) | Convolution operations; feature vector similarity (cosine distance) |
| **MTH 301** (Numerical Methods) | INT8 quantization precision; floating-point optimization for mobile inference |

### 5.2 Fisher's Linear Discriminant for Product Classification

For fast on-device classification when MobileNetV3 is too slow:

```kotlin
/**
 * Fisher's Linear Discriminant for product classification.
 * 
 * Given feature vectors (color histograms, texture descriptors),
 * find the projection that maximizes class separation.
 * 
 * This is a classic statistical approach that Valentine's STA 304
 * (Multivariate Analysis) covers directly.
 * 
 * Advantage: Extremely fast (matrix multiply), no GPU needed.
 * Use case: Quick pre-filter before running full MobileNetV3.
 */
class FisherClassifier(
    private val classMeans: Map<String, FloatArray>,  // Per-class mean feature vectors
    private val withinClassScatter: FloatArray,        // S_W inverse
    private val globalMean: FloatArray                  // Overall mean
) {
    /**
     * Classify a feature vector using Fisher's discriminant.
     * Returns (className, confidence) pair.
     */
    fun classify(features: FloatArray): Pair<String, Double> {
        var bestClass = ""
        var bestScore = Double.NEGATIVE_INFINITY
        
        for ((className, classMean) in classMeans) {
            // Fisher projection: w = S_W^{-1} (μ_i - μ_global)
            val diff = FloatArray(features.size) { i ->
                classMean[i] - globalMean[i]
            }
            // Score = w^T x (project onto discriminant direction)
            val score = diff.zip(features) { a, b -> a * b }.sum()
            
            if (score > bestScore) {
                bestScore = score
                bestClass = className
            }
        }
        
        // Convert score to confidence using sigmoid
        val confidence = 1.0 / (1.0 + Math.exp(-bestScore))
        return bestClass to confidence
    }
    
    companion object {
        /**
         * Train Fisher classifier from labeled feature vectors.
         * Called during nightly fine-tuning.
         */
        fun train(data: List<Pair<String, FloatArray>>): FisherClassifier {
            val grouped = data.groupBy({ it.first }, { it.second })
            val globalMean = computeMean(data.map { it.second })
            val classMeans = grouped.mapValues { (_, vectors) -> computeMean(vectors) }
            // Simplified S_W computation
            val withinClassScatter = computeWithinClassScatter(data, classMeans)
            return FisherClassifier(classMeans, withinClassScatter, globalMean)
        }
        
        private fun computeMean(vectors: List<FloatArray>): FloatArray {
            val n = vectors.size
            return FloatArray(vectors[0].size) { i ->
                vectors.sumOf { it[i].toDouble() }.toFloat() / n
            }
        }
        
        private fun computeWithinClassScatter(
            data: List<Pair<String, FloatArray>>,
            classMeans: Map<String, FloatArray>
        ): FloatArray {
            // Simplified: return diagonal of S_W
            val dim = data.first().second.size
            val scatter = FloatArray(dim)
            for ((label, features) in data) {
                val mean = classMeans[label]!!
                for (i in 0 until dim) {
                    val diff = features[i] - mean[i]
                    scatter[i] += diff * diff
                }
            }
            return FloatArray(dim) { scatter[it] / data.size }
        }
    }
}
```

### 5.3 Bayesian Confidence Calibration

```kotlin
/**
 * Bayesian confidence calibration for vision recognition.
 * 
 * P(product | image_features) ∝ P(image_features | product) × P(product)
 * 
 * Prior P(product) comes from:
 * - What the worker usually sells (business profile)
 * - Time of day (morning → fresh produce, evening → cooked food)
 * - Location (market stall vs home)
 * - Season (mango season → higher P(mango))
 * 
 * This directly uses STA 201 (Probability) and STA 341 (Estimation Theory).
 */
class BayesianConfidenceCalibrator(
    private val classPriors: Map<String, Double>,  // P(product) from business profile
    private val locationPriors: Map<String, Map<String, Double>>,  // P(product | location)
    private val timePriors: Map<String, Map<String, Double>>       // P(product | time_of_day)
) {
    /**
     * Calibrate raw model confidence with Bayesian priors.
     * 
     * @param rawScores Raw softmax scores from MobileNetV3
     * @param locationId Current market/stall identifier
     * @param hourOfDay 0-23
     * @return Calibrated probabilities
     */
    fun calibrate(
        rawScores: Map<String, Double>,
        locationId: String? = null,
        hourOfDay: Int = 12
    ): Map<String, Double> {
        val timeSlot = when (hourOfDay) {
            in 5..10 -> "morning"
            in 11..14 -> "midday"
            in 15..17 -> "afternoon"
            else -> "evening"
        }
        
        // Posterior ∝ Likelihood × Prior
        val posteriors = rawScores.map { (product, likelihood) ->
            val classPrior = classPriors[product] ?: (1.0 / rawScores.size)
            val locationPrior = locationPriors[locationId]?.get(product) ?: 1.0
            val timePrior = timePriors[timeSlot]?.get(product) ?: 1.0
            
            product to (likelihood * classPrior * locationPrior * timePrior)
        }
        
        // Normalize to sum to 1
        val total = posteriors.sumOf { it.second }
        return posteriors.associate { (product, score) ->
            product to (score / total)
        }
    }
}
```

### 5.4 Time Series for Inventory Tracking

```kotlin
/**
 * Inventory tracking using time series analysis.
 * 
 * Tracks product quantities over time using exponential smoothing.
 * Based on STA 303 (Time Series Analysis).
 * 
 * Use cases:
 * - "Umeuza nyanya 12 leo" (You sold 12 tomatoes today)
 * - "Stock ya pilipili inaisha" (Pepper stock is running out)
 * - Predict when to restock
 */
class InventoryTimeSeries {
    // Exponential smoothing state per product
    private val smoothedQuantities = mutableMapOf<String, Double>()
    private val smoothedTrends = mutableMapOf<String, Double>()
    
    private val alpha = 0.3  // Smoothing factor for level
    private val beta = 0.1   // Smoothing factor for trend
    
    /**
     * Update inventory count from a vision scan.
     * 
     * @param productId Product identifier
     * @param observedCount Count from YOLOv8n detection
     * @param timestamp Observation time
     */
    fun observe(productId: String, observedCount: Int, timestamp: Long) {
        val prevLevel = smoothedQuantities[productId] ?: observedCount.toDouble()
        val prevTrend = smoothedTrends[productId] ?: 0.0
        
        // Holt's double exponential smoothing
        val newLevel = alpha * observedCount + (1 - alpha) * (prevLevel + prevTrend)
        val newTrend = beta * (newLevel - prevLevel) + (1 - beta) * prevTrend
        
        smoothedQuantities[productId] = newLevel
        smoothedTrends[productId] = newTrend
    }
    
    /**
     * Predict future inventory level.
     * 
     * @param productId Product to predict
     * @param periodsAhead Number of observation periods ahead
     * @return Predicted quantity
     */
    fun predict(productId: String, periodsAhead: Int = 1): Double {
        val level = smoothedQuantities[productId] ?: return 0.0
        val trend = smoothedTrends[productId] ?: return level
        return level + trend * periodsAhead
    }
    
    /**
     * Check if product needs restocking.
     * Returns (needsRestock, estimatedDaysUntilStockout)
     */
    fun restockAlert(productId: String, threshold: Int): Pair<Boolean, Int> {
        val current = smoothedQuantities[productId] ?: return false to -1
        val trend = smoothedTrends[productId] ?: 0.0
        
        if (current <= threshold) return true to 0
        if (trend >= 0) return false to -1  // Increasing or stable
        
        val daysUntilStockout = ((threshold - current) / trend).toInt()
        return (daysUntilStockout <= 3) to daysUntilStockout
    }
}
```

---

## 6. Daily Improvement System

### 6.1 How Msaidizi Learns From Every Photo

```
┌─────────────────────────────────────────────────────────────┐
│                    DAILY LEARNING CYCLE                      │
│                                                             │
│  MORNING: Worker uses Msaidizi to scan products             │
│    │                                                        │
│    ├── Photo → MobileNetV3 → "Hii ni nyanya" (85%)         │
│    ├── Worker confirms: "Ni nyanya ✓"                       │
│    └── Store: (image_features, "nyanya", confirmed=true)    │
│                                                             │
│    ├── Photo → MobileNetV3 → "Hii ni nyanya" (72%)         │
│    ├── Worker corrects: "Hii si nyanya, ni pilipili"        │
│    └── Store: (image_features, "pilipili", confirmed=false) │
│         + correction: was_nyanya → now_pilipili             │
│                                                             │
│  EVENING: Phone charging, connected to WiFi                 │
│    │                                                        │
│    ├── 1. Collect all new labeled samples                    │
│    ├── 2. Run Fisher classifier update (instant, <1s)        │
│    ├── 3. Run MobileNetV3 fine-tune (5 epochs, ~5 min)      │
│    ├── 4. Run Bayesian prior update                         │
│    ├── 5. Validate on held-out samples                      │
│    ├── 6. If accuracy improved → swap model                 │
│    └── 7. Upload anonymized gradients (federated)           │
│                                                             │
│  NEXT DAY: Worker uses improved model                       │
│    └── "Hii ni pilipili" (91%) ← now correctly identifies!  │
└─────────────────────────────────────────────────────────────┘
```

### 6.2 On-Device Fine-Tuning Pipeline

```kotlin
/**
 * On-device fine-tuning for product recognition.
 * 
 * Runs nightly when device is charging and connected to WiFi.
 * Uses transfer learning: freeze MobileNetV3 backbone, train only
 * the classification head (last 2 layers).
 * 
 * This keeps training fast (~5 min) and memory low (~200MB peak).
 */
class OnDeviceTrainer(
    private val context: Context,
    private val modelManager: VisionModelManager
) {
    // Training hyperparameters
    private val learningRate = 0.001f
    private val epochs = 5
    private val batchSize = 16
    
    /**
     * Fine-tune the vision model on newly collected samples.
     * 
     * @return TrainingResult with accuracy metrics
     */
    suspend fun fineTune(): TrainingResult = withContext(Dispatchers.Default) {
        // 1. Load new training samples from SQLite
        val samples = loadNewSamples()
        if (samples.size < 10) {
            return@withContext TrainingResult(
                success = false,
                reason = "Not enough samples (${samples.size}/10 minimum)"
            )
        }
        
        // 2. Split into train/validation (80/20)
        val (train, valid) = splitData(samples, 0.8)
        
        // 3. Load current model
        val model = modelManager.loadCurrentModel()
        
        // 4. Freeze backbone, only train classifier head
        val trainableParams = model.getClassifierParams()
        
        // 5. Training loop
        var bestAccuracy = 0.0
        var bestParams = trainableParams
        
        for (epoch in 1..epochs) {
            train.shuffle()
            var totalLoss = 0.0f
            
            for (batch in train.chunked(batchSize)) {
                val (images, labels) = prepareBatch(batch)
                val logits = model.forward(images, trainableParams)
                val loss = crossEntropyLoss(logits, labels)
                val gradients = computeGradients(loss, trainableParams)
                
                // SGD update
                for (i in trainableParams.indices) {
                    trainableParams[i] -= learningRate * gradients[i]
                }
                totalLoss += loss
            }
            
            // Validate
            val accuracy = evaluate(model, valid, trainableParams)
            if (accuracy > bestAccuracy) {
                bestAccuracy = accuracy
                bestParams = trainableParams.copyOf()
            }
            
            Timber.d("Fine-tune epoch $epoch: loss=$totalLoss, accuracy=$accuracy")
        }
        
        // 6. Save improved model
        if (bestAccuracy > modelManager.getCurrentAccuracy()) {
            modelManager.saveModel(bestParams, bestAccuracy)
            // Mark samples as used
            markSamplesTrained(samples)
        }
        
        TrainingResult(
            success = true,
            accuracy = bestAccuracy,
            samplesUsed = samples.size,
            epochs = epochs
        )
    }
    
    /**
     * Load newly collected samples that haven't been used for training yet.
     */
    private suspend fun loadNewSamples(): List<TrainingSample> {
        // Query SQLite for unprocessed vision_training entries
        return visionTrainingDao.getUnprocessed(limit = 500)
    }
}

data class TrainingSample(
    val imageFeatures: FloatArray,  // Extracted MobileNetV3 features
    val label: String,              // Product class
    val isCorrection: Boolean,      // True if worker corrected initial prediction
    val originalPrediction: String?, // What the model originally predicted
    val confidence: Double,
    val timestamp: Long
)

data class TrainingResult(
    val success: Boolean,
    val accuracy: Double = 0.0,
    val samplesUsed: Int = 0,
    val epochs: Int = 0,
    val reason: String = ""
)
```

### 6.3 Federated Learning for Vision Models

```kotlin
/**
 * Federated learning client for vision model improvement.
 * 
 * Workers' phones collectively improve the shared model
 * without sharing raw images (privacy-preserving).
 * 
 * Flow:
 * 1. Each phone trains locally → produces gradient updates
 * 2. Gradient updates encrypted and uploaded (small: ~50KB)
 * 3. Server aggregates gradients from all workers
 * 4. Updated global model distributed back
 * 
 * This leverages:
 * - STA 301: Statistical inference for convergence detection
 * - STA 401: Federated averaging algorithm
 * - MTH 201: Linear algebra for gradient aggregation
 * - IS 301: Differential privacy for worker protection
 */
class FederatedVisionClient(
    private val context: Context,
    private val syncManager: SyncManager
) {
    /**
     * Upload local gradient updates (not raw images).
     * 
     * Privacy guarantee: only ~50KB of gradient updates leave the device.
     * No images, no labels, no product names transmitted.
     */
    suspend fun uploadGradients(localModel: FloatArray, sampleCount: Int) {
        // Add Gaussian noise for differential privacy
        val noisyGradients = addDpNoise(localModel, epsilon = 1.0)
        
        // Encrypt before upload
        val encrypted = encrypt(noisyGradients)
        
        // Upload to aggregation server
        syncManager.uploadVisionGradients(
            gradients = encrypted,
            sampleCount = sampleCount,
            deviceId = getDeviceId(),
            timestamp = System.currentTimeMillis()
        )
    }
    
    /**
     * Download and apply global model update.
     * Called after server aggregates gradients from multiple workers.
     */
    suspend fun downloadGlobalUpdate(): Boolean {
        val globalUpdate = syncManager.downloadVisionModel() ?: return false
        
        // Validate model integrity
        if (!validateModel(globalUpdate)) return false
        
        // Apply to local model
        visionModelManager.applyGlobalUpdate(globalUpdate)
        return true
    }
    
    private fun addDpNoise(params: FloatArray, epsilon: Double): FloatArray {
        val sensitivity = 1.0  // L2 sensitivity
        val sigma = sensitivity / epsilon
        return FloatArray(params.size) { i ->
            params[i] + (gaussianRandom() * sigma).toFloat()
        }
    }
}
```

### 6.4 Worker Correction → "Hii si nyanya, ni pilipili"

```kotlin
/**
 * Correction handler — when Msaidizi gets it wrong, the worker teaches it.
 * 
 * This is the core of daily improvement:
 * Every correction becomes a training signal.
 */
class VisionCorrectionHandler(
    private val trainingDao: VisionTrainingDao,
    private val statsTracker: VisionStatsTracker
) {
    /**
     * Process a worker correction.
     * 
     * @param originalImagePath Path to the original photo
     * @param modelPrediction What the model thought it was
     * @param modelConfidence How confident the model was
     * @param correctLabel What it actually is (from worker)
     * @param language Language of correction (sw/en)
     */
    suspend fun handleCorrection(
        originalImagePath: String,
        modelPrediction: String,
        modelConfidence: Double,
        correctLabel: String,
        language: String = "sw"
    ) {
        // 1. Extract features from the image (for training)
        val features = extractFeatures(originalImagePath)
        
        // 2. Store correction as training sample
        val sample = TrainingSample(
            imageFeatures = features,
            label = correctLabel,
            isCorrection = true,
            originalPrediction = modelPrediction,
            confidence = modelConfidence,
            timestamp = System.currentTimeMillis()
        )
        trainingDao.insert(sample)
        
        // 3. Update Bayesian priors
        // If model frequently confuses nyanya with pilipili,
        // adjust the prior for that region of feature space
        statsTracker.recordCorrection(
            from = modelPrediction,
            to = correctLabel,
            confidence = modelConfidence
        )
        
        // 4. Log for analytics
        Timber.i(
            "Vision correction: %s → %s (confidence was %.2f)",
            modelPrediction, correctLabel, modelConfidence
        )
        
        // 5. Respond to worker in their language
        val response = if (language == "sw") {
            "Sawa! Sasa najua $correctLabel. Asante kunisaidia!"
        } else {
            "Got it! Now I know this is $correctLabel. Thanks for helping me learn!"
        }
        // Response is spoken back via TTS
    }
    
    /**
     * Parse Swahili correction commands.
     * "Hii si nyanya, ni pilipili" → (was: nyanya, now: pilipili)
     * "Si tomato, ni pepper" → (was: tomato, now: pepper)
     */
    fun parseCorrection(text: String): Pair<String, String>? {
        // Swahili pattern: "Hii si X, ni Y" or "Si X, ni Y"
        val swPattern = Regex(
            """(?:Hii\s+)?si\s+(\w+),?\s+ni\s+(\w+)""",
            RegexOption.IGNORE_CASE
        )
        swPattern.find(text)?.let { match ->
            return match.groupValues[1] to match.groupValues[2]
        }
        
        // English pattern: "Not X, it's Y" or "This is Y, not X"
        val enPattern = Regex(
            """(?:not|isn't)\s+(\w+),?\s+(?:it's|its|this is)\s+(\w+)""",
            RegexOption.IGNORE_CASE
        )
        enPattern.find(text)?.let { match ->
            return match.groupValues[1] to match.groupValues[2]
        }
        
        return null
    }
}
```

---

## 7. Model Selection & Sizing

### 7.1 Complete Model Inventory

| Model | File | Size | RAM Peak | Latency | Purpose |
|-------|------|------|----------|---------|---------|
| ML Kit Text v2 | Auto-downloaded | 260KB | 30MB | 50-200ms | OCR |
| ML Kit Barcode | Auto-downloaded | 260KB | 20MB | 30-100ms | Barcode/QR |
| MobileNetV3-Small INT8 | `mobilenet_v3_small_int8.tflite` | 2.4MB | 15MB | 15-30ms | Classification |
| YOLOv8n INT8 320 | `yolov8n_int8_320.tflite` | 6MB | 50MB | 80-150ms | Detection |
| Product labels | `products.json` | 50KB | 1MB | - | Label mapping |
| Fisher classifier | `fisher_model.bin` | 100KB | 5MB | <5ms | Fast pre-filter |
| **TOTAL** | | **~9MB** | **~100MB** | | |

### 7.2 Bundle vs Download Strategy

```kotlin
/**
 * Model management for vision models.
 * 
 * Strategy:
 * - ML Kit models: bundled (auto-downloaded by Google Play Services)
 * - MobileNetV3: bundled in APK (2.4MB small enough)
 * - YOLOv8n: downloaded on first use (6MB, might not need for all users)
 * - Fisher classifier: generated on-device from training data
 */
class VisionModelManager(private val context: Context) {
    
    private val modelsDir = File(context.filesDir, "vision_models")
    
    // Bundled models (in assets/)
    val mobilenetPath: File
        get() = File(modelsDir, "mobilenet_v3_small_int8.tflite")
    
    // Downloaded models
    val yolov8nPath: File
        get() = File(modelsDir, "yolov8n_int8_320.tflite")
    
    /**
     * Initialize vision models.
     * Copies bundled models from assets to internal storage.
     */
    suspend fun initialize() {
        modelsDir.mkdirs()
        
        // Copy MobileNetV3 from assets (bundled)
        if (!mobilenetPath.exists()) {
            context.assets.open("models/mobilenet_v3_small_int8.tflite").use { input ->
                mobilenetPath.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        
        // YOLOv8n downloaded on-demand when inventory scanning is first used
    }
    
    /**
     * Download YOLOv8n model (called when user first uses inventory scanning).
     */
    suspend fun downloadYOLOv8n(): Boolean {
        if (yolov8nPath.exists()) return true
        
        return try {
            // Download from CDN
            val url = "https://models.msaidizi.app/vision/yolov8n_int8_320.tflite"
            downloadToFile(url, yolov8nPath)
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to download YOLOv8n model")
            false
        }
    }
    
    /**
     * Get current model accuracy (for tracking improvement over time).
     */
    fun getCurrentAccuracy(): Double {
        val prefs = context.getSharedPreferences("vision_models", 0)
        return prefs.getFloat("current_accuracy", 0.0f).toDouble()
    }
    
    /**
     * Save improved model after fine-tuning.
     */
    fun saveModel(params: FloatArray, accuracy: Double) {
        // Save to temp file, then atomic rename
        val tempFile = File(modelsDir, "mobilenet_v3_finetuned.tflite.tmp")
        // ... write params ...
        tempFile.renameTo(File(modelsDir, "mobilenet_v3_finetuned.tflite"))
        
        context.getSharedPreferences("vision_models", 0).edit()
            .putFloat("current_accuracy", accuracy.toFloat())
            .putLong("last_trained", System.currentTimeMillis())
            .apply()
    }
}
```

---

## 8. Implementation Plan

### 8.1 Timeline

```
WEEK 1-2: Receipt Scanning (OCR)
├── Day 1-2: Add ML Kit dependency, create VisionModelManager
├── Day 3-4: Implement ReceiptParser with Kenyan receipt patterns
├── Day 5-6: Build CameraX capture screen
├── Day 7-8: Wire OCR → ReceiptParser → Transaction Agent
├── Day 9-10: Test with real Kenyan receipts, iterate on parser
└── Milestone: "Piga picha ya receipt → inatransaction moja kwa moja"

WEEK 3-6: Product Recognition (Classification)
├── Week 3: 
│   ├── Add MobileNetV3 INT8 TFLite model to assets
│   ├── Implement TFLite inference wrapper
│   └── Create initial product label set (50 products)
├── Week 4:
│   ├── Build product labeling UI ("Hii ni nini?" → worker taps)
│   ├── Collect initial 500 labeled photos (internal testing)
│   └── Implement Fisher classifier for fast pre-filter
├── Week 5:
│   ├── Fine-tune MobileNetV3 on collected data
│   ├── Implement Bayesian confidence calibrator
│   └── Integrate with price lookup (Soko Pulse)
├── Week 6:
│   ├── Field test with 10 real workers
│   ├── Collect correction data ("Hii si X, ni Y")
│   ├── Implement OnDeviceTrainer
│   └── Milestone: "Piga picha ya bidhaa → inajua ni nini"

WEEK 7-12: Inventory Counting (Detection)
├── Week 7-8: Add YOLOv8n, implement detection pipeline
├── Week 9-10: Build inventory scan UI with counting overlay
├── Week 11: Implement InventoryTimeSeries tracking
├── Week 12: Integration testing, field test
└── Milestone: "Piga picha ya shelf → inahesabu items"

WEEK 13-15: Price Tag Reading (OCR + Parsing)
├── Week 13: PriceTagParser with Kenyan patterns
├── Week 14: Price comparison with Soko Pulse
├── Week 15: Integration + field test
└── Milestone: "Piga picha ya price tag → inapata bei"

WEEK 16+: Daily Improvement Loop
├── Federated learning client
├── Nightly fine-tuning pipeline
├── A/B testing framework for model versions
└── Continuous collection from field workers
```

### 8.2 What Can Be Built in 1 Week (Quick Win)

**Receipt OCR in 5 days:**

```kotlin
// Day 1: Add dependency
// build.gradle.kts
implementation("com.google.mlkit:text-recognition:16.0.1")

// Day 2: Create OcrEngine
class MlKitOcrEngine(private val context: Context) {
    private val recognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )
    
    suspend fun recognizeText(bitmap: Bitmap): List<TextBlock> = 
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    cont.resume(result.textBlocks.map { block ->
                        TextBlock(
                            text = block.text,
                            boundingBox = block.boundingBox,
                            confidence = block.lines.flatMap { it.elements }
                                .mapNotNull { it.confidence }.average()
                        )
                    })
                }
                .addOnFailureListener { e ->
                    cont.resumeWithException(e)
                }
        }
}

// Day 3-4: ReceiptParser (see Phase 1 above)
// Day 5: Wire into MultimodalPipeline.processProductImage()
```

### 8.3 Training Data Requirements

| Phase | Minimum Samples | Ideal Samples | Collection Method |
|-------|----------------|---------------|-------------------|
| Receipt OCR | 0 (ML Kit pre-trained) | 50 receipts | Photograph real receipts |
| Product Classification | 50 per class × 50 classes = 2,500 | 200 per class = 10,000 | Worker labeling + web scraping |
| Inventory Detection | 100 annotations per class × 20 classes = 2,000 | 500 per class = 10,000 | Bounding box annotation tool |
| Price Tag OCR | 0 (ML Kit pre-trained) | 100 price tags | Photograph market price tags |

---

## 9. Code Examples

### 9.1 Complete Vision Inference Engine

```kotlin
/**
 * Unified vision inference engine for Msaidizi.
 * 
 * Handles all vision tasks on 2GB phones using lightweight models.
 * Integrates with existing MultimodalPipeline and MoERouter.
 */
class VisionInferenceEngine(
    private val context: Context,
    private val modelManager: VisionModelManager
) {
    // Lazy-loaded interpreters
    private var mobilenetInterpreter: Interpreter? = null
    private var yoloInterpreter: Interpreter? = null
    private val textRecognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )
    
    // Fisher classifier for fast pre-filtering
    private var fisherClassifier: FisherClassifier? = null
    
    // Bayesian calibrator
    private var calibrator: BayesianConfidenceCalibrator? = null
    
    /**
     * Initialize all models. Call during app startup.
     */
    suspend fun initialize() {
        modelManager.initialize()
        
        // Load MobileNetV3
        val mobilenetBuffer = loadModelFile(modelManager.mobilenetPath)
        mobilenetInterpreter = Interpreter(mobilenetBuffer, Interpreter.Options().apply {
            setNumThreads(2)  // Conservative for 2GB phones
            setUseXNNPACK(true)  // CPU optimization
        })
        
        // Load Fisher classifier if available
        fisherClassifier = loadFisherClassifier()
        
        // Load Bayesian calibrator
        calibrator = loadCalibrator()
    }
    
    /**
     * Process a vision task.
     * 
     * This is the main entry point called by MultimodalPipeline.
     */
    suspend fun process(
        bitmap: Bitmap,
        inputType: MultimodalPipeline.InputType,
        context: VisionContext = VisionContext()
    ): MultimodalResult {
        val startTime = System.currentTimeMillis()
        
        return when (inputType) {
            MultimodalPipeline.InputType.RECEIPT_IMAGE -> 
                processReceipt(bitmap)
            
            MultimodalPipeline.InputType.PRODUCT_IMAGE -> 
                processProduct(bitmap, context)
            
            MultimodalPipeline.InputType.INVENTORY_IMAGE -> 
                processInventory(bitmap)
            
            MultimodalPipeline.InputType.DOCUMENT_IMAGE -> 
                processDocument(bitmap)
            
            MultimodalPipeline.InputType.BARCODE_IMAGE -> 
                processBarcode(bitmap)
        }.copy(processingTimeMs = System.currentTimeMillis() - startTime)
    }
    
    // ── Receipt Processing ────────────────────────────────────
    
    private suspend fun processReceipt(bitmap: Bitmap): MultimodalResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        val textBlocks = suspendCancellableCoroutine<List<TextBlock>> { cont ->
            textRecognizer.process(image)
                .addOnSuccessListener { result ->
                    cont.resume(result.textBlocks.map { block ->
                        TextBlock(block.text, block.boundingBox)
                    })
                }
                .addOnFailureListener { cont.resume(emptyList()) }
        }
        
        val parsed = ReceiptParser().parse(textBlocks)
        
        return MultimodalResult(
            inputType = MultimodalPipeline.InputType.RECEIPT_IMAGE,
            recognizedProduct = parsed.vendor,
            confidence = parsed.confidence,
            extractedData = mapOf(
                "vendor" to (parsed.vendor ?: ""),
                "date" to (parsed.date ?: ""),
                "total" to (parsed.total?.toString() ?: ""),
                "item_count" to parsed.items.size.toString(),
                "currency" to parsed.currency
            ) + parsed.items.mapIndexed { i, item ->
                "item_${i}" to "${item.name}: ${item.quantity}x${item.unitPrice}=${item.totalPrice}"
            }.toMap(),
            expertUsed = "mlkit-ocr"
        )
    }
    
    // ── Product Recognition ───────────────────────────────────
    
    private suspend fun processProduct(
        bitmap: Bitmap, 
        ctx: VisionContext
    ): MultimodalResult {
        // Step 1: Fast Fisher pre-filter (if available)
        val fisherResult = fisherClassifier?.let { classifier ->
            val features = extractMobileNetFeatures(bitmap)
            classifier.classify(features)
        }
        
        // Step 2: If Fisher confidence > 0.95, skip MobileNet (saves battery)
        if (fisherResult != null && fisherResult.second > 0.95) {
            val calibrated = calibrator?.calibrate(
                mapOf(fisherResult.first to fisherResult.second),
                ctx.locationId,
                ctx.hourOfDay
            ) ?: mapOf(fisherResult.first to fisherResult.second)
            
            return MultimodalResult(
                inputType = MultimodalPipeline.InputType.PRODUCT_IMAGE,
                recognizedProduct = fisherResult.first,
                confidence = calibrated.values.max(),
                extractedData = calibrated.mapKeys { it.key },
                expertUsed = "fisher-classifier"
            )
        }
        
        // Step 3: Full MobileNetV3 inference
        val preprocessed = preprocessForMobileNet(bitmap)
        val output = Array(1) { FloatArray(NUM_CLASSES) }
        mobilenetInterpreter?.run(preprocessed, output)
        
        // Step 4: Apply softmax
        val probabilities = softmax(output[0])
        
        // Step 5: Build score map
        val scores = PRODUCT_LABELS.zip(probabilities.toList())
            .sortedByDescending { it.second }
            .take(5)
            .associate { it.first to it.second.toDouble() }
        
        // Step 6: Bayesian calibration
        val calibrated = calibrator?.calibrate(scores, ctx.locationId, ctx.hourOfDay) 
            ?: scores
        
        val topProduct = calibrated.maxByOrNull { it.value }
        
        return MultimodalResult(
            inputType = MultimodalPipeline.InputType.PRODUCT_IMAGE,
            recognizedProduct = topProduct?.key,
            confidence = topProduct?.value ?: 0.0,
            extractedData = calibrated.mapKeys { it.key },
            expertUsed = "mobilenet-v3"
        )
    }
    
    // ── Inventory Counting ────────────────────────────────────
    
    private suspend fun processInventory(bitmap: Bitmap): MultimodalResult {
        // Download YOLOv8n if not available
        if (yoloInterpreter == null) {
            if (!modelManager.downloadYOLOv8n()) {
                // Fallback: use MobileNetV3 on grid tiles
                return processInventoryFallback(bitmap)
            }
            val yoloBuffer = loadModelFile(modelManager.yolov8nPath)
            yoloInterpreter = Interpreter(yoloBuffer, Interpreter.Options().apply {
                setNumThreads(2)
            })
        }
        
        // Preprocess for YOLO (320×320, normalized)
        val input = preprocessForYOLO(bitmap, 320)
        val output = Array(1) { Array(25200) { FloatArray(6) } }  // YOLOv8n output
        yoloInterpreter?.run(input, output)
        
        // Parse detections
        val detections = parseYOLOOutput(output[0], confThreshold = 0.45f)
        
        // Count per class
        val counts = detections.groupBy { it.className }
            .mapValues { it.value.size }
        
        return MultimodalResult(
            inputType = MultimodalPipeline.InputType.INVENTORY_IMAGE,
            confidence = detections.map { it.confidence }.average(),
            extractedData = counts.mapKeys { it.key } + mapOf(
                "total_items" to detections.size.toString(),
                "detection_count" to detections.size.toString()
            ),
            expertUsed = "yolov8n"
        )
    }
    
    // ── Helpers ───────────────────────────────────────────────
    
    private fun preprocessForMobileNet(bitmap: Bitmap): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
        val buffer = ByteBuffer.allocateDirect(224 * 224 * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())
        
        val pixels = IntArray(224 * 224)
        resized.getPixels(pixels, 0, 224, 0, 0, 224, 224)
        
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16 and 0xFF) / 127.5f) - 1.0f)  // R
            buffer.putFloat(((pixel shr 8 and 0xFF) / 127.5f) - 1.0f)   // G
            buffer.putFloat(((pixel and 0xFF) / 127.5f) - 1.0f)          // B
        }
        
        return buffer
    }
    
    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.max()
        val exps = logits.map { Math.exp((it - max).toDouble()).toFloat() }
        val sum = exps.sum()
        return exps.map { it / sum }.toFloatArray()
    }
    
    companion object {
        const val NUM_CLASSES = 50
        val PRODUCT_LABELS = listOf(
            "nyanya", "pilipili", "vitunguu", "ndizi", "embe", "parachichi",
            "nyama", "samaki", "maziwa", "mkate", "unga", "mchele",
            "sukari", "chai", "kahawa", "viazi", "karoti", "kabichi",
            "spinachi", "tende", "zabibu", "sabuni", "dawa", "mafuta",
            "tishu", "mswaki", "shampoo", "simu", "chaja", "kipaza_sauti",
            "nguo", "viatu", "kofia", "mafuta_ya_upishi", "dagaa",
            "njugu", "maharage", "choroko", "njahi", "mtama",
            "wimbi", "mihogo", "ikimbia", "malenge", "bamia",
            "matango", "lettuce", "terere", "managu", "kunde"
        )
    }
}

data class VisionContext(
    val locationId: String? = null,
    val hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
    val workerId: String? = null
)

data class TextBlock(
    val text: String,
    val boundingBox: android.graphics.Rect? = null,
    val confidence: Double = 0.0
)

data class YoloDetection(
    val className: String,
    val confidence: Float,
    val boundingBox: android.graphics.RectF
)
```

### 9.2 CameraX Integration

```kotlin
/**
 * Camera screen for vision tasks.
 * Uses CameraX for consistent behavior across 2GB+ devices.
 */
@Composable
fun VisionCaptureScreen(
    inputType: MultimodalPipeline.InputType,
    onCapture: (Bitmap) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val imageCapture = remember { ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setTargetResolution(android.util.Size(1280, 720))
        .build()
    }
    
    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { previewView ->
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
        }
    )
    
    // Capture button
    Button(
        onClick = {
            imageCapture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bitmap = image.toBitmap()
                        image.close()
                        onCapture(bitmap)
                    }
                }
            )
        },
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(32.dp)
            .size(72.dp),
        shape = CircleShape
    ) {
        Icon(Icons.Default.CameraAlt, "Capture")
    }
    
    // Guidance text based on input type
    Text(
        text = when (inputType) {
            MultimodalPipeline.InputType.RECEIPT_IMAGE -> 
                "Piga picha ya receipt\nPoint camera at receipt"
            MultimodalPipeline.InputType.PRODUCT_IMAGE -> 
                "Piga picha ya bidhaa\nPoint camera at product"
            MultimodalPipeline.InputType.INVENTORY_IMAGE -> 
                "Piga picha ya shelf\nPoint camera at shelf"
            MultimodalPipeline.InputType.BARCODE_IMAGE -> 
                "Piga picha ya barcode\nPoint camera at barcode"
            else -> "Point camera at target"
        },
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(16.dp)
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(12.dp)
    )
}
```

---

## 10. Cost, Battery & Storage Budget

### 10.1 Per-Scan Cost

| Operation | Battery | Data | Time | Cost |
|-----------|---------|------|------|------|
| Receipt OCR | ~2mAh | 0KB (offline) | 200ms | $0.00 |
| Product Classification | ~1mAh | 0KB (offline) | 30ms | $0.00 |
| Inventory Detection | ~5mAh | 0KB (offline) | 150ms | $0.00 |
| Price Tag OCR | ~2mAh | 0KB (offline) | 100ms | $0.00 |
| Cloud fallback (if offline fails) | ~10mAh | ~50KB | 2-5s | $0.001 |

### 10.2 Daily Budget (50 scans/day)

| Metric | Value |
|--------|-------|
| Total battery | ~100mAh (0.5-1% of 3000mAh battery) |
| Storage (models) | ~10MB |
| Storage (training data) | ~50MB (500 images × 100KB) |
| Data usage | 0KB (fully offline) |
| Cost | $0.00 |
| RAM peak | ~100MB (leaves 700MB+ for OS + other apps) |

### 10.3 Nightly Training Budget

| Metric | Value |
|--------|-------|
| Duration | 5-10 minutes |
| Battery | ~50mAh (runs while charging) |
| RAM peak | ~200MB |
| Storage for gradients | ~50KB |
| Federated upload | ~50KB compressed |

---

## Summary

**Key Design Decisions:**

1. **ML Kit + MobileNetV3 + YOLOv8n ensemble** for 2GB phones (not Gemma 4 E2B)
2. **Phase 1 (OCR) is the quick win** — 5 days to working receipt scanning
3. **On-device fine-tuning nightly** — every worker correction improves the model
4. **Fisher classifier as fast pre-filter** — <5ms for common products, skip MobileNet
5. **Bayesian calibration** — worker's business context improves recognition accuracy
6. **Dual-track architecture** — lightweight models for 2GB, Gemma E2B for 4GB+
7. **Training data collection through usage** — the app bootstraps its own dataset

**What makes this work on 2GB phones:**
- Total model size: ~10MB (not 1.5GB)
- Peak RAM: ~100MB (not 1.8GB)
- All inference on CPU (no GPU/NPU needed)
- Fully offline (no data costs)
- Battery-friendly (~1% per day with 50 scans)

**How it improves daily:**
- Every "Hii si nyanya, ni pilipili" becomes a training sample
- Nightly fine-tuning on-device (5 min while charging)
- Fisher classifier updates instantly from corrections
- Bayesian priors adapt to each worker's product mix
- Federated learning shares improvements across all workers
