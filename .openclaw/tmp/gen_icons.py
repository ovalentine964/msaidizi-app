#!/usr/bin/env python3
"""Generate all PNG icon variants for Msaidizi app from SVG."""
import cairosvg
import os

BASE = "/home/work/.openclaw/workspace/msaidizi-app"

# Africa path — recognizable silhouette with key geographic features:
#   - Flat Mediterranean north coast (y≈17)
#   - Horn of Africa at (74,41) — sharp eastern protrusion
#   - Gulf of Guinea indentation at (38,46)
#   - West Africa bulge / Senegal at (27,28) — westernmost point
#   - Cape of Good Hope at (50,78) — southernmost point
#   - Madagascar island off SE coast
AFRICA_PATH = 'M34,20 L48,17 L60,17 L68,22 L67,30 L66,36 L74,41 L70,46 L66,52 L62,60 L56,70 L50,78 L44,70 L40,62 L38,55 L37,50 L38,46 L34,42 L30,36 L27,28 L30,22 Z'
MADAGASCAR_PATH = 'M69,58 L71,60 L72,66 L70,70 L68,68 L67,62 Z'
SHIELD_PATH = 'M28,12 C38,8 60,8 72,14 C80,20 86,30 86,42 C86,54 78,66 68,76 C60,84 54,88 50,88 C46,88 40,84 32,76 C22,66 14,54 14,42 C14,30 20,20 28,12 Z'

# Full icon SVG (512x512) with all elements
FULL_ICON_SVG = f"""<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">
  <!-- Dark navy background -->
  <rect width="108" height="108" fill="#0F172A"/>

  <!-- Africa continent — recognizable silhouette -->
  <path d="{AFRICA_PATH}" fill="#22C55E"/>
  <!-- Madagascar island -->
  <path d="{MADAGASCAR_PATH}" fill="#22C55E"/>

  <!-- Shield border — thick gold wrapping around Africa -->
  <path d="{SHIELD_PATH}" fill="none" stroke="#FBBF24" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>

  <!-- Speech bubble — white, upper-right -->
  <path d="M62,20 C62,17.8 63.8,16 66,16 L79,16 C81.2,16 83,17.8 83,20 L83,31 C83,33.2 81.2,35 79,35 L74,35 L69,40 L70.5,35 L66,35 C63.8,35 62,33.2 62,31 Z" fill="#FFFFFF"/>
  <circle cx="68" cy="24" r="1.5" fill="#1E293B"/>
  <circle cx="73" cy="24" r="1.5" fill="#1E293B"/>
  <circle cx="78" cy="24" r="1.5" fill="#1E293B"/>

  <!-- Chart data points -->
  <circle cx="30" cy="74" r="2.5" fill="#F59E0B"/>
  <circle cx="42" cy="66" r="2.5" fill="#F59E0B"/>
  <circle cx="54" cy="62" r="2.5" fill="#F59E0B"/>
  <circle cx="66" cy="56" r="2.5" fill="#F59E0B"/>

  <!-- Chart connecting line -->
  <polyline points="30,74 42,66 54,62 66,56" fill="none" stroke="#FBBF24" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>

  <!-- Upward arrow endpoint -->
  <polygon points="66,56 61,62 66,59 71,62" fill="#FBBF24"/>
</svg>
"""

# Foreground-only SVG (for adaptive icon PNG fallbacks)
FOREGROUND_SVG = f"""<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">
  <!-- Africa continent — recognizable silhouette -->
  <path d="{AFRICA_PATH}" fill="#22C55E"/>
  <path d="{MADAGASCAR_PATH}" fill="#22C55E"/>
  <!-- Shield border -->
  <path d="{SHIELD_PATH}" fill="none" stroke="#FBBF24" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>
  <!-- Speech bubble -->
  <path d="M62,20 C62,17.8 63.8,16 66,16 L79,16 C81.2,16 83,17.8 83,20 L83,31 C83,33.2 81.2,35 79,35 L74,35 L69,40 L70.5,35 L66,35 C63.8,35 62,33.2 62,31 Z" fill="#FFFFFF"/>
  <circle cx="68" cy="24" r="1.5" fill="#1E293B"/>
  <circle cx="73" cy="24" r="1.5" fill="#1E293B"/>
  <circle cx="78" cy="24" r="1.5" fill="#1E293B"/>
  <!-- Chart -->
  <circle cx="30" cy="74" r="2.5" fill="#F59E0B"/>
  <circle cx="42" cy="66" r="2.5" fill="#F59E0B"/>
  <circle cx="54" cy="62" r="2.5" fill="#F59E0B"/>
  <circle cx="66" cy="56" r="2.5" fill="#F59E0B"/>
  <polyline points="30,74 42,66 54,62 66,56" fill="none" stroke="#FBBF24" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>
  <polygon points="66,56 61,62 66,59 71,62" fill="#FBBF24"/>
</svg>
"""

