# 04 — Screens

Every screen below lists: what it is for, its structure, its states, and the runtime calls behind
it. Wireframes are ASCII; the real reference shots are in [`assets/screens/`](assets/screens).

Floating surfaces (menus, tooltips, toasts, popouts, quick switcher) are catalogued separately in
[`10-windows.md`](10-windows.md); this file covers the full-page screens. Screens that are not
backed by the backend yet are **stubs** — they exist, with real chrome and placeholder content (`10-windows.md` §17,
`03-ux-patterns.md` §8).

Screen IDs are stable — use them in commit messages and tests.

---

## S0 — Boot & lifecycle

`orchestrator.state: StateFlow<OrchestratorState>` drives everything. Build one `AppRoot` that
switches on it; never let a screen assume `Running`.

| State                   | Screen                                                            |
|-------------------------|-------------------------------------------------------------------|
| `Created`               | Splash (logo, 1 line) → immediately transitions                   |
| `SetupRequired`         | **S1 Setup**                                                      |
| `Starting`              | Splash + progress line (`Starting network…`, `Recovering state…`) |
| `Running`               | **S5 Main shell**                                                 |
| `Stopping` / `Stopped`  | Splash with `Stopped` + `Restart` / `Reset`                       |
| `ResetRequired(reason)` | **S4 Reset**                                                      |
| `Failed(cause)`         | **S4 Failure**                                                    |

Splash: `#2c2d32` background, 64 px mark, app name `heading/xxl`, subtitle `body/sm` muted,
indeterminate 2 px brand bar under the logo. No spinner rings.

---

## S1 — Setup (first run)

Full-screen, no rail, no list. Centred card max 480 dp, background `#2c2d32`, no card chrome —
Discord's login is a floating form on the app background.

**Step 0 — choose path** (4 large choice rows, each 72 dp, icon + title + one-line description):

| Choice                        | `SetupIntent`                | When                                     |
|-------------------------------|------------------------------|------------------------------------------|
| Create a new network          | `Genesis`                    | first user, becomes OWNER                |
| Join with a new account       | `NewAccountFirstDevice`      | newcomer sponsored by an existing member |
| Add this device to my account | `AddDeviceToExistingAccount` | second device for an existing account    |
| Restore from recovery key     | `ImportAccountRecoveryKey`   | disaster recovery                        |

**Step 1 — details**: display name (required, 2–32 chars), device name (defaults to platform).

**Step 2 — progress**: a vertical stepper, 4 rows, each `○ pending / ● done / ✕ failed`:
`Creating keys → Contacting network → Publishing identity → Syncing`. Cancel is available only
before the publish step (calls `onboarding.newcomerCancelOnboarding()`).

**Step 3 — result**:

- If the current node is a *newcomer*, show the **QR invite** (S3) so a sponsor can scan it, with
  `Waiting for a sponsor…` and a cancel link.
- If `SetupResult.recoveryKey != null`, show **S2 Recovery key**.
- Else → S5.

Errors are inline above the primary button, never a dialog. Button stays enabled so the user can
retry.

---

## S2 — Recovery key (show once)

Modal (medium 602) over the setup background.

```
┌──────────────────────────────────────────────┐
│  Save your recovery key                   ×  │
├──────────────────────────────────────────────┤
│  This is the only way to recover your        │
│  account if you lose every device.           │
│                                              │
│  ┌────────────────────────────────────────┐  │
│  │  ABCD-EFGH-IJKL-MNOP-QRST-UVWX        │  │  ← 14 mono, selectable
│  └────────────────────────────────────────┘  │
│  [ Copy ]  [ Download .txt ]                 │
│                                              │
│  ☐ I have stored my recovery key safely      │
│                                              │
│                          [ Continue ]        │
└──────────────────────────────────────────────┘
```

- The key is **never** shown again; if the user closes the app, show a warning in Settings that it
  was not acknowledged.
- Continue is disabled until the checkbox is ticked.
- `Copy` shows a success toast; `Download` writes `yapyap-recovery-<date>.txt`.

---

## S3 — QR invite

Two modes share one component.

**Show mode** (sponsor generates / newcomer shows identity): white 240 × 240 QR on a `#fff` rounded (12) card, room name
above, `heading/sm` caption below, `Copy invite` secondary button.
The rest of the screen is dimmed. The QR encodes `SetupResult.inviteBytes` or the sponsor's payload;
the GUI never parses it.

