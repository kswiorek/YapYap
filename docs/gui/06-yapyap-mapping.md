# 06 — Discord → YapYap mapping

The GUI may only touch `OrchestratorFactory`, `Orchestrator`, `OrchestratorRuntime`, the seven
service interfaces, and the vocabulary types in `org.yapyap.protocol` / `org.yapyap.config`.
`GuiApiBoundaryTest` fails the build on anything else. **If a screen needs data that isn't
reachable, that's a missing runtime API — ask for it, don't reach into `:core` internals.**

See [`docs/gui-api.md`](../gui-api.md) for the canonical boundary description.

---

## 1. Concept map

`state` follows `08-feature-readiness.md`: **ready** (backed today), **stub** (page exists, content
pending), **out** (never).

| Discord                                                     | state                       | YapYap                                         | Notes                                                        |
|-------------------------------------------------------------|-----------------------------|------------------------------------------------|--------------------------------------------------------------|
| Server / guild                                              | stub                        | —                                              | No such concept yet. Render W25 with one implicit group. §2. |
| Server icon rail                                            | ready                       | **Rail** (repurposed)                          | Networks/pins/destinations. §2.                              |
| Text channel                                                | ready                       | **Room** (`RoomType.TEXT_CHANNEL`)             | Flat list, categories stub.                                  |
| Voice channel                                               | stub                        | `RoomType.VOICE_CHANNEL`                       | No voice service. Render with a speaker icon + W29 stub.     |
| DM                                                          | ready                       | **Room** with 2 members, empty `name`          | Title = the other participant.                               |
| Group DM                                                    | ready                       | **Room** with >2 members                       | Title = `RoomDetails.name`, else participant names.          |
| Channel list                                                | ready                       | **Room list**                                  | 1:1.                                                         |
| Message                                                     | ready                       | `MessageDisplayItem.Text`                      |                                                              |
| Attachment                                                  | ready (card) / stub (bytes) | `MessageDisplayItem.File`                      | Metadata only — see §4 gap 1.                                |
| Missing messages                                            | ready                       | `MessageDisplayItem.Gap`                       | Render a "not loaded" divider.                               |
| Member list                                                 | ready                       | `RoomDetails.members` + `identity.accounts`    | Join by `AccountId`.                                         |
| Role (Owner/Admin/Member)                                   | ready                       | `AccountRole`, `RoomMemberRole`                | Owner implies admin.                                         |
| Online / offline                                            | ready                       | `AvailabilityLabel` ONLINE / OFFLINE / UNKNOWN |                                                              |
| Idle / DND / custom status / activity                       | stub                        | —                                              | Status picker lists them with `Soon`; never invent a state.  |
| "Last seen"                                                 | ready                       | `AccountAvailability.lastSeen`                 | Render relative.                                             |
| Avatar / banner                                             | stub                        | —                                              | Generated from `accountId`; keep final geometry.             |
| Unread / mention badges                                     | stub (local)                | —                                              | **Local-only** (§3).                                         |
| Read receipts                                               | out                         | —                                              | Not in the protocol. Don't imply them.                       |
| Typing indicator                                            | ready                       | `MessagingService.typingState`                 | Real.                                                        |
| Reactions / GIFs / stickers / custom emoji                  | stub                        | —                                              | Slots + stub pickers (§5).                                   |
| Replies / threads / edits / deletes / pins / search         | stub                        | —                                              | Menu items and rails exist with `Soon`.                      |
| Embeds / link previews                                      | stub                        | —                                              | Plain link for now.                                          |
| Friends / blocking / friend requests                        | stub                        | —                                              | W24 page with the real tabs.                                 |
| Voice / video / screen share                                | stub                        | —                                              | W29 banner.                                                  |
| Profile editing                                             | stub                        | —                                              | W30.                                                         |
| Multiple accounts                                           | stub                        | —                                              | Rail slot reserved.                                          |
| Settings → Account                                          | ready                       | read-only identity                             | No profile editing yet.                                      |
| Nitro / Shop / Quests / Boosts / Billing / Discovery / Apps | out                         | —                                              | Never render, never stub.                                    |

---

## 2. What to do with the rail

Discord's rail is a list of servers. YapYap has one network and a flat room list. Three options:

**A. Destinations + pins (recommended).**

```
┌────┐
│ ✦  │  Home — all rooms                 ← selected by default
│ ☺  │  Friends                          ← STUB (W24)
│ 🔔 │  Inbox                            ← STUB (W28)
├────┤
│ A  │  pinned / favourite rooms         ← local-only pin
│ B  │
│ C  │
├────┤
│ +  │  invite (QR)
│ ⚙  │  settings
└────┘
```

Why: it reproduces Discord's exact rail rhythm — *destinations on top, a scrollable middle, add and
settings pinned to the bottom* — so the muscle memory transfers even though YapYap has no servers.
The middle section is the part that grows into servers/guilds later (W25); until then it holds pins.
Pins are a UI preference, so no backend work.

**B. Hide the rail on desktop** and put everything in the room list. Cheaper, but the shell stops
reading as Discord and you lose a natural place for invite/settings.

**C. Invent a "space"/folder concept.** Don't — it's a protocol change, and it will conflict with
whatever grouping lands later.

Rules for A:

- 40 px round items, 8 px gaps, 16 px padding, 32 dp between the top group and the pins.
- 4 px white pill on the left edge for the selected item; 8 px white dot for unread.
- Tooltip on hover, anchored right; no badges on the rail except a mention dot.
- `Friends` and `Inbox` are **stub destinations** — they switch the content pane to W24 / W28, and
  both carry the `Soon` chip. Do not omit them: Discord users reach for that second circle by
  reflex, and an empty slot there reads as a bug.
