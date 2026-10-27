# 08 — Feature readiness: ready, stub, out

YapYap is **early alpha** for a product whose target is "Discord, self-hosted". Almost every Discord
feature is going to exist eventually. So the GUI is not allowed to simply omit them — an app that
keeps changing shape as features land is worse than one that always had the shelf.

Three states, applied consistently:

| State     | Meaning                                                                                                   | What the GUI does                                                                                       |
|-----------|-----------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| **READY** | The runtime backs it today.                                                                               | Render it for real.                                                                                     |
| **STUB**  | UX is designed and planned; the backend isn't there yet.                                                  | Render the **final chrome** with placeholder content and a `Soon` badge. Reachable, not broken-looking. |
| **OUT**   | No plan: contradicts the product model (centralised commerce/discovery) or is replaced by something else. | Don't build it, don't stub it.                                                                          |

Rules for a stub:

1. **Same layout as the finished feature.** If the real page will have tabs, the stub has tabs.
2. **No fake data.** No invented friends, channels, unread counts or "online" users. Placeholder
   text only.
3. **No dead controls.** Buttons that can't work are either absent or carry the `Soon` chip and
   explain themselves when activated (`03-ux-patterns.md` §8).
4. **One flag per feature.** Everything is gated on `Capabilities` (§4), so flipping it to `true`
   should reveal the real implementation rather than require a rewrite.

---

## 1. Capability model

```kotlin
@Immutable
data class Capabilities(
    // READY today
    val roomChat: Boolean = true,
    val typing: Boolean = true,
    val attachmentCards: Boolean = true,   // metadata only
    // STUB today
    val realAvatars: Boolean = false,
    val avatarsUpload: Boolean = false,
    val attachments: Boolean = false,      // send + download bytes
    val inlinePreviews: Boolean = false,
    val reactions: Boolean = false,
    val gifs: Boolean = false,
    val stickers: Boolean = false,
    val customEmoji: Boolean = false,
    val embeds: Boolean = false,
    val mentions: Boolean = false,
    val messageEditing: Boolean = false,
    val messageDeletion: Boolean = false,
    val replies: Boolean = false,
    val threads: Boolean = false,
    val pins: Boolean = false,
    val search: Boolean = false,
    val friends: Boolean = false,
    val guilds: Boolean = false,
    val categories: Boolean = false,
    val voice: Boolean = false,
    val video: Boolean = false,
    val screenShare: Boolean = false,
    val richPresence: Boolean = false,
    val statusMessage: Boolean = false,
    val profileEditing: Boolean = false,
    val multipleAccounts: Boolean = false,
    val lightTheme: Boolean = false,
    val customKeybinds: Boolean = false,
    val pushNotifications: Boolean = false,
    val localisation: Boolean = false,
)
```

Defaults reflect **today**; screens read the flag and choose `RealThing()` vs `StubPage()`.

---

## 2. Readiness matrix

### Identity & presentation

| Feature                                        | State     | Alpha behaviour                                                            | Stub content                                              | Seam                                            |
|------------------------------------------------|-----------|----------------------------------------------------------------------------|-----------------------------------------------------------|-------------------------------------------------|
| Avatars (view)                                 | **STUB**  | Deterministic gradient + initials, correct circle/ring/status-dot geometry | — (already looks final)                                   | `AvatarSource`: `Generated(id)` → `Remote(url)` |
| Avatar upload / crop                           | **STUB**  | —                                                                          | "Change avatar" row in profile edit, `Soon` chip          | `ProfileEditor`                                 |
| Banners                                        | **STUB**  | Deterministic gradient, 105 dp, same crop                                  | —                                                         | `BannerSource`                                  |
| Display name / handle                          | **READY** | Read-only from chain                                                       | —                                                         | `AccountView`                                   |
| Profile editing (bio, pronouns, links, badges) | **STUB**  | Profile popout shows the sections with placeholder lines                   | "Edit profile" opens a page with disabled fields + `Soon` | Section list, not fixed layout                  |
| Badges                                         | **STUB**  | One "Alpha" chip only                                                      | Empty badge row                                           | `badges: List<Badge>`                           |
| Multiple accounts                              | **STUB**  | Rail top slot exists, single account                                       | "Add another account" item with `Soon`                    | Rail slot                                       |

### Messages