# Round icon SVG (circular background + same foreground elements)
ROUND_ICON_SVG = f"""<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">
  <!-- Circular dark navy background -->
  <circle cx="54" cy="54" r="54" fill="#0F172A"/>
  <!-- Africa continent — recognizable silhouette -->
  <path d="{AFRICA_PATH}" fill="#22C55E"/>
  <path d="{MADAGASCAR_PATH}" fill="#22C55E"/>
  <!-- Shield border -->
  <path d="{SHIELD_PATH}" fill="none" stroke="#FBBF24" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>
  <!-- Speech bubble -->
  <path d="M62,20 C62,17.8 63.8,16 66,16 L79,16 C81.2,16 83,17.8 83,20 L83,31 C83,33.2 81.2,35 79,35 L74,35 L69,40 L70.5,35 L66,35 C63.8,35 62,33.2 62,31 Z" fill="#FFFFFF"/>
  <circle cx="68" cy="24" r="1.5" fill="#1E293B"/>
  <circle cx="73" cy="24" r="1.5" fill="#1E293B"/>
  <circle cx="78" cy="24" r="1.5" fill="#1E293B"/>
  <!-- Chart -->
  <circle cx="30" cy="74" r="2.5" fill="#F59E0B"/>
  <circle cx="42" cy="66" r="2.5" fill="#F59E0B"/>
  <circle cx="54" cy="62" r="2.5" fill="#F59E0B"/>
  <circle cx="66" cy="56" r="2.5" fill="#F59E0B"/>
  <polyline points="30,74 42,66 54,62 66,56" fill="none" stroke="#FBBF24" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>
  <polygon points="66,56 61,62 66,59 71,62" fill="#FBBF24"/>
</svg>
"""

# Density configs: (density_name, icon_size)
DENSITIES = [
    ("mipmap-mdpi", 48),
    ("mipmap-hdpi", 72),
    ("mipmap-xhdpi", 96),
    ("mipmap-xxhdpi", 144),
    ("mipmap-xxxhdpi", 192),
]

def generate_png(svg_data, output_path, size):
    """Convert SVG to PNG at given size."""
    cairosvg.svg2png(
        bytestring=svg_data.encode('utf-8'),
        write_to=output_path,
        output_width=size,
        output_height=size,
    )
    print(f"  Generated: {output_path} ({size}x{size})")

def main():
    res_dir = os.path.join(BASE, "app/src/main/res")

    for density, size in DENSITIES:
        mipmap_dir = os.path.join(res_dir, density)
        os.makedirs(mipmap_dir, exist_ok=True)

        # ic_launcher.png (full icon with background)
        generate_png(
            FULL_ICON_SVG,
            os.path.join(mipmap_dir, "ic_launcher.png"),
            size
        )

        # ic_launcher_round.png (round variant)
        generate_png(
            ROUND_ICON_SVG,
            os.path.join(mipmap_dir, "ic_launcher_round.png"),
            size
        )

    # Also generate a high-res standalone logo
    logo_dir = os.path.join(BASE, "assets/icons")
    os.makedirs(logo_dir, exist_ok=True)
    generate_png(FULL_ICON_SVG, os.path.join(logo_dir, "msaidizi-icon-512.png"), 512)
    generate_png(FULL_ICON_SVG, os.path.join(logo_dir, "msaidizi-icon-192.png"), 192)

    print("\nAll PNG icons generated successfully!")

if __name__ == "__main__":
    main()
