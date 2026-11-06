#!/usr/bin/env python3
"""Regenerate every YapYap brand asset from a single source: ``yap_icon.svg``.

Run from anywhere::

    python3 composeApp/icons/generate_icons.py

What it produces (do not hand-edit the outputs):

* ``src/commonMain/composeResources/drawable/yap_mark.xml``
  full-detail glyph (white + pink knockouts, **no disc** — the disc comes
  from the composable background, so one asset serves the pink logo and the
  themed-grey mono mark).
* ``src/commonMain/composeResources/drawable/yap_mark_mono.xml``
  white-only silhouette for the quiet mono mark (tinted by the theme; the
  dark side is theme grey, never pure black).
* ``src/androidMain/res/drawable-v24/ic_launcher_foreground.xml``
  full icon incl. disc, scaled to the 108 dp adaptive-viewport.
* ``src/androidMain/res/drawable/ic_launcher_background.xml``
  solid Yap pink (adaptive background).
* ``src/androidMain/res/mipmap-*/ic_launcher{,_round}.png`` (48–192 px),
  iOS ``AppIcon`` 1024 px, ``src/jvmMain/resources/app-icon.png`` (256 px,
  dev-window icon), ``icons/icon-linux.png`` (512), ``icons/icon-windows.ico``
  (multi-size), ``icons/icon-macos.icns``.

Requirements: ``inkscape`` (SVG raster), ImageMagick ``magick``/``convert``
(ICO), and Pillow (``pip install pillow``, ICNS). The script fails loudly when
a tool is missing instead of writing half the assets.

After tweaking the icon: edit ``yap_icon.svg``, re-run, rebuild the app
(``./gradlew :composeApp:compileKotlinJvm`` picks up the CMP drawables).
"""

import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent  # repo root
SRC = HERE / "yap_icon.svg"

PINK = "#FF4A74"
SVG_NS = "{http://www.w3.org/2000/svg}"

# ---------------------------------------------------------------- SVG parsing

def parse_icon(src: Path):
    """Return (fills, viewbox): fills is [(fill_hex, path_d)] for every filled
    path (stroke-only hairlines are sub-pixel at icon sizes — dropped)."""
    tree = ET.parse(src)
    root = tree.getroot()
    fills = {}
    for style in root.iter(f"{SVG_NS}style"):
        for m in re.finditer(r"\.(cls-\d+)\{([^}]*)\}", style.text or ""):
            props = dict(p.split(":") for p in m.group(2).split(";") if p)
            fills[m.group(1)] = props.get("fill", "none").strip().upper()
    paths = []
    for p in root.iter():
        if p.tag != f"{SVG_NS}path":
            continue
        fill = fills.get(p.get("class"), "NONE")
        if fill in ("NONE", "") or not p.get("d"):
            continue
        if not re.fullmatch(r"#[0-9A-F]{6}", fill):
            raise ValueError(f"unexpected paint {fill!r} — extend the script first")
        paths.append((fill, p.get("d")))
    vb = [float(v) for v in root.get("viewBox").split()]
    return paths, vb


def fmt(v: float) -> str:
    t = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if t in ("-0", "") else t


def scale_path(d: str, s: float, tx: float = 0.0, ty: float = 0.0) -> str:
    """Affine-transform absolute path data. Only M/C/L/Z occur in our source;
    anything else aborts instead of silently mangling the artwork."""
    toks = re.findall(r"[MLCZmlcz]|-?\d*\.?\d+", d)
    out, cmd, nums = [], None, []

    def flush() -> str:
        coords = [float(n) for n in nums]
        pts = [
            fmt(coords[i] * s + tx) + "," + fmt(coords[i + 1] * s + ty)
            for i in range(0, len(coords), 2)
        ]
        return cmd + ",".join(pts)

    for t in toks:
        if t in "MLCZ":
            if cmd is not None:
                out.append(flush())
                nums = []
            cmd = t
        elif t in "mlcz":
            raise ValueError(f"relative path command {t!r} — extend scale_path first")
        else:
            nums.append(t)
    if cmd is not None:
        out.append(flush())
    return "".join(out)


def vector_xml(items, viewport: int, dp: str) -> str:
    lines = [
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'        android:width="{dp}" android:height="{dp}"',
        f'        android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">',
    ]
    lines += [
        f'    <path android:fillColor="{fill}" android:pathData="{d}"/>'
        for fill, d in items
    ]
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


# ------------------------------------------------------------------ rastering

def need(prog: str, why: str) -> str:
    path = shutil.which(prog)
    if path is None:
        sys.exit(f"error: {prog!r} not found ({why}); install it and re-run")
    return path


