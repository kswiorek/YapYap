# 10 — Window & overlay inventory

Everything the app can put on screen, what it's for, and what it looks like. If a window isn't in
this list, it doesn't exist in YapYap v1.

Reference: the live Discord client (screenshots in `assets/screens/`, geometry from `data/`).

---

## 0. Window chrome

**Desktop**

```
┌──────────────────────────────────────────────┐
│ ···  32 dp drag region (title bar)           │   ← custom title bar, no OS chrome
├──────────────────────────────────────────────┤
│  app shell                                   │
└──────────────────────────────────────────────┘
```

- The title bar is 32 dp, background `#2c2d32`, drag-to-move across its whole width.
- macOS: keep native traffic lights at the left, inset 76 dp; the rail starts below them.
- Windows/Linux: draw minimise/maximise/close at the right, 46 × 32 hit targets, hover
  `#97979f` @ 12 %, close hover `#d22d39`.
- Double-click the bar = maximise.
- Window minimum: 360 × 480 dp. Default: 1 280 × 800 dp, restored from prefs.

**Mobile**

- No custom title bar. Respect `safeDrawing` (notch, status bar) and `ime` insets.
- The app draws its own 56 dp top app bar inside the safe area.

---

## 1. Inventory

| #    | Window                          | Type                      | Size            | Scrim | Dismiss           | Alpha                   |
|------|---------------------------------|---------------------------|-----------------|-------|-------------------|-------------------------|
| W1   | **Main shell**                  | window                    | fill            | —     | —                 | yes                     |
| W2   | **Splash / boot**               | full screen               | fill            | —     | —                 | yes                     |
| W3   | **Setup wizard**                | full screen               | max 480 col     | —     | ✗                | yes                     |
| W4   | **Recovery key**                | modal                     | 602 × auto      | 72 %  | ✗ (explicit ack) | yes                     |
| W5   | **QR invite**                   | modal                     | 480 × auto      | 72 %  | ✓ Esc            | yes                     |
| W6   | **Quick switcher**              | floating panel            | 680 × 396       | 72 %  | ✓ Esc / outside  | yes                     |
| W7   | **Create room**                 | modal                     | 480 × 800       | 72 %  | ✓ Esc            | yes                     |
| W8   | **Settings**                    | full-window modal         | ≤1400 × ≤945    | 72 %  | ✓ Esc            | yes                     |
| W9   | **Settings sub-page** (compact) | screen                    | fill            | —     | ✓ back           | yes                     |
| W10  | **Profile popout**              | anchored popout           | 300 × auto      | none  | ✓ outside        | yes                     |
| W11  | **Profile panel** (docked)      | side panel                | 300             | none  | ✓ close          | yes                     |
| W12  | **Member list**                 | side panel                | 264             | none  | ✓ close          | yes                     |
| W13  | **Context menu**                | anchored menu             | 188–320         | none  | ✓ outside        | yes                     |
| W14  | **Tooltip**                     | anchored                  | auto            | none  | on leave          | yes                     |
| W15  | **Confirm dialog**              | modal                     | 442 × auto      | 72 %  | ✓ Cancel         | yes                     |
| W16  | **Toast**                       | floating                  | ≤280            | none  | 4 s / click       | yes                     |
| W17  | **Reset / failure**             | full screen               | fill            | —     | ✗                | yes                     |
| W18  | **Connection banner**           | in-surface                | full width × 24 | none  | auto              | yes                     |
| W19  | **Emoji picker**                | anchored popout           | 498 × 486       | none  | ✓ outside        | **stub** (Unicode only) |
| W20  | **Search panel**                | right rail                | 420             | none  | ✓ close          | **stub**                |
| W22  | **Attachment lightbox**         | overlay                   | fill            | 92 %  | ✓ Esc            | **stub**                |
| W23  | **Drag-and-drop overlay**       | overlay                   | fill            | 72 %  | on leave          | **stub**                |
| W24  | **Friends**                     | full page (replaces chat) | fill            | —     | —                 | **stub**                |
| W25  | **Server view**                 | shell variant             | fill            | —     | —                 | **stub**                |
| W25b | **Server settings**             | full-window modal         | ≤1400 × ≤945    | 72 %  | ✓ Esc            | **stub**                |
| W26  | **Pinned messages**             | right rail                | 420             | none  | ✓ close          | **stub**                |
| W27  | **Threads**                     | right rail                | 420             | none  | ✓ close          | **stub**                |
| W28  | **Inbox**                       | popout                    | 480 × 600       | none  | ✓ outside        | **stub**                |
| W29  | **Voice room**                  | chat variant              | fill            | —     | —                 | **stub**                |
| W30  | **Profile editor**              | full-window modal         | ≤602 × auto     | 72 %  | ✓ Esc            | **stub**                |

