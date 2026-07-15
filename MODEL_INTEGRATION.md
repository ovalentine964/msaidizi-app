# MODEL_INTEGRATION.md — Qwen 3.5 0.8B On-Device Model Integration

## Overview

Msaidizi uses **Qwen 3.5 0.8B Q4_K_M** as the primary on-device LLM for all device tiers.
This document covers model specifications, download infrastructure, bundling, memory requirements,
and performance benchmarks.

---

## 1. Model Specifications

| Property              | Value                                                |
|-----------------------|------------------------------------------------------|
| **Model**             | Qwen 3.5 0.8B                                       |
| **Quantization**      | Q4_K_M (4-bit, k-quant mixed)                       |
| **Format**            | GGUF (llama.cpp compatible)                          |
| **File**              | `Qwen3.5-0.8B-Q4_K_M.gguf`                          |
| **File Size**         | ~580 MB (579,615,840 bytes)                          |
| **RAM (loaded)**      | ~600 MB                                              |
| **Parameters**        | 0.8B                                                 |
| **Context Length**    | 32,768 tokens (Qwen 3.5 extended context)            |
| **License**           | Apache 2.0                                           |
| **Languages**         | 100+ (strong Swahili, Yoruba, Hausa, Amharic, etc.)  |
| **Special Features**  | Thinking mode, non-thinking mode, improved reasoning  |
| **SHA-256**           | `fb044e93939a70469c905781334f5de1e6c8b608ced6cbc8c9249bd4127d9526` |

### Expected Performance (ARM64)

| Device Tier   | RAM     | Tokens/sec | Latency (first token) | Notes                    |
|---------------|---------|------------|----------------------|--------------------------|
| LOW (2GB)     | 2 GB    | ~8-10      | ~500ms               | Sequential mode required |
| MID (3-4GB)   | 3-4 GB  | ~10-12     | ~300ms               | Standard loading         |
| HIGH (6GB+)   | 6+ GB   | ~12-15     | ~200ms               | Can preload              |

---

## 2. Download URLs

### Primary Download (HuggingFace)

```
https://huggingface.co/bartowski/Qwen_Qwen3.5-0.8B-GGUF/resolve/main/Qwen_Qwen3.5-0.8B-Q4_K_M.gguf
```

### Alternative Hosting (if HuggingFace is slow in Africa)

```
https://github.com/ovalentine964/msaidizi-models/releases/download/v1.0/Qwen3.5-0.8B-Q4_K_M.gguf
```

### CDN Base URL (for all models)

```
https://huggingface.co/ovalentine964/msaidizi-models/resolve/main
```

---

## 3. Model Bundling Strategy

### Approach: Bundled Mini + Background Full Download

The APK ships with the full Qwen 3.5 0.8B model as a bundled asset for instant first-launch
functionality. A background download mechanism ensures the model stays up-to-date.

#### Bundled Assets (extracted on first launch)

| Asset                        | Size     | Purpose                |
|------------------------------|----------|------------------------|
| `whisper-encoder-int8.onnx`  | ~10 MB   | Speech recognition     |
| `whisper-decoder-int8.onnx`  | ~31 MB   | Speech recognition     |
| `whisper-tokens.json`        | ~2.5 MB  | Tokenizer              |
| `piper-swahili.onnx`         | ~63 MB   | Text-to-speech         |
| `piper-tokens.txt`           | ~1 MB    | TTS tokenizer          |
| `Qwen3.5-0.8B-Q4_K_M.gguf` | ~580 MB  | On-device LLM          |
| `espeak-ng-data/`            | ~15 MB   | Phoneme data for TTS   |

**Total bundled size:** ~700 MB

#### Build Instructions

1. Place model files in `app/src/main/assets/models/`
2. Ensure filenames match exactly (case-sensitive on Android):
   ```
   app/src/main/assets/models/Qwen3.5-0.8B-Q4_K_M.gguf
   ```
3. Update `BundledModelManager.BUNDLED_ASSETS` if filenames change
4. APK size will increase by ~580 MB for the LLM model

#### Reducing APK Size (Alternative)

If APK size is a concern, remove the LLM from bundled assets and rely on first-launch download:

1. Remove `Qwen3.5-0.8B-Q4_K_M.gguf` from `app/src/main/assets/models/`
2. The `BundledModelManager` will trigger a background download on first launch
3. The app will use rule-based responses until the model downloads

---

## 4. Memory Requirements Per Device Tier

### RAM Budget (2GB Device — BASIC Tier)

