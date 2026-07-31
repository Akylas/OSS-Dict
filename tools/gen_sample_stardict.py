#!/usr/bin/env python3
"""Generate sample uncompressed StarDict dictionaries for OSS-Dict manual testing.

Targets issue #32 (horizontal scroll / pinch zoom in the article view):
entries deliberately contain content wider than the viewport, plus a narrow
control article. Two dictionaries share the same keys so a lookup opens
several pager tabs — needed to test swipe-to-change-article.

Output: <outdir>/scroll-test-{a,b}.{ifo,idx,dict} plus scroll-test-{a,b}.zip

Import the .zip files in the app: loading a loose .ifo from a SAF folder is
broken app-side (findCompanionFile uses DocumentFile.fromSingleUri, whose
getParentFile() is always null, so the .idx is never found).
"""

import os
import struct
import sys
import zipfile

ELEMENTS = [
    ("H", "He"), ("Li", "Be"), ("Na", "Mg"), ("K", "Ca"), ("Rb", "Sr"),
    ("Cs", "Ba"), ("Fr", "Ra"),
]
GROUPS = 18

CSS = """
<style>
 body { font-family: sans-serif; margin: 8px; }
 table.pt { border-collapse: collapse; }
 table.pt td { border: 1px solid #888; width: 64px; min-width: 64px;
               height: 48px; text-align: center; font-size: 14px; }
 pre.wide { background: #f0f0f0; padding: 6px; }
 .strip { width: 2400px; height: 40px;
          background: linear-gradient(90deg, #f00, #0f0, #00f); }
</style>
"""


def periodic_table(variant):
    rows = []
    for period, (left, right) in enumerate(ELEMENTS, start=1):
        cells = []
        for group in range(1, GROUPS + 1):
            if group == 1:
                cells.append(f"<td><b>{left}</b><br>{period}</td>")
            elif group == GROUPS:
                cells.append(f"<td><b>{right}</b><br>{period}</td>")
            else:
                cells.append(f"<td>{period}.{group}</td>")
        rows.append("<tr>" + "".join(cells) + "</tr>")
    header = "<tr>" + "".join(f"<td><b>G{g}</b></td>" for g in range(1, GROUPS + 1)) + "</tr>"
    return (
        f"{CSS}<h1>Periodic table ({variant})</h1>"
        "<p>Table is ~18 x 64px wide, so it overflows any phone viewport. "
        "Drag horizontally: the article must scroll, not switch to the next tab. "
        "At the left/right edge a further swipe changes article.</p>"
        f"<table class='pt'>{header}{''.join(rows)}</table>"
        "<p>Scroll down, then pinch to zoom in and out: the zoom level must hold "
        "when the fingers are lifted.</p>"
        + "".join(f"<p>Filler line {i} to make the page tall enough to scroll "
                  "vertically as well.</p>" for i in range(1, 41))
    )


def wide_code(variant):
    long_line = " ".join(f"token_{i}" for i in range(1, 121))
    return (
        f"{CSS}<h1>Wide code ({variant})</h1>"
        "<p>A single unwrapped line inside &lt;pre&gt;.</p>"
        f"<pre class='wide'>{long_line}</pre>"
        "<p>Below is a fixed 2400px wide block:</p>"
        "<div class='strip'></div>"
    )


def narrow(variant):
    return (
        f"{CSS}<h1>Narrow control ({variant})</h1>"
        "<p>Nothing here is wider than the viewport, so a horizontal swipe must "
        "still change article — this is the control case for issue #32.</p>"
        + "".join(f"<p>Paragraph {i}.</p>" for i in range(1, 31))
    )


def zoom(variant):
    sizes = "".join(
        f"<p style='font-size:{size}px'>Pinch zoom sample at {size}px.</p>"
        for size in (10, 14, 20, 28, 40)
    )
    return (
        f"{CSS}<h1>Zoom ({variant})</h1>"
        "<p>Pinch in and out repeatedly, lifting one finger before the other. "
        "The page must keep the zoom level instead of snapping to minimum.</p>"
        + sizes
    )


ARTICLES = [
    ("periodic table", periodic_table),
    ("wide code", wide_code),
    ("narrow control", narrow),
    ("zoom", zoom),
]


def write_dictionary(outdir, base, bookname, variant):
    entries = []
    dict_blob = bytearray()
    for key, builder in ARTICLES:
        body = builder(variant).encode("utf-8")
        entries.append((key, len(dict_blob), len(body)))
        dict_blob += body

    # .idx: null-terminated UTF-8 key + 4-byte BE offset + 4-byte BE size,
    # sorted by raw key bytes as the StarDict spec requires.
    idx = bytearray()
    for key, offset, size in sorted(entries, key=lambda item: item[0].encode("utf-8")):
        idx += key.encode("utf-8") + b"\x00" + struct.pack(">II", offset, size)

    ifo = (
        "StarDict's dict ifo file\n"
        "version=2.4.2\n"
        f"bookname={bookname}\n"
        f"wordcount={len(entries)}\n"
        f"idxfilesize={len(idx)}\n"
        "sametypesequence=h\n"
        "description=Sample dictionary for OSS-Dict issue #32 (horizontal scroll and zoom)\n"
    )

    os.makedirs(outdir, exist_ok=True)
    ifo_path = os.path.join(outdir, base + ".ifo")
    idx_path = os.path.join(outdir, base + ".idx")
    dict_path = os.path.join(outdir, base + ".dict")
    with open(ifo_path, "w", encoding="utf-8") as handle:
        handle.write(ifo)
    with open(idx_path, "wb") as handle:
        handle.write(idx)
    with open(dict_path, "wb") as handle:
        handle.write(dict_blob)

    with zipfile.ZipFile(os.path.join(outdir, base + ".zip"), "w", zipfile.ZIP_DEFLATED) as archive:
        for path in (ifo_path, idx_path, dict_path):
            archive.write(path, os.path.basename(path))

    return len(entries), len(idx), len(dict_blob)


def main():
    outdir = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
    for base, bookname, variant in (
        ("scroll-test-a", "Scroll Test A", "A"),
        ("scroll-test-b", "Scroll Test B", "B"),
    ):
        words, idx_size, dict_size = write_dictionary(outdir, base, bookname, variant)
        print(f"{base}: {words} entries, idx {idx_size} B, dict {dict_size} B -> {outdir}")


if __name__ == "__main__":
    main()
