#!/usr/bin/env bash
# ============================================================
# validate-build.sh — Pre-commit build validation
# ============================================================
# Static analysis checks that run fast (no Gradle build).
# ============================================================

set -euo pipefail

FAIL=0

echo "━━━ Msaidizi Build Validation ━━━"
echo ""

# 1. Check Kotlin source files exist
echo "📂 Checking Kotlin source files..."
KT_COUNT=$(find app/src -name "*.kt" 2>/dev/null | wc -l)
if [ "$KT_COUNT" -eq 0 ]; then
  echo "  ❌ No Kotlin source files found!"
  FAIL=1
else
  echo "  ✅ Found $KT_COUNT Kotlin source files"
fi

# 2. Check AndroidManifest.xml
echo "📱 Checking AndroidManifest.xml..."
if [ -f "app/src/main/AndroidManifest.xml" ]; then
  echo "  ✅ AndroidManifest.xml exists"
else
  echo "  ❌ AndroidManifest.xml missing!"
  FAIL=1
fi

# 3. Check build.gradle.kts files
echo "📦 Checking build files..."
if [ -f "build.gradle.kts" ]; then
  echo "  ✅ Root build.gradle.kts exists"
else
  echo "  ❌ Root build.gradle.kts missing!"
  FAIL=1
fi
if [ -f "app/build.gradle.kts" ]; then
  echo "  ✅ app/build.gradle.kts exists"
else
  echo "  ❌ app/build.gradle.kts missing!"
  FAIL=1
fi

# 4. Check settings.gradle.kts
if [ -f "settings.gradle.kts" ]; then
  echo "  ✅ settings.gradle.kts exists"
else
  echo "  ❌ settings.gradle.kts missing!"
  FAIL=1
fi

# 5. Check gradle wrapper
echo "🔧 Checking Gradle wrapper..."
if [ -f "gradlew" ] && [ -x "gradlew" ]; then
  echo "  ✅ gradlew exists and is executable"
else
  echo "  ❌ gradlew missing or not executable!"
  FAIL=1
fi

# 6. Check for syntax errors in XML layouts (dollar signs)
echo "🎨 Checking XML layouts for syntax errors..."
XML_DOLLAR=$(grep -r '\$' app/src/main/res/layout/ --include="*.xml" 2>/dev/null | grep -v '@{' | grep -v 'tools:' | wc -l || echo 0)
if [ "$XML_DOLLAR" -gt 0 ]; then
  echo "  ⚠️  Found $XML_DOLLAR lines with unescaped dollar signs in XML layouts"
else
  echo "  ✅ No XML layout syntax issues"
fi

# 7. Check color resources
echo "🎨 Checking color resources..."
if [ -f "app/src/main/res/values/colors.xml" ]; then
  BAD_COLORS=$(grep -c 'name=.*value=' app/src/main/res/values/colors.xml 2>/dev/null || echo 0)
  if [ "$BAD_COLORS" -gt 0 ]; then
    echo "  ❌ Found $BAD_COLORS color resources with 'value=' instead of proper XML"
    FAIL=1
  else
    echo "  ✅ Color resources look good"
  fi
fi

# 8. Check string resources for duplicates
echo "📝 Checking string resources..."
if [ -f "app/src/main/res/values/strings.xml" ]; then
  DUPES=$(grep -oP 'name="[^"]*"' app/src/main/res/values/strings.xml | sort | uniq -d | head -5)
  if [ -n "$DUPES" ]; then
    echo "  ❌ Duplicate string names found: $DUPES"
    FAIL=1
  else
    echo "  ✅ No duplicate string resources"
  fi
fi

# 9. Check brace balance in build.gradle.kts
echo "📐 Checking brace balance in build.gradle.kts..."
for f in build.gradle.kts app/build.gradle.kts; do
  if [ -f "$f" ]; then
    OPEN=$(grep -o '{' "$f" | wc -l)
    CLOSE=$(grep -o '}' "$f" | wc -l)
    if [ "$OPEN" -ne "$CLOSE" ]; then
      echo "  ❌ $f: unbalanced braces (open=$OPEN, close=$CLOSE)"
      FAIL=1
    else
      echo "  ✅ $f: braces balanced ($OPEN pairs)"
    fi
  fi
done

echo ""
if [ "$FAIL" -eq 1 ]; then
  echo "❌ Validation FAILED"
  exit 1
fi
echo "✅ All validation checks passed"