| Component              | RAM (MB) | Notes                              |
|------------------------|----------|------------------------------------|
| Android OS + framework | ~800     | System overhead                    |
| App heap               | ~200     | Kotlin/Java runtime                |
| Whisper (ASR)          | ~200     | Loaded during voice input only     |
| Qwen 3.5 0.8B (LLM)   | ~600     | Loaded during inference only       |
| Piper (TTS)            | ~80      | Loaded during speech output only   |
| **Total peak**         | ~1,880   | Sequential mode: only 1 at a time |

**Strategy:** On 2GB devices, `SequentialModelLoader` ensures only ONE model is in memory
at any time: ASR → LLM → TTS, each loaded and unloaded sequentially.

### RAM Budget (3-4GB Device — MID Tier)

| Component              | RAM (MB) | Notes                              |
|------------------------|----------|------------------------------------|
| Android OS + framework | ~1,000   |                                    |
| App heap               | ~300     |                                    |
| Qwen 3.5 0.8B (LLM)   | ~600     | Can stay loaded                    |
| Whisper (ASR)          | ~200     | Can coexist with LLM               |
| **Total peak**         | ~2,100   | Parallel loading possible          |

### RAM Budget (6GB+ Device — HIGH Tier)

| Component              | RAM (MB) | Notes                              |
|------------------------|----------|------------------------------------|
| Android OS + framework | ~1,200   |                                    |
| App heap               | ~400     |                                    |
| Qwen 3.5 2B (LLM)     | ~1,200   | Higher-quality model available     |
| Whisper (ASR)          | ~200     | Preloaded                          |
| Piper (TTS)            | ~80      | Preloaded                          |
| Gemma 4 E2B (Vision)   | ~600     | On-demand for image tasks          |
| **Total peak**         | ~3,680   | All models can coexist             |

---

## 5. Model Loading Architecture

### Engine: llama.cpp NDK

The model runs via `LlamaCppEngine` which uses JNI bindings to llama.cpp:

```
LlamaCppEngine → nativeLoadModel() → llama.cpp C++ → GGUF mmap
```

#### Key Configuration

| Parameter          | LOW Tier | MID Tier | HIGH Tier | Notes                    |
|--------------------|----------|----------|-----------|--------------------------|
| Context length     | 1,024    | 2,048    | 4,096     | Qwen 3.5 supports 32K   |
| CPU threads        | 2        | 2        | 4         | Half of available cores  |
| KV cache Q4_0      | Yes      | Yes      | No        | 4-bit KV cache on ≤3GB   |
| Sequential mode    | Yes      | No       | No        | One model at a time      |

#### KV Cache Q4_0 Optimization

On devices with ≤3GB RAM, KV cache uses Q4_0 quantization (4-bit) instead of FP16.
This reduces KV cache memory by ~4x and provides a 2-3x inference speed boost by
reducing memory bandwidth pressure during the autoregressive decode loop.

---

## 6. Mutual Exclusion & Memory Management

### LoadedHeavyModel Enum

On BASIC tier (2GB) devices, only ONE heavy model may be loaded at a time:

```kotlin
enum class LoadedHeavyModel { NONE, WHISPER, KOKORO, LLM }
```

The `MemoryManager.acquireHeavyModelSlot()` method enforces this:
- Before loading a new model, the currently loaded model is unloaded
- Memory availability is checked before proceeding
- OOM recovery releases all models

### Memory Thresholds

| Threshold               | Value  | Action                           |
|-------------------------|--------|----------------------------------|
| `LOW_MEMORY_THRESHOLD`  | 150 MB | Trim caches, release non-essential |
| `CRITICAL_MEMORY`       | 80 MB  | Emergency: release all models    |
| `MODEL_RELEASE`         | 200 MB | Release models below this free   |
| `MIN_FREE_TO_LOAD`      | 200 MB | Refuse to load model if below    |

---

## 7. MoE Router Integration

The MoE (Mixture-of-Experts) Router routes tasks to the appropriate model:

| Task Type            | Primary Expert       | Fallback             |
|----------------------|----------------------|----------------------|
| Transaction recording| On-device (Qwen 0.8B)| DeepSeek V4 Flash    |
| Balance inquiry      | On-device (Qwen 0.8B)| DeepSeek V4 Flash    |
| Credit assessment    | DeepSeek V4 Flash    | Claude Haiku         |
| Goods recognition    | Gemma 4 E2B (Vision) | DeepSeek V4 Flash    |
| Growth planning      | Claude Haiku         | DeepSeek V4 Flash    |

### Cost Model

- **80% of requests** → On-device Qwen 0.8B ($0.00/request)
- **15% of requests** → Cloud reasoning ($0.001/request)
- **5% of requests** → Cloud premium ($0.01-0.05/request)
- **Average:** $0.0015/request ≈ $0.013/user/month

