# 01 — Visual language

The point of this file is that a developer can build a screen that "looks like Discord" without
opening Discord. Everything here is measured from the live client unless labelled **Recommended**.

The generated Compose version of this file lives at [`reference/YapTheme.kt`](reference/YapTheme.kt).

---

## 1. Surfaces — the colour ladder

Discord's whole look comes from a flat, low-contrast ladder of near-neutral greys with a slight
blue cast (hue ≈ 230–240, saturation ≈ 4–7 %). There are **six** steps and they are used
consistently: the further "back" a region is, the darker it is.

| Role       | Token                          | Hex       | Used for                                  |
|------------|--------------------------------|-----------|-------------------------------------------|
| App / rail | `--background-base-lowest`     | `#2c2d32` | guild rail, DM sidebar, window background |
| Chat       | `--background-base-lower`      | `#323339` | message list, channel header, modal body  |
| Raised     | `--background-base-low`        | `#36373e` | user panel, cards, secondary blocks       |
| Surface    | `--background-surface-high`    | `#393a41` | composer, primary modal surface           |
| Floating   | `--background-surface-higher`  | `#3c3d45` | popouts, menus, dropdowns                 |
| Highest    | `--background-surface-highest` | `#3f4048` | tooltips, hovered menu rows               |

Two extras:

- `--background-accent` `#41424a` — separators, pressed states, scrollbar tracks.
- `--background-secondary-alt` `#242429` — code blocks, inset wells.
- Scrim: `#000000` at **72 %** (lightbox 92 %).

> **Rule of thumb:** chat content sits on `#323339`; anything that *floats above* chat is one to
> three steps lighter; anything that *contains* chat (rail, list) is one step darker.

### Light theme

Deferred for v1. Keep every colour behind a `YapColors` value object (see `YapTheme.kt`) so a light
palette is a second instance, not a refactor. To capture Discord's light values, flip
Appearance → Light and re-run the harness (`tools/discord-capture/README.md`).

---

## 2. Text & icons

| Role                 | Token                              | Hex                   | Notes                                       |
|----------------------|------------------------------------|-----------------------|---------------------------------------------|
| Primary text         | `--text-strong` / `--text-default` | `#ffffff` / `#f3f3f4` | usernames, headers, message body            |
| Secondary text       | `--text-subtle`                    | `#c5c6ca`             | descriptions, timestamps                    |
| Muted                | `--text-muted`                     | `#abacb2`             | placeholders in prose, disabled labels      |
| Composer placeholder | `--channel-text-area-placeholder`  | `#6c6d76`             | the only really dim text                    |
| Link                 | `--text-link`                      | `#76aff6`             | links, mentions of channels                 |
| Brand text           | `--text-brand`                     | `#94a8ff`             | "Nitroless" brand links, selected tab label |
| Icons (idle)         | `--interactive-icon-default`       | `#c5c6ca`             | every toolbar glyph                         |
| Icons (hover/active) | `--interactive-icon-hover`         | `#ffffff`             |                                             |
| Icons (muted)        | `--icon-muted`                     | `#abacb2`             |                                             |

Contrast rule: body text is `#f3f3f4` on `#323339` (≈ 11:1). Never use a darker grey than
`#abacb2` for anything a user must read.

---

## 3. Brand, status, feedback

| Role                         | Hex                   | Token                                                               |
|------------------------------|-----------------------|---------------------------------------------------------------------|
| Blurple (primary)            | `#5865f2`             | `--brand-500` / `--control-primary-background-default`              |
| Blurple hover                | `#4452bb`             | `--control-primary-background-hover`                                |
| Blurple pressed              | `#3a48a3`             | `--control-primary-background-active`                               |
| Blurple light (text on dark) | `#8ca0fd`             | `--brand-360`                                                       |
| Online                       | `#3d9e60`             | `--status-online`                                                   |
| Idle                         | `#ffcb6e`             | `--text-status-idle`                                                |
| Do not disturb               | `#dc4247`             | `--text-status-dnd`                                                 |
| Offline / unknown            | `#9d9ea5`             | `--text-status-offline`                                             |
| Positive                     | `#7ecb94`             | `--text-feedback-positive`                                          |
| Warning                      | `#fcb529`             | `--text-feedback-warning`                                           |
| Danger                       | `#d22d39` / `#da3e44` | `--control-critical-primary-background-default` / `--status-danger` |
| Info                         | `#8ebefa`             | `--text-feedback-info`                                              |

