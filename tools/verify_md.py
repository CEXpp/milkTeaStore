#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 docx → markdown 转换是否零丢失。

用法: python verify_md.py <input.docx> <output.md>
判定: docx 中每个 <w:t> 文本片段（去掉空白与 markdown 转义后）都应能在 md 中找到。
"""
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def norm(s):
    s = s.replace("\u00a0", " ").replace("\u3000", " ")
    return re.sub(r"\s+", "", s)


def md_variants(md):
    """md 的若干种归一化视图，命中任一即视为「文字未丢」。

    A: 去掉 markdown 强调标记（处理 **粗体** 跨片段的情况）
    B: 仅去掉反斜杠转义（处理 ******、/api/admin/** 这类真实星号）
    """
    s = md.replace("\\*", "*").replace("\\|", "|").replace("\\_", "_")
    b = norm(s)
    a = s.replace("**", "").replace("~~", "").replace("`", "").replace("<br>", "")
    a = norm(a)
    return a, b


def main():
    docx_path, md_path = sys.argv[1], sys.argv[2]
    z = zipfile.ZipFile(docx_path)
    root = ET.fromstring(z.read("word/document.xml"))

    frags = [t.text or "" for t in root.iter(W + "t")]
    frags = [f for f in frags if norm(f)]
    md = open(md_path, encoding="utf-8").read()
    views = md_variants(md)

    missing = sorted({f for f in frags if not any(norm(f) in v for v in views)})
    print("docx 文本片段: %d 个" % len(frags))
    print("md 原始字符: %d  视图长度: %s" % (len(md), [len(v) for v in views]))
    print("未命中片段: %d 个" % len(missing))
    for m in missing[:40]:
        print("  MISS: %r" % m)
    return 0 if not missing else 1


if __name__ == "__main__":
    sys.exit(main())