---

## 8. Model Versioning & Upgrade Path

### Current Upgrade Path

```
Qwen 0.5B (deprecated) → Qwen 3.5 0.8B (current) → Qwen 3.5 2B (future)
```

### ModelVersionManager

The `ModelVersionManager` tracks installed model versions and supports:
- A/B testing between model versions
- Automatic rollback on failure
- Device-aware model recommendation
- Progressive download for large models

### Model ID Registry

| Model ID                | File                              | Status      |
|-------------------------|-----------------------------------|-------------|
| `qwen-3.5-0.8b-q4km`   | `Qwen3.5-0.8B-Q4_K_M.gguf`      | **Current** |
| `qwen3.5-2b-q4km`      | `qwen3.5-2b-q4_k_m.gguf`         | Future      |
| `gemma-4-e2b-q4km`     | `gemma-4-e2b-q4_k_m.gguf`        | Alternative |

### Backward Compatibility Aliases

Old model IDs are aliased to new IDs in `ModelRegistry`:

```kotlin
"qwen-0.5b-q4km"      → "qwen-3.5-0.8b-q4km"
"qwen3.5-0.8b-q4km"   → "qwen-3.5-0.8b-q4km"
"qwen-3.5-0.8b-mini"  → "qwen-3.5-0.8b-q4km"
```

---

## 9. Model Download Infrastructure

### Existing: ModelDownloader + WorkManager

The app already has a robust download infrastructure:

- **ModelDownloader** — orchestrates downloads with resume capability
- **WorkManager** — background execution that survives process death
- **WiFi-only mode** — large models (>50MB) default to WiFi
- **SHA-256 verification** — integrity check before use
- **Progress notifications** — persistent notification with progress bar
- **Exponential backoff** — retry with increasing delays

### Download Flow

```
1. App starts → BundledModelManager.initialize()
2. Check if bundled models extracted → if not, extract from APK assets
3. Background: ModelDownloader.downloadModel("qwen-3.5-0.8b-q4km")
4. SHA-256 verification
5. Model ready → notify UI
```

### Adding a New Model

To add a new model to the download infrastructure:

1. Add entry to `ModelRegistry.MODELS` map with SHA-256, URL, size
2. Add model ID alias if renaming from an old ID
3. Update `ModelManager.getModelCandidates()` for device tier routing
4. Update `SequentialModelLoader.ModelType` if sequential loading needed
5. Update `MemoryManager.LoadedHeavyModel` if mutual exclusion needed

---

## 10. Troubleshooting

### Model won't load

1. Check `adb logcat | grep -i "model\|gguf\|llama"` for errors
2. Verify file exists: `adb shell ls -la /data/data/com.msaidizi.app/files/models/`
3. Check file size matches expected (~580MB)
4. Verify SHA-256: `adb shell sha256sum /data/data/.../Qwen3.5-0.8B-Q4_K_M.gguf`

### OOM during inference

1. Check device RAM: `adb shell cat /proc/meminfo`
2. Ensure sequential mode is active on 2GB devices
3. Reduce context length in `DeviceTier.getMaxContextLength()`
4. Enable KV cache Q4_0: `LlamaCppEngine.setKvCacheQ4Enabled(true)`

### Slow inference

1. Check CPU threads: should be `cores / 2`, capped at 4
2. Enable KV cache Q4_0 for memory-bandwidth-limited devices
3. Reduce `MAX_ON_DEVICE_TOKENS` in `ReasoningModelManager`
4. Check thermal throttling: `adb shell cat /sys/class/thermal/thermal_zone*/temp`

---

## 11. Files Modified (This Integration)

| File                                                  | Changes                                          |
|-------------------------------------------------------|--------------------------------------------------|
| `ModelVersionManager.kt`                              | Default version → QWEN3_5_0_8B, rollback updated |
| `ReasoningModelManager.kt`                            | MAX_CONTEXT_LENGTH → 32768                       |
| `MoERouter.kt`                                        | avgLatencyMs → 300ms for 0.8B                    |
| `LlamaCppEngine.kt`                                   | Fixed filename casing to match ModelRegistry      |
| `BundledModelManager.kt`                              | Bundled asset filename → Qwen3.5-0.8B            |
| `ModelDownloader.kt`                                  | Download size → 580MB                            |
| `ModelManager.kt`                                     | Model size estimate → 580MB                      |
| `MemoryManager.kt`                                    | Added LLM_MEMORY_MB (600), LLM to HeavyModel    |
| `SequentialModelLoader.kt`                            | QWEN_MEMORY_MB → 600                            |
| `ModelRegistry.kt`                                    | Added aliases for backward compat                 |