Mentions and highlights are **tinted overlays**, not solid fills:

- mention pill: `#5865f2` @ 24 %, text `#cdd7ff`
- mentioned message row: `#f8a300` @ 8 %
- new-message divider: brand line + brand label

---

## 4. Translucent modifiers

Every hover/selected state in Discord is the *same* grey at a different alpha. This is the single
most important trick for matching the feel — a solid `#3a3b42` selected row looks wrong; a 12 %
white overlay looks right on every background.

| Token                     | Value            | Use                                       |
|---------------------------|------------------|-------------------------------------------|
| `--background-mod-muted`  | `#97979f` @ 8 %  | subtle zebra, disabled well               |
| `--background-mod-subtle` | `#97979f` @ 12 % | **idle row background**, secondary button |
| `--background-mod-normal` | `#97979f` @ 16 % | **hover**                                 |
| `--background-mod-strong` | `#97979f` @ 20 % | **pressed / selected**                    |
| `--border-normal`         | `#97979f` @ 20 % | card borders, dividers on dark            |
| `--border-strong`         | `#97979f` @ 44 % | focus-adjacent strong borders             |

---

## 5. Typography

`gg sans` is proprietary. Use **Inter** (OFL) — same humanist grotesque proportions, similar
x-height. Fallback stack: `Inter, "Noto Sans", "Helvetica Neue", Helvetica, Arial, sans-serif`.
Set `letter-spacing: 0` everywhere; Discord does.

The scale is derived from the client's own class tokens (`heading-*`, `body-*`):

| Token            | Size | Line height | Weight | Use                                               |
|------------------|------|-------------|--------|---------------------------------------------------|
| `heading/xxl`    | 32   | 40          | 600    | "Add Friend", group chat welcome title            |
| `heading/xl`     | 24   | 32          | 600    | modal page titles                                 |
| `heading/lg`     | 20   | 24          | 600    | section titles inside settings                    |
| `heading/md`     | 18   | 22          | 600    | card titles                                       |
| `heading/sm`     | 16   | 20          | 600    | "It's quiet for now…" empty state                 |
| `heading/xs`     | 14   | 18          | 500    | settings nav group labels ("Billing")             |
| `body/lg`        | 20   | 24          | 400    | large prose                                       |
| `body/md`        | 16   | 20          | 400    | **message body**, chat header title               |
| `body/md/medium` | 16   | 20          | 500    | button labels, selected tab                       |
| `body/sm`        | 14   | 18          | 400    | descriptions, timestamps, sidebar section headers |
| `body/sm/medium` | 14   | 18          | 500    | room list names                                   |
| `body/xs`        | 12   | 16          | 400    | timestamps on hover, badges, "3 Members"          |

Notes:

- Message body is **16 px** (not 14). This is the biggest single "does it feel like Discord" tell.
- Timestamps inside a message header are 12 px muted; the date divider label is 12 px semibold.
- Room-list names are 14 px medium; the last-message preview is 14 px regular muted.
- Nothing is bold-black; max weight in the chrome is 600.

---

## 6. Spacing

4-based, with a 12/16/20/24 rhythm for gaps and padding:

| Token  | px |
|--------|----|
| `xxs`  | 4  |
| `xs`   | 8  |
| `sm`   | 12 |
| `md`   | 16 |
| `lg`   | 20 |
| `xl`   | 24 |
| `xxl`  | 32 |
| `huge` | 40 |

Typical values:

- horizontal page gutter in chat: **16** (and 8 between the scroller and composer)
- gap between rail and list: **0** (they share a background), list has 8 px inner gutter
- settings content column: **max 740 px**, centred, 24 px vertical padding per section
- modal padding: 24 horizontal, 16 vertical
- room-list row: 8 px horizontal padding, 8 px radius, 32–40 px tall

