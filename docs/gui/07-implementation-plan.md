# YapYap GUI — Early-Alpha Implementation Plan (Compose Multiplatform)

Status: final, agreed with the product owner (plan-mode discussion, Oct 2026).
Purpose: handoff for an implementation agent. The authoritative specs are the design pack in
`docs/gui/` — this plan records **what to build, in what order, and every decision made on top
of the pack**. Where this plan and the pack disagree, this plan wins (owner-approved deltas).

Primary inputs:

- `docs/gui/README.md` — reading order, fidelity contract, legal constraints
- `docs/gui/01…10` — visual language, layout/adaptive, UX patterns, screens, settings, mapping,
  readiness, component states, window inventory
- `docs/gui/reference/YapTheme.kt` — copy into the project verbatim
- `docs/gui-api.md` — the runtime boundary (three orchestrator types + seven services +
  vocabulary); `composeApp/src/jvmTest/kotlin/org/yapyap/GuiApiBoundaryTest.kt` enforces it

## 0. Ground rules

- **`:core` is untouchable.** Zero edits to core sources or build. All GUI code lives in
  `:composeApp` (packages `org.yapyap.gui.*` — outside the boundary test's core-package list).
  A missing runtime API is **raised to the owner**, never worked around.
- Root-level changes allowed: `gradle/libs.versions.toml`, root `build.gradle.kts` plugin
  registration, `composeApp/build.gradle.kts`, `composeApp` sources, `.run` configs,
  Android `AndroidManifest.xml` (Application class), packaging config.
- Fidelity contract per `docs/gui/README.md` (must-match list) and
  `09-component-states.md` (state matrix) are non-negotiable.
- Stubs follow `03-ux-patterns.md` §8: real chrome, placeholder content, `Soon` chip/action,
  never fake data, never dead-looking controls.

## 1. Locked decisions (owner-approved)

| Decision                | Choice                                                                                                                                                                                                                                                                                                                                                                                            |
|-------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Scope                   | Full pack **except** W25/W25b (Server view & Server settings). "Add a server" rail item shows the `Soon` toast instead.                                                                                                                                                                                                                                                                           |
| Desktop chrome          | Custom 32 dp title bar per `10-windows.md` §0: undecorated window, drag region, Windows/Linux min/max/close (46×32, close hover `#d22d39`), double-click = maximise, **hand-rolled edge-resize**. Min 360×480, default 1280×800 restored from prefs.                                                                                                                                              |
| Invites (W5/S3)         | Both forms side by side: rendered **QR** (qrose, byte-payload mode) + selectable **base64 text** with `Copy invite`. Sponsor side onboards via paste of the same base64. Camera scanning deferred to the mobile milestone.                                                                                                                                                                        |
| Connection banner (W18) | **Dropped entirely.** Store-and-forward makes it near-meaningless; revisit in the A/V era when session state exists.                                                                                                                                                                                                                                                                              |
| Distribution            | Portable-first: `createDistributable` output (bundled jlink JRE) + zip step = "installer where the user picks the directory". Keep Dmg/Msi/Deb native installers. No `.jar` runs for users.                                                                                                                                                                                                       |
| Data directory          | Resolution order (first match): 1) `YAPYAP_DATA` env / `-Dyapyap.dataDir` (multi-instance dev); 2) packaged app → `<compose.application.compose.dir>/data`; 3) app root not writable (MSI/Deb → Program Files, `/usr`) → `~/.yapyap`; 4) dev `gradlew run` → `./.yapyap`. Effective path always shown in Settings → Advanced. Android: `filesDir/yapyap`; iOS: app-support dir.                   |
| Navigation              | **Voyager** (`cafe.adriel.voyager:voyager-navigator` + transitions). Fallback if incompatible with CMP 1.10.3/Kotlin 2.3.20: JetBrains `org.jetbrains.androidx.navigation:navigation-compose`. Decide in the M1 spike.                                                                                                                                                                            |
| Markdown                | **Library-powered**: `com.mikepenz:multiplatform-markdown-renderer` (core module, no Material dep) parsing via `org.jetbrains:markdown`, + a thin **Discord dialect layer** (§5). Full Discord formatting set. Syntax highlighting = optional M4 stretch (lib integration, extra dep).                                                                                                            |
| Device names            | Deterministic human-readable names from `PeerId` (§6). **Always** show the full `PeerId` next to the name in `bodyXs` muted, copyable.                                                                                                                                                                                                                                                            |
| Mentions                | **Real GUI feature, id-based** (§7). Backend carries plain text only; detection, rendering, highlighting, notification are GUI concerns. Mobile push deferred (needs backend to fetch/decrypt the real message).                                                                                                                                                                                  |
| Inline embeds           | **Real GUI feature (M5), not a stub** (§7b): the GUI fetches public web content directly (Coil 3 + Ktor) — displaying public data is a display concern; core stays out. URL-hash file cache under `dataDirectory/gui-cache/embeds/`; sender suppression `<url>`; receiver per-embed X / "Hide preview"; fetch-on-view; global opt-out toggle. Attachments (files-as-messages) remain core's stub. |
| Setup wizard            | Forms match the API exactly: Genesis / NewAccountFirstDevice → display name (2–32 chars); AddDeviceToExistingAccount → no fields (straight to its QR); ImportAccountRecoveryKey → recovery key + bootstrap endpoint (device id, onion address, port). The pack's "device name" field is **dropped** (no API).                                                                                     |
| Theme                   | Copy `docs/gui/reference/YapTheme.kt` into `org.yapyap.gui.theme` verbatim + `YapTheme {}` wrapper. Inter (OFL) bundled via composeResources with system-sans fallback. Icons: Lucide (ISC) path data in code, 2 dp stroke, one `YapIcons` object + NOTICE file.                                                                                                                                  |
| Emoji picker (W19)      | Unicode-only, shared `:shortcode → emoji` table with the markdown transformer.                                                                                                                                                                                                                                                                                                                    |

