#!/bin/bash
# Pre-commit build validation for Msaidizi
# Run this BEFORE every commit to catch issues early

set -e

echo "🔍 Msaidizi Build Validator"
echo "=========================="

ERRORS=0

# 1. Check brace balance in ALL Kotlin files
#    Uses a proper character-by-character parser that handles:
#    - Single-line comments (//)
#    - Block comments (/* */)
#    - Regular strings ("...")
#    - Triple-quoted strings ("""...""")
#    - String templates ($var and ${expr})
#    - Char literals ('...')
echo ""
echo "📋 Checking brace balance..."
while IFS= read -r -d '' f; do
    BRACE_DEPTH=$(python3 -c "
import sys

def count_braces(path):
    with open(path) as fh:
        content = fh.read()
    depth = 0
    i = 0
    n = len(content)
    while i < n:
        c = content[i]
        # Line comment
        if c == '/' and i+1 < n and content[i+1] == '/':
            while i < n and content[i] != '\n':
                i += 1
            continue
        # Block comment
        if c == '/' and i+1 < n and content[i+1] == '*':
            i += 2
            while i < n-1 and not (content[i] == '*' and content[i+1] == '/'):
                i += 1
            i += 2
            continue
        # Triple-quoted string
        if c == '\"' and i+2 < n and content[i+1] == '\"' and content[i+2] == '\"':
            i += 3
            while i < n-2:
                if content[i] == '\"' and content[i+1] == '\"' and content[i+2] == '\"':
                    break
                i += 1
            i += 3
            continue
        # Regular string
        if c == '\"':
            i += 1
            while i < n and content[i] != '\"':
                if content[i] == '\\\\':
                    i += 2
                    continue
                i += 1
            i += 1
            continue
        # Char literal
        if c == \"'\":
            i += 1
            while i < n and content[i] != \"'\":
                if content[i] == '\\\\': i += 1
                i += 1
            i += 1
            continue
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
        i += 1
    return depth

print(count_braces(sys.argv[1]))
" "$f" 2>&1)
    if [ "$BRACE_DEPTH" != "0" ]; then
        echo "  ❌ BRACE MISMATCH depth=$BRACE_DEPTH: $f"
        ERRORS=$((ERRORS + 1))
    fi
done < <(find app/src/main/java -name "*.kt" -print0)
if [ $ERRORS -eq 0 ]; then
    echo "  ✅ All Kotlin files have balanced braces"
fi

# 2. Check XML validity — flag $ NOT inside Android format strings
echo ""
echo "📋 Checking XML layouts..."
while IFS= read -r -d '' f; do
    BAD_DOLLAR=$(python3 -c "
import re, sys
with open(sys.argv[1]) as fh:
    for i, line in enumerate(fh, 1):
        # Remove Android format strings like %1\$d, %2\$s
        cleaned = re.sub(r'%\d+\\\\?\$[sd]', '', line)
        # Remove \$ escapes (literal dollar in Android XML)
        cleaned = cleaned.replace('\\\\\$', '')
        if '\$' in cleaned:
            print(f'{i}:{line.rstrip()[:100]}')
" "$f" 2>&1)
    if [ -n "$BAD_DOLLAR" ]; then
        echo "  ❌ DOLLAR SIGN in XML: $f"
        echo "$BAD_DOLLAR"
        ERRORS=$((ERRORS + 1))
    fi
done < <(find app/src/main/res -name "*.xml" -print0)
if [ $ERRORS -eq 0 ] 2>/dev/null; then true; fi
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
