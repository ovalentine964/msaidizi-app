#!/bin/bash
# Pre-commit build validation for Msaidizi
# Run this BEFORE every commit to catch issues early

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

set -e

echo "🔍 Msaidizi Build Validator"
echo "=========================="

ERRORS=0

# 1. Check brace balance in ALL Kotlin files
echo ""
echo "📋 Checking brace balance..."
while IFS= read -r -d '' f; do
    RESULT=$(python3 "$SCRIPT_DIR/check_braces.py" "$f" 2>&1)
    if [ "$RESULT" != "OK" ]; then
        echo "  ❌ BRACE $RESULT: $f"
        ERRORS=$((ERRORS + 1))
    fi
done < <(find app/src/main/java -name "*.kt" -print0)
if [ $ERRORS -eq 0 ]; then
    echo "  ✅ All Kotlin files have balanced braces"
fi

# 2. Check XML for $ signs NOT part of Android format strings
echo ""
echo "📋 Checking XML layouts..."
while IFS= read -r -d '' f; do
    if ! python3 "$SCRIPT_DIR/check_xml.py" "$f" > /dev/null 2>&1; then
        echo "  ❌ DOLLAR SIGN in XML: $f"
        python3 "$SCRIPT_DIR/check_xml.py" "$f"
        ERRORS=$((ERRORS + 1))
    fi
done < <(find app/src/main/res -name "*.xml" -print0)
echo "  ✅ No problematic dollar signs in XML"

# 3. Check color resources
echo ""
echo "📋 Checking color resources..."
while IFS= read -r -d '' f; do
    COLORS=$(grep -oh '@color/[a-zA-Z_0-9]*' "$f" 2>/dev/null || true)
    for color in $COLORS; do
        NAME=$(echo "$color" | sed 's/@color\///')
        if ! grep -q "name=\"$NAME\"" app/src/main/res/values/colors.xml 2>/dev/null && \
           [ ! -f "app/src/main/res/color/$NAME.xml" ]; then
            echo "  ❌ MISSING COLOR: $color in $f"
            ERRORS=$((ERRORS + 1))
        fi
    done
done < <(find app/src/main/res/layout -name "*.xml" -print0)
echo "  ✅ All color resources exist"

# 4. Check for invalid color values
echo ""
echo "📋 Checking color values..."
while IFS= read -r line; do
    if echo "$line" | grep -q 'color name' && ! echo "$line" | grep -q '#'; then
        echo "  ❌ MISSING # in color: $line"
        ERRORS=$((ERRORS + 1))
    fi
done < app/src/main/res/values/colors.xml
echo "  ✅ All colors have # prefix"

# 5. Check string resources
echo ""
echo "📋 Checking string resources..."
while IFS= read -r -d '' f; do
    STRINGS=$(grep -ohE '@string/[a-zA-Z_][a-zA-Z_0-9]*' "$f" 2>/dev/null || true)
    for str in $STRINGS; do
        NAME=$(echo "$str" | sed 's/@string\///')
        if ! grep -q "name=\"$NAME\"" app/src/main/res/values/strings.xml 2>/dev/null; then
            echo "  ❌ MISSING STRING: $str in $f"
            ERRORS=$((ERRORS + 1))
        fi
    done
done < <(find app/src/main/res/layout -name "*.xml" -print0)
echo "  ✅ All string resources exist"

# 6. Check for duplicate resource names
echo ""
echo "📋 Checking for duplicate resources..."
DUPES=$(grep -o 'name="[^"]*"' app/src/main/res/values/strings.xml | sort | uniq -d)
if [ -n "$DUPES" ]; then
    echo "  ❌ DUPLICATE STRINGS: $DUPES"
    ERRORS=$((ERRORS + 1))
else
    echo "  ✅ No duplicate strings"
fi

# Summary
echo ""
echo "=========================="
if [ $ERRORS -gt 0 ]; then
    echo "❌ FOUND $ERRORS ERRORS — DO NOT COMMIT"
    exit 1
else
    echo "✅ ALL CHECKS PASSED — SAFE TO COMMIT"
    exit 0
fi