**Scan mode** (sponsor scans a newcomer): camera preview in a 1:1 letterbox with a 240 px rounded
cut-out and corner brackets; manual "paste invite text" fallback underneath. On success:
`onboarding.sponsorNewcomer(bytes, admin = false)` and a checkbox `Grant admin to this account`.

Result mapping:

- `Sponsored(...)` → success toast + close
- `MalformedInvite` → inline error "That code isn't valid — ask for a fresh one"
- `NotAdmin` → inline error + hide the admin checkbox

---

## S4 — Reset / Failure

**ResetRequired (reason)** — full screen, warning icon 48 in `#fcb529`, `heading/xl` "This device
has been removed from the network" (or the ban wording), one paragraph of what happened, then
`Reset app` (danger) with a type-to-confirm. `resetApp()` wipes local data.

**Failed (cause)** — error icon, `heading/xl` "Something went wrong", collapsible technical details (`body/xs` mono,
selectable, copy button), `Retry` primary and `Reset` secondary.

---

## S5 — Main shell

See [`02-layout-and-adaptive.md`](02-layout-and-adaptive.md) for regions and collapse.

### S5.1 — No room selected

```
┌──────┬───────────────┬──────────────────────────────────────────┐
│ rail │  room list    │                                          │
│      │               │            ✦  (brand-tinted mark)        │
│  ◉   │  …            │        Pick up where you left off        │
│  ◯   │               │   ┌────────────┐ ┌────────────┐          │
│  ◯   │               │   │ Alice      │ │ Dev room   │          │
│      │               │   │ 2h ago     │ │ yesterday  │          │
│      │               │   └────────────┘ └────────────┘          │
└──────┴───────────────┴──────────────────────────────────────────┘
```

Background is chat colour with a very faint brand radial tint (5 % opacity) behind the mark.
Show up to 4 recent rooms as 240 × 88 cards (`mod-subtle`, 12 radius, hover → `mod-hover`).

**Rail destinations:** Home (this view) · Friends *(stub, W24)* · Inbox *(stub, W28)* · pins ·
invite · settings. See `06-yapyap-mapping.md` §2.

**Data:** `rooms.rooms`, `messaging.roomPreview` per room, local `lastOpenedAt`.

**States:** empty → no cards, single primary "Start a conversation"; loading → skeleton cards.

### S5.2 — Room chat

Already specified in `02` (layout) and `03` §1 (behaviour). Additions:

- **Header**: room avatar 24, name 16 medium, divider dot, topic/preview 14 muted (single line,
  ellipsis). Right side: member-list toggle (24), search (24), overflow (24). On `Expanded` and
  below, search collapses into an icon.
- **Group rooms** show a stacked avatar; 1:1 shows a single avatar with status ring.
- **Announcement bar** slot under the header (uses `#8ebefa` @ 8 % fill) for sync warnings.

---

## S6 — Right panel

### S6.1 — Members (group rooms)

```
Members — 5
── Online ─ 3 ──────────────
 ● Alice        Owner
 ● Bob          Admin
 ● Carol
── Offline ─ 2 ─────────────
 ○ Dave         last seen 2h
 ○ Eve
```

- Group header 12 px semibold uppercase muted, 16 px top padding.
- Rows 40 px, avatar 32 with status dot, name 14 medium, role chip 12 (`Owner` brand, `Admin`
  `mod-subtle`).
- Hover → `mod-subtle`, click → profile popout.
- Owners/admins get a context menu with the admin actions they're allowed to perform.

**Data:** `rooms.room(roomId).members` joined with `identity.accounts`; live via `rooms.rooms`.

### S6.2 — Profile (1:1)

Banner 120 (deterministic gradient), avatar 80 overlapping by 40, name 20 semibold, handle 14
muted, presence line, then sections separated by 1 px `mod-subtle`:

- `Member since` (from `AccountView` if available; otherwise omit the section entirely)
- `Devices` — list of `DeviceView` with type icon + last seen (self only)
- Actions: `Remove friend` and `Block` render as **stub actions** with the `Soon` chip (`03-ux-patterns.md` §8.3) — the
  relationship model is planned, so the shelf exists now.

---

## S7 — Create room

Modal (medium 602) / full-screen sheet on Compact.