| Feature                       | State     | Alpha behaviour                                                                | Stub content                           | Seam                           |
|-------------------------------|-----------|--------------------------------------------------------------------------------|----------------------------------------|--------------------------------|
| Text                          | **READY** | Links + inline code; parser is minimal                                         | —                                      | `MessageBody`                  |
| Attachment card               | **READY** | Name, size, icon; no download                                                  | —                                      | `FileCard`                     |
| Attachment send/download      | **STUB**  | `+` opens picker, then a `Soon` popover                                        | "Sending files is coming soon"         | `FileTransferService`          |
| Inline previews (image/video) | **STUB**  | Card only                                                                      | Preview slot reserved, 0 dp            | `PreviewRenderer`              |
| Reactions                     | **STUB**  | Reaction row reserved, 0 dp; hover toolbar has one disabled-with-`Soon` action | Empty state inside the reaction popout | `ReactionRow`, `MessageAction` |
| Replies                       | **STUB**  | `Reply` menu item with `Soon`                                                  | —                                      | Message header slot            |
| Threads                       | **STUB**  | "Open thread" item with `Soon`                                                 | —                                      | Rooms with `parentRoomId`      |
| Edits / deletes               | **STUB**  | `Edit`/`Delete` menu items with `Soon`                                         | —                                      | sealed `MessageDisplayItem`    |
| Pins                          | **STUB**  | Header overflow → "Pinned messages" opens stub page                            | Stub page W26                          | `PinnedStore`                  |
| Search                        | **STUB**  | Header search icon opens stub panel                                            | Stub panel W20                         | `SearchService`                |
| Mentions                      | **STUB**  | Mention rows hidden; `@` autocomplete shows `Soon`                             | —                                      | Annotations                    |
| GIFs / stickers               | **STUB**  | Composer buttons hidden until the popout stubs exist                           | Stub popouts W19 sibling tabs          | Picker tabs                    |
| Custom emoji                  | **STUB**  | Emoji picker exists, single "Emoji" tab                                        | Second tab "Server" with `Soon`        | Picker tabs                    |
| Embeds / link previews        | **STUB**  | Plain link, no card                                                            | —                                      | `EmbedRenderer`                |

### Presence & social

| Feature                               | State            | Alpha behaviour                                                   | Stub content                         | Seam                            |
|---------------------------------------|------------------|-------------------------------------------------------------------|--------------------------------------|---------------------------------|
| Presence (online/offline/unknown)     | **READY**        | Real                                                              | —                                    | `AvailabilityLabel`             |
| Idle / DND / custom status / activity | **STUB**         | Dot renders only the three real states; status line says "Online" | Status picker lists them with `Soon` | Enum mapping in one place       |
| Typing                                | **READY**        | Real                                                              | —                                    | —                               |
| Unread / read state                   | **STUB** (local) | Local `lastReadAt`, dot only                                      | —                                    | `ReadStateStore` → remote later |
| Mention badges                        | **STUB**         | Badge slot exists, always 0                                       | —                                    | `RoomRow(mentionCount)`         |
| Friends                               | **STUB**         | Rail/menu entry opens W24                                         | Stub page with the real 4 tabs       | `FriendsService`                |
| Add friend / requests                 | **STUB**         | Tab exists inside W24                                             | Empty state + `Soon` input           | `FriendsService`                |
| Blocking                              | **STUB**         | Member menu item with `Soon`                                      | —                                    | `ModerationService`             |
| Notifications (desktop)               | **READY**        | Local desktop notifications                                       | —                                    | `NotificationSink`              |
| Notifications (push / background)     | **STUB**         | —                                                                 | Settings row with `Soon`             | Push pipeline                   |
| Notification sounds                   | **STUB**         | —                                                                 | Sound rows with `Soon`               | `SoundPlayer`                   |

### Structure

| Feature                            | State              | Alpha behaviour                                                                   | Stub content                                     | Seam                     |
|------------------------------------|--------------------|-----------------------------------------------------------------------------------|--------------------------------------------------|--------------------------|
| Rooms (flat list)                  | **READY**          | Real                                                                              | —                                                | `RoomListModel`          |
| Servers / guilds                   | **STUB**           | Rail shows one network icon; no server switching                                  | "Add a server" item with `Soon`; W25 page exists | `Grouping` function      |
| Categories                         | **STUB**           | One implicit group "Rooms" + "Pinned"                                             | Collapsible header renders, only one exists      | `Grouping` function      |
| Channel topics                     | **STUB**           | Header shows the last-message preview instead                                     | Topic slot, editable later                       | Header subtitle          |
| Voice channels                     | **STUB**           | Render with a speaker icon; opening shows the chat + a "Voice coming soon" banner | Voice room stub view W29                         | `RoomType.VOICE_CHANNEL` |
| Stage channels                     | **STUB**           | Same as voice with a stage icon                                                   | W29 stage variant                                | `RoomType`               |
| Forum / announcement channels      | **STUB**           | Render with their icon in the list; opening behaves like a text room + `Soon`     | Placeholder layout inside the room               | `RoomType`               |
| Video / screen share               | **STUB**           | Same banner                                                                       | —                                                | WebRTC tracks            |
| Roles & permissions                | **READY** (coarse) | Owner / admin / member                                                            | Granular ACLs listed with `Soon`                 | `PermissionGate`         |
| Server settings                    | **STUB**           | —                                                                                 | W25b with the real sections, all stubbed         | `GuildSettings`          |
| Server events                      | **STUB**           | —                                                                                 | Events pane inside W25b                          | `EventService`           |
| Server onboarding / welcome screen | **STUB**           | —                                                                                 | Welcome editor page from the server header menu  | `OnboardingConfig`       |

