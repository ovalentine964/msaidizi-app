#!/usr/bin/env python3
"""
Download WAXAL dataset from HuggingFace.

WAXAL: Google's African language ASR/TTS dataset.
- 27 African languages
- 1,846+ hours ASR data
- 565+ hours TTS data
- CC-BY-4.0 licensed

Usage:
    python3 download_waxal.py [--output-dir ./data] [--languages sw,ha,yo]
"""

import argparse
import json
import os
import sys
from pathlib import Path

try:
    from datasets import load_dataset
except ImportError:
    print("ERROR: 'datasets' library not found. Install with:")
    print("  pip install datasets")
    sys.exit(1)

WAXAL_LANGUAGES = {
    "sw": "Swahili",
    "ha": "Hausa",
    "yo": "Yoruba",
    "ig": "Igbo",
    "am": "Amharic",
    "zu": "Zulu",
    "xh": "Xhosa",
    "so": "Somali",
    "wo": "Wolof",
    "tw": "Twi",
    "lg": "Luganda",
    "sn": "Shona",
    "ny": "Chichewa",
    "st": "Southern Sotho",
    "tn": "Tswana",
    "ts": "Tsonga",
    "ve": "Venda",
    "nr": "Southern Ndebele",
    "ss": "Swati",
    "rw": "Kinyarwanda",
    "rn": "Kirundi",
    "ln": "Lingala",
    "kg": "Kongo",
    "ff": "Fulfulde",
    "bm": "Bambara",
    "ee": "Ewe",
    "ak": "Akan",
}


def download_waxal(output_dir: str, languages: list[str] | None = None):
    """Download WAXAL dataset for specified languages."""
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)

    if languages is None:
        languages = ["sw"]  # Default: Swahili only

    print(f"Downloading WAXAL dataset for languages: {languages}")
    print(f"Output directory: {output_path}")

    for lang_code in languages:
        lang_name = WAXAL_LANGUAGES.get(lang_code, lang_code)
        print(f"\n{'='*60}")
        print(f"Downloading: {lang_name} ({lang_code})")
        print(f"{'='*60}")

        try:
            # WAXAL dataset on HuggingFace
            # Format: google/waxal or similar — adjust path as needed
            dataset = load_dataset(
                "google/waxal",
                lang_code,
                split="train",
                trust_remote_code=True,
            )

            # Save to disk
            lang_dir = output_path / lang_code
            lang_dir.mkdir(exist_ok=True)

            # Save as JSONL for easy processing
            output_file = lang_dir / f"{lang_code}_train.jsonl"
            with open(output_file, "w", encoding="utf-8") as f:
                for idx, example in enumerate(dataset):
                    record = {
                        "id": f"{lang_code}_{idx}",
                        "audio_path": example.get("audio", {}).get("path", ""),
                        "transcript": example.get("transcript", example.get("text", "")),
                        "language": lang_code,
                        "duration_seconds": _get_duration(example),
                        "speaker_id": example.get("speaker_id", "unknown"),
                    }
                    f.write(json.dumps(record, ensure_ascii=False) + "\n")

            print(f"  Saved {len(dataset)} examples to {output_file}")

            # Also download eval split if available
            try:
                eval_dataset = load_dataset(
                    "google/waxal",
                    lang_code,
                    split="test",
                    trust_remote_code=True,
                )
                eval_file = lang_dir / f"{lang_code}_eval.jsonl"
                with open(eval_file, "w", encoding="utf-8") as f:
                    for idx, example in enumerate(eval_dataset):
                        record = {
                            "id": f"{lang_code}_eval_{idx}",
                            "audio_path": example.get("audio", {}).get("path", ""),
                            "transcript": example.get("transcript", example.get("text", "")),
                            "language": lang_code,
                            "duration_seconds": _get_duration(example),
                        }
                        f.write(json.dumps(record, ensure_ascii=False) + "\n")
                print(f"  Saved {len(eval_dataset)} eval examples to {eval_file}")
            except Exception:
                print(f"  No eval split available for {lang_code}")

        except Exception as e:
            print(f"  ERROR downloading {lang_code}: {e}")
            print(f"  Try manually: huggingface-cli download google/waxal --include '{lang_code}/*'")
            continue

    # Write manifest
    manifest = {
        "dataset": "waxal",
        "version": "1.0",
        "license": "CC-BY-4.0",
        "source": "https://github.com/google/waxal",
        "languages": {lang: WAXAL_LANGUAGES.get(lang, lang) for lang in languages},
        "total_hours_asr": "1,846+",
        "total_hours_tts": "565+",
    }
    manifest_file = output_path / "manifest.json"
    with open(manifest_file, "w") as f:
        json.dump(manifest, f, indent=2)
    print(f"\nManifest written to {manifest_file}")
    print("Download complete!")


def _get_duration(example: dict) -> float:
    """Extract audio duration from example."""
    audio = example.get("audio", {})
    if "array" in audio and "sampling_rate" in audio:
        return len(audio["array"]) / audio["sampling_rate"]
    return 0.0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Download WAXAL dataset")
    parser.add_argument(
        "--output-dir",
        default="./data",
        help="Output directory (default: ./data)",
    )
    parser.add_argument(
        "--languages",
        default="sw",
        help="Comma-separated language codes (default: sw)",
    )
    args = parser.parse_args()

    languages = [l.strip() for l in args.languages.split(",")]
    download_waxal(args.output_dir, languages)
