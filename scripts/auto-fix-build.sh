#!/bin/bash
# auto-fix-build.sh — Auto-fixes common Kotlin/Room build issues for Msaidizi App.
#
# Fixes:
#   1. @Serializable on @Entity classes (Room kapt conflicts with kotlinx.serialization)
#   2. Missing @Database registrations (new @Entity not in AppDatabase)
#   3. Duplicate class names in the same package
#   4. Missing imports (report only)
#
# Exit code: 0 = no fixes needed, 1 = fixes applied (caller should re-commit), 2 = fatal error

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/.."

SRC_ROOT="app/src/main/java"
DB_FILE="app/src/main/java/com/msaidizi/app/core/database/AppDatabase.kt"
FIXES_APPLIED=0

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_fix() { echo -e "${YELLOW}🔧 FIX:${NC} $1"; FIXES_APPLIED=$((FIXES_APPLIED + 1)); }
log_ok()   { echo -e "${GREEN}✅ OK:${NC} $1"; }
log_err()  { echo -e "${RED}❌ ERR:${NC} $1"; }

echo "🔧 Msaidizi Auto-Fix Build Script"
echo "=================================="

# ─────────────────────────────────────────────
# FIX 1: Remove @Serializable from @Entity classes
# ─────────────────────────────────────────────
echo ""
echo "📋 Fix 1: @Serializable on @Entity classes..."

FIX1_COUNT=0

