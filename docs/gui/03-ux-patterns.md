# 03 — UX patterns

This is the file that matters most: the *behaviour* a Discord user expects. Each pattern names the
YapYap API that backs it so the implementer never has to guess.

Legend: **[Y]** = backed by the runtime, **[L]** = local-only (GUI state), **[X]** = Discord has it,
YapYap does not — drop or adapt.

---

## 1. Messages

### 1.1 Grouping

Consecutive messages from the **same author within 5 minutes** collapse into one group:

```
[avatar] Author   Today at 14:02
         first line
         second line            ← same author, same minute: no avatar, no name
[avatar] Other    Today at 14:05
```

- Group start: show avatar (40), author name (16 medium), timestamp (12 muted).
- Continuation: no avatar; content aligns to the same 72 px gutter. Timestamp appears on row hover
  in the gutter's left edge instead of in the header.
- A date change, a system event, a gap, or a different author always starts a new group.
- 16 px vertical gap between groups; 2 px inside a group.

### 1.2 Row anatomy

| Part              | Spec                                                                |
|-------------------|---------------------------------------------------------------------|
| Avatar            | 40 px circle, top-aligned to the first line; click → profile popout |
| Author            | 16 px medium `#f3f3f4`; click → profile popout                      |
| Timestamp         | 12 px `#abacb2`, hover-only for continuations                       |
| Body              | 16 / 20 px `#f3f3f4`                                                |
| Hover background  | full-bleed row, `#97979f` @ 8 % (very faint)                        |
| Mention highlight | `#f8a300` @ 8 % left bar 2 px + fill                                |

### 1.3 Hover toolbar **[L]**

Measured from the live client (pointer hover, not CSS guesswork):

- Positioned **absolute, top −25 dp, right 14 dp** — it straddles the top edge of the row and
  overlaps the message above.
- Container: `#393a41`, radius 8, padding 2, height 33, no shadow, no border.
- Buttons: 28 × 28, icon 20, no gap, radius 6, hover `#97979f` @ 16 %, idle icon `#c5c6ca`.
- Row hover background: `#94949c` @ 12 % (that is `--message-background-hover` after compositing).
- It fades in over 150 ms and is only interactive when visible.

Discord's full button set, left to right:

```
[ 😀100 ] [ 👍 ] [ 👎 ] | [ ＋react ] | [ reply ] [ forward ] [ ⋯ more ]
```

For YapYap alpha render **all** of them, but only `More` → `Copy text` works. The rest carry the
`Soon` chip (`03-ux-patterns.md` §8.3) and explain themselves when activated. Because the container
is content-sized and right-aligned, turning them on later doesn't shift the layout.

On touch devices there is no hover: long-press opens the context sheet with the same actions.

### 1.4 Message content types **[Y]**

`MessageDisplayItem` is a closed set — build exactly these three renderers:

| Type   | Render                                                                                                                                            |
|--------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| `Text` | plain text with link detection, inline `code`, `**bold**`, `*italic*`, `> quote`, `- list`, and monospace blocks. Keep the parser small and safe. |
| `File` | attachment card: 40 px file icon, name (14 medium), size (12 muted), download action. No inline preview in v1.                                    |
| `Gap`  | centred divider: `——— 12 messages not loaded ———` with a retry affordance; never render an empty row.                                             |

### 1.4.1 Near-term vs later

The product direction is to match Discord's message surface closely — reactions, attachments,
GIFs, embeds — but the backend for most of that does not exist yet. The plan is to **build the
shelf now and fill it later**, not to hide it.

| Capability                                      | State     | Alpha behaviour                                                      |
|-------------------------------------------------|-----------|----------------------------------------------------------------------|
| Text, files (metadata), gaps                    | **READY** | render for real                                                      |
| File download / open                            | **STUB**  | card renders; the download action explains it's coming               |
| Reactions, GIFs, stickers, custom emoji, embeds | **STUB**  | slots exist at the right size; pickers open with placeholder content |
| Replies, threads, edits, deletes, pins, search  | **STUB**  | menu items and header buttons exist with a `Soon` chip               |
| Voice messages                                  | **OUT**   | not planned                                                          |

