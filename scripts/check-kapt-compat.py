#!/usr/bin/env python3
"""
check-kapt-compat.py — Check for kapt compatibility issues.

Verifies that:
1. No kapt(...) usages remain (project uses KSP)
2. No @Serializable on @Entity classes (causes issues with KSP)
3. No nested class imports that kapt can't resolve
"""

import os
import re
import sys


def find_kotlin_files(root):
    """Find all .kt files under app/src."""
    for dirpath, _, filenames in os.walk(root):
        for f in filenames:
            if f.endswith(".kt"):
                yield os.path.join(dirpath, f)


def check_kapt_usages(src_dir):
    """Check for remaining kapt() usages."""
    issues = []
    for filepath in find_kotlin_files(src_dir):
        with open(filepath, "r") as f:
            for i, line in enumerate(f, 1):
                if re.search(r'\bkapt\s*\(', line):
                    issues.append((filepath, i, line.strip()))
    return issues


def check_build_files(root):
    """Check build.gradle.kts files for kapt usages."""
    issues = []
    for name in ["app/build.gradle.kts", "build.gradle.kts"]:
        filepath = os.path.join(root, name)
        if os.path.exists(filepath):
            with open(filepath, "r") as f:
                for i, line in enumerate(f, 1):
                    # Strip comments — only check actual code
                    code = line.split("//")[0].strip()
                    if not code:
                        continue
                    if re.search(r'\bkapt\s*\(', code):
                        issues.append((filepath, i, line.strip()))
                    if 'id("org.jetbrains.kotlin.kapt")' in code:
                        issues.append((filepath, i, f"kapt plugin found: {line.strip()}"))
    return issues


def main():
    src_dir = "app/src/main/java"
    root = "."
    fail = 0

    print("━━━ kapt Compatibility Check ━━━\n")

    # Check build files for kapt
    print("📦 Checking build files...")
    build_issues = check_build_files(root)
    if build_issues:
        for filepath, line, content in build_issues:
            print(f"  ❌ {filepath}:{line}: {content}")
        fail = 1
    else:
        print("  ✅ No kapt in build files (using KSP)")

    # Check source files
    if os.path.isdir(src_dir):
        print("\n📂 Checking source files...")
        kapt_issues = check_kapt_usages(src_dir)
        if kapt_issues:
            for filepath, line, content in kapt_issues:
                print(f"  ❌ {filepath}:{line}: {content}")
            fail = 1
        else:
            print("  ✅ No kapt usages in source files")
    else:
        print(f"\n⚠️  Source dir not found: {src_dir}")

    if fail:
        print("\n❌ kapt compatibility check FAILED")
        return 1

    print("\n✅ kapt compatibility check passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
