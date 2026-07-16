#!/usr/bin/env python3
"""Generate all Android app icons for Msaidizi (Angavu Intelligence).

Design: Eye with concentric arcs + Africa silhouette
Colors: Deep Blue (#1B4965) + Golden Hour (#E8A838)
"""

import math
import os
import shutil
from PIL import Image, ImageDraw

# ── Brand Colors ──
DEEP_BLUE = (0x1B, 0x49, 0x65)
DEEP_BLUE_DARK = (0x0F, 0x2D, 0x42)
GOLDEN_HOUR = (0xE8, 0xA8, 0x38)
AFRICA_BLUE = (0x2A, 0x6F, 0x99)
WHITE = (255, 255, 255)

# ── Icon specs ──
DENSITIES = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192,
}
ADAPTIVE_PX = 432  # 108dp * 4 (xxxhdpi)
PLAY_STORE = 512
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
RES_DIR = os.path.join(BASE_DIR, 'app', 'src', 'main', 'res')
ASSETS_DIR = os.path.join(BASE_DIR, 'app', 'src', 'main', 'assets', 'icons')


def lerp_color(c1, c2, t):
    return tuple(int(a + (b - a) * t) for a, b in zip(c1, c2))


def alpha_composite(base, overlay):
    """Composite overlay onto base, both RGBA."""
    return Image.alpha_composite(base, overlay)


def draw_gradient_bg(size):
    """Create gradient background image."""
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    for y in range(size):
        t = y / max(size - 1, 1)
        color = lerp_color(DEEP_BLUE, DEEP_BLUE_DARK, t)
        draw.line([(0, y), (size, y)], fill=(*color, 255))
    return img


