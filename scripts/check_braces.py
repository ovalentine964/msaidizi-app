#!/usr/bin/env python3
"""Check brace balance in Kotlin files, properly handling strings and comments."""
import sys
import re

def check_braces(filepath):
    with open(filepath) as fh:
        content = fh.read()

    # Remove block comments
    content = re.sub(r'/\*.*?\*/', '', content, flags=re.DOTALL)

    depth = 0
    i = 0
    n = len(content)

    while i < n:
        ch = content[i]

        # Single-line comment
        if ch == '/' and i + 1 < n and content[i + 1] == '/':
            while i < n and content[i] != '\n':
                i += 1
            continue

        # Newline — reset regular string state (regular strings can't span lines)
        if ch == '\n':
            i += 1
            continue

        # Triple-quoted string
        if ch == '"' and i + 2 < n and content[i:i+3] == '"""':
            i += 3
            while i < n:
                if content[i] == '"' and i + 2 < n and content[i:i+3] == '"""':
                    i += 3
                    break
                i += 1
            continue

        # Regular string
        if ch == '"':
            i += 1
            while i < n:
                if content[i] == '\n':
                    # Unterminated string — skip to next line
                    i += 1
                    break
                if content[i] == '\\':
                    i += 2
                    continue
                if content[i] == '"':
                    i += 1
                    break
                i += 1
            continue

        # Character literal
        if ch == "'":
            i += 1
            while i < n:
                if content[i] == '\n':
                    i += 1
                    break
                if content[i] == '\\':
                    i += 2
                    continue
                if content[i] == "'":
                    i += 1
                    break
                i += 1
            continue

        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1

        i += 1

    return depth

if __name__ == '__main__':
    depth = check_braces(sys.argv[1])
    if depth != 0:
        print(f'MISMATCH depth={depth}')
        sys.exit(1)
    print('OK')
