# 09 — Interaction states, motion, scrollbars

Extracted from the live client's authored CSS (`:hover`, `:active`, `:focus-visible`, `:disabled`,
`@media (hover: hover)`) rather than by hovering — the app ignores synthetic mouse events.

All values are the variables defined in [`01-visual-language.md`](01-visual-language.md).

---

## 1. The one rule that makes it feel like Discord

Almost every interactive surface uses **the same grey at a different alpha**:

| State              | Token                               | Value            |
|--------------------|-------------------------------------|------------------|
| idle (row)         | transparent                         | —                |
| hover              | `--interactive-background-hover`    | `#97979f` @ 12 % |
| selected / pressed | `--interactive-background-selected` | `#97979f` @ 20 % |
| subtle container   | `--background-mod-muted`            | `#97979f` @ 8 %  |
| border             | `--border-normal`                   | `#97979f` @ 20 % |

If you get this one ladder right, 80 % of the "feel" follows, on any surface.

---

## 2. Component state matrix

### Buttons (`button_a22cb0`)

Base: `border: 1px solid transparent`, `border-radius: 8px`, `display: flex`, `font-weight: 400`,
`transition: background-color 50ms ease-in, color 50ms ease-in, border-color 50ms ease-in,
opacity 50ms ease-in`.
On `:hover` the transition flips to `150ms ease-out` — *fast out, slower in*. Copy that trick.

| Variant           | Idle             | Hover     | Active    | Text               |
|-------------------|------------------|-----------|-----------|--------------------|
| Primary           | `#5865f2`        | `#4452bb` | `#3a48a3` | `#fff`             |
| Secondary         | `#97979f` @ 12 % | @ 16 %    | @ 20 %    | `#fff`             |
| Danger            | `#d22d39`        | `#a9232e` | darker    | `#fff`             |
| Ghost / icon-only | transparent      | @ 16 %    | @ 20 %    | `#c5c6ca` → `#fff` |
| Link              | transparent      | underline | underline | `#76aff6`          |

Disabled: `opacity: 0.5; pointer-events: none`. That's the whole spec — no separate grey palette.

Sizes (measured tokens): `sm` 32, `md` 38, `lg` 44, `xl` 50 tall; horizontal padding 16 (sm 12).
Every button has a `1px` border in `rgba(255,255,255,0.08)` regardless of variant — it reads as a
subtle top edge on the blurple.

### Room list rows

| State    | Background                   | Text                                       |
|----------|------------------------------|--------------------------------------------|
| Idle     | transparent                  | `#c5c6ca` name (medium), `#abacb2` preview |
| Hover    | `#97979f` @ 12 %             | unchanged                                  |
| Selected | `#97979f` @ 20 %             | `#fff`                                     |
| Unread   | transparent + 8 px white dot | `#fff` 600                                 |
| Muted    | transparent                  | `#abacb2`, no dot                          |

Corner radius 8, horizontal margin 8, vertical padding 1–2 px (40 px row for DMs, 28 px for
single-line channels).

Hover-only affordances (close button, call duration) are gated behind
`@media (hover: hover)` — **do this too**, or phones will show phantom buttons.

### Message rows

Authored rule:

```css
.theme-dark .message__5126c.selected__5126c,
.theme-dark.mouse-mode.full-motion .message__5126c:hover {
  background: var(--message-background-hover);   /* #97979f @ 12 % */
}
.message__5126c:hover .buttons__5126c { opacity: 1; }
```

- Hover background spans the full row width, not the content column.
- The action toolbar fades in (`opacity 0 → 1`, 150 ms) and is only interactive on hover.
- Mentioned rows use `#f8a300` @ 8 %; on hover @ 4 % (they *lighten*, not darken).
- Selected (keyboard focus / jump target) is the same as hover.
- `mouse-mode` gating means touch devices never get the hover background — use
  `pointerMoveFilter`/`hoverable` equivalents only on pointer inputs.