## 2. Architecture

### Platform glue

Process-level **`YapSession`** — creates `OrchestratorFactory(dataDir, NodeMode.FULL_CLIENT).create()`,
owns the app `CoroutineScope`, `UiPreferencesController`, `NotificationSink`, notification
sound player; `start()`/`stop()` map to orchestrator lifecycle:

- Desktop: created in `main()`'s `application {}`; `onCloseRequest` → graceful stop + exit.
- Android: `Application` subclass (Manifest edit) so rotation never recreates it.
- iOS: top-level `remember` in `MainViewController`.

`expect/actual` surface kept minimal: data-dir resolution, clipboard, open-folder,
desktop notification sink (AWT tray, best-effort per platform), save-file (recovery-key `.txt`), taskbar badge.

### Package layout (all `:composeApp`, commonMain unless noted)

```
org.yapyap.gui
  theme/        YapTheme.kt (copied) + wrapper + motion tokens + focus ring
  components/   Avatar (deterministic gradient+initials), StatusDot, SoonChip, YapButton,
                YapTextField, YapSwitch, YapSlider, Tooltip, ToastHost, ConfirmDialog,
                ModalHost, PopoverHost, ContextMenu, YapScrollbar, StubPage, StubEmpty
  navigation/   WindowSizeClass helper (Compact<600 / Medium600-1023 / Expanded1024-1439 / Wide≥1440)
                + Voyager screens
  session/      YapSession, orchestrator-state mapping
  state/        RoomsController, ChatController, SetupController, SettingsController,
                ReadStateStore (lastReadAt / pins / mutes / drafts)
  prefs/        UiPreferences @Serializable + UiPreferencesController
                (debounced 250 ms JSON in dataDirectory/ui-prefs.json, unknown-key survival,
                schemaVersion + migration hook)
  capabilities/ Capabilities + LocalCapabilities (real/stub gate per 08-feature-readiness §1)
  copy/         RefusalText (single refusal→copy mapper per 06-yapyap-mapping §7),
                stub toasts, empty-state copy
  markdown/     Discord dialect layer over mikepenz renderer (§5)
  mentions/     MentionMatcher + mention token grammar (§7)
  identity/     DeviceNames (§6), avatar/banner generation
  emoji/        Unicode emoji picker + shared name table
  icons/        YapIcons (Lucide path data)
  screens/
    boot/ setup/ reset/          S0, S1–S3, S4
    shell/                       S5: rail, room list, chat header, messages, composer,
                                 right panel, user panel
    createroom/ settings/        S7, S8/S9 (+ generic Setting renderer + Keybinds table)
    stubs/                       Friends W24, Inbox W28, Pinned W26, Threads W27,
                                 Search W20, Voice room W29, Profile editor W30
    quickswitcher/               W6
```

