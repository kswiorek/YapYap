# 02 — Layout & adaptive behaviour

YapYap must ship on desktop, Android and iOS from one Compose codebase. Discord's web layout is a
fixed four-pane desktop canvas; its mobile apps are a completely different single-pane shell. The
plan is to build **one shell that collapses**, not two shells.

Reference numbers below are from the desktop capture at 1703 × 1090 CSS px. They are *starting
values*, not fixed widths.

---

## 1. The regions

```
┌──────────────────────────────────────────────────────────────────────────┐
│ 0  title bar / traffic-light drag region            (desktop only, 32)   │
├──────┬───────────────────┬───────────────────────────────────────────────┤
│      │                   │  3  room header                        (49)   │
│  1   │   2  room list    ├───────────────────────────────────┬───────────┤
│ rail │                   │                                   │           │
│ (72) │       (300)       │        4  message scroller        │  5  right │
│      │                   │                                   │   panel   │
│      │                   │                                   │  (300)    │
│      │                   ├───────────────────────────────────┤           │
│      │                   │  6  composer                (65)  │           │
├──────┴───────────────────┴───────────────────────────────────┴───────────┤
│ 7  user panel (56, bottom-left, spans rail+list)                          │
└──────────────────────────────────────────────────────────────────────────┘
```

| # | Region                                                   | Desktop reference                                   | Resizable | Collapsible                                     |
|---|----------------------------------------------------------|-----------------------------------------------------|-----------|-------------------------------------------------|
| 0 | Title bar                                                | 32 high                                             | —         | desktop only                                    |
| 1 | **Rail** — networks/accounts + primary destinations      | 72 wide; 40 px icons, 8 px gaps, 16 px side padding | no        | becomes a drawer below 600                      |
| 2 | **Room list** — search, sections, rooms, user panel      | 300 wide (measured 302 incl. 1 px divider)          | 240–360   | becomes the *only* pane in Compact              |
| 3 | **Room header** — title, topic, actions, search          | 49 high, 8 px padding                               | no        | shrinks actions into an overflow menu below 720 |
| 4 | **Message scroller**                                     | fills                                               | —         | always                                          |
| 5 | **Right panel** — members (group rooms) or profile (1:1) | 264 (members) / 300 (profile)                       | 200–380   | toggleable; overlay below 1200                  |
| 6 | **Composer**                                             | 56 min height, 8 px form padding                    | —         | always                                          |
| 7 | **User panel**                                           | 56 high, avatar 32, name + status                   | no        | bottom nav on Compact                           |

Vertical rhythm of a message (cozy layout, the default):

```
row padding      2px  top/bottom
left gutter     72px  (16 edge + 40 avatar + 16 gap)
right padding   24px
avatar          40px, aligned to the top of the first line
name line       16px medium + 12px timestamp, baseline aligned
body            16px / 20px line-height
row gap between groups  16px
```

---

## 2. Window size classes

> **Provenance note:** Discord's *web* client does not reflow — it is a fixed desktop canvas (it only
> has one narrow-mode hack at `max-width: 485px` for the member list). Their native mobile apps use a
> completely separate shell. So the breakpoints and collapse rules below are **authored
> recommendations** for YapYap, not captured values. The desktop reference numbers are measured.

Use these four classes (they map cleanly to Compose's `WindowSizeClass` and to Discord's own
breakpoint tokens 640 / 1024 / 1280):

| Class        | Width            | Shell                                                                                                                                                            |
|--------------|------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Compact**  | `< 600 dp`       | One pane. Rail + room list live in a **drawer**; chat is a screen. Bottom navigation bar with 4 destinations. Member list and profile open as **bottom sheets**. |
| **Medium**   | `600 – 1023 dp`  | Rail + **one** pane. Room list and chat swap in a navigation stack; a back affordance appears in the header. Right panel is an overlay sheet.                    |
| **Expanded** | `1024 – 1439 dp` | Rail + room list + chat. Right panel is an **overlay** over chat, opened from the header button.                                                                 |
| **Wide**     | `≥ 1440 dp`      | Rail + room list + chat + **docked** right panel (default on for 1:1 rooms, opt-in for groups).                                                                  |

Extra rules:

- **Short windows** (`height < 600 dp`): settings become full-screen instead of a centred modal;
  the room header may collapse its topic line.
- **Min window size** (desktop): `360 × 480 dp`. Below that, let the OS clip.
- Never let a pane get narrower than its min: if the space is short, drop a pane, don't squeeze.