def raster(svg: Path, png: Path, size: int) -> None:
    inkscape = need("inkscape", "SVG rasterization")
    subprocess.run(
        [inkscape, str(svg), "--export-type=png",
         f"--export-filename={png}", "-w", str(size), "-h", str(size)],
        check=True, capture_output=True,
    )


def make_ico(png256: Path, ico: Path) -> None:
    magick = shutil.which("magick") or shutil.which("convert")
    if magick is None:
        sys.exit("error: neither 'magick' nor 'convert' found (ICO export)")
    subprocess.run(
        [magick, str(png256), "-define",
         "icon:auto-resize=256,128,64,48,32,16", str(ico)],
        check=True, capture_output=True,
    )


def make_icns(png1024: Path, icns: Path) -> None:
    try:
        from PIL import Image
    except ImportError:
        sys.exit("error: Pillow missing (ICNS export); pip install pillow")
    Image.open(png1024).save(icns)


# ---------------------------------------------------------------------- main

def main() -> None:
    if not SRC.is_file():
        sys.exit(f"error: source not found: {SRC}")
    paths, vb = parse_icon(SRC)
    if not paths:
        sys.exit("error: no filled paths parsed — source format changed?")
    _, _, vb_w, vb_h = vb
    assert vb_w == vb_h, "non-square viewBox — extend the script first"
    written = []

    def write(dest: Path, content: str | bytes) -> None:
        dest.parent.mkdir(parents=True, exist_ok=True)
        mode = "wb" if isinstance(content, bytes) else "w"
        with open(dest, mode) as f:
            f.write(content)
        written.append(dest)

    # 1–2. CMP glyph vectors (viewport 920, no disc).
    k = 920 / vb_w
    write(ROOT / "composeApp/src/commonMain/composeResources/drawable/yap_mark.xml",
          vector_xml([(f, scale_path(d, k)) for f, d in paths], 920, "48dp"))
    write(ROOT / "composeApp/src/commonMain/composeResources/drawable/yap_mark_mono.xml",
          vector_xml([(f, scale_path(d, k)) for f, d in paths if f == "#FFFFFF"], 920, "48dp"))

    # 3–4. Android adaptive icon (viewport 108, full-bleed, disc included).
    s = 108 / vb_w
    r, c = 460.286 * s, 460.286 * s  # disc geometry matches the source ellipse
    # Disc as two half-arcs (robust across renderers; avoids degenerate full arc).
    disc = (f"M{fmt(c - r)},{fmt(c)}"
            f"a{fmt(r)},{fmt(r)} 0 1,0 {fmt(2 * r)},0"
            f"a{fmt(r)},{fmt(r)} 0 1,0 {fmt(-2 * r)},0Z")
    fg = [(PINK, disc)] + [(f, scale_path(d, s)) for f, d in paths]
    write(ROOT / "composeApp/src/androidMain/res/drawable-v24/ic_launcher_foreground.xml",
          vector_xml(fg, 108, "108dp"))
    write(ROOT / "composeApp/src/androidMain/res/drawable/ic_launcher_background.xml",
          '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
          '        android:width="108dp"\n'
          '        android:height="108dp"\n'
          '        android:viewportWidth="108"\n'
          '        android:viewportHeight="108">\n'
          '    <path\n'
          f'            android:fillColor="{PINK}"\n'
          '            android:pathData="M0,0h108v108h-108z"/>\n'
          "</vector>\n")

    # 5–8. Rasters, all from the single SVG.
    with tempfile.TemporaryDirectory(prefix="yapicons") as tmp:
        tmp = Path(tmp)
        p1024 = tmp / "icon-1024.png"
        p256 = tmp / "icon-256.png"
        raster(SRC, p1024, 1024)
        raster(SRC, p256, 256)

        res = ROOT / "composeApp/src/androidMain/res"
        for d, size in [("mipmap-mdpi", 48), ("mipmap-hdpi", 72),
                        ("mipmap-xhdpi", 96), ("mipmap-xxhdpi", 144),
                        ("mipmap-xxxhdpi", 192)]:
            png = tmp / f"icon-{size}.png"
            raster(SRC, png, size)
            data = png.read_bytes()
            for name in ("ic_launcher.png", "ic_launcher_round.png"):
                write(res / d / name, data)  # disc is circular; round == square

        write(ROOT / "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png",
              p1024.read_bytes())
        write(ROOT / "composeApp/src/jvmMain/resources/app-icon.png", p256.read_bytes())
        p512 = tmp / "icon-512.png"
        raster(SRC, p512, 512)
        write(HERE / "icon-linux.png", p512.read_bytes())

        make_ico(p256, HERE / "icon-windows.ico")
        written.append(HERE / "icon-windows.ico")
        make_icns(p1024, HERE / "icon-macos.icns")
        written.append(HERE / "icon-macos.icns")

    print(f"source: {SRC}")
    for dest in written:
        print(f"  wrote {dest.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