"stub" windows follow `03-ux-patterns.md` §8: real chrome, placeholder content, `Soon` chip, no
fake data. They are **reachable** — that's the point.

`Alpha` = shipped now; `stub` = the window exists with real chrome and placeholder content (`03-ux-patterns.md` §8);
`out` = never build.

---

## 2. W1 — Main shell

Already specified in `02-layout-and-adaptive.md`. Summary of what it contains:

- rail (72) · room list (300) · room header (49) + chat · optional right panel (264/300)
- user panel bottom-left (56): avatar 32 + status dot, name 14 medium, status line 12 muted,
  mic / deafen / settings icon buttons 32 × 32.
- room list top: 32 dp secondary button "Find or start a conversation" (this is the search entry).
- Section header: `DIRECT MESSAGES` 12 semibold uppercase, muted, distance-to-bottom 4, `+` on the
  right (opacity 0 → 1 on section hover).

---

## 3. W3 — Setup wizard

Full-bleed `#2c2d32`, no card. Content column 480, vertically centred, 40 dp top offset.

```
              ✦  YapYap
        A private network for people
             you actually know.

   ┌───────────────────────────────────┐
   │ (1)  Create a new network         │   72 dp choice row
   │      You'll be the owner          │
   ├───────────────────────────────────┤
   │ (2)  Join with a new account      │
   │      You were invited             │
   ├───────────────────────────────────┤
   │ (3)  Add this device to my account│
   ├───────────────────────────────────┤
   │ (4)  Restore from a recovery key  │
   └───────────────────────────────────┘

        [ Continue ]        ← primary, full width, 44 dp
```

Choice rows: `#97979f` @ 8 % fill, 8 radius, 24 px icon, title 16 medium, description 14 muted,
hover @ 12 %, selected = 2 dp brand border + brand icon.

Step 2 (details) reuses the same column with two `YapTextField`s and a Back link.
Step 3 (progress) is a 4-row stepper: 20 dp circle, connector 2 dp `#97979f` @ 12 % → brand as
steps complete.

---

## 4. W6 — Quick switcher (`Ctrl/Cmd+K`)

Centred floating panel, **680 × 396**, radius 12, `#393a41`,
`0 12px 24px rgba(0,0,0,.24)`, no border. Scrim 72 %.

```
        Search for servers, channels or DMs      ← 14 muted, above the panel
   ┌────────────────────────────────────────────┐
   │ Where would you like to go?                │   70 dp tall, 8 radius,
   └────────────────────────────────────────────┘   #000 @ 8 %, 22 sp text
     Previous rooms
     ● Just A Bee        justabee.               ← 34 dp row, 20 % fill when selected
     ● Group chat
   
   Protip: type @ for people, # for rooms      ← 12 muted, 8 dp inset
   └────────────────────────────────────────────┘
```

- The input takes focus immediately; ↑/↓ move the selection, Enter opens, Esc closes.
- No network call. Filter locally over rooms (+ accounts, if searching by person).
- Empty query shows "Previous rooms" (last 5 opened).
- The footer hint disappears once the query is non-empty.

---

## 5. W7 — Create room ("New Message")

Centred modal **480 × 800**, radius 12, `#393a41`.

```
┌──────────────────────────────────────────────┐
│ New Message                              ×   │  76 dp header, 20/24 semibold
│ Group rooms can hold up to 10 people.        │  14 muted
├──────────────────────────────────────────────┤
│ ┌──────────────────────────────────────────┐ │  search input, 40 dp,
│ │ Search                                   │ │  2 dp brand border while focused
│ └──────────────────────────────────────────┘ │
│ Add people to this room.                     │  12 muted
│                                              │
│ ◉  Alice                                     │  56 dp rows, avatar 32,
│    @alice                                    │  checkbox 20 right
│ ○  Bob                                       │
│    @bob                                      │
│                                              │
├──────────────────────────────────────────────┤
│            [ Cancel ]   [ Create room ]      │  footer 76, buttons 196 × 38
└──────────────────────────────────────────────┘
```

- Footer is a separate surface `#393a41` with a 1 dp top border `#97979f` @ 12 %.
- Cancel = secondary, Create = primary; Create is disabled until ≥1 person is selected.
- Selected people become chips above the list once the user scrolls (optional, nice-to-have).

