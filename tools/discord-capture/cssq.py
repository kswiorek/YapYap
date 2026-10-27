#!/usr/bin/env python3
"""Query the dumped Discord stylesheet bundle for rules matching a class name.

The dump (tools/discord-capture/.cache/discord-css.txt) has one rule per line,
prefixed with the media/at-rule context it was nested in:

    @media (min-width: 900px) | .foo_abc:hover{...}

Usage:
    python3 cssq.py button_a22cb0          # every rule mentioning the class
    python3 cssq.py --grep 'text-md\\/normal'
    python3 cssq.py --selectors-only channel__972a0
"""
import re
import sys
from pathlib import Path

CACHE = Path(__file__).resolve().parent / ".cache/discord-css.txt"


def load():
    return CACHE.read_text(errors="replace").splitlines()


DECL_SPLIT = re.compile(r";(?![^(]*\))")


def pretty(rule: str, indent: str = "  ") -> str:
    m = re.match(r"^(.*?)\{(.*)\}$", rule, re.S)
    if not m:
        return rule
    sel, body = m.group(1).strip(), m.group(2).strip()
    decls = [d.strip() for d in DECL_SPLIT.split(body) if d.strip()]
    lines = [f"{indent}{sel} {{"]
    lines += [f"{indent}  {d};" for d in decls]
    lines.append(f"{indent}}}")
    return "\n".join(lines)


def main(argv):
    if not argv:
        print(__doc__)
        return 1
    selectors_only = "--selectors-only" in argv
    argv = [a for a in argv if a != "--selectors-only"]
    grep = "--grep" in argv
    argv = [a for a in argv if a != "--grep"]
    needle = argv[0]

    lines = load()
    hits = [ln for ln in lines if needle in ln]
    if not hits:
        print(f"no rules match {needle!r}")
        return 2

    print(f"# {len(hits)} rule(s) matching {needle!r}\n")
    for ln in hits:
        ctx, _, rule = ln.rpartition(" | ")
        if not rule:
            ctx, rule = "", ln
        if selectors_only:
            print(re.sub(r"\{.*\}$", "", rule).strip())
            continue
        if ctx:
            print(f"/* {ctx} */")
        print(pretty(rule))
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
