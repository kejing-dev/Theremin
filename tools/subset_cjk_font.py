#!/usr/bin/env python
"""Subset Noto Sans SC (variable, OFL 1.1) to the characters the app's Chinese copy uses.

Re-run whenever the zh strings or the fold library change:

    python tools/subset_cjk_font.py [--source <NotoSansSC-VF.ttf>]

Default source: the full font bundled by ../fire-plan (17 MB; the songs.json names are added). Output:
app/src/main/res/font/noto_sans_sc.ttf (wght axis kept) + third_party/noto-sans-sc/SUBSET.txt.
"""
import argparse
import os
import re

from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
SOURCES = [
    os.path.join(ROOT, "app", "src", "main", "res", "values", "strings.xml"),
    os.path.join(ROOT, "app", "src", "main", "res", "values-zh", "strings.xml"),
    os.path.join(ROOT, "app", "src", "main", "java", "io", "embodit", "theremin", "ui", "play", "components", "Panels.kt"),
    os.path.join(ROOT, "app", "src", "main", "java", "io", "embodit", "theremin", "domain", "play", "Practice.kt"),
    os.path.join(ROOT, "app", "src", "main", "assets", "songs.json"),
]
EXTRA = "，。、；：？！…—·（）《》〈〉“”‘’「」『』【】｜　～●○✓×÷°±→←↑↓" + "0123456789★☆空中琴"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", default=os.path.join(ROOT, "..", "fire-plan", "app", "src", "main", "res", "font", "noto_sans_sc.ttf"))
    args = ap.parse_args()
    chars = set(EXTRA)
    for f in SOURCES:
        with open(f, encoding="utf-8") as fh:
            for ch in fh.read():
                if ord(ch) > 0x2E80 or ch in EXTRA:
                    chars.add(ch)
    chars = sorted(c for c in chars if not c.isspace())
    out = os.path.join(ROOT, "app", "src", "main", "res", "font", "noto_sans_sc.ttf")
    font = TTFont(args.source)
    opts = subset.Options()
    opts.layout_features = ["*"]
    opts.name_IDs = ["*"]
    opts.notdef_outline = True
    opts.recalc_bounds = True
    opts.drop_tables += ["DSIG"]
    sub = subset.Subsetter(opts)
    sub.populate(text="".join(chars))
    sub.subset(font)
    font.save(out)
    with open(os.path.join(ROOT, "third_party", "noto-sans-sc", "SUBSET.txt"), "w", encoding="utf-8") as fh:
        fh.write("Noto Sans SC (variable) subset to %d characters for Chime Bells:\n%s\n" % (len(chars), "".join(chars)))
    print("subset: %d chars -> %s (%d bytes)" % (len(chars), out, os.path.getsize(out)))


if __name__ == "__main__":
    main()