### What collapses, in order

1. Right panel docks → overlays (1440 → 1200)
2. Right panel disappears behind a header button (1200 → 1024)
3. Chat and room list stop being side-by-side (1024 → 600)
4. Rail becomes a drawer, bottom nav appears, sheets replace panels (< 600)

---

## 3. Compact shell (phones)

```
┌─────────────────────────────┐
│ ≡   Rooms            ⌕  ⋮   │  top app bar (56)
├─────────────────────────────┤
│  search / filter            │
│  ┌───────────────────────┐  │
│  │ avatar  Room name   • │  │  72 dp tall rows (touch)
│  │         preview  12:03│  │
│  └───────────────────────┘  │
│  …                          │
├─────────────────────────────┤
│  Chats   People   Settings  │  bottom nav (80 incl. safe area)
└─────────────────────────────┘
```

- Rows grow from 40 → **64–72 dp**, avatars 40 → 48 dp, hit targets ≥ 48 dp.
- No hover states. Replace the hover toolbar with **long-press → context sheet**.
- The composer sticks above the keyboard; use `imePadding()`, send on IME action, Shift+Enter
  does not exist — use a newline button or just allow multiline with a send FAB.
- Swipe-back pops the chat screen; the drawer edge-swipe opens the rail.
- Safe areas: `WindowInsets.safeDrawing` on the shell, `ime` on the composer.

---

## 4. Rail behaviour (desktop)

The rail is the most "Discord" element and the least obvious to map (YapYap has no servers).

- Width 72, background *one step darker* than the list, 8 px top padding.
- Items are 40 × 40 circles (48 on hover is **not** Discord — Discord keeps 40 and only rounds the
  square; the corner radius animates 50 % ↔ 16 px on hover). Keep 40.
- A 4 px white pill on the left edge marks the selected item; a dot marks unread.
- Tooltip on hover with the item name, anchored to the right.
- Scrolls vertically when overflowing; hides the scrollbar.

**YapYap mapping (recommended):**

- top: YapYap/network icon (home) → room list
- then: **Pinned rooms** or **favourites** as round icons
- bottom: add/invite (opens QR invite), settings

See `06-yapyap-mapping.md` §"Rail" for the reasoning.

---

## 5. Panel behaviour

**Room list**

- Header: 49 px, contains a full-width "Find or start a conversation" button (secondary, 32 px).
- Section headers: 12 px semibold, uppercase, muted, 8 px top / 4 px bottom, with a `+` action.
- Rows: 8 px radius, 8 px horizontal inset, min 40 px tall; two lines for DM/groups with a preview
  line, one line for plain rooms.
- Selected row: `mod-strong` fill, text goes to `#fff`, no left border (Discord does **not** use an
  accent bar on rows).
- Unread: name is `#fff` **semibold** + a 8 px white dot on the left; mentions add a red pill
  badge on the right.

**Right panel**

- Member list: groups virtualised by role ("Online", "Offline"); 32 px rows, 32 px avatars,
  8 px radius on hover, name 14 px, status text 12 px dimmed.
- Profile panel (1:1): banner 120 px (blurred/gradient fallback when no image), 80 px avatar
  overlapping the banner, name 20 px semibold, handle 14 px muted, badges row, then sections.

**Composer**

- Rounded 8 px, background `#393a41` (one step lighter than chat).
- Left: 32 px circular `+` (attach). Right: emoji (and nothing else you can't implement).
- Placeholder: `Message @name` for 1:1, `Message #room` for groups, `Message` otherwise.
- Grows from 56 px to 50 % of the panel height, then scrolls internally.

---

## 6. Scrolling

- Each pane owns its scroll state; only the message list auto-sticks to the bottom.
- Show a floating "Jump to present" pill when scrolled up and new messages arrive.
- Scrollbars: 8 px wide, thumb `#7d7e87`, track transparent, only visible while scrolling on
  touch, always visible on desktop when hovered.
- Message list uses reverse layout or a sticky-bottom list so prepending history doesn't jump.

---

## 7. Layering (z-order)

| Layer | Content                              |
|-------|--------------------------------------|
| 0     | shell panes                          |
| 10    | sticky headers, jump-to-present pill |
| 20    | tooltips                             |
| 30    | popouts (profile card, emoji, menus) |
| 40    | modals                               |
| 50    | toasts                               |
| 60    | drag-and-drop overlay                |

Scrim only under modals (`#000` @ 72 %) — menus and popouts have no scrim in Discord.
