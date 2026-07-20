#!/usr/bin/env python3
"""
check-room-entities.py — Verify Room entities are registered in the database.
"""

import os
import re
import sys

ENTITY_ANNOTATION = "@Entity"
DATABASE_ANNOTATION = "@Database"
ENTITIES_PATTERN = r"entities\s*=\s*\["


def find_kotlin_files(root):
    """Find all .kt files under app/src/main/java."""
    for dirpath, _, filenames in os.walk(root):
        for f in filenames:
            if f.endswith(".kt"):
                yield os.path.join(dirpath, f)


def find_entities(src_dir):
    """Find all classes annotated with @Entity."""
    entities = []
    for filepath in find_kotlin_files(src_dir):
        with open(filepath, "r") as f:
            content = f.read()
        if ENTITY_ANNOTATION in content:
            # Extract class name
            match = re.search(r"class\s+(\w+)", content)
            if match:
                entities.append((match.group(1), filepath))
    return entities


def find_database_class(src_dir):
    """Find the @Database annotated class and its entity list."""
    for filepath in find_kotlin_files(src_dir):
        with open(filepath, "r") as f:
            content = f.read()
        if DATABASE_ANNOTATION in content:
            # Extract entities list
            match = re.search(ENTITIES_PATTERN, content)
            if match:
                # Find all class references in the entities array
                # Look for patterns like: SomeEntity::class
                start = match.end()
                bracket_count = 1
                end = start
                for i in range(start, len(content)):
                    if content[i] == "[":
                        bracket_count += 1
                    elif content[i] == "]":
                        bracket_count -= 1
                        if bracket_count == 0:
                            end = i
                            break
                entities_str = content[start:end]
                registered = re.findall(r"(\w+)::class", entities_str)
                return filepath, registered
    return None, []


def main():
    src_dir = "app/src/main/java"
    if not os.path.isdir(src_dir):
        print("⚠️  Source directory not found — skipping")
        return 0

    entities = find_entities(src_dir)
    if not entities:
        print("✅ No Room @Entity classes found — skipping check")
        return 0

    db_file, registered = find_database_class(src_dir)
    if not db_file:
        print("⚠️  No @Database class found — skipping check")
        return 0

    print(f"📦 Found {len(entities)} @Entity classes")
    print(f"📦 Database registers {len(registered)} entities in {os.path.basename(db_file)}")

    missing = []
    for entity_name, entity_file in entities:
        if entity_name not in registered:
            missing.append(entity_name)
            print(f"  ❌ {entity_name} NOT in @Database ({os.path.basename(entity_file)})")
        else:
            print(f"  ✅ {entity_name}")

    if missing:
        print(f"\n❌ {len(missing)} entity/entities not registered in @Database!")
        return 1

    print("\n✅ All Room entities are properly registered")
    return 0


if __name__ == "__main__":
    sys.exit(main())