---

## 6. W8 — Settings

Full-window modal: **`min(1400, width − 80) × min(945, height − 80)`**, radius 12, `#323339`.
Two columns: nav **253** (`#2c2d32`-ish, its own surface), content fluid with a **740 max** column.

```
┌───────────────────────────────────────────────────────────────────┐
│ Alice  Edit profile                         Appearance        ×  │  64 dp header
│ ┌───────────────────────────────────────────────────────────────┐ │
│ │ Search settings                                               │ │  32 dp, 8 radius
│ └───────────────────────────────────────────────────────────────┘ │
│ ───────────────────────────────────────────────────────────────── │
│ ACCOUNT            │  Theme                                      │
│  Account           │  Same as device theme              (o)      │
│  Devices           │                                             │
│  Privacy           │  Default themes                             │
│                    │  [■][■][■][■][↻]                            │
│ APP                │                                             │
│  Appearance     ▌  │  Message Display                            │
│  Notifications     │  Cozy (•)   Compact ( )                     │
│  Keybinds          │                                             │
│                    │  ─────────────────────────────────────────  │
│ NETWORK            │  Sync theme across my devices      (o)      │
│  Members           │                                             │
│  Advanced          │                                             │
└───────────────────────────────────────────────────────────────────┘
```

Details that make it read as Discord:

- Nav rows 32 dp tall, 8 radius, 16 medium label, 20 icon, **8 %** hover/active fill, white text
  when active, plus a 2 dp brand bar *on the content edge* (not on the row).
- Nav group labels: 12 semibold uppercase, `#abacb2`, 24 dp above, 8 below.
- Content title (`Appearance`) is 14 semibold — surprisingly small.
- Section titles inside the content (`Theme`, `Message Display`) are 20/24 semibold with 24 dp
  above and 16 below.
- Rows are 48 dp min, label 16 medium + description 14 muted stacked, control right-aligned,
  24 dp between rows, no dividers.
- Scrolls only the content column; the nav is fixed.

**Compact:** full-screen; nav is a list; selecting a section pushes the content pane with a 56 dp
top bar containing a back chevron and the section name.

---

## 7. W10/W11 — Profile

**Popout (W10)** — measured **300 × 277 dp**, background `#393a41` (not darker than chat — the
popout is *lighter*), radius 8, no border, shadow
`0 0 0 1 px rgba(255,255,255,.08), 0 12 px 24 px rgba(0,0,0,.24)` (the 1 px white ring is the
"border"). Anchored to the clicked avatar/name, flipped to stay on screen, no scrim.

```
┌──────────────────────────────┐
│ ░░░░░░ banner 105 ░░░░░░ (i)(⋯)│  2 circular 32 dp buttons,
│ ░░░░░░░░░░░░░░░░░░░░░░░░░░░░ │  #000 @ 56 %, top-right
│   ┌──────┐                   │
│   │ 80px │  ← overlaps by 44 │
│   └──────┘                   │
│  Alice                       │  20/24 semibold  #f3f3f4
│  @alice · Online             │  14/18 regular   #ffffff
│  ──────────────────────────  │  1 dp #97979f @ 12 %
│  MEMBER SINCE                │  12 semibold uppercase
│  27 October 2026             │  14
│  ──────────────────────────  │
│  [   Message @alice   ]      │  44 dp, primary, full width
└──────────────────────────────┘
```

**Docked panel (W11)** — same content, **304 wide**, full height, `#393a41`, radius 8, same 1 px
white ring. Used for 1:1 rooms on Wide; becomes the popout below 1440 dp.

Alpha: the avatar is generated (initials on a gradient) and the banner is a deterministic gradient,
so the popout renders identically minus the photo. Keep the 80 dp avatar, the 44 dp overlap and the
105 dp banner — those are what make it read as Discord.

---

## 8. W12 — Member list

264 wide, `#2c2d32`, scrolls independently. Header `Members — 5` 16 semibold with a close `×`.

Groups: `ONLINE — 3`, `OFFLINE — 2` at 12 semibold uppercase muted, 24 dp top spacing.
Rows 40 dp: avatar 32 with status dot, name 14 medium, role chip 12 on the right for owner/admin.
Hover `#97979f` @ 8 %, radius 8, 8 dp inset. Click → profile popout.

Owner row gets a small crown/star glyph before the name; admins get a subtle `ADMIN` chip.

---

## 9. W13 — Context menu