The concrete rule is in §8: **a stub always has the finished layout and never fakes data.**

### 1.5 Dates, unread, and "new"

- **Date divider** before the first message of a day: 1 px line + centred 12 px semibold label (`27 October 2026`), line
  is `#97979f` @ 12 %.
- **New-messages divider** **[L]**: red/brand line + 12 px semibold label, inserted at the first
  message newer than the stored last-read timestamp. Persist per room.
- **Jump to present**: pill bottom-right of the scroller when the user is scrolled up and new
  messages arrive; click scrolls to bottom and clears unread.

### 1.6 Sending **[Y]**

```
onSend(text):
  if text.isBlank -> ignore
  if text.byteLength > messaging.maxTextMessageBytes -> show inline error, don't clear
  optimistic row (state = SENDING) appended locally
  messaging.sendMessage(roomId, Message(text)) ->
     Sent(fanout)  -> keep row, clear pending
                      if fanout.membersPullOnly > 0 -> no user-visible warning (pull path covers it)
     Refused(TooLarge)        -> mark row failed, keep text
     Refused(NotMember)       -> mark failed, offer leave/close
     Refused(HistoryIncomplete) -> toast "Still syncing — try again in a moment"
```

- The real item arrives from `RoomMessageWindow.displayItems`; dedupe by `messageId` and replace
  the optimistic row instead of appending a duplicate.
- Failed rows: red 12 px label "Not sent" + retry action. Never lose the text.
- Clear the composer only after `sendMessage` returns `Sent`.

### 1.7 Composer **[Y]**

| Behaviour        | Rule                                                                                 |
|------------------|--------------------------------------------------------------------------------------|
| Placeholder      | `Message @name` (1:1), `Message #room` (group), `Message` (unnamed room)             |
| Enter            | send (desktop). Shift+Enter = newline. On mobile: IME send action.                   |
| Auto-grow        | 56 → 50 % of panel height, then internal scroll                                      |
| Character budget | bytes, not chars. Show `n / max` at 90 %, turn warning colour at 100 %               |
| Typing signal    | call `setTyping(roomId, true)` on first keystroke, `false` after 3 s idle or on send |
| Disabled states  | room not a member → read-only banner above composer; history incomplete → same       |
| Attach           | `+` opens file picker; **files are messages**, so a file send is a separate message  |
| Reply/edit       | none                                                                                 |

### 1.8 Typing indicator **[Y]**

Above the composer, 12 px muted, height 24, fades in/out:

- 1 person → `Alice is typing…`
- 2 → `Alice and Bob are typing…`
- 3+ → `Several people are typing…`
- Animated three dots, 1.2 s loop.

Source: `messaging.typingState[roomId]` minus the local account.

### 1.9 Scrolling

- Stick to bottom when the user is within 40 px of it.
- Never auto-scroll if the user has scrolled up; show the pill instead.
- Loading older: trigger at 200 px from the top, call `window.loadOlder(50)`, then restore the
  previous visual anchor (prepend without jumping).
- `hasMoreOlder == false` → show nothing (no "beginning of channel" text unless the room is empty).

---

## 2. Room list

### 2.1 Ordering **[Y]** / **[L]**

`RoomService.rooms` is projection order. The GUI re-sorts:

1. **Pinned/favourites** (local) first.
2. Then by last activity: `roomPreview.timestamp`, descending. Rooms with no preview sort last,
   then alphabetically.
3. Empty-name rooms display participants' names joined with `, ` (see `RoomDetails.name` docs).

Re-pull `messaging.roomPreview(roomId)` on every `incomingMessageEvents` for that room, and when a
room is opened.

### 2.2 Row states

| State         | Visual                                                                        |
|---------------|-------------------------------------------------------------------------------|
| Idle          | transparent                                                                   |
| Hover         | `mod-subtle` (12 %)                                                           |
| Selected      | `mod-strong` (20 %), text `#fff`                                              |
| Unread        | name `#fff` 600 weight + 8 px white dot left of the avatar; preview `#f3f3f4` |
| Mention       | red pill `#da3e44` with white count, right-aligned                            |
| Muted **[L]** | name `#abacb2`, no dot                                                        |
| Typing        | preview line replaced with `typing…` in brand-ish colour                      |

