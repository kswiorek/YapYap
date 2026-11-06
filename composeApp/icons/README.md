# Brand sources

Single source of truth: **`yap_icon.svg`** (white yap on Yap pink `#FF4A74`).

Regenerate everything after tweaking it — just run (from anywhere):

    python3 composeApp/icons/generate_icons.py

The script parses the SVG and rewrites all outputs in place:

- `../src/commonMain/composeResources/drawable/yap_mark.xml` — full-detail
  glyph (white + pink knockouts, **no disc**; the disc comes from the
  composable background, so one asset serves the pink logo and the
  themed-grey mono mark).
- `../src/commonMain/composeResources/drawable/yap_mark_mono.xml` —
  white-only silhouette for the quiet mono mark (`YapMark` tints it with the
  theme; the dark side is theme grey, never pure black).
- Android adaptive icon (`../src/androidMain/res/drawable*/ic_launcher_*`)
  plus legacy `mipmap-*/ic_launcher*.png`, iOS `AppIcon` PNG, desktop
  `icon-*.{png,ico,icns}` here, and `../src/jvmMain/resources/app-icon.png`
  for the dev-window icon.

Requirements: `inkscape` (SVG raster), ImageMagick `magick`/`convert` (ICO),
Pillow (`pip install pillow`, ICNS). The script aborts with a clear error
when a tool is missing instead of writing half the assets.

Also kept here, not used by the script:

- `yap.svg` — line-art construction drawing (strokes only).
- `yap_icon_bw.svg` / `yap_icon_bw_invert.svg` — monochrome variants,
  superseded by the theme-aware `YapMark`.

The pink ramp itself lives in exactly one place: `YapAccent` in
`../src/commonMain/kotlin/org/yapyap/gui/theme/YapTheme.kt`.
