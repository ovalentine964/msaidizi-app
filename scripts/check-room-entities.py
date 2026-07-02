#!/usr/bin/env python3
"""
Room Entity Registration Checker for Msaidizi App.

Scans all @Entity annotations in Kotlin source files, verifies each is
registered in AppDatabase's @Database(entities = [...]), and reports
any missing registrations.

Exit code 0 = all entities registered.
Exit code 1 = one or more entities missing from @Database.
"""
import os
import re
import sys

# Paths (relative to repo root)
SRC_ROOT = "app/src/main/java"
DB_FILE = "app/src/main/java/com/msaidizi/app/core/database/AppDatabase.kt"


def find_entity_classes(src_root: str) -> list[tuple[str, str, int]]:
    """
    Find all Kotlin classes annotated with @Entity.
    Returns list of (class_name, file_path, line_number).
    Handles multi-line @Entity(...) annotations.
    """
    entities = []
    entity_pattern = re.compile(r'@Entity\b')

    for dirpath, _, filenames in os.walk(src_root):
        for fname in filenames:
            if not fname.endswith('.kt'):
                continue
            fpath = os.path.join(dirpath, fname)
            try:
                with open(fpath, 'r', encoding='utf-8') as f:
                    lines = f.readlines()
            except (OSError, UnicodeDecodeError):
                continue

            i = 0
            while i < len(lines):
                if entity_pattern.search(lines[i]):
                    # Found @Entity — now find the class declaration
                    # It could be on the same line or subsequent lines
                    j = i
                    found_class = False
                    while j < min(i + 10, len(lines)):
                        # Match: data class, class, enum class, sealed class
                        m = re.search(
                            r'(?:data\s+|enum\s+|sealed\s+|abstract\s+|open\s+)?class\s+([A-Za-z_]\w*)',
                            lines[j]
                        )
                        if m:
                            entities.append((m.group(1), fpath, j + 1))
                            found_class = True
                            break
                        # If we hit another annotation or blank line before class,
                        # keep searching (but stop at next @Entity or top-level decl)
                        if re.match(r'^(package |import |fun |val |var |object )', lines[j].strip()):
                            break
                        j += 1
                    if not found_class:
                        # @Entity without a class following — skip (might be commented out)
                        pass
                    i = j + 1 if found_class else i + 1
                else:
                    i += 1
    return entities


def get_registered_entities(db_file: str) -> set[str]:
    """
    Extract entity class names from the @Database(entities = [...]) block.
    Handles multi-line arrays like:
        entities = [
            Foo::class,
            Bar::class,
        ]
    """
    try:
        with open(db_file, 'r', encoding='utf-8') as f:
            content = f.read()
    except OSError:
        print(f"ERROR: Could not read database file: {db_file}", file=sys.stderr)
        return set()

    # Find the @Database block
    db_match = re.search(r'@Database\s*\(', content)
    if not db_match:
        print(f"ERROR: No @Database annotation found in {db_file}", file=sys.stderr)
        return set()

    # Extract from @Database( to the matching )
    start = db_match.end()
    depth = 1
    pos = start
    while pos < len(content) and depth > 0:
        if content[pos] == '(':
            depth += 1
        elif content[pos] == ')':
            depth -= 1
        pos += 1
    db_block = content[start:pos - 1]

    # Find entities = [ ... ] inside the block
    entities_match = re.search(r'entities\s*=\s*\[([^\]]*)\]', db_block, re.DOTALL)
    if not entities_match:
        print(f"ERROR: No entities array found in @Database annotation", file=sys.stderr)
        return set()

    entities_str = entities_match.group(1)
    # Extract ClassName::class patterns
    registered = set(re.findall(r'(\w+)::class', entities_str))
    return registered


def main():
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    os.chdir(repo_root)

    print("🔍 Room Entity Registration Checker")
    print("=" * 40)

    # 1. Find all @Entity classes
    entities = find_entity_classes(SRC_ROOT)
    print(f"\nFound {len(entities)} @Entity class(es) in source:")
    for name, fpath, line in sorted(entities, key=lambda x: x[0]):
        print(f"  📦 {name}  ({fpath}:{line})")

    # 2. Get registered entities
    registered = get_registered_entities(DB_FILE)
    print(f"\nRegistered in AppDatabase ({len(registered)}):")
    for name in sorted(registered):
        print(f"  ✅ {name}")

    # 3. Compare
    entity_names = {name for name, _, _ in entities}
    missing = entity_names - registered
    orphaned = registered - entity_names

    errors = 0

    if missing:
        print(f"\n❌ MISSING from @Database ({len(missing)}):")
        for name in sorted(missing):
            # Find the file for context
            loc = next((f"{f}:{l}" for n, f, l in entities if n == name), "unknown")
            print(f"  ❌ {name}  ({loc})")
        errors += len(missing)

    if orphaned:
        print(f"\n⚠️  Registered but not found in source ({len(orphaned)}):")
        for name in sorted(orphaned):
            print(f"  ⚠️  {name}")
        # This is a warning, not an error — might be intentional

    print("\n" + "=" * 40)
    if errors:
        print(f"❌ {errors} entity(ies) missing from @Database — build WILL fail")
        print("   Add missing classes to the entities = [...] array in AppDatabase.kt")
        sys.exit(1)
    else:
        print("✅ All Room entities are properly registered")
        sys.exit(0)


if __name__ == '__main__':
    main()