Read state is **local**: store `lastReadAt` per room in UI preferences, compare against
`roomPreview.timestamp`. There is no server-side read receipt **[L]**.

### 2.3 Row layout

```
[avatar 32/40]  Name                     ·  dot / badge
                last message preview…    ·  time
```

- 1:1: 40 px avatar with a status dot (online/idle/offline) bottom-right.
- Group: stacked 2×2 avatar collage, or a single generated mosaic.
- Single-line mode (optional setting): one row, name + preview + time, 28 px tall.

### 2.4 Context menu **[L]**

Right-click / long-press a row: **Mark as read**, **Mute** **[L]**, **Pin** **[L]**, **Copy room ID**, **Leave room** *(
destructive, confirm)*.

### 2.5 Create room **[Y]**

Trigger: `+` next to the "Direct Messages" section header, or `Ctrl+N`.

Flow (a modal on desktop, full-screen on mobile):

1. Pick members from `identity.accounts` (searchable multi-select, avatars + names).
2. Optional name field (blank → unnamed group, rendered from participants).
3. Primary action `Create` → `rooms.createRoom(name, memberIds)`.
4. On `Created(roomId)` open the room. On `Refused(EMPTY_MEMBERS)` disable the button before this.

---

## 3. Navigation & keyboard

### 3.1 Shortcuts (desktop)

| Keys                   | Action                                                 |
|------------------------|--------------------------------------------------------|
| `Ctrl/Cmd + K`         | Quick switcher (room search, fuzzy)                    |
| `Ctrl/Cmd + N`         | New room                                               |
| `Ctrl/Cmd + F`         | Search in room *(stub: filters loaded messages)*       |
| `Esc`                  | Close top overlay / clear composer / exit search       |
| `Alt + ↑ / ↓`          | Previous / next room                                   |
| `Ctrl/Cmd + Shift + M` | Toggle mute **[L]**                                    |
| `Ctrl/Cmd + ,`         | Settings                                               |
| `Shift + Esc`          | Mark room read                                         |
| `Tab`                  | Move focus; focus ring = 2 px `#6aa8f4` at 2 px offset |

Function keys and Discord's full keybind table are out of scope. Ship this list, document it in
settings → Keybinds as read-only for v1.

### 3.2 Quick switcher **[L]**

`Ctrl+K` → centred overlay (width 480, max 70 % height) with a search field, then up to 10 room
rows; arrow keys move, Enter opens, Esc closes. No network call — filter `RoomService.rooms`.

---

## 4. Overlays

### 4.1 Modal

- Desktop: centred, max-width 442 / 602 / 800 dp by content class, `#393a41`, radius 12,
  `shadow-medium`, scrim 72 %.
- Structure: header (title 20/24 semibold, close `×` 24 px right, 16 px padding), scrollable body,
  optional footer with right-aligned buttons (Cancel = secondary, Confirm = primary/danger).
- Focus trap, `Esc` closes, click-scrim closes only for non-destructive modals.
- Mobile: full-screen sheet, top bar with back.

### 4.2 Popout (profile card / emoji / menus)

- Anchored to the trigger, 8 px gap, `#3c3d45`, radius 8, `shadow-high`, 1 px `elevation-stroke`.
- Flips above/below and left/right to stay on screen; clamps to 8 px viewport margin.
- Closes on outside click, `Esc`, or scroll of the parent.
- No scrim.

### 4.3 Context menu

Measured from a live right-click on a message:

| Property    | Value                                                         |
|-------------|---------------------------------------------------------------|
| Width       | content-driven; 221 dp in the message case (min 188, max 320) |
| Background  | `#3c3d45`                                                     |
| Radius      | 8 (container), **2** (rows)                                   |
| Padding     | 8 vertical on the scroller; rows inset 8 horizontal           |
| Row height  | 36 (text rows), 44 (emoji quick-react grid)                   |
| Row padding | 8 all round                                                   |
| Label       | 14/18 regular, `#c5c6ca`                                      |
| Icon        | 20, `#c5c6ca`, 8 gap to label                                 |
| Hover       | `#97979f` @ 16 %, text → `#fff`                               |
| Destructive | text `#ffa09b`, hover fill red @ 8 %                          |
| Separator   | 1 dp `#97979f` @ 12 %, 4 dp vertical margin                   |
| Shadow      | `0 8px 16px rgba(0,0,0,.24)` on the container (no border)     |

Structure of the real message menu (for reference — YapYap renders only the bold ones):

```
[ 😀100 ][ 👍 ][ 👎 ][ ❤ ]     ← 44 dp quick-react grid, 4 columns
───────────────────────────
 Add Reaction
 Reply
 Forward
 **Copy Text**
 Pin Message
 Bookmark Message
 Create Reminder
 Apps
 Mark Unread
 **Copy Message Link**
 Speak Message
───────────────────────────
 Report Message                ← destructive
```

Keyboard: ↑/↓ move, Enter activate, Esc close. Submenus open to the right, aligned to the row.

On touch: the same rows in a bottom sheet, 56 dp tall, 16 dp top radius.

### 4.4 Tooltip

- `#3f4048`, radius 8, padding 8 × 12, text 14/18 medium white, `shadow-high`.
- Delay 400 ms on first hover, 0 ms for subsequent ones within 500 ms.
- Pointer arrow 8 px. Never wrap more than 2 lines.

### 4.5 Toast

- Bottom-centre (desktop) / above bottom nav (mobile), max width 280, `#3c3d45`, radius 8,
  left 3 px accent bar in the semantic colour, 14 px text, 4 s, dismiss on click.
- Variants: success `#7ecb94`, error `#ffa09b`, info `#8ebefa`.
- Queue them; never stack more than 3.

### 4.6 Confirmation dialog

For destructive room admin actions and account/device removal. Requires typing the name for **remove account** and
**remove last device**. Text states the irreversible consequence, not
"are you sure?".

---

## 5. Non-happy states

| State                                  | Design                                                                                                                                      |
|----------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| **Loading room list**                  | 6 skeleton rows: 40 px circle + two 12 px bars at 40 % / 70 % width, shimmer 1.2 s.                                                         |
| **Empty room list**                    | Centred 64 px illustration-free icon + `heading/sm` "No conversations yet" + secondary button "Start a conversation".                       |
| **Empty room**                         | Room name as `heading/xxl`, one-line welcome, primary action (invite / add member). Mirrors Discord's "beginning of your DM history" block. |
| **No room selected** (desktop)         | Blurple-tinted empty canvas with the app mark, `heading/lg` "Pick up where you left off", quick list of 3 recent rooms.                     |
| **Syncing / history incomplete**       | 24 px info banner under the header: "Catching up — some messages may be missing." Auto-dismiss.                                             |
| **Send failed**                        | Inline row error + toast; keep the draft.                                                                                                   |
| **Not a member anymore**               | Replace composer with a bar: "You're no longer in this room" + "Leave" action.                                                              |
| **Offline / Tor degraded**             | Thin 24 px banner, amber, top of chat: "Reconnecting…". Never block input.                                                                  |
| **Fatal (`OrchestratorState.Failed`)** | Full-screen error with the cause and two actions: Retry, Reset (with confirm).                                                              |
| **Unknown author**                     | `identity.account(id) == null` → render avatar placeholder + "Unknown user" in muted; do not hide the message.                              |

---

## 6. Presence & identity **[Y]**

- `AccountAvailability`: `ONLINE` → green dot, `OFFLINE` → grey dot, `UNKNOWN` → hollow grey ring.
- Text under a name: `Online`, `Offline`, or `Last seen <relative>` using `lastSeen`.
- Never show "Do not disturb" / "Idle" / rich presence — the backend has no such states. Do not
  invent them.