### State & Compose practices

- Service `StateFlow`s collected with `collectAsStateWithLifecycle()`; screens stateless,
  one-way data flow; `@Immutable` GUI models (`RoomListItem`, grouped `MessageRow`).
- `ChatController` (screen-scoped): `openRoom`/`close` tied to screen lifecycle;
  `displayItems` → grouped rows; optimistic pending rows keyed by local UUID, **deduped and
  replaced by `messageId`** when the real item lands; sticky-bottom via `derivedStateOf`
  (40 px threshold); anchor-preserving `loadOlder` (keyed `LazyColumn`, restore first-visible
  key + offset after prepend).
- Room list ordering per `03-ux-patterns.md` §2.1: local pins → `roomPreview.timestamp`
  desc → name; re-pull on every `incomingMessageEvents` and on room open.
- Read state is local (`ReadStateStore`) — per-device, no fake server semantics.
- Every destructive/admin flow uses `ConfirmDialog` (type-to-confirm where docs demand:
  remove account, remove last device, reset); refusal values → `RefusalText`; infra throws →
  toast + preserved input.
- Adaptive: ONE collapsing shell per `02-layout-and-adaptive.md` (Compact drawer + bottom
  nav + bottom sheets + long-press menus; Medium swap-stack + back affordance; Expanded
  overlay right panel; Wide docked panel). `safeDrawing`/`ime` insets, ≥48 dp touch targets,
  hover-only affordances gated on pointer input.
- Message content rendering is one exhaustive `when` over `MessageDisplayItem` (seam per
  `06-yapyap-mapping.md` §5).

## 3. Dependencies & build changes

Add to `gradle/libs.versions.toml` + `:composeApp`:

- `kotlinx-io-core` (the `Path` in `OrchestratorFactory`'s signature)
- `kotlinx-serialization-json` + serialization plugin (UiPreferences; version-aligned with core)
- `io.github.alexzhirkevich:qrose` (QR generation, byte payloads)
- `cafe.adriel.voyager:voyager-navigator` (+ `voyager-transitions`) — or JB navigation-compose fallback
- `com.mikepenz:multiplatform-markdown-renderer` (core only; feed it YapColors/YapType — no `-m2/-m3`)
- `kotlinx-datetime` (timezone/DST-correct `<t:>` formatting; aligns with `kotlin.time.Instant`)
- M5 (embeds): Coil 3 (`coil-compose` + `coil-network-ktor`; `coil-gif` where supported) — fetch,
  decode, memory cache, and the size-capped disk-cache GC in one ready-made package.

Toolchain caveat (M1 spike): the newest markdown-renderer builds target CMP 1.11 /
Kotlin 2.4 metadata; we are on CMP 1.10.3 / Kotlin 2.3.20 (+1 forward metadata compat should
hold). If resolution fails: either bump CMP in the catalog (GUI-side decision — core has no
Compose dependency) or pin an older renderer release aligned to 1.10. Voyager likewise.

## 4. Distribution & dev workflow

- Packaging: `nativeDistributions` kept (Dmg/Msi/Deb, packageName "YapYap", vendor/description
  filled in) + portable zip task over `createDistributable`.
- `.run` configs: "Desktop app", "Desktop app (instance B)" using `-Dyapyap.dataDir`
  overrides so two nodes (genesis + join) can chat on one desktop.
- Data-dir resolution per §1; always render the effective path in Settings → Advanced.

## 5. Markdown — Discord dialect on a ready-made parser

Parser/renderer: `multiplatform-markdown-renderer` (async `rememberMarkdownState`,
cache by content hash so message rows parse once per text change). Everything standard (bold/italic/combinations,
strikethrough via GFM, lists incl. 2-space indentation, autolinks,
masked links, inline code, escapes, fenced code, headers, quotes) = library behavior styled
to `YapColors`/`YapType`.

Our layer in `org.yapyap.gui.markdown`:

1. **`DiscordMarkdownTransformer`** (pre-parser string pass, **code-aware**: mask
   `` ` ``-spans and ``` ``` ```-blocks first, transform, restore — spoilers/mentions are
   negated inside code, per Discord):
    - `__underline__` → underline sentinels (**CommonMark would read `__` as bold** — must rewrite)
    - `||spoilers||` → spoiler sentinels
    - `<t:unixSeconds:style>` → timestamp sentinels
    - `>>>` (quote-to-end) → expand to per-line `>`
    - `-# subtext` → `######` (renderer maps h6 → subtext style)
    - `:shortcodes:` → emoji chars (shared table with the picker)
    - Sentinel chars are private-use-area; **strip unknown PUA from input first** so nobody can forge them
2. **Custom components/spans** (MarkdownComponents overrides): spoiler (tap-to-reveal +
   respects "Show spoiler content" pref), timestamp chip (all styles `t T d D f F R`; `R`
   relative on a refresh tick), mention pill (§7), code blocks on `#242429` inset with
   language label, quote accent bars, Discord header sizes (h1 big / h2 / h3 small),
   link color + underline pref.
3. Room-list previews and notification snippets render **stripped plain text** with mention
   ids substituted to display names. `Copy text` copies raw source (ids included) — matches Discord.

Supported grammar (from the live support article + Discord timestamp syntax):

| Category          | Syntax                                                                                                                                    |
|-------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| Emphasis          | `**bold**`, `*italic*` / `_italic_`, `***bold italic***`, `__underline__`, `~~strikethrough~~`, all `__**…**__` combinations, `\` escapes |
| Structure         | `# ## ###` headers, `-#` subtext, `-`/`*`/`1.` lists + 2-space indent, `>` / `>>>` block quotes (nested)                                  |
| Code              | `` `inline` `` + ``` ``` ``` fenced blocks with language label; syntax highlighting optional M4 stretch                                   |
| Links             | bare-URL autolinks, `[masked links](url)`                                                                                                 |
| Extras            | `\|\|spoilers\|\|`, `<t:unix:style>` dates, `:shortcodes:`                                                                                |
| Mentions/channels | `@<AccountId>` / `@everyone` / `#<RoomId>` — §7                                                                                           |

## 6. Device names

`DeviceNames.from(peerId)` → `"Amber Falcon"` style: curated versioned adjective × noun
word lists (~500k combinations; collisions irrelevant at network scale), deterministic via
SHA-256 of `PeerId.id` → two stable list indices. Golden tests pin output so names never
silently change between releases. **Every device row always shows the full `PeerId`**
(`bodyXs`, muted, copyable) beside the prominent name — no collision-detection logic.
Same deterministic-hash utility powers generated avatars/banners. Display-only sugar;
identity stays `DeviceView` (type icon + last seen alongside).

## 7. Mentions — id-based, fully GUI-side

Wire format in plain message text (backend never interprets it):

| Token                                                                                                        | Resolves to                                     | Renders                                                                                                              |
|--------------------------------------------------------------------------------------------------------------|-------------------------------------------------|----------------------------------------------------------------------------------------------------------------------|
| `@` + 64-hex (`AccountId.id` = SHA-256 hex of account signing key — rigid token, names can contain anything) | `identity.account(id)` live at composition time | mention pill (`#5865f2` @ 24 %, `#cdd7ff` text) with the account's display name; unknown → muted "Unknown user" pill |
| `@everyone` + boundary                                                                                       | keyword                                         | `@everyone` pill (group ping; `@here`/roles out — no role model)                                                     |
| `#` + UUID (`RoomId.value`)                                                                                  | room list / `rooms.room()`                      | clickable room ref; opens the room; unknown → muted plain text                                                       |

- **Composer**: `@` opens member autocomplete, `#` opens room picker; both insert the id
  token + trailing space. Users never type hex. Freeform `@alice` renders as plain text.
- **Mention-of-me** = id equality with `localAccount` (+ `@everyone`), minus self-authored
  messages — deterministic, no name-collision ambiguity.
- **Consumers**: message-row highlight (`#f8a300` @ 8 % fill + 2 px left bar per
  `03-ux-patterns.md` §1.2); red mention pill on room rows (`#da3e44`, white count) cleared
  on open/mark-read; desktop notification **only when window unfocused** (respects the
  "Notify when the window is focused" toggle); bundled notification sound (respecting its
  toggle) — real on desktop; taskbar/dock badge where supported (best-effort); NEW-messages
  divider (red bar + label) + jump-to-present integration.
- **Counting mechanics** (honest): incremental from `incomingMessageEvents` while running;
  recomputed from the loaded window vs `lastReadAt` on room open; after cold start, for
  never-opened rooms the preview exposes only the latest message → render a **dot (count
  unknown)** when that message mentions me. Never fabricate numbers.
- **Mobile push**: deferred — a real pipeline needs the backend to let a woken app fetch and
  decrypt the actual message (E2EE payloads can't ride a push). The `NotificationSink` seam
  is where it plugs in.
- **Settings impact**: mention rows in Notifications move from `Soon`-chipped to real;
  sound real on desktop; push stays the stub row.
- A protocol-level mention annotation (badges/notifications server-side semantics) remains a
  possible future backend feature; `MentionMatcher` is the seam where it lands.

## 7b. Embeds & linked media — real GUI feature (M5)

Owner's goal: linked images/GIFs render inline (GIFs play); linked pages render OG preview
cards — Discord behavior. **Owner-approved deviation from the pack** (`08-feature-readiness.md`
marks embeds STUB behind a runtime gap; the concern boundary was re-cut):

- **Core delivers messages, files, metadata — decentralized transport is its whole job.
  Displaying publicly available web data is a display concern, so fetching it is the GUI's.**
  The GUI fetches public web content directly over the normal internet (exactly like a
  browser click); no Tor/core routing. `.onion` links are not expected — if one appears,
  the fetch fails and the card degrades to a plain link.
- Privacy stance (owner): links are public resources the user would expose their IP to by
  clicking anyway; anyone bothered uses the opt-out toggle (previews off → plain links,
  nothing fetched). Fetch-on-view adds intent-matching for free (offscreen links are
  never fetched).
- **Attachments (files-as-messages) are unrelated and remain core's documented stub** —
  embeds cover only *linked* web media.

**Fetch pipeline (ready-made):** Coil 3 + `coil-network-ktor` in `:composeApp` — memory
cache, request dedupe, sizing, GIF decode (Android solid; desktop/iOS Skia path is an M5
spike — **static first frame is the floor**, never fake animation). Fetch hygiene (the one
place simple must not mean naive — web servers are wild): scheme allowlist (`http`/`https`
only), content-type allowlist (images, GIF, HTML-for-OG), hard byte caps, timeouts,
redirect limit, and OG extraction as a strict `<meta property="og:…">` scan — never a full
DOM, never executed content.

**Caching (owner's design + agreed refinements):**

- **Key = `sha256(url)`** (not messageId): one fetch serves every message that links the
  same URL — reshare dedupe, exactly what Discord's CDN does; dissolves multi-link
  naming (`messageId_N` unnecessary); GC stays age + size only, no message-liveness
  bookkeeping.
- Location: `dataDirectory/gui-cache/embeds/` (not raw cwd): the blessed GUI-owned-file
  pattern (`ui-prefs.json`), deterministic across packaged/launcher runs, wiped by
  `resetApp()` — a fresh identity leaves no old thumbnails behind.
- Images: Coil's disk cache pointed at that folder, `maxSizeBytes` from the user setting —
  a size-capped LRU plain-file store, already written (the "garbage collector").
- OG metadata: small JSON sidecar store (same folder, same caps) + a startup retention-age
  sweep — the only GC code we write (~80 lines).
- The cache is plaintext and hints at message content — accepted for alpha (owner), with
  honest settings copy ("cached unencrypted locally"). Real encryption later means a
  core-owned encrypted store or platform encrypted storage — parked; breaking the cache
  is harmless (refetch).
- Settings rows: preview toggle, cache size limit, retention time, `Clear cached previews`.

**Sender-side suppression — Discord's exact convention:** wrap the URL in angle brackets,
`<https://example.com>` — renders as a clickable link, no preview generated. Mechanism:
angle-wrapped URLs are distinct autolink nodes in the parser AST (vs. bare-URL autolinks
and masked links) so the embed extractor distinguishes by node type; fallback if the AST
doesn't separate them cleanly: mark via the sentinel pre-transform. Links inside code
never embed.

**Receiver-side suppression — X on the card (hover-revealed on desktop) and `Hide
preview` in the message context menu (touch); same action, persisted as local per-device
display state in `UiPreferences` (`embeds.dismissed`):**

- Key: `(messageId, linkIndex)` — per-embed, multi-link safe.
- Bounded LRU set (~512 entries) — embeds render only for visible messages, so this is
  effectively lossless in practice and cannot grow forever.
- Semantics: collapse to a plain link **and never fetch this link again for this
  message**; already-cached bytes from other occurrences are left to the normal GC (`Clear cached previews` covers the
  nuclear case).
- Survives restarts; per-device honest (settings copy says "hidden previews" — the
  sender's `<>` is the only universal suppression); cleared by `resetApp()` with the rest.

**Embed decision table (spec source for tests):**

| Link form                   | Renders as                          | Embed?                                    |
|-----------------------------|-------------------------------------|-------------------------------------------|
| bare `https://…`            | link                                | yes — fetched on view, cached by URL hash |
| `[label](url)`              | masked link                         | yes (Discord embeds masked links)         |
| `<https://…>`               | link                                | no — sender suppressed                    |
| link inside code            | code text                           | no                                        |
| receiver X / "Hide preview" | link only, never refetched          | no                                        |
| previews off in settings    | links only                          | no                                        |
| fetch failed / offline      | card with honest "not loaded" state | n/a                                       |

Capability flags `Capabilities.embeds`/`inlinePreviews` stay so the whole subsystem can be
force-disabled; honest loading/failed/offline states; no fake thumbnails or animation.

## 8. Milestones

**M1 — Bring-up.**
Theme in place; `YapSession` + platform entry points (desktop/Android/iOS); custom title bar

+ hand-rolled edge-resize + window prefs; dependency spike (markdown renderer + Voyager
  compat); `AppRoot` switching on `OrchestratorState` (S0 splash + progress lines); S1 setup
  wizard (4 intents, real forms per §1); S2 recovery-key modal (copy + download + ack-gate);
  S3 QR + base64 invite modal + paste-sponsor flow; S4 reset/failure screens. *Exit:* two desktop instances run
  genesis + join via invite, land in the shell.

**M2 — The messaging loop.**
S5 shell (rail per `06-yapyap-mapping.md` §2-A, room list with all row states + previews +
unread dots, chat header, grouped message list with date/gap dividers + unknown-author
rendering, composer with typing signal + byte budget + disabled states + Shift+Enter/IME,
right panel members/profile, user panel); S7 create-room modal; markdown wired with basic
library styling (emphasis, inline code, links, line breaks); send/receive/dedupe/retry. *Exit:* a real conversation
between two instances — send, receive, dedupe, retry-failed, typing.

**M3 — Discord behaviours & stubs.**
Full markdown dialect layer (§5: spoilers, quotes, lists, headers, subtext, code blocks,
masked links, timestamps); mentions complete (§7: pills, autocomplete, highlight, badges,
notifications); hover toolbar; context menus (message/room/member with `Soon` actions);
profile popout; quick switcher (W6); settings modal (Account, Devices, Appearance, Chat,
Notifications, Keybinds read-only, Network → Members/Devices/Advanced with the generic
`Setting` renderer, Advanced incl. data-dir + reset); confirm dialogs; toasts; stubs:
Friends W24, Inbox W28, Pinned W26, Threads W27, Search W20, Voice room W29, Profile editor
W30; Unicode emoji picker W19; keyboard shortcuts (`Ctrl/Cmd+K/N/F`, `Esc`, `Alt+↑/↓`,
`Ctrl/Cmd+,`, `Shift+Esc`). *Exit:* full pack minus server chrome; must-match fidelity list verified against
`09-component-states.md`.

**M4 — Mobile & polish.**
Compact/Medium passes (drawer, bottom nav, sheets, long-press, IME handling); desktop
notification sink + sound + taskbar badge; optional syntax highlighting; window geometry
prefs; packaging (portable zip + installers); test suite complete; docs/guide handoff note. *Exit:* Android/iOS targets
compile and the adaptive shell renders; desktop packaging works.

**M5 — Embeds & linked media (§7b).**
Coil 3 + `coil-network-ktor` wired into the markdown renderer's inline-image integration;
`EmbedCard` for images/GIFs (Discord-measured geometry) + OG page cards; URL-hash cache
under `dataDirectory/gui-cache/embeds/` with Coil-as-GC + the metadata sidecar sweep;
sender `<>` suppression in the dialect layer; receiver X / "Hide preview" with bounded
persistence; settings rows (toggle, cache size, retention, clear); GIF playback spike (desktop/iOS; static-first-frame
floor); honest not-loaded/offline states. *Exit:* linked image/GIF renders inline (playing where supported), pages show
OG cards,
the cache respects size/age caps, both suppressions persist, previews-off degrades
everything to plain links.

## 9. Tests

- `GuiApiBoundaryTest` stays green (imports discipline; `:core` untouched).
- Common unit tests: message grouping (author/time/gap/system boundaries); room-list
  ordering; refusal→copy mapper coverage; `UiPreferences` round-trip + unknown-key survival;
  `DiscordMarkdownTransformer` golden files (incl. code-negation, PUA forging, `>>>`,
  underline vs bold conflict, timestamps); mention token grammar + "mentions me" logic;
  `DeviceNames` stability; data-dir resolution matrix (JVM).
- JVM integration-style test: two orchestrators in temp dirs (genesis + sponsor path) with
  send/dedupe assertions through the GUI controllers — validates the "actually send
  messages" loop.
- Embeds (M5): URL-hash cache keying + reshare dedupe; dismissal set persistence + LRU
  bound; the §7b embed decision table as table-driven tests (every row); OG extraction
  golden files with hostile input (non-http schemes, wrong content types, oversized
  bodies, redirect loops, malformed HTML); GC sweep logic (age + size caps); `<>`
  sender suppression and dismissed-key receiver suppression.

## 10. Deviations from the pack & flagged gaps (all owner-approved)

1. **W18 connection banner** — dropped (A/V-era feature).
2. **W25/W25b Server chrome** — deferred; "Add a server" rail item = `Soon` toast.
3. **Setup wizard "device name" field** — dropped (no API).
4. **Markdown parser** — supersedes `03-ux-patterns.md` §1.4 "keep the parser small": full
   Discord markdown, library-powered + dialect layer (§5).
5. **Mentions** — upgraded from STUB (per `08-feature-readiness.md` §2) to a real GUI-side
   feature with id-based tokens (§7); notification/badge behavior is local, mobile push deferred.
6. **Device names** — new display-only feature not in the pack (§6).
7. **File send/download, room rename, message search** — stubs exactly as the pack documents (runtime gaps listed in
   `06-yapyap-mapping.md` §4).
8. **Inline embeds (linked images/GIFs, page previews)** — **upgraded from STUB to a real
   GUI feature**: the owner re-cut the concern boundary (core delivers messages/files/
   metadata; displaying public web data is the GUI's, fetched directly over the normal
   internet; `.onion` degrades to a plain link; privacy handled by the opt-out toggle +
   fetch-on-view). GUI-owned URL-hash file cache (never `vault.db`); full design in §7b,
   delivered in M5. **Attachments (files-as-messages) remain the pack's documented stub** —
   only *linked* web media is GUI-owned.
9. **`FanoutReport`** — advisory only, no user-visible warning, per `03-ux-patterns.md` §1.6.
10. **`RoomType.UNKNOWN`** — filtered per `RoomService.rooms` docs.
11. **Avatars/banners** — deterministic generated (pack-approved temporary pixel source);
    geometry final per `08-feature-readiness.md` §2.

## 11. Handoff notes

- Start at M1; first tasks: copy `YapTheme.kt`, dependency spike, platform glue, title bar.
- Reading order for the implementer: `docs/gui/README.md` → `01` → `10` → `02` → `04` →
  `03` → `09` → `05` → `06`/`08`, then this plan.
- Move/copy this plan into `docs/gui/` as the implementation addendum (owner to do, or the
  implementation agent's first commit) — suggested name: `docs/gui/07-implementation-plan.md`.
- Dev loop: two `.run` configs with `-Dyapyap.dataDir` overrides; hot-reload plugin already applied.