def draw_radial_glow(size, cx, cy, radius, color, max_alpha=20):
    """Create a subtle radial glow overlay."""
    overlay = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    for r in range(radius, 0, -max(1, radius // 60)):
        t = r / radius
        alpha = int(max_alpha * (1 - t) * (1 - t))
        if alpha > 0:
            draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(*color, alpha))
    return overlay


def draw_africa_silhouette(size, cx, cy, scale, color, opacity=0.5):
    """Draw recognizable Africa continent as polygon overlay.

    Key geographic features:
    - Flat Mediterranean north coast
    - Horn of Africa (eastern protrusion at Somalia)
    - Gulf of Guinea indentation
    - West Africa bulge (Senegal/Mauritania)
    - Cape of Good Hope (southern tip)
    """
    # Normalized Africa outline points — recognizable silhouette
    # (x, y) where x=0 is center, positive=right/east, y=0 is center, positive=south
    africa_pts = [
        # North coast (flat Mediterranean) — going left to right
        (-0.62, -0.88), (-0.22, -0.96), (0.12, -0.96), (0.42, -0.88),
        # Egypt / Sinai
        (0.50, -0.72), (0.47, -0.56),
        # Horn of Africa — sharp eastern protrusion!
        (0.56, -0.40), (0.80, -0.24), (0.80, -0.12),
        # East coast going south
        (0.64, 0.00), (0.48, 0.16), (0.36, 0.36),
        # Mozambique / SE coast
        (0.24, 0.56), (0.08, 0.76),
        # Cape of Good Hope — southern tip
        (-0.08, 0.92), (-0.16, 0.96),
        # West coast going north
        (-0.32, 0.76), (-0.44, 0.52),
        # Gulf of Guinea — indentation (coast goes east here)
        (-0.48, 0.32), (-0.40, 0.20),
        # West Africa bulge — westernmost point!
        (-0.60, 0.04), (-0.68, -0.12), (-0.68, -0.28),
        # Back to NW
        (-0.60, -0.56), (-0.56, -0.72),
    ]
    r = 140 * scale
    pts = [(cx + x * r, cy + y * r) for x, y in africa_pts]

    overlay = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    fill = (*color, int(255 * opacity))
    draw.polygon(pts, fill=fill)
    return overlay


def draw_east_africa(size, cx, cy, scale, color, opacity=0.35):
    """Draw East Africa / Horn of Africa highlight overlay."""
    ea_pts = [
        (0.56, -0.40), (0.80, -0.24), (0.80, -0.12),
        (0.64, 0.00), (0.48, 0.16), (0.36, 0.36),
        (0.44, 0.24), (0.56, 0.08), (0.68, -0.04),
        (0.68, -0.16), (0.56, -0.40),
    ]
    r = 140 * scale
    pts = [(cx + x * r, cy + y * r) for x, y in ea_pts]

    overlay = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    draw.polygon(pts, fill=(*color, int(255 * opacity)))
    return overlay


def draw_madagascar(size, cx, cy, scale):
    """Draw Madagascar hint."""
    mcx = cx + int(58 * scale * 1.15)
    mcy = cy + int(52 * scale * 1.15)
    mrx = max(1, int(6 * scale * 1.15))
    mry = max(2, int(14 * scale * 1.15))

    overlay = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    draw.ellipse([mcx - mrx, mcy - mry, mcx + mrx, mcy + mry],
                 fill=(*AFRICA_BLUE, int(255 * 0.35)))
    return overlay


def draw_eye(size, cx, cy, scale):
    """Draw the eye with concentric arcs, iris, pupil, highlights."""
    overlay = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    s = scale

    # Upper concentric arcs (outer → inner)
    arcs = [
        (155, 120, 4.5, 0.22),
        (125, 97, 5.0, 0.38),
        (96, 74, 5.5, 0.58),
        (70, 54, 6.0, 0.78),
        (48, 37, 6.5, 0.95),
    ]
    for rx, ry, w, alpha in arcs:
        bbox = [cx - int(rx * s), cy - int(ry * s), cx + int(rx * s), cy + int(ry * s)]
        color = (*GOLDEN_HOUR, int(255 * alpha))
        draw.arc(bbox, 180, 360, fill=color, width=max(1, int(w * s)))

    # Lower mirror arcs (subtle)
    lower = [
        (155, 120, 2.5, 0.07),
        (125, 97, 2.5, 0.10),
        (96, 74, 2.5, 0.13),
    ]
    for rx, ry, w, alpha in lower:
        bbox = [cx - int(rx * s), cy - int(ry * s), cx + int(rx * s), cy + int(ry * s)]
        color = (*GOLDEN_HOUR, int(255 * alpha))
        draw.arc(bbox, 0, 180, fill=color, width=max(1, int(w * s)))

    # Iris circle
    iris_r = int(28 * s)
    iris_w = max(1, int(5.5 * s))
    draw.ellipse([cx - iris_r, cy - iris_r, cx + iris_r, cy + iris_r],
                 outline=(*GOLDEN_HOUR, 255), width=iris_w)

    # Pupil
    pupil_r = max(2, int(13 * s))
    draw.ellipse([cx - pupil_r, cy - pupil_r, cx + pupil_r, cy + pupil_r],
                 fill=(*GOLDEN_HOUR, 255))

    # Highlights
    hl1_r = max(1, int(4.5 * s))
    hl1_x = cx + int(7 * s)
    hl1_y = cy - int(7 * s)
    draw.ellipse([hl1_x - hl1_r, hl1_y - hl1_r, hl1_x + hl1_r, hl1_y + hl1_r],
                 fill=(255, 255, 255, int(255 * 0.55)))

    hl2_r = max(1, int(2.2 * s))
    hl2_x = cx - int(3.5 * s)
    hl2_y = cy + int(3.5 * s)
    draw.ellipse([hl2_x - hl2_r, hl2_y - hl2_r, hl2_x + hl2_r, hl2_y + hl2_r],
                 fill=(255, 255, 255, int(255 * 0.25)))

    return overlay


def create_icon(size, rounded=True, corner_radius_ratio=0.21):
    """Create full legacy icon."""
    img = draw_gradient_bg(size)

    # Radial glow
    glow = draw_radial_glow(size, size // 2, int(size * 0.4), int(size * 0.4), GOLDEN_HOUR, 20)
    img = alpha_composite(img, glow)

    scale = size / 512.0
    cx = size // 2
    africa_cy = int(size * 0.625)

    # Africa
    africa = draw_africa_silhouette(size, cx, africa_cy, scale * 1.15, AFRICA_BLUE, 0.5)
    img = alpha_composite(img, africa)

    # East Africa
    ea = draw_east_africa(size, cx, africa_cy, scale * 1.15, GOLDEN_HOUR, 0.35)
    img = alpha_composite(img, ea)

    # Madagascar
    mad = draw_madagascar(size, cx, africa_cy, scale * 1.15)
    img = alpha_composite(img, mad)

    # Eye
    eye_cy = int(size * 0.38)
    eye = draw_eye(size, cx, eye_cy, scale)
    img = alpha_composite(img, eye)

    # Rounded corners
    if rounded:
        mask = Image.new('L', (size, size), 0)
        md = ImageDraw.Draw(mask)
        r = int(size * corner_radius_ratio)
        md.rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=255)
        img.putalpha(mask)

    return img


def create_round(size):
    """Create circular icon."""
    img = create_icon(size, rounded=False)
    mask = Image.new('L', (size, size), 0)
    md = ImageDraw.Draw(mask)
    md.ellipse([0, 0, size - 1, size - 1], fill=255)
    img.putalpha(mask)
    return img


def create_foreground(size):
    """Create adaptive foreground (eye + Africa, transparent bg)."""
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    scale = size / 512.0
    cx = size // 2
    africa_cy = int(size * 0.625)

    # Subtle Africa
    africa = draw_africa_silhouette(size, cx, africa_cy, scale * 0.9, GOLDEN_HOUR, 0.12)
    img = alpha_composite(img, africa)

    ea = draw_east_africa(size, cx, africa_cy, scale * 0.9, GOLDEN_HOUR, 0.2)
    img = alpha_composite(img, ea)

    # Eye
    eye_cy = int(size * 0.41)
    eye = draw_eye(size, cx, eye_cy, scale)
    img = alpha_composite(img, eye)

    return img


def create_background(size):
    """Create adaptive background (solid deep blue)."""
    return Image.new('RGBA', (size, size), (*DEEP_BLUE, 255))


def main():
    # Ensure directories
    for density in DENSITIES:
        os.makedirs(os.path.join(RES_DIR, f'mipmap-{density}'), exist_ok=True)
    os.makedirs(os.path.join(RES_DIR, 'mipmap-anydpi-v26'), exist_ok=True)
    os.makedirs(ASSETS_DIR, exist_ok=True)

    # 1. Density PNGs
    for density, size in DENSITIES.items():
        print(f'{density} ({size}x{size})...')

        icon = create_icon(size)
        icon.save(os.path.join(RES_DIR, f'mipmap-{density}', 'ic_launcher.png'))

        rnd = create_round(size)
        rnd.save(os.path.join(RES_DIR, f'mipmap-{density}', 'ic_launcher_round.png'))

        fg = create_foreground(size)
        fg.save(os.path.join(RES_DIR, f'mipmap-{density}', 'ic_launcher_foreground.png'))

        bg = create_background(size)
        bg.save(os.path.join(RES_DIR, f'mipmap-{density}', 'ic_launcher_background.png'))

    # 2. Adaptive layers at xxxhdpi (432px)
    print(f'Adaptive layers ({ADAPTIVE_PX}x{ADAPTIVE_PX})...')
    fg = create_foreground(ADAPTIVE_PX)
    fg.save(os.path.join(RES_DIR, 'mipmap-xxxhdpi', 'ic_launcher_foreground.png'))

    bg = create_background(ADAPTIVE_PX)
    bg.save(os.path.join(RES_DIR, 'mipmap-xxxhdpi', 'ic_launcher_background.png'))

    # 3. Play Store icon
    print('Play Store icon (512x512)...')
    play = create_icon(PLAY_STORE)
    play.save(os.path.join(ASSETS_DIR, 'ic_launcher_playstore.png'))

    # 4. Copy SVG sources
    print('SVG sources...')
    svg_map = {
        'ic_launcher.svg': os.path.join(BASE_DIR, 'assets', 'msaidizi-icon.svg'),
        'ic_launcher_foreground.svg': os.path.join(BASE_DIR, 'assets', 'msaidizi-icon-foreground.svg'),
        'ic_launcher_round.svg': os.path.join(BASE_DIR, 'assets', 'msaidizi-icon-round.svg'),
    }
    for name, src in svg_map.items():
        if os.path.exists(src):
            shutil.copy2(src, os.path.join(ASSETS_DIR, name))
            print(f'  {name}')
        else:
            print(f'  {name} (skipped)')

    # 5. Adaptive icon XML
    print('Adaptive XML...')
    xml = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/icon_bg"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
'''
    for fname in ['ic_launcher.xml', 'ic_launcher_round.xml']:
        with open(os.path.join(RES_DIR, 'mipmap-anydpi-v26', fname), 'w') as f:
            f.write(xml)

    # Summary
    print('\n✅ Done!')
    for density, size in DENSITIES.items():
        files = sorted(os.listdir(os.path.join(RES_DIR, f'mipmap-{density}')))
        print(f'  mipmap-{density}/ ({size}px): {", ".join(files)}')
    print(f'  assets/icons/: {", ".join(sorted(os.listdir(ASSETS_DIR)))}')


if __name__ == '__main__':
    main()