- Avatars: YapYap has **no avatar images yet** (planned). Generate a deterministic gradient +
  initials from `accountId`. Keep the same circle geometry, status-dot placement and group collage
  as Discord so the layout reads correctly — only the pixel source is temporary (`08-feature-readiness.md` §2).

---

## 7. Admin

Gate everything on `admin.localIsAdmin` (live). Put it in **Settings → Network → Members**,
not in the chat chrome.

| Action                       | API                                         | Confirmation                                  |
|------------------------------|---------------------------------------------|-----------------------------------------------|
| Grant/revoke admin           | `admin.grantAdmin/revokeAdmin`              | none for grant; confirm for revoke            |
| Ban account                  | `admin.removeAccount`                       | confirm dialog, states device cascade         |
| Ban device                   | `admin.removeDevice`                        | confirm dialog                                |
| Remove own device            | `account.removeOwnDevice`                   | confirm; ask recovery key if last device      |
| Remove this device / account | `account.removeThisDevice/removeOwnAccount` | type-to-confirm; owner needs successor picker |

Map refusals to human text in one place (`RefusalText.kt`) — the backend deliberately returns
typed values, never copy.

---

## 8. Stub surfaces

YapYap is early alpha, but the app must already look like the finished product. Every planned
Discord feature gets a **stub**: the real chrome, placeholder content, and a small `Soon` marker.

Three stub shapes, and nothing else:

### 8.1 `Soon` chip

```
( Soon )
```

- 11 sp, weight 600, uppercase, `letter-spacing: 0.02 em`
- text `#abacb2`, background `#97979f` @ 12 %, radius 4, padding 2 × 6
- never brand-coloured — it must not compete with real actions
- placed **after** the label, 8 dp gap; in menus it is right-aligned where shortcut hints go

### 8.2 Stub page

For a whole surface that has no data yet (Friends, Pinned, Search…):

```
┌─────────────────────────────────────────────────┐
│  Friends — 0                            ( Soon )│  real header, real tabs
│  Online · All · Pending · Blocked · Add Friend  │
├─────────────────────────────────────────────────┤
│                                                 │
│                    (icon 48)                    │  #97979f @ 40 %
│                                                 │
│              Friends are coming soon            │  heading/sm
│      You'll be able to add people you know      │  body/sm #abacb2
│      and see who's around.                      │
│                                                 │
└─────────────────────────────────────────────────┘
```

- **Keep every piece of real chrome**: header, tabs, sidebars, filters, search field. The page must
  be navigable and occupy the correct pane.
- Empty state = icon + one line + one sentence of explanation. No illustration, no CTA that does
  nothing.
- Tabs are switchable and each shows its own empty state — the structure is real, only the rows
  are missing.
- No skeleton shimmer and no spinner: it isn't loading, it's not built. Skeletons imply data is
  coming.

### 8.3 Stub action

For a control inside an otherwise-working surface (menu item, toolbar button, settings row):

- Render it normally with the `Soon` chip.
- It **is** focusable and clickable; activating it shows a 2 s toast: *"Coming in a later build."*
  (or a one-line explanation specific to the action).
- Never grey it out into an unclickable dead pixel, and never remove it — a missing "Reply" makes
  the app look broken; a Reply with `Soon` makes it look unfinished, which is the truth.

### 8.4 What a stub must never do

- invent rows, counts, names, avatars or timestamps,
- show a spinner or shimmer,
- look *disabled* (grey text, 50 % opacity) — that reads as "you can't use this", not "not built",
- change the layout when it later becomes real.

### 8.5 Stub placement rules

| Surface                                             | Stub shape                                                 |
|-----------------------------------------------------|------------------------------------------------------------|
| Whole page (Friends, Pinned, Search, Inbox, Server) | 8.2 page                                                   |
| Settings section not backed yet                     | real section with rows, each row a 8.3 action              |
| Message/room/member menu item                       | 8.3 action                                                 |
| Composer button (GIF, sticker)                      | 8.3 action, or hidden if the picker stub doesn't exist yet |
| Reaction row                                        | reserved 0 dp slot, no pill until real                     |

Voice messages, login, commerce and discovery are **not** stubs — see `08-feature-readiness.md` §3.