### Appearance & platform

| Feature                               | State     | Alpha behaviour                                                  | Stub content                   | Seam                        |
|---------------------------------------|-----------|------------------------------------------------------------------|--------------------------------|-----------------------------|
| Dark theme                            | **READY** | Real                                                             | —                              | `YapColors`                 |
| Light theme / system                  | **STUB**  | Toggle exists; selecting Light shows `Soon` toast and stays dark | Theme swatches render          | second `YapColors` instance |
| Custom themes / accent                | **STUB**  | Accent swatches work; "Custom" swatch with `Soon`                | —                              | palette generation          |
| Density / message display / text size | **READY** | Real                                                             | —                              | prefs                       |
| Localisation                          | **STUB**  | English only                                                     | Language row with `Soon`       | string resources            |
| Custom keybinds                       | **STUB**  | Read-only table                                                  | "Customise" button with `Soon` | keymap store                |

---

## 3. OUT — do not build, do not stub

These are not "not yet". They contradict the product (centralised commerce or discovery) or are
replaced by YapYap's own flow. No placeholders: a stub here would advertise something that will
never exist.

| Surface                                                                   | Why                                              |
|---------------------------------------------------------------------------|--------------------------------------------------|
| Login / register / QR login / passkey                                     | Replaced by the setup wizard (S1)                |
| Nitro, Shop, Quests, Server Boost, Subscriptions, Gift Inventory, Billing | Commerce does not exist in a self-hosted network |
| Server Discovery / Explore servers                                        | No central directory, by design                  |
| App Directory, Connected Apps, OAuth authorisations                       | No third-party platform                          |
| Family Centre, teen safety settings                                       | No accounts-for-minors model                     |
| Streamer mode, app icon picker, game overlay                              | Desktop-client-specific, no backend              |
| Legacy username / discriminator (`name#1234`)                             | Identity is key-based                            |
| Game store / library integration                                          | Not in scope                                     |

If someone asks for one of these later, it's a product decision — not a GUI gap.

---

## 4. Where the flags live

- One `Capabilities` instance at the composition root, exposed via `LocalCapabilities`.
- Defaults are the constants above; later, read from the runtime where a service exists (e.g. `runtime.files != null`).
- **Never** branch on build type, and never scatter booleans through screens — a screen receives
  `capabilities` and picks `Real()` vs `Stub()` **once**, at the top.
- When a backend lands: flip the flag, delete the stub content for that feature, keep the page.
  The upgrade checklist is §6 of this file.

---

## 5. Stub screen inventory

Every stub page is listed with its final chrome in [`10-windows.md`](10-windows.md) §17. Summary:

| Page                      | Reached from                   | Chrome shown today                                                                             |
|---------------------------|--------------------------------|------------------------------------------------------------------------------------------------|
| Friends (W24)             | rail destination / menu        | Tabs: Online · All · Pending · Blocked · Add Friend                                            |
| Server / guild view (W25) | rail "Add a server"            | Channel list with categories, server header, member list                                       |
| Server settings (W25b)    | server header menu             | Overview · Roles · Emoji · Stickers · Events · Moderation · Audit Log · Integrations · Invites |
| Pinned messages (W26)     | room header overflow           | Search field + empty list                                                                      |
| Threads (W27)             | message menu / room header     | Thread side panel with header                                                                  |
| Search results (W20)      | room header search             | Query field + filter chips + empty results                                                     |
| Inbox / mentions (W28)    | rail bell                      | Tabs: Unread · Mentions · Scheduled                                                            |
| Voice room (W29)          | opening a `VOICE_CHANNEL` room | Participant grid placeholders + connect bar                                                    |
| Profile editor (W30)      | profile popout → Edit          | Avatar/banner slots, bio/pronouns/links fields                                                 |
| Server onboarding (W25c)  | server header menu             | Welcome-screen editor layout                                                                   |

Sub-pages of W25b are not separate windows — they are stub panes inside the same modal, exactly like
the real settings sub-pages in W8.

---

## 6. Upgrade checklist (when a backend feature lands)

1. Flip the capability flag; run the app; list every screen that changed.
2. Replace the *source* (generator → real data) — not the layout.
3. Keep the generated fallback wherever real data can still be missing (offline, unknown author).
4. Add new states to the existing component API (`RoomRow(mentionCount = 0)`), never fork it.
5. Delete the stub content for that page but keep the page; update §2 and `05-settings.md`.