### Settings nav items

```css
.item_caf372.active_caf372,
.item_caf372:hover {
  background-color: var(--background-mod-muted);  /* #97979f @ 8 % */
  color: var(--text-strong);
}
.item_caf372.destructive_caf372:hover {
  background-color: var(--background-feedback-critical);  /* red @ 8 % */
}
```

Note it's **8 %**, not 12 % — settings rows are quieter than list rows. Destructive hover is a red *tint*, never a red
fill.

### Inputs / composer

- Idle: `background: #000 @ 8 %` on dark, `border: 1px solid #97979f @ 20 %`, radius 8.
- Focus: border `#5865f2`, no glow, no shadow.
- Error: `box-shadow: 0 0 0 2px #ffa09b` **only when not hovered/focused** (so the ring doesn't
  fight the focus border).
- Placeholder `#6c6d76`; text `#f3f3f4`.

### Switches / checkboxes / radios

Track/box idle `#000 @ 12 %`, border `#97979f @ 20 %`; selected `#5865f2`; hover selected `#4452bb`;
thumb `#fff`; disabled `opacity .5`. Sizes: switch 40 × 24 (thumb 16), checkbox/radio 20 × 20,
radius 4 (checkbox) / round (radio).

### Avatars

`opacity: 0.8` on hover for the user panel avatar. Status dot sits bottom-right, 10–14 px,
2–3 px ring in the surface colour behind it. No shadow.

---

## 3. Focus

Discord mostly **doesn't** draw a visible focus ring — it changes colour, and relies on
`:focus-visible`. That's an accessibility weak spot; don't copy it verbatim.

**Recommended:** keep the colour change *and* add

```
2 dp ring, colour #6aa8f4, offset 2 dp, only for keyboard focus (:focus-visible)
```

so mouse users see Discord's look and keyboard users can actually navigate.

---

## 4. Motion

Distribution of every transition duration in the client's CSS:

| Duration   | Count | Typical use                       |
|------------|-------|-----------------------------------|
| 200 ms     | 201   | the default for almost everything |
| 100 ms     | 86    | opacity fades                     |
| 150 ms     | 85    | hover colours, toolbar fade       |
| 300 ms     | 40    | panels, drawers                   |
| 50 ms      | 29    | button press / background         |
| 250 ms     | 25    | popouts                           |
| 400–500 ms | 46    | banners, illustrations (ignore)   |

Practical guidance:

- **Hover/press:** 100–150 ms, `ease-out` on the way in, 50 ms `ease-in` on the way out.
- **Panels/drawers:** 200–250 ms, `ease-in-out`.
- **Modals:** 150–200 ms fade + 4 dp rise.
- **Tooltips:** no animation, just appear.
- Gate all transforms behind "reduce motion".

---

## 5. Scrollbars

```css
/* thin (sidebars, lists) */
thumb: background --scrollbar-thin-thumb (#767780), border 2px transparent,
       border-radius 4px, min-height 40px
track: transparent
/* auto (message list) */
thumb: --scrollbar-auto-thumb (#7d7e87), border 4px transparent, border-radius 8px,
       min-height 40px
track: transparent
```

- `fade` variant: thumb/track `visibility: hidden` until the scroller is hovered or scrolling.
- Do **not** reserve gutter space for the scrollbar in Compose; overlay it like Discord does, so
  content doesn't shift when it appears.

Compose equivalent: hide the platform scrollbar and draw a 8 dp rounded rect positioned from
`LazyListState`, fading in on scroll and while hovered.

---

## 6. Things that look like states but aren't

- **Selected row has no accent bar.** Discord does not put a coloured stripe on the selected room;
  only the text goes white and the fill goes to 20 %.
- **Unread is not a background.** It's a white dot + bolder name.
- **Mention is a pill, not a row colour** (row colour is only for the *message* mention).
- **Disabled is opacity, not a colour swap.** `0.5` across the whole component.
