# YapYap GUI — design spec pack

This folder is the handoff for building the YapYap desktop/mobile GUI to *feel* like Discord:
same information architecture, same interaction patterns, same visual language — adapted to a
Compose Multiplatform app and to YapYap's early-alpha feature set.

It is **not** a pixel-copy kit. The goal is:

> A user who knows Discord should need zero explanation to use YapYap, and the app should look
> like it belongs next to Discord on the same desktop — while remaining genuinely responsive from
> a 360 dp phone to a 2560 px monitor.

## Reading order

If you are the one building this, go in this order:

1. **`01-visual-language.md`** — copy `reference/YapTheme.kt` into the project and stop using
   colour literals forever.
2. **`10-windows.md`** — this is the answer to "what windows exist and what's on them".
3. **`02-layout-and-adaptive.md`** — how those windows reflow from phone to desktop.
4. **`04-screens.md`** — the full-page screens, state by state.
5. **`03-ux-patterns.md`** — what happens when you interact with them.
6. **`09-component-states.md`** — the exact hover/active/focus values.
7. **`05-settings.md`** — the settings catalogue.
8. **`06-yapyap-mapping.md`** + **`08-feature-readiness.md`** — what the backend can back today,
   and how to gate the rest.

## What's in here

| File                                                     | Contents                                                                                             |
|----------------------------------------------------------|------------------------------------------------------------------------------------------------------|
| [`01-visual-language.md`](01-visual-language.md)         | Colour, type, spacing, radius, elevation, icon sizes, motion. The "general feel".                    |
| [`02-layout-and-adaptive.md`](02-layout-and-adaptive.md) | Shell regions, window size classes, collapse behaviour, touch vs pointer adaptations.                |
| [`03-ux-patterns.md`](03-ux-patterns.md)                 | Chat, room list, overlays, menus, toasts, empty/loading/error states, keyboard.                      |
| [`04-screens.md`](04-screens.md)                         | Screen-by-screen spec with wireframes, states and data sources.                                      |
| [`05-settings.md`](05-settings.md)                       | Discord's settings catalogue mapped to YapYap, and which settings are real vs cosmetic.              |
| [`06-yapyap-mapping.md`](06-yapyap-mapping.md)           | Discord concept → YapYap runtime API. What must be dropped.                                          |
| [`08-feature-readiness.md`](08-feature-readiness.md)     | **Ready / stub / out** for every Discord feature, the capability model, and the stub-page inventory. |
| [`09-component-states.md`](09-component-states.md)       | Hover / active / focus / disabled values, motion timings, scrollbar spec.                            |
| [`10-windows.md`](10-windows.md)                         | Every window and overlay: type, size, scrim, contents, wireframe. §17 lists the stub pages.          |
| [`reference/YapTheme.kt`](reference/YapTheme.kt)         | Generated, copy-pasteable Compose theme (colours, type scale, dimensions).                           |
| [`data/`](data)                                          | Raw capture output: resolved tokens, element specs, icon paths, CSS excerpts.                        |
| [`assets/screens/`](assets/screens)                      | Reference screenshots from the live web app.                                                         |

## Where the numbers come from

Captured on 2026-10-27 from the live Discord web client (dark theme, `font-size-16`,
`density-default`, `visual-refresh` build) at a 1703 × 1090 CSS-px viewport, DPR 1.65.
The harness used is in [`tools/discord-capture`](../../tools/discord-capture/README.md) and can be
re-run against a future Discord build.

Anything in these docs that came from a measurement is marked with the value and, where it
matters, the token name. Anything that is judgement is labelled **Recommended**.

## How to read the sizes

All reference dimensions are CSS px at 100 % zoom, which map 1:1 to `dp` at Compose's default
density. **Do not hard-code them as fixed widths.** Treat them as:

- *proportions* on desktop (the rail is ~5 %, the list ~20 %, chat takes the rest),
- *minimums and maximums* for resizable panels,
- *reference values* for a 1280 dp window — smaller windows switch layout classes instead of
  shrinking the columns (see `02-layout-and-adaptive.md`).

## Legal / provenance

- Discord's UI, wordmarks, icons and `gg sans`/`Whitney` fonts are Discord's IP. **Do not ship
  their assets, icon SVGs, or brand marks.** Reuse measurements, layout and colour *relationships*
  — that's what this pack documents. Draw your own icon set (or use an OFL set such as Lucide)
  and use a freely licensed UI font (Inter is metrically close to `gg sans`; see
  `01-visual-language.md` §5).
- The raw captures under `data/` are reference material for the implementer, not build inputs.
  Nothing in `composeApp` should read from this folder.
- Scraping was read-only and session-scoped: no Discord assets are redistributed beyond
  screenshots of a personal test account.

## Fidelity contract

The app is **early alpha**: many Discord features (avatars, attachments, reactions, GIFs, search,
voice) are not backed by the protocol yet but are planned. They are not omitted — they ship as **stubs**: the final
chrome, placeholder content, and a `Soon` chip (`03-ux-patterns.md` §8, `08-feature-readiness.md`). Only features the
product will never have are
left out entirely.

**Must match** (the user notices immediately if it's wrong):

- three-column desktop shell with the same *pane order and proportions*,
- the colour ladder (darkest rail → chat → raised surfaces) and the blurple accent,
- 14/16 px UI text, 32 px rows, 8 px radii everywhere,
- hover/selected/unread states on room list items,
- message grouping, 40 px avatar column, the floating hover toolbar,
- settings as a full-window modal with a left nav and section rows,
- composer behaviour (Shift+Enter, auto-grow, placeholder per room type),
- modal/popout/menu geometry (`10-windows.md` — these are measured, not guessed).

**May differ** (nobody will mind):

- exact font (Inter vs gg sans), exact antialiasing,
- icon art,
- animation timing (keep it in the 100–200 ms band),
- exact paddings inside a row (±2 px),
- anything behind a `Soon` chip — the shape is final, the content is not.
