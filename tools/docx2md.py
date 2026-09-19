#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
docx2md.py —— 把 .docx 转成结构化的、对 AI 友好的 Markdown。

设计目标：
  1. 内容零丢失：正文、标题层级、表格、图片、目录、脚注全部保留。
  2. 低 token：去掉装饰性空段落、图片二进制、页眉页脚样板、重复 TOC 页码字段。
  3. 结构可读：标题用 # 层级，表格用 GFM 表格，图片抽到 assets/ 并写成相对引用，
     等宽字体单列「代码框」还原为围栏代码块（保留空行与缩进）。

用法：
  python docx2md.py <input.docx> [-o out.md] [--assets-dir 名字] [--no-assets] [--no-fence-code]

仅依赖 Python 标准库。
"""

import argparse
import os
import re
import sys
import zipfile
import xml.etree.ElementTree as ET
from collections import Counter

W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
R = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
A = "{http://schemas.openxmlformats.org/drawingml/2006/main}"
MC = "{http://schemas.openxmlformats.org/markup-compatibility/2006}"
PKG_REL = "{http://schemas.openxmlformats.org/package/2006/relationships}"

# pStyle -> markdown 前缀
HEADING_STYLE = {
    "Title": "#",
    "Heading1": "#",
    "Heading2": "##",
    "Heading3": "###",
    "Heading4": "####",
    "Heading5": "#####",
    "Heading6": "######",
    "Heading7": "######",
}
# TOC 样式 -> 缩进层级
TOC_STYLE = {"9": 1, "10": 2, "11": 2, "12": 3}

IMG_EXT = {".png", ".jpg", ".jpeg", ".gif", ".bmp", ".tiff", ".emf", ".wmf", ".svg"}

# 形如 "H  I  G  H" 的字距装饰
RE_TRACKED = re.compile(r"(?:[A-Za-z0-9\-\.] {2,}){3,}[A-Za-z0-9\-\.]")
# 整格加粗 -> 直接去掉星号（单元格已有语义）
RE_FULL_BOLD = re.compile(r"^\s*\*\*(.+?)\*\*\s*$", re.S)


def text_of(el):
    """取元素下所有 w:t 的纯文本（用于脚注等简单场景）。"""
    return "".join(t.text or "" for t in el.iter(W + "t"))


def _untrack(m):
    """字距装饰：正好两个空格的间隙抹掉，更宽的间隙压成一个空格当分隔。"""
    parts = re.split(r"( +)", m.group(0))
    return "".join("" if len(p) == 2 else (" " if len(p) > 2 else p) for p in parts)


def tidy(s):
    """把排版用的多余空白压掉，同时还原被人工拉开的字距。"""
    if not s:
        return s
    s = RE_TRACKED.sub(_untrack, s)
    s = re.sub(r"[ \t\u00a0\u3000]{3,}", " ", s)
    return re.sub(r"[ \t]+$", "", s)


def strip_marks(s):
    """去掉行内强调标记，用于标题这类本身已是块级标记的文本。"""
    s = re.sub(r"</?br\s*/?>", " ", s, flags=re.I)
    s = s.replace("**", "").replace("~~", "").replace("`", "")
    s = re.sub(r"(?<!\*)\*(?!\*)", "", s)
    return re.sub(r"\s+", " ", s).strip()


def is_list_block(s):
    lines = [l for l in s.split("\n") if l.strip()]
    return bool(lines) and all(re.match(r"^\s*- ", l) for l in lines)


def has_child(el, tag):
    return el.find(tag) is not None


class Converter:
    def __init__(self, docx_path, assets_dir_name="assets", extract_assets=True,
                 fence_code=True):
        self.docx_path = docx_path
        self.z = zipfile.ZipFile(docx_path)
        self.rels = self._load_rels("word/_rels/document.xml.rels")
        self.assets_dir_name = assets_dir_name
        self.extract_assets = extract_assets
        self.fence_code = fence_code
        self.assets = []          # 已提取的图片 [(rel_id, out_relpath, media_name)]
        self.media_written = {}
        self.image_seq = 0
        self.footnote_text = {}   # id -> markdown 文本
        self.footnote_seq = {}
        self.warnings = []
        self.stats = Counter()
        self.header_title = self.read_header_title()
        self.cover_title = None

    # ---------- 基础 ----------
    def _load_rels(self, name):
        if name not in self.z.namelist():
            return {}
        root = ET.fromstring(self.z.read(name))
        out = {}
        for rel in root.iter(PKG_REL + "Relationship"):
            out[rel.get("Id")] = (rel.get("Target"), rel.get("Type", ""))
        return out

    # ---------- 行内内容 ----------
    def inline(self, node, ctx=None):
        """把一段子树渲染成行内 markdown 文本。"""
        out = []
        for ch in node:
            tag = ch.tag
            if tag == W + "r":
                out.append(self.run(ch))
            elif tag == W + "hyperlink":
                inner = self.inline(ch, ctx)
                rid = ch.get(R + "id")
                url = None
                if rid and rid in self.rels:
                    url = self.rels[rid][0]
                if url and url.startswith("http"):
                    out.append("[%s](%s)" % (inner, url))
                else:
                    # 内部锚点（目录跳转）-> 只留文字
                    out.append(inner)
            elif tag in (W + "fldSimple", W + "smartTag", W + "sdt", W + "sdtContent"):
                out.append(self.inline(ch, ctx))
            elif tag == W + "ins":
                out.append(self.inline(ch, ctx))
            elif tag == W + "del":
                pass  # 修订删除内容不输出
            elif tag == W + "bookmarkStart" or tag == W + "bookmarkEnd":
                pass
            else:
                out.append(self.inline(ch, ctx))
        return "".join(x for x in out if x)

    def run(self, r):
        rpr = r.find(W + "rPr")
        bold = italic = strike = False
        mono = False
        if rpr is not None:
            b = rpr.find(W + "b")
            if b is not None and b.get(W + "val") not in ("0", "false"):
                bold = True
            i = rpr.find(W + "i")
            if i is not None and i.get(W + "val") not in ("0", "false"):
                italic = True
            st = rpr.find(W + "strike")
            if st is not None and st.get(W + "val") not in ("0", "false"):
                strike = True
            fonts = rpr.find(W + "rFonts")
            if fonts is not None:
                ascii_font = (fonts.get(W + "ascii") or "") + (fonts.get(W + "hAnsi") or "")
                if re.search(r"consol|mono|courier|code", ascii_font, re.I):
                    mono = True

        parts = []
        for ch in r:
            tag = ch.tag
            if tag == W + "t":
                parts.append(ch.text or "")
            elif tag == W + "tab":
                parts.append(" ")
            elif tag == W + "br":
                parts.append("<br>")
            elif tag == W + "drawing":
                parts.append(self.drawing(ch))
            elif tag == W + "pict":
                parts.append(self.vml_pict(ch))
            elif tag == W + "footnoteReference":
                parts.append(self.footnote_ref(ch.get(W + "id")))
            elif tag == W + "endnoteReference":
                parts.append(self.footnote_ref(ch.get(W + "id"), endnote=True))
            elif tag == W + "noBreakHyphen":
                parts.append("-")
            elif tag in (W + "instrText", W + "fldChar", W + "sym", W + "softHyphen"):
                pass

        txt = "".join(parts)
        if not txt:
            return ""
        if mono and txt.strip() and "<img" not in txt:
            txt = "`%s`" % txt
        if bold and txt.strip() and "<img" not in txt:
            txt = "**%s**" % txt
        if italic and txt.strip() and "<img" not in txt:
            txt = "*%s*" % txt
        if strike and txt.strip():
            txt = "~~%s~~" % txt
        return txt

    def footnote_ref(self, fid, endnote=False):
        if fid is None:
            return ""
        store = self.footnote_text
        if fid not in store:
            self.load_footnotes()
        if fid not in self.footnote_seq:
            self.footnote_seq[fid] = len(self.footnote_seq) + 1
        n = self.footnote_seq[fid]
        return "[^%d]" % n

    def load_footnotes(self):
        for name, tag in (("word/footnotes.xml", "footnote"), ("word/endnotes.xml", "endnote")):
            if name not in self.z.namelist():
                continue
            root = ET.fromstring(self.z.read(name))
            for fn in root.iter(W + tag):
                fid = fn.get(W + "id")
                if fid in (None, "-1", "0"):
                    continue
                paras = [self.para_text(p) for p in fn.findall(W + "p")]
                txt = " ".join(x for x in paras if x).strip()
                if txt:
                    self.footnote_text[fid] = txt

    def para_text(self, p):
        return re.sub(r"\s+", " ", self.inline(p, None)).strip()

    # ---------- 图片 ----------
    def media_out_name(self, target):
        base = os.path.basename(target)
        return base

    def register_image(self, rid):
        if not rid or rid not in self.rels:
            return None
        target, rtype = self.rels[rid]
        if "image" not in rtype:
            return None
        media_path = "word/" + target if not target.startswith("/") else target.lstrip("/")
        media_path = os.path.normpath(media_path).replace("\\", "/")
        if media_path not in self.z.namelist():
            self.warnings.append("缺少媒体文件: %s" % media_path)
            return None
        if media_path in self.media_written:
            return self.media_written[media_path]
        self.image_seq += 1
        ext = os.path.splitext(media_path)[1].lower() or ".png"
        out_name = "img%02d%s" % (self.image_seq, ext)
        rel_path = "%s/%s" % (self.assets_dir_name, out_name) if self.extract_assets else out_name
        entry = (media_path, rel_path, out_name)
        self.assets.append(entry)
        self.media_written[media_path] = rel_path
        return rel_path

    def drawing(self, d):
        blip = None
        for b in d.iter(A + "blip"):
            blip = b
            break
        if blip is None:
            return ""
        rid = blip.get(R + "embed") or blip.get(R + "link")
        rel = self.register_image(rid)
        if not rel:
            return ""
        alt = ""
        for docpr in d.iter(
            "{http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing}docPr"
        ):
            alt = docpr.get("descr") or docpr.get("name") or ""
            break
        alt = (alt or "图片").replace("]", "）").replace("[", "（")
        # 作为独立块输出（前后加换行），便于 markdown 渲染
        return "\n\n![%s](%s)\n\n" % (alt, rel)

    def vml_pict(self, p):
        for im in p.iter("{urn:schemas-microsoft-com:vml}imagedata"):
            rid = im.get(R + "id")
            rel = self.register_image(rid)
            if rel:
                return "\n\n![](%s)\n\n" % rel
        return ""

    # ---------- 段落 ----------
    @staticmethod
    def content_nodes(node):
        """按文档顺序遍历段落正文节点，跳过 pPr（内含制表位定义，不是正文分隔符）。"""
        for ch in node:
            if ch.tag == W + "pPr":
                continue
            yield ch
            for sub in Converter.content_nodes(ch):
                yield sub

    def toc_entry(self, p, style):
        """目录条目 = [标题文字][制表位][PAGEREF 页码]，整体是内部书签链接。"""
        anchor = None
        for h in p.iter(W + "hyperlink"):
            a = h.get(W + "anchor")
            if a:
                anchor = a
                break
        pre, post, seen_tab = [], [], False
        for node in self.content_nodes(p):
            if node.tag == W + "tab":
                seen_tab = True
            elif node.tag == W + "t":
                (post if seen_tab else pre).append(node.text or "")
        title = re.sub(r"\s+", " ", "".join(pre)).strip()
        if not title:
            return ""
        page = "".join(post).strip()
        item = "[%s](#%s)" % (title, anchor) if anchor else title
        if page:
            item += " " + page          # 页码是自动域，一并保留
        self.stats["toc"] += 1
        return "%s- %s" % ("  " * (TOC_STYLE[style] - 1), item)

    def paragraph(self, p):
        ppr = p.find(W + "pPr")
        style = None
        if ppr is not None:
            ps = ppr.find(W + "pStyle")
            if ps is not None:
                style = ps.get(W + "val")

        # 目录条目
        if style in TOC_STYLE:
            return self.toc_entry(p, style)

        # 段落自带书签（标题锚点）：输出 HTML 锚点，供目录链接跳转
        marks = [(bs.get(W + "name") or "") for bs in p.findall(W + "bookmarkStart")]
        marks = [m for m in marks if m and not m.startswith("_GoBack")]

        body = self.inline(p, None)
        body = re.sub(r"\n{3,}", "\n\n", body).strip()
        body = tidy(body)

        # 纯换行占位段落
        if not body or re.fullmatch(r"(<br>\s*)+", body):
            return ""

        # 手工项目符号 -> markdown 列表
        body = re.sub(r"^[•·▪◦]\s*", "- ", body)

        if style in HEADING_STYLE:
            body = strip_marks(body)
            if not body:
                return ""
            level = len(HEADING_STYLE[style])
            self.stats["heading%d" % level] += 1
            head = "%s %s" % ("#" * level, body)
            if marks:
                self.stats["anchor"] += len(marks)
                tags = "".join('<a id="%s"></a>' % m for m in marks)
                # HTML 块需空行收尾，后面的标题才不会被吞进去
                return "%s\n\n%s" % (tags, head)
            return head

        self.stats["para"] += 1
        if marks:
            self.stats["anchor"] += len(marks)
            return "".join('<a id="%s"></a>' % m for m in marks) + "\n\n" + body
        return body

    # ---------- 表格 ----------
    def cell_markdown(self, tc):
        blocks = []
        for ch in tc:
            if ch.tag == W + "p":
                t = self.paragraph(ch)
                if t:
                    blocks.append(t)
            elif ch.tag == W + "tbl":
                blocks.append(self.table(ch))
        txt = "<br>".join(blocks)
        txt = RE_FULL_BOLD.sub(r"\1", txt)
        # 落单的星号（如 /api/admin/** 这类通配路径）转义，避免污染 markdown 渲染
        if txt.count("**") % 2 == 1:
            txt = txt.replace("**", r"\*\*")
        return txt.replace("|", "\\|").strip()

    def table(self, tbl):
        rows = []
        for tr in tbl.findall(W + "tr"):
            cells = []
            for tc in tr.findall(W + "tc"):
                tcpr = tc.find(W + "tcPr")
                span = 1
                if tcpr is not None:
                    gs = tcpr.find(W + "gridSpan")
                    if gs is not None:
                        try:
                            span = int(gs.get(W + "val"))
                        except (TypeError, ValueError):
                            span = 1
                    vm = tcpr.find(W + "vMerge")
                    if vm is not None and (vm.get(W + "val") in (None, "continue")):
                        span = max(span, 1)
                md = self.cell_markdown(tc)
                cells.append(md)
                for _ in range(span - 1):
                    cells.append("")
            rows.append(cells)

        # 去掉全空行
        rows = [r for r in rows if any(c.strip() for c in r)]
        if not rows:
            return ""
        ncols = max(len(r) for r in rows)
        if ncols > 8:
            # 超宽表：退化为紧凑列表，避免 markdown 表格爆炸
            self.stats["table_wide"] += 1
            out = []
            for r in rows:
                pairs = [c for c in r if c.strip()]
                if pairs:
                    out.append("- " + " ｜ ".join(pairs))
            return "\n".join(out)

        rows = [r + [""] * (ncols - len(r)) for r in rows]
        self.stats["table"] += 1
        lines = []
        header = rows[0]
        lines.append("| " + " | ".join(header) + " |")
        lines.append("|" + "|".join(["---"] * ncols) + "|")
        for r in rows[1:]:
            lines.append("| " + " | ".join(r) + " |")
        return "\n".join(lines)

    # ---------- 代码框（等宽单列表格） ----------
    def is_code_table(self, tbl):
        """单行单列、且正文全部是等宽字体的表格 —— Word 里的「代码框」。"""
        trs = tbl.findall(W + "tr")
        if len(trs) != 1:
            return False
        if len(trs[0].findall(W + "tc")) != 1:
            return False
        fonts = set()
        for rpr in tbl.iter(W + "rPr"):
            f = rpr.find(W + "rFonts")
            if f is None:
                continue
            name = ((f.get(W + "ascii") or "") + (f.get(W + "hAnsi") or "")).strip()
            if name:
                fonts.add(name)
        if not fonts:
            return False
        return all(re.search(r"consol|mono|courier|code", n, re.I) for n in fonts)

    def code_block(self, tbl):
        """原样取出代码行：保空白、保缩进、保空行，围栏包裹。"""
        lines = []
        for p in tbl.iter(W + "p"):
            buf = []
            for node in p.iter():
                if node.tag == W + "t":
                    buf.append(node.text or "")
                elif node.tag == W + "tab":
                    buf.append("    ")
                elif node.tag == W + "br":
                    buf.append("\n")
            lines.append("".join(buf))
        while lines and not lines[0].strip():
            lines.pop(0)
        while lines and not lines[-1].strip():
            lines.pop()
        if not lines:
            return ""
        text = "\n".join(l.replace("\u00a0", " ").rstrip() for l in lines)
        self.stats["codeblock"] += 1
        return "```%s\n%s\n```" % (self.detect_lang(text), text)

    @staticmethod
    def detect_lang(t):
        """按内容猜语言，仅用于代码高亮，不影响文字本身。"""
        if re.search(r"^\s*(CREATE\s+TABLE|ALTER\s+TABLE|INSERT\s+INTO|SELECT\s)\b",
                     t, re.M | re.I):
            return "sql"
        if re.search(r"^\s*(services|version|volumes|networks)\s*:\s*$", t, re.M) or \
           re.search(r"^\s*spring\s*:", t, re.M):
            return "yaml"
        if re.search(r"^\s*(@\w+|public|private|protected|class\s|interface\s)", t, re.M):
            return "java"
        if re.match(r"\s*[\[{]", t):
            return "json"
        return "text"

    # ---------- 封面 ----------
    def render_cover(self, lines):
        """把封面版式表格渲染成「H1 + 引用块」，文字一条不丢。"""
        lines = [l for l in (strip_marks(tidy(x)) for x in lines) if l]
        if not lines:
            return []
        eyebrow = ""
        if re.fullmatch(r"[A-Z0-9\-\.\s]+", lines[0]) and len(lines[0]) > 8:
            eyebrow = lines.pop(0)

        doc_title = self.header_title
        if not doc_title:
            title = []
            while lines and len(title) < 2 and "：" not in lines[0] and "·" not in lines[0]:
                title.append(lines.pop(0))
            doc_title = " ".join(title) or "文档"

        sub, meta, plain = [], [], []
        for l in lines:
            if "：" in l:
                meta.append(l)
            elif any(c in l for c in "·—|"):
                sub.append(l)
            else:
                plain.append(l)

        out = ["# %s" % doc_title]
        if eyebrow:
            out.append("*%s*" % eyebrow)
        block = []
        for l in sub:
            block.append(l)
        for l in plain:
            block.append(l)
        for i in range(0, len(meta), 2):
            block.append(" ｜ ".join(meta[i:i + 2]))
        if block:
            out.append("\n".join("> " + b for b in block))
        self.cover_title = doc_title
        return out

    # ---------- 主体 ----------
    def convert(self):
        root = ET.fromstring(self.z.read("word/document.xml"))
        body = root.find(W + "body")
        blocks = []
        for ch in body:
            if ch.tag == W + "p":
                t = self.paragraph(ch)
                if t:
                    blocks.append(t)
            elif ch.tag == W + "tbl":
                trs = ch.findall(W + "tr")
                cols = len(trs[0].findall(W + "tc")) if trs else 0
                if cols <= 1 and self.fence_code and self.is_code_table(ch):
                    t = self.code_block(ch)
                    if t:
                        blocks.append(t)
                elif cols <= 1:
                    # 版式表格（封面/整页底色块）：取其中段落另作封面处理
                    lines = []
                    for p in ch.iter(W + "p"):
                        t = self.paragraph(p)
                        if t:
                            lines.append(t)
                    if lines:
                        self.stats["layout_table"] += 1
                        if not blocks:
                            blocks.extend(self.render_cover(lines))
                        else:
                            blocks.extend(lines)
                else:
                    t = self.table(ch)
                    if t:
                        blocks.append(t)
            elif ch.tag == W + "sdt":
                for p in ch.iter(W + "p"):
                    t = self.paragraph(p)
                    if t:
                        blocks.append(t)

        # 连续列表项合并成一块，保证 markdown 列表不被空行打断
        merged = []
        for b in blocks:
            if merged and is_list_block(merged[-1]) and is_list_block(b):
                merged[-1] = merged[-1] + "\n" + b
                continue
            # 图片与其图注贴在一起，便于阅读与检索
            if merged and re.fullmatch(r"!\[[^\]]*\]\([^)]+\)", merged[-1]) and re.match(r"^图\s?\S", b):
                merged[-1] = merged[-1] + "\n" + b
                continue
            merged.append(b)

        text = "\n\n".join(merged)
        text = re.sub(r"\n{3,}", "\n\n", text).strip() + "\n"

        # 脚注定义
        if self.footnote_text:
            fns = []
            for fid, n in sorted(self.footnote_seq.items(), key=lambda kv: kv[1]):
                if fid in self.footnote_text:
                    fns.append("[^%d]: %s" % (n, self.footnote_text[fid]))
            if fns:
                text += "\n" + "\n".join(fns) + "\n"
        return text

    # ---------- 页眉 ----------
    def read_header_title(self):
        """页眉里通常写着文档全名，用作 H1 兜底标题。"""
        for i in range(1, 6):
            name = "word/header%d.xml" % i
            if name not in self.z.namelist():
                continue
            ok = False
            for t in ET.fromstring(self.z.read(name)).iter(W + "t"):
                v = (t.text or "").strip()
                if len(v) > 2:
                    return v
            _ = ok
        return None

    # ---------- 资源 ----------
    def write_assets(self, out_md):
        if not self.extract_assets:
            return
        base = os.path.dirname(os.path.abspath(out_md))
        adir = os.path.join(base, self.assets_dir_name)
        for media_path, rel_path, out_name in self.assets:
            os.makedirs(adir, exist_ok=True)
            with open(os.path.join(adir, out_name), "wb") as f:
                f.write(self.z.read(media_path))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("-o", "--output")
    ap.add_argument("--assets-dir", default=None,
                    help="图片目录名，默认取「输出文件名.assets」，避免多份文档互相覆盖")
    ap.add_argument("--no-assets", action="store_true")
    ap.add_argument("--no-fence-code", action="store_true",
                    help="不要把等宽单列表格转成围栏代码块")
    a = ap.parse_args()

    out_md = a.output or os.path.splitext(a.input)[0] + ".md"
    adir = a.assets_dir or (os.path.basename(os.path.splitext(out_md)[0]) + ".assets")
    c = Converter(a.input, assets_dir_name=adir, extract_assets=not a.no_assets,
                  fence_code=not a.no_fence_code)
    md = c.convert()
    with open(out_md, "w", encoding="utf-8") as f:
        f.write(md)
    c.write_assets(out_md)

    print("输出:", out_md)
    print("字数:", len(md), " 行数:", md.count("\n") + 1)
    print("统计:", dict(c.stats))
    print("图片:", len(c.assets))
    for m, r, o in c.assets:
        print("   ", m, "->", r)
    if c.warnings:
        print("警告:", c.warnings)


if __name__ == "__main__":
    main()