```
┌──────────────────────────────┐
│  Copy text              ⌘C   │  36 dp row, 8 dp inset, 14 sp
│  Copy message link           │
│  ──────────────────────────  │  1 dp, #97979f @ 12 %, 4 dp margin
│  Mark unread                 │
│  Report message        red   │  destructive: #ffa09b, red @ 8 % hover
└──────────────────────────────┘
```

Width is content-driven (measured 221 dp for the message menu; clamp 188–320), background
`#3c3d45`, container radius **8**, row radius **2**, 8 dp row padding, 20 dp icons, 14 sp labels in
`#c5c6ca`, hover `#97979f` @ 16 % with the label going white, container shadow
`0 8px 16px rgba(0,0,0,.24)`, no border.

Measured reference (message menu) also includes a 44 dp emoji quick-react grid at the top — skip
that until reactions exist.

Right-click on desktop; **long-press** on touch (bottom sheet, 56 dp rows, 16 dp top radius).

---

## 10. W14 — Tooltip

`#3f4048`, radius 8, padding 8 × 12, 14/18 medium white, `0 8px 16px rgba(0,0,0,.24)`, 8 dp
pointer. Max 2 lines, no wrapping of long words. 400 ms delay on first hover, then instant.

---

## 11. W15 — Confirm dialog

442 wide. Title 20 semibold, body 14 `#c5c6ca`, footer right-aligned `Cancel` (secondary) +
verb (danger/primary). Max 2 sentences; no "Are you sure?".

Destructive flows that require typing the name: remove account, remove last device, reset app.

---

## 12. W16 — Toast

280 max wide, anchored bottom-centre 24 dp above the bottom edge (desktop) or the bottom nav (mobile). `#3c3d45`, radius
8, 3 dp left accent bar, 14/18 text, 12 dp padding, `shadow-high`.
Enters with a 8 dp rise + fade over 200 ms, holds 4 s, exits 150 ms. Max 3 stacked, 8 dp apart.

---

## 13. W17 — Reset / failure

Full screen, centred column 480. 48 dp icon (warning `#fcb529` / error `#ffa09b`), `heading/xl`,
one paragraph 14 muted, then actions. Collapsible "Technical details" shows a mono 12 block with a
copy button. Reset's confirm requires typing `reset`.

---

## 14. W18 — Connection banner

24 dp tall strip directly under the room header, full width, `#fcb529` @ 10 % fill with a 12 sp
`#fcb529` label and a 16 dp spinner. Pulses opacity 0.6 ↔ 1 every 1.2 s. Auto-hides on reconnect
with a 150 ms fade. Never blocks the composer.

---

## 15. W19 — Emoji picker (stub-aware)

Measured **498 × 486**, anchored above the composer's emoji button (right-aligned, 8 dp gap),
background `#393a41`, radius 8, shadow `0 12px 24px rgba(0,0,0,.24)`, no scrim.

```
┌──────────────────────────────────────────────────┐
│   GIFs     Stickers     (Emoji)                  │  30 dp pill tabs,
│                                                  │  selected #9595a2 @16 %
│ ┌──────────────────────────────────┐   ┌────┐    │
│ │ :smiling_face_with_3_hearts:     │   │ 🥰 │    │  36 dp search,
│ └──────────────────────────────────┘   └────┘    │  emoji preview right
├────┬─────────────────────────────────────────────┤
│ 🕐 │  ▾ Frequently Used                          │  section header 12 semibold
│ 😀 │  [48][48][48][48][48][48][48][48]           │  48 dp cells, 8 dp gutter
│ 🌿 │  [48][48][48][48][48][48][48][48]           │
│ 🍔 │  ▾ People                                   │
│ ⚽ │  …                                          │
│ 🚗 │                                             │
│ 💡 │                                   ↑ scroll  │  thin scrollbar
│ ❤️ │                                             │
│ 🚩 │                                             │
├────┴─────────────────────────────────────────────┤
│ 🥰  :smiling_face_with_3_hearts:                 │  40 dp preview footer
└──────────────────────────────────────────────────┘
```

- Left rail: 36 wide, 48 dp category buttons, active one gets a 2 dp brand bar on the left and a
  white icon; idle `#abacb2`.
- Hover on a cell: `#97979f` @ 12 %, radius 8; click inserts the emoji at the caret.
- Keyboard: type to search, ↑/↓/←/→ move the grid, Enter inserts, Esc closes.
- Empty search result: centred `body/sm` "No emoji found".

YapYap alpha ships **Unicode emoji only**; custom/server emoji become a second tab later (`08-feature-readiness.md`).

---

## 16. OUT — windows that must not exist

