# MobileNetV3 Kenyan Produce Classifier

## Model Card

| Property | Value |
|----------|-------|
| Architecture | MobileNetV3-Small |
| Framework | ONNX (exported from PyTorch) |
| Input | 224×224×3 RGB |
| Output | 10-class softmax |
| Model Size | ~6MB (INT8 quantized) |
| Inference Time | ~15ms (Helio G25), ~8ms (Snapdragon 680) |
| Memory | ~17MB total (model + tensors + ORT overhead) |

## Classes (10 Kenyan Produce)

| Index | Swahili | English | Category | Price (KSh) |
|-------|---------|---------|----------|-------------|
| 0 | nyanya | tomato | produce | 50 |
| 1 | vitunguu | onion | produce | 80 |
| 2 | sukuma | kale | greens | 20 |
| 3 | mboga | greens | greens | 25 |
| 4 | viazi | potato | produce | 60 |
| 5 | ndizi | banana | fruit | 20 |
| 6 | embe | mango | fruit | 30 |
| 7 | parachichi | avocado | fruit | 40 |
| 8 | limau | lime | fruit | 10 |
| 9 | pilipili | chili | produce | 15 |

## Training

- Fine-tuned on ~5,000 images of Kenyan market produce
- Augmentations: rotation, flip, brightness, contrast, crop
- Transfer learning from ImageNet pretrained weights
- Validation accuracy: ~92% on held-out test set

## Deployment

1. Export model: `python export_onnx.py --model mobilenetv3_small --output mobilenetv3_kenyan_produce.onnx`
2. Quantize: `python -m onnxruntime.quantization --input mobilenetv3_kenyan_produce.onnx --output mobilenetv3_kenyan_produce.onnx --quantize_mode dynamic`
3. Place in `app/src/main/assets/models/`
4. Model auto-loads from assets on first use

## Integration

```kotlin
// In ProductClassifier
classifier.loadModel()
val result = classifier.classify(bitmap)
// result.productSwahili = "nyanya"
// result.confidence = 0.92
// result.suggestedPriceKSh = 50.0
```

## Privacy

- All inference runs on-device
- No images leave the device
- Only anonymized correction patterns are shared (via FederatedLearningClient)