---

## 7. Radii

| Token | px    | Use                                                        |
|-------|-------|------------------------------------------------------------|
| `xs`  | 4     | small badges, checkbox                                     |
| `sm`  | **8** | **buttons, inputs, room rows, composer, avatars of cards** |
| `md`  | 12    | modals, large cards, sidebar container                     |
| `lg`  | 16    | promo cards, images                                        |
| `xl`  | 24    | full-bleed banners                                         |
| `xxl` | 32    | oversized sheets                                           |
| round | 50 %  | avatars, status dots, pills                                |

If you remember one number: **8 px** for interactive rectangles, **12 px** for containers.

---

## 8. Elevation

Shadows are subtle and mostly black at low alpha. Most "elevation" in Discord is actually achieved
by the surface ladder, not by shadow.

| Token                | Value                            |
|----------------------|----------------------------------|
| `--shadow-low`       | `0 1px 4px 0 rgb(0 0 0 / .14)`   |
| `--shadow-medium`    | `0 4px 8px 0 rgb(0 0 0 / .16)`   |
| `--shadow-high`      | `0 12px 24px 0 rgb(0 0 0 / .24)` |
| `--elevation-stroke` | `0 0 0 1px rgb(0 0 0 / .16)`     |

Use `shadow-high` for menus/tooltips/popouts, `shadow-medium` for modals, and `elevation-stroke`
as a hairline on floating surfaces (it reads as a border without a border colour).

---

## 9. Icons

Discord uses a **filled, 24 px grid, ~2 px optical stroke** icon set, drawn at 16/18/20/24 px.
Sizes:

| Token      | px |
|------------|----|
| `icon-xxs` | 12 |
| `icon-xs`  | 16 |
| `icon-sm`  | 18 |
| `icon-md`  | 20 |
| `icon-lg`  | 24 |
| `icon-xl`  | 32 |

**Recommended:** use [Lucide](https://lucide.dev) (ISC) as the base set at 2 px stroke, 20 px
default, `#c5c6ca` tint, 150 ms colour transition to `#ffffff` on hover. Filled variants are
better for the guild-rail glyphs; stick to outline for toolbars so the set stays consistent.

Extracted reference SVG paths (minus the Discord-specific marks) are in
[`data/icons.json`](data/icons.json) — use them to check proportions, not to ship.

---

## 10. Motion

Discord keeps motion fast and almost boring. **Recommended** values:

| Interaction                     | Duration          | Easing                     |
|---------------------------------|-------------------|----------------------------|
| Colour / background on hover    | 100–150 ms        | `ease-out`                 |
| Press feedback                  | 50–100 ms         | `ease-in`                  |
| Modal in/out                    | 150–200 ms        | `ease-out` + 4 px slide    |
| Popout/menu                     | 120–150 ms        | `ease-out`, scale 0.98 → 1 |
| Panel collapse (mobile drawers) | 200–250 ms        | `ease-in-out`              |
| Toast in                        | 200 ms, stays 4 s | `ease-out`                 |

The client's own CSS uses `50ms ease-in` on the base and `150ms ease-out` on `:hover`. That
asymmetry (fast out, slower in) is what makes it feel responsive — copy it.

Respect "reduce motion": drop transforms, keep opacity fades.

---

## 11. Do / don't

**Do**

- Keep the chrome quiet: greys, one accent, no gradients except banners/avatars.
- Use the same 8 px radius and 12 % white hover everywhere.
- Let the surface ladder do the layering.
- Use 16 px for message text and 14 px for chrome labels.

**Don't**

- Don't add drop shadows to list rows or cards on the chat surface.
- Don't use pure black (`#000`) as a background — it's `#2c2d32`.
- Don't put a border on every card; use `mod-subtle` fills and `elevation-stroke`.
- Don't use Discord's logo, wordmark, blurple *logo shape*, or `gg sans`.