Only things the product will never have (see `08-feature-readiness.md` §3). No stubs for these —
a placeholder would advertise something that isn't coming.

- Login / register / QR login / passkey
- Nitro, Shop, Quests, Server Boost, Subscriptions, Gift Inventory, Billing
- Server Discovery / Explore servers, App Directory, Connected Apps / OAuth
- Family Centre and teen-safety surfaces
- Streamer mode, app icon picker, game overlay, game store

Everything else Discord has is either real or a stub below.

---

## 17. Stub pages

Each one uses the finished layout, shows its real chrome and tabs, and replaces only the rows with
an empty state plus a `Soon` chip (`03-ux-patterns.md` §8.2). Build them **now** — the goal is that
shipping the backend feature later deletes an empty state, not that it adds a page.

### W24 — Friends

Reached from the rail (second destination) or the room-list header.

```
┌──────┬──────────────────────────────────────────────────────────┐
│ rail │  Friends  ( Soon )                                       │  49 dp header
│      │  Online · All · Pending · Blocked · Add Friend           │  tabs, "Add Friend"
│      │                                                          │  is the brand-tinted
│      │                    (icon 48)                             │  tab
│      │                                                          │
│      │            Friends are coming soon                       │  heading/sm
│      │   You'll be able to add people you know and see          │  body/sm #abacb2
│      │   who's around.                                          │
└──────┴──────────────────────────────────────────────────────────┘
```

- Tabs exist and switch; each has its own empty state copy.
- `Add Friend` tab shows the username field and a disabled-looking-but-clickable
  `Send friend request` that answers with the `Soon` toast.
- No friend rows, no online counts, no avatars in a list.

### W25 — Server view

The alternative shell when the network has more than one "space". Same geometry as W1 — rail,
channel list (300), chat, member list (264) — but the channel list shows groups:

```
┌──────┬───────────────────┬───────────────────────────────────────┐
│  ◉   │  Space name    ▾  │                                       │
│  ◯   │  ── TEXT ──       │   (empty state + Soon)                │
│  ◯   │   # general       │                                       │
│      │   # off-topic     │                                       │
│      │  ── VOICE ──      │                                       │
│      │   🔊 Lounge  (Soon)│                                      │
└──────┴───────────────────┴───────────────────────────────────────┘
```

- Categories are collapsible from day one; today there is exactly one real group of rooms.
- Channel **types** that Discord has and YapYap's model may grow into — text, voice, forum,
  announcement, stage — all render with their distinct 20 dp icon in the list. Voice/forum/stage
  carry the `Soon` chip; text is real.
- Voice channels render with a speaker icon and a `Soon` chip; stage channels with the stage icon;
  opening either lands on W29.
- The server header menu includes `Server settings` → W25b, and `Server onboarding` (stub page with
  the welcome-screen editor).

### W25b — Server settings

Same modal geometry as W8, but a different nav: Overview · Roles · Emoji · Stickers · Events ·
Moderation · Audit Log · Integrations · Invites. Every pane is a stub page with the real section
titles and `Soon` chips. Keeps the mental model intact for the day group administration lands.

### W26 — Pinned messages

Right rail, 420 wide, `#2c2d32`, header `Pinned messages ( Soon )`, a 32 dp search field, then the
empty state. Opened from the room header overflow menu.

### W27 — Threads

Right rail, 420 wide, header with a back chevron + thread title placeholder, and the empty state.
Opened from a message's `Create thread` action (stub) and from the header overflow.

### W28 — Inbox

Anchored popout 480 × 600 under the rail bell. Tabs `Unread · Mentions · Scheduled`; each shows the
empty state. Bell has no badge until the backend can produce one.

### W29 — Voice room

The chat screen, plus a 56 dp bar above the composer:

```
│  🔊  Voice  ( Soon )   Connect to talk                        │
│      Voice channels are coming in a later build.              │
```

The message list still works (it's a normal room). No participant grid until audio exists.

**Stage channels** reuse the same screen with a "Stage" label and a `Soon` chip; the future layout
is: a 16:9 speaker tile grid above the chat, then a 72 dp control bar (`Mute · Deafen · Camera · Share · Leave`). Draw
the bar only when `capabilities.voice` is true.

### W30 — Profile editor

Modal ≤602, over the app. Shows the avatar circle (generated) with a `Change avatar` row (`Soon`),
banner strip, and fields: Display name (disabled, read-only from chain), Bio, Pronouns, Links — the
last three with `Soon` chips. Footer: `Cancel` / `Save` (disabled until editable).

