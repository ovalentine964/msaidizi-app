#!/usr/bin/env python3
"""Check XML files for problematic $ signs (not Android format strings)."""
import sys

def check_xml_dollars(filepath):
    problems = []
    with open(filepath) as fh:
        for i, line in enumerate(fh, 1):
            j = 0
            while j < len(line):
                if line[j] == '$':
                    # Check if preceded by backslash (\$ = literal dollar in XML)
                    if j > 0 and line[j-1] == '\\':
                        j += 1
                        continue
                    # Check if part of Android format string %N$d or %N$s
                    # Walk backwards to find %
                    k = j - 1
                    while k >= 0 and line[k].isdigit():
                        k -= 1
                    if k >= 0 and line[k] == '%':
                        j += 1
                        continue
                    problems.append(f'{i}: {line.rstrip()}')
                    break
                j += 1
    return problems

if __name__ == '__main__':
    problems = check_xml_dollars(sys.argv[1])
    for p in problems:
        print(p)
    sys.exit(1 if problems else 0)
