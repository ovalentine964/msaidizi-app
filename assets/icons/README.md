# Msaidizi App — Icon Assets

## Concept A: "The Shield CFO"

A rounded shield symbolizing **protection** and trust, with a flowing gold chart line representing **finance** and growth. A subtle speech bubble is integrated into the shield's upper curve — representing **voice** and conversation. At small sizes it reads as a strong shield; at larger sizes the voice element becomes visible.

## Files

### Msaidizi App Icons (Shield CFO)

| File | Size | Usage | Details |
|------|------|-------|---------|
| `msaidizi-icon-512.svg` | 512×512 | App store, splash | Full detail — shield, chart line, speech bubble, data points |
| `msaidizi-icon-192.svg` | 192×192 | Adaptive icon, home screen | Simplified — same elements, fewer details |
| `msaidizi-icon-48.svg` | 48×48 | Notifications, status bar | Minimal — shield + gold chart line only |
| `ic_launcher_foreground.svg` | 108×108 | Android adaptive foreground | Shield with all elements, centered in safe zone |

### Angavu Company Icons

| File | Size | Usage | Details |
|------|------|-------|---------|
| `angavu-icon-512.svg` | 512×512 | Company branding | Corporate hexagonal badge with stylized "A" |
| `angavu-icon-192.svg` | 192×192 | Company adaptive icon | Simplified hex badge |

### Android Configuration

| File | Purpose |
|------|---------|
| `adaptive-icon.xml` | Android adaptive icon (foreground + background layers) |
| `splash-screen.xml` | Splash screen — green background, white icon, tagline |
| `ic_launcher_background.xml` | Background color resource (`#22C55E`) |
| `splash_tagline.svg` | Tagline text: "Your Voice. Your Finance. Your Shield." |

## Color Palette

| Color | Hex | Usage |
|-------|-----|-------|
| Primary Green | `#22C55E` | Shield, backgrounds |
| Dark Green | `#16A34A` | Shield gradient end |
| Gold Accent | `#F59E0B` | Chart line, data points |
| Dark Background | `#0F172A` | Icon backgrounds (AMOLED-friendly) |
| White | `#FFFFFF` | Speech bubble, letterforms |

## Design Notes

- **AMOLED-friendly**: Dark `#0F172A` background provides high contrast
- **Scalable**: SVG paths optimized for both 48px and 512px rendering
- **Clean SVG**: No unnecessary groups, minimal IDs, optimized path data
- **Adaptive icon**: Foreground layer with transparent background for Android 8+
- **Speech bubble**: Subtle (15-18% opacity) — almost hidden at small sizes, visible at large sizes

## Integration

Copy these files to the appropriate Android/iOS asset directories:

```bash
# Android
cp msaidizi-icon-192.svg android/app/src/main/res/mipmap-xxxhdpi/
cp ic_launcher_foreground.svg android/app/src/main/res/drawable/
cp splash-screen.xml android/app/src/main/res/drawable/
cp ic_launcher_background.xml android/app/src/main/res/values/

# iOS
cp msaidizi-icon-512.svg ios/Assets.xcassets/AppIcon.appiconset/
cp msaidizi-icon-192.svg ios/Assets.xcassets/AppIcon.appiconset/
```
