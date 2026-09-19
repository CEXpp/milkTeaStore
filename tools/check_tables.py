#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查 markdown 中 GFM 表格各行列数是否一致（不一致会在渲染时错位）。"""
import re
import sys

p = sys.argv[1]
lines = open(p, encoding="utf-8").read().split("\n")


def ncols(l):
    s = re.sub(r"\\\|", "\x00", l.strip())
    return s.count("|") - 1


bad = []
tables = 0
i = 0
while i < len(lines):
    if lines[i].strip().startswith("|") and i + 1 < len(lines) \
            and re.fullmatch(r"\|[\s:\-|]+\|", lines[i + 1].strip()):
        tables += 1
        head = ncols(lines[i])
        j = i + 1
        while j < len(lines) and lines[j].strip().startswith("|"):
            if ncols(lines[j]) != head:
                bad.append((j + 1, head, ncols(lines[j]), lines[j][:70]))
            j += 1
        i = j
    else:
        i += 1

print("表格数: %d  列数不一致行: %d" % (tables, len(bad)))
for b in bad[:15]:
    print("  line %d 期望 %d 列 实际 %d 列: %s" % b)