- Never more than one item per room — no nesting.

---

## 3. State that only exists in the GUI

These are **not** backend features. Store them in `UiPreferences` (`05-settings.md` §1) and be
honest in the UI (no fake server-side semantics).

| Thing                 | Where it lives       | Consequence                                       |
|-----------------------|----------------------|---------------------------------------------------|
| `lastReadAt` per room | UI prefs             | Unread dots are per-device. Two devices disagree. |
| Pinned rooms          | UI prefs             | Rail pins are per-device.                         |
| Muted rooms           | UI prefs             | No push suppression server-side — only visual.    |
| Draft text per room   | UI prefs (debounced) | Nice-to-have; Discord has it.                     |
| Scroll position       | memory only          | Reset on app restart.                             |
| Room ordering         | computed             | `roomPreview.timestamp`, then name.               |

---

## 4. Runtime gaps behind the stubs

These are needed by the screens described above but are **not** exposed today. Each is a request to
the backend, not something to work around in the GUI — and until it lands, the corresponding page or
action is a **stub** rather than an omission.

1. **File transfer (send + fetch).** `MessageDisplayItem.File` carries `fileId`, `fileName`,
   `fileSize`, but there is no `sendFile(...)` and no way to obtain bytes for a `fileId`. The GUI
   can render the card; it cannot open or download. Needs a `FileTransferService` (or methods on
   `MessagingService`): send, progress, open/stream.
2. **Room rename.** `RoomDetails.name` is readable; `RoomService` has no rename. The "Edit group"
   entry renders as a stub action (`Soon`) until this exists.
3. **Message search.** `openRoom` windows are paginated; there's no query API. v1 search filters
   only loaded messages and must say so ("Search loaded messages").
4. **Mention notifications.** No mention event/annotation — see §3 of `05-settings.md`.
5. **Reactions, GIFs, embeds** — planned; stubs only (`08-feature-readiness.md`).

Everything else in this pack is implementable against the current runtime.

---

## 5. Seams to leave for the future features

The backend isn't there yet, but the UI shelf is: leave the hook points so a landing feature is a
new branch rather than a rewrite.

```kotlin
@Composable
fun MessageBody(item: MessageDisplayItem) = when (item) {
    is MessageDisplayItem.Text -> TextBody(item.text)
    is MessageDisplayItem.File -> FileCard(item)
    is MessageDisplayItem.Gap  -> GapDivider(item)
    // future: is MessageDisplayItem.ReactionSummary -> ...
}
```

- Keep `MessageDisplayItem` handling in **one** `when`, exhaustive, so a new sealed subtype is a
  compile error rather than a silent blank.
- The hover toolbar is a `List<MessageAction>`; currently it holds `More`, later it grows the
  reaction/reply/forward entries (`03-ux-patterns.md` §1.3).
- Reserve the reaction row under the message (0 dp when empty) so pills don't reflow the list.
- Every planned feature has a page or a `Soon` action today (`08-feature-readiness.md` §5). When
  it lands, delete the placeholder content — keep the page.
- Don't write "not supported" copy anywhere; the honest copy is "coming in a later build".

---

## 6. Data availability cheat-sheet

| UI element             | Source                        | Live?                              |
|------------------------|-------------------------------|------------------------------------|
| Room list              | `rooms.rooms`                 | yes (StateFlow)                    |
| Room name/members      | same `RoomDetails`            | yes                                |
| Last message + time    | `messaging.roomPreview`       | re-pull on `incomingMessageEvents` |
| Message list           | `openRoom()` → `displayItems` | yes                                |
| Older messages         | `RoomMessageWindow.loadOlder` | paged                              |
| Typing                 | `messaging.typingState`       | yes                                |
| Accounts/names         | `identity.accounts`           | yes                                |
| Own account            | `identity.localAccount`       | yes                                |
| Presence               | `AccountView.availability`    | yes                                |
| Devices                | `AccountView.devices`         | yes                                |
| Admin flag             | `admin.localIsAdmin`          | yes                                |
| Backend settings       | `config.settings`             | yes                                |
| Orchestrator lifecycle | `orchestrator.state`          | yes                                |
| Newcomer progress      | `onboarding.newcomerState`    | yes                                |

---

## 7. Refusal → copy

Domain refusals are values; never surface a raw enum. One `RefusalText` mapper:

| Refusal                             | User copy                                            |
|-------------------------------------|------------------------------------------------------|
| `SendRefusal.TooLarge`              | "That message is too long (`n` bytes over)."         |
| `SendRefusal.NotMember`             | "You're no longer in this room."                     |
| `SendRefusal.HistoryIncomplete`     | "Still syncing — try again in a moment."             |
| `CreateRoomRefusal.EMPTY_MEMBERS`   | "Pick at least one person."                          |
| `CreateRoomRefusal.UNKNOWN_MEMBER`  | "One of those accounts isn't synced yet."            |
| `RoomEventRefusal.NotAdmin`         | "Only admins can do that."                           |
| `RoomEventRefusal.OwnerIrrevocable` | "The owner can't be removed or demoted."             |
| `RoomEventRefusal.NotReady`         | "Still syncing. Try again shortly."                  |
| `RoomEventRefusal.InvalidSuccessor` | "Pick an active member as the new owner."            |
| `RecoveryKeyRequired`               | "Enter your recovery key to remove the last device." |

Infrastructure exceptions are different: catch at the screen boundary, show a toast, log, keep the
user's input.