while IFS= read -r -d '' fpath; do
    # Find @Entity class names in this file using Python (reliable multi-line)
    ENTITY_CLASSES=$(python3 -c "
import re, sys
with open('$fpath') as f:
    content = f.read()
# Find all class names that are preceded by @Entity (within 15 lines)
lines = content.split('\n')
for i, line in enumerate(lines):
    if '@Entity' in line:
        for j in range(i, min(i+15, len(lines))):
            m = re.search(r'(?:data|enum|sealed|abstract|open)\s+class\s+(\w+)', lines[j])
            if m:
                print(m.group(1))
                break
" 2>/dev/null || true)

    if [[ -z "$ENTITY_CLASSES" ]]; then
        continue
    fi

    # For each @Entity class, check if @Serializable is above it and remove it
    while IFS= read -r cls; do
        if [[ -z "$cls" ]]; then continue; fi

        # Use Python to remove @Serializable above this specific @Entity class
        FIXED=$(python3 -c "
import re, sys

with open('$fpath') as f:
    lines = f.readlines()

# Find the @Entity class '$cls'
entity_line = -1
for i, line in enumerate(lines):
    if '@Entity' in line:
        for j in range(i, min(i+15, len(lines))):
            if re.search(r'(?:data|enum|sealed|abstract|open)\s+class\s+${cls}\b', lines[j]):
                entity_line = i
                break
        if entity_line >= 0:
            break

if entity_line < 0:
    sys.exit(0)

# Check lines above @Entity for @Serializable
removed = False
for k in range(entity_line - 1, max(entity_line - 5, -1), -1):
    stripped = lines[k].strip()
    if '@Serializable' in stripped:
        lines[k] = ''
        removed = True
        print(f'Removed @Serializable at line {k+1}')
        break
    elif stripped == '' or stripped.startswith('@'):
        continue
    else:
        break

if removed:
    with open('$fpath', 'w') as f:
        f.writelines(lines)
" 2>/dev/null || true)

        if [[ -n "$FIXED" ]]; then
            log_fix "$FIXED in $fpath (class $cls)"
            FIX1_COUNT=$((FIX1_COUNT + 1))
        fi
    done <<< "$ENTITY_CLASSES"
done < <(find "$SRC_ROOT" -name "*.kt" -print0)

if [[ $FIX1_COUNT -eq 0 ]]; then
    log_ok "No @Serializable on @Entity classes found"
fi

# ─────────────────────────────────────────────
# FIX 2: Missing @Database registrations
# ─────────────────────────────────────────────
echo ""
echo "📋 Fix 2: Missing @Database registrations..."

# Use the existing Python checker to find entity classes reliably
ENTITY_INFO=$(python3 -c "
import os, re, sys

SRC_ROOT = '$SRC_ROOT'
entities = []

for dirpath, _, filenames in os.walk(SRC_ROOT):
    for fname in filenames:
        if not fname.endswith('.kt'):
            continue
        fpath = os.path.join(dirpath, fname)
        try:
            with open(fpath) as f:
                lines = f.readlines()
        except:
            continue

        i = 0
        while i < len(lines):
            if re.search(r'@Entity\b', lines[i]):
                j = i
                found = False
                while j < min(i + 15, len(lines)):
                    m = re.search(r'(?:data|enum|sealed|abstract|open)\s+class\s+(\w+)', lines[j])
                    if m:
                        # Get package
                        pkg = ''
                        for pl in lines[:5]:
                            pm = re.match(r'^package\s+([\w.]+)', pl)
                            if pm:
                                pkg = pm.group(1)
                                break
                        entities.append((m.group(1), pkg, fpath, j + 1))
                        found = True
                        break
                    if re.match(r'^(package |import |fun |val |var |object )', lines[j].strip()):
                        break
                    j += 1
                i = j + 1 if found else i + 1
            else:
                i += 1

for name, pkg, fpath, line in sorted(entities, key=lambda x: x[0]):
    print(f'{name}|{pkg}|{fpath}|{line}')
" 2>/dev/null)

# Extract registered entities from AppDatabase
REGISTERED=$(grep -oP '\w+::class' "$DB_FILE" | sed 's/::class//' | sort -u)

MISSING_ENTITIES=()
MISSING_PKGS=()
MISSING_FILES=()

while IFS='|' read -r name pkg fpath line; do
    if [[ -z "$name" ]]; then continue; fi
    if ! echo "$REGISTERED" | grep -qx "$name"; then
        MISSING_ENTITIES+=("$name")
        MISSING_PKGS+=("$pkg")
        MISSING_FILES+=("$fpath")
    fi
done <<< "$ENTITY_INFO"

FIX2_COUNT=0

if [[ ${#MISSING_ENTITIES[@]} -gt 0 ]]; then
    echo "  Found ${#MISSING_ENTITIES[@]} unregistered @Entity class(es):"

    # Find the last import line number
    IMPORT_LINE=$(grep -n "^import " "$DB_FILE" | tail -1 | cut -d: -f1)
    # Find the entities array closing bracket
    ENTITIES_END=$(grep -n "^\s*\]" "$DB_FILE" | head -1 | cut -d: -f1)
    # Find current version
    CURRENT_VERSION=$(grep -oP 'version\s*=\s*\K\d+' "$DB_FILE" | head -1)
    NEW_VERSION=$((CURRENT_VERSION + 1))

    for i in "${!MISSING_ENTITIES[@]}"; do
        entity="${MISSING_ENTITIES[$i]}"
        pkg="${MISSING_PKGS[$i]}"

        # Add import if not present
        if ! grep -q "import ${pkg}.${entity}" "$DB_FILE"; then
            sed -i "${IMPORT_LINE}a import ${pkg}.${entity}" "$DB_FILE"
            IMPORT_LINE=$((IMPORT_LINE + 1))
            log_fix "Added import ${pkg}.${entity} to AppDatabase.kt"
        fi

        # Add entity to entities array (before closing ])
        sed -i "$((ENTITIES_END))i\\        ${entity}::class," "$DB_FILE"
        ENTITIES_END=$((ENTITIES_END + 1))

        log_fix "Registered ${entity}::class in @Database"
        FIX2_COUNT=$((FIX2_COUNT + 1))
    done

    # Bump version
    sed -i "s/version = ${CURRENT_VERSION}/version = ${NEW_VERSION}/" "$DB_FILE"
    log_fix "Bumped database version ${CURRENT_VERSION} → ${NEW_VERSION}"
else
    log_ok "All @Entity classes are registered in @Database"
fi

# ─────────────────────────────────────────────
# FIX 3: Duplicate class names in same package
# ─────────────────────────────────────────────
echo ""
echo "📋 Fix 3: Duplicate class names in same package..."

FIX3_COUNT=0

# Build a map of (package, class_name) -> file using Python for reliability
DUPES=$(python3 -c "
import os, re
from collections import defaultdict

SRC_ROOT = '$SRC_ROOT'
class_map = defaultdict(list)

for dirpath, _, filenames in os.walk(SRC_ROOT):
    for fname in filenames:
        if not fname.endswith('.kt'):
            continue
        fpath = os.path.join(dirpath, fname)
        try:
            with open(fpath) as f:
                lines = f.readlines()
        except:
            continue

        pkg = ''
        for line in lines[:5]:
            m = re.match(r'^package\s+([\w.]+)', line)
            if m:
                pkg = m.group(1)
                break

        for i, line in enumerate(lines):
            # Only match TOP-LEVEL classes: no leading whitespace
            if line and not line[0].isspace():
                m = re.search(r'(?:data|enum|sealed|abstract|open|object)\s+(?:class|object)\s+(\w+)', line)
                if m:
                    cls = m.group(1)
                    class_map[(pkg, cls)].append(fpath)

for (pkg, cls), files in sorted(class_map.items()):
    if len(files) > 1:
        for f in files[1:]:  # Skip first occurrence
            print(f'{pkg}|{cls}|{f}')
" 2>/dev/null || true)

if [[ -n "$DUPES" ]]; then
    while IFS='|' read -r pkg cls fpath; do
        if [[ -z "$cls" ]]; then continue; fi
        base=$(basename "$fpath" .kt)
        new_name="${cls}${base}"

        echo -e "  ${YELLOW}⚠️  Duplicate:${NC} $cls in package $pkg"
        echo "       Renaming in: $fpath → $new_name"

        # Rename class and all references within the file
        sed -i "s/\b${cls}\b/${new_name}/g" "$fpath"
        log_fix "Renamed $cls → $new_name in $fpath"
        FIX3_COUNT=$((FIX3_COUNT + 1))
    done <<< "$DUPES"
else
    log_ok "No duplicate class names found"
fi

# ─────────────────────────────────────────────
# FIX 4: Missing imports (report only)
# ─────────────────────────────────────────────
echo ""
echo "📋 Fix 4: Missing import detection (report only)..."

MISSING_IMPORT_COUNT=0

while IFS= read -r -d '' fpath; do
    content=$(cat "$fpath")

    # Check for wildcard imports too (import androidx.room.*)
    has_room_import=$(echo "$content" | grep -c "import androidx.room" || true)
    has_dagger_import=$(echo "$content" | grep -c "import javax.inject.Inject\|import dagger" || true)

    if echo "$content" | grep -q "@Entity" && [[ $has_room_import -eq 0 ]]; then
        log_err "Missing import androidx.room.Entity in $fpath"
        MISSING_IMPORT_COUNT=$((MISSING_IMPORT_COUNT + 1))
    fi

    if echo "$content" | grep -q "@Dao" && [[ $has_room_import -eq 0 ]]; then
        log_err "Missing import androidx.room.Dao in $fpath"
        MISSING_IMPORT_COUNT=$((MISSING_IMPORT_COUNT + 1))
    fi

    if echo "$content" | grep -q "@PrimaryKey" && [[ $has_room_import -eq 0 ]]; then
        log_err "Missing import androidx.room.PrimaryKey in $fpath"
        MISSING_IMPORT_COUNT=$((MISSING_IMPORT_COUNT + 1))
    fi

    if echo "$content" | grep -q "@Inject" && [[ $has_dagger_import -eq 0 ]]; then
        log_err "Missing @Inject import in $fpath"
        MISSING_IMPORT_COUNT=$((MISSING_IMPORT_COUNT + 1))
    fi
done < <(find "$SRC_ROOT" -name "*.kt" -print0)

if [[ $MISSING_IMPORT_COUNT -eq 0 ]]; then
    log_ok "No missing imports detected"
else
    echo -e "  ${YELLOW}⚠️  ${MISSING_IMPORT_COUNT} missing import(s) detected — manual fix required${NC}"
fi

# ─────────────────────────────────────────────
# Summary
# ─────────────────────────────────────────────
TOTAL_FIXES=$((FIX1_COUNT + FIX2_COUNT + FIX3_COUNT))
echo ""
echo "=================================="
if [[ $TOTAL_FIXES -gt 0 ]]; then
    echo -e "${YELLOW}🔧 Applied ${TOTAL_FIXES} auto-fix(es)${NC}"
    echo "   - @Serializable removals: $FIX1_COUNT"
    echo "   - @Database registrations: $FIX2_COUNT"
    echo "   - Duplicate renames: $FIX3_COUNT"
    if [[ $MISSING_IMPORT_COUNT -gt 0 ]]; then
        echo "   - Missing imports (manual): $MISSING_IMPORT_COUNT"
    fi
    exit 1  # Fixes were applied — caller should re-commit
else
    echo -e "${GREEN}✅ No auto-fixes needed${NC}"
    if [[ $MISSING_IMPORT_COUNT -gt 0 ]]; then
        echo -e "   ${YELLOW}⚠️  ${MISSING_IMPORT_COUNT} missing import(s) need manual fix${NC}"
    fi
    exit 0
fi