```
┌───────────────────────────────────────────────┐
│  Create a room                            ×   │
├───────────────────────────────────────────────┤
│  To:  [ Alice × ] [ Bob × ]  [ + Add ]        │
│                                               │
│  Room name (optional)                         │
│  ┌─────────────────────────────────────────┐  │
│  │                                         │  │
│  └─────────────────────────────────────────┘  │
│                                               │
│                          [ Create ]           │
└───────────────────────────────────────────────┘
```

- Member picker: search + multi-select list of `identity.accounts` (exclude self, show role chip).
- Create disabled while the member set is empty.
- `CreateRoomResult.Refused(UNKNOWN_MEMBER)` → inline "That account isn't synced yet."
- After `Created`, close and navigate to the room.

---

## S8 — Settings

Full-window modal on desktop (`min(1400 dp, 100 % − 80) × min(945 dp, 100 % − 80)`, radius 12,
centred), full-screen on Compact. Two columns: nav 253 dp, content fluid with a **max 740 dp**
column, left-aligned with 24 px gutters.

```
┌──────────────────────────────────────────────────────────────┐
│ Alice  Edit profile            Appearance                 ×  │
│ [ search settings ]                                          │
│                                                              │
│ ACCOUNT                       Theme                          │
│  Account                      Same as device theme      (o)  │
│  Devices                      Default themes                 │
│  Privacy                      ┌──┐┌──┐┌──┐                   │
│                                                              │
│ APP                           Message Display                │
│  Appearance                   Cozy      (•)                  │
│  Notifications                Compact   ( )                  │
│  Keybinds                     Font size  ──────●──  16px     │
│                                                              │
│ NETWORK                       ───────────────────────────    │
│  Members                                                     │
│  Advanced                                                    │
└──────────────────────────────────────────────────────────────┘
```

Rules:

- Nav rows 32 px, 8 px radius, icon 20 + label 16 medium; selected = `mod-strong` + white text,
  plus a 2 px brand bar on the left edge of the content pane (not the row).
- Section labels in the nav: 12 px semibold uppercase muted, 24 px top margin.
- Content rows: label 16 medium + description 14 muted on the left, control right-aligned;
  min 48 px tall; rows separated by 24 px, not by lines.
- Sub-sections use a 1 px `mod-subtle` rule with a 16 px title above.
- The whole content area scrolls; the nav does not.
- `Esc` closes; unsaved changes apply instantly (no Save button) — Discord's model.

Full catalogue in [`05-settings.md`](05-settings.md).

---

## S9 — Network & admin (inside settings)

**Members** — table-like list of `AccountView`: avatar, name, role chip, status chip, device count.
Row menu: `Grant admin`, `Revoke admin`, `Ban account` (all gated on `localIsAdmin` and on the
target's role; owner rows are read-only).

**Devices** — two sections: `This device` (type, last seen, `Remove this device`), `My devices`
(each with `Remove`), then `Remove my account` in a danger zone. Last-device removal asks for the
recovery key.

**Advanced** — the backend settings from `config.settings`, rendered generically (see
`05-settings.md` §Backend settings).

---

## S10 — Confirmations & toasts

Covered in `03` §4.5–4.6. Copy rules:

- Title = the consequence (`Remove Bob from the network`), not `Are you sure?`.
- Body = what will happen, including cascade (`This also removes Bob's 3 devices.`).
- Confirm button = the verb (`Remove`), danger-styled.
- Cancel = secondary, left of confirm.

---

## Screen → runtime summary

| Screen | Reads                                                                                | Writes                                                   |
|--------|--------------------------------------------------------------------------------------|----------------------------------------------------------|
| S0     | `orchestrator.state`                                                                 | `resetApp()`                                             |
| S1     | `onboardingState`, setup result                                                      | `completeSetup(intent)`                                  |
| S3     | —                                                                                    | `onboarding.sponsorNewcomer`, `newcomerCancelOnboarding` |
| S5     | `rooms.rooms`, `messaging.roomPreview`, `identity.accounts`, `messaging.typingState` | —                                                        |
| S5.2   | `openRoom()` window, `getMessage`                                                    | `sendMessage`, `setTyping`                               |
| S6     | `rooms.room()`, `identity.account()`                                                 | `rooms.add/remove/grant/revoke`, `leaveRoom`             |
| S7     | `identity.accounts`                                                                  | `rooms.createRoom`                                       |
| S8     | `config.settings` (advanced)                                                         | `config.update`, local UI prefs                          |
| S9     | `identity.accounts`, `admin.localIsAdmin`                                            | `admin.*`, `account.*`                                   |
