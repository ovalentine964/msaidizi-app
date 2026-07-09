# WAXAL Data Pipeline

## Overview

Google's WAXAL dataset: 27 African languages, 1,846+ hours ASR, 565+ hours TTS.
Licensed CC-BY-4.0. This pipeline processes WAXAL data for on-device fine-tuning
of Msaidizi's Whisper Tiny ASR model.

## Why WAXAL?

- **No model size increase**: Fine-tunes existing Whisper Tiny (~40MB), doesn't add a new model
- **Better Swahili accuracy**: WAXAL includes 100+ hours of Swahili ASR data
- **Dialect coverage**: Covers regional Swahili variants (Coastal, Bantu-influenced, etc.)
- **African language breadth**: Hausa, Yoruba, Igbo, Amharic, Zulu, Xhosa, Somali

## Pipeline Steps

1. **Download**: `download_waxal.py` — Downloads WAXAL dataset from HuggingFace
2. **Format**: `format_for_whisper.py` — Converts to Whisper fine-tuning format
3. **Extract**: `extract_swahili.py` — Filters Swahili + related language data
4. **LoRA Fine-tune**: `finetune_whisper.py` — Creates LoRA adapter (~5MB)
5. **Export**: `export_to_onnx.py` — Exports LoRA adapter to ONNX for mobile

## On-Device Usage

The LoRA adapter is loaded alongside Whisper Tiny in `SpeechRecognizer.kt`:

```
Whisper Tiny INT4 (40MB) + WAXAL Swahili LoRA (5MB) = 45MB total
```

This is still well within the 2GB phone budget.

## Supported Languages (from WAXAL)

| Language | Code | Hours | Region |
|----------|------|-------|--------|
| Swahili | sw | 120+ | East Africa |
| Hausa | ha | 80+ | West Africa |
| Yoruba | yo | 60+ | Nigeria |
| Igbo | ig | 50+ | Nigeria |
| Amharic | am | 70+ | Ethiopia |
| Zulu | zu | 40+ | South Africa |
| Xhosa | xh | 35+ | South Africa |
| Somali | so | 45+ | Horn of Africa |
| Wolof | wo | 30+ | Senegal |
| Twi | tw | 25+ | Ghana |
| + 17 more | ... | ... | ... |

## Running

```bash
# Full pipeline
cd scripts/waxal
python3 download_waxal.py
python3 format_for_whisper.py
python3 extract_swahili.py
python3 finetune_whisper.py --output ../../app/src/main/assets/models/
python3 export_to_onnx.py

# Just Swahili data
python3 extract_swahili.py --lang sw
```

## Output Files

- `waxal-swahili-adapter.onnx` — LoRA adapter for Whisper Tiny (~5MB)
- `waxal-swahili-train.jsonl` — Training data (for reference)
- `waxal-swahili-eval.jsonl` — Evaluation data (for WER testing)
