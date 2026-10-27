#!/usr/bin/env python3
"""Resolve Discord's raw CSS custom-property dump into flat sRGB values.

Input : docs/gui/data/discord-tokens.json   (produced by collector.js -> __YAP.tokens())
Output: docs/gui/data/discord-tokens-resolved.json
        { name: { "hex": "#rrggbb", "alpha": 0..1, "css": "<original>" } }

Discord's values are almost always `color-mix(in oklab, X 100%, Y 0%)`, which is
just X, or a plain `hsl(H calc(1*S%) L% / A)`. The few genuine partial mixes are
flagged as unresolved instead of being silently mis-converted.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RAW = ROOT / "docs/gui/data/discord-tokens.json"
OUT = ROOT / "docs/gui/data/discord-tokens-resolved.json"

HSL_RE = re.compile(
    r"hsl\(\s*(-?[\d.]+)\s+calc\(\s*1\s*\*\s*([\d.]+)%\s*\)\s+([\d.]+)%\s*(?:/\s*([\d.]+)\s*)?\)"
)


def hsl_to_rgb(h, s, l):
    h = (h % 360) / 360.0
    s /= 100.0
    l /= 100.0
    if s == 0:
        v = round(l * 255)
        return v, v, v

    def hue(p, q, t):
        if t < 0:
            t += 1
        if t > 1:
            t -= 1
        if t < 1 / 6:
            return p + (q - p) * 6 * t
        if t < 1 / 2:
            return q
        if t < 2 / 3:
            return p + (q - p) * (2 / 3 - t) * 6
        return p

    q = l * (1 + s) if l < 0.5 else l + s - l * s
    p = 2 * l - q
    return tuple(round(hue(p, q, t) * 255) for t in (h + 1 / 3, h, h - 1 / 3))


def parse_hsl(text):
    m = HSL_RE.search(text)
    if not m:
        return None
    h, s, l = float(m.group(1)), float(m.group(2)), float(m.group(3))
    a = float(m.group(4)) if m.group(4) else 1.0
    r, g, b = hsl_to_rgb(h, s, l)
    return r, g, b, a


def strip_mix(value):
    """Return (inner_css, resolved) for color-mix(in oklab, A p%, B q%)."""
    m = re.match(r"color-mix\(in oklab,\s*(.*)\)$", value.strip(), re.S)
    if not m:
        return None
    body = m.group(1)
    # split on the top-level comma that separates the two colours
    depth = 0
    for i, ch in enumerate(body):
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif ch == "," and depth == 0:
            first, second = body[:i].strip(), body[i + 1:].strip()
            break
    else:
        return None

    def pct(part):
        mm = re.search(r"\s([\d.]+)%$", part)
        return float(mm.group(1)) if mm else None

    p1, p2 = pct(first), pct(second)
    if p1 == 100:
        return first[: re.search(r"\s[\d.]+%$", first).start()].strip()
    if p2 == 100:
        return second[: re.search(r"\s[\d.]+%$", second).start()].strip()
    return None


def resolve(value):
    v = value.strip()
    # pure alpha-only whites/blacks appear as hsl(0 calc(1*0%) 100%/1)
    direct = parse_hsl(v)
    if direct:
        return direct
    inner = strip_mix(v)
    if inner:
        direct = parse_hsl(inner)
        if direct:
            return direct
        # nested #hex or rgba fallback
        hm = re.search(r"#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})\b", inner)
        if hm:
            h = hm.group(1)
            if len(h) == 3:
                h = "".join(c * 2 for c in h)
            return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 1.0
    return None


def main():
    raw = json.loads(RAW.read_text())
    resolved, unresolved = {}, []
    for name, value in raw["vars"].items():
        parsed = resolve(value)
        if parsed is None:
            unresolved.append(name)
            continue
        r, g, b, a = parsed
        resolved[name] = {
            "hex": "#%02x%02x%02x" % (r, g, b),
            "alpha": round(a, 4),
            "css": value,
        }
    OUT.write_text(json.dumps(resolved, indent=1, sort_keys=True))
    print(f"resolved {len(resolved)} / {len(raw['vars'])}")
    print(f"unresolved {len(unresolved)}")
    for n in unresolved[:20]:
        print("  !", n, "=", raw["vars"][n][:90])


if __name__ == "__main__":
    sys.exit(main())
