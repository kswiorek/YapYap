## GUI Boundary

How the Compose GUI consumes the core. This file derives from docs/gui-api.md (the authoritative GUI API contract),
corroborated by the enforcing tests (`composeApp/src/jvmTest/kotlin/org/yapyap/GuiApiBoundaryTest.kt`,
`core/src/jvmTest/kotlin/org/yapyap/PublicApiSurfaceTest.kt`) and the build configuration. The structural counterpart is
the two-module layering: `:composeApp` depends on `:core` (via `implementation(project(":core"))`); `:core` declares
zero GUI framework dependencies — never the reverse.

### GUI reaches the core through exactly three types — ask, don't bypass

`:core` is a library. The GUI in `:composeApp` talks to it through exactly three types — `OrchestratorFactory` (in
org.yapyap.wiring), `Orchestrator`, `OrchestratorRuntime` — plus the service interfaces the runtime exposes and the
vocabulary types in their signatures. Everything else in `:core` is `internal` and invisible to this module. If the GUI
needs something not reachable through the runtime, that is a missing runtime API — ask, don't bypass.
`GuiApiBoundaryTest` fails the build on any other `org.yapyap.*` import (the documented vocabulary list mirrors its
allow-list exactly); star imports of core packages are banned outright. The GUI never imports from orchestrator
sub-packages; nothing in the list names a transport except the dev-only `LoopbackTransport`.

Sources: docs (gui-api.md), code, config, CI config · confidence 92.
Evidence:

- gui-api.md: "The GUI in `:composeApp` talks to it through exactly three types … **If you need something that isn't
  reachable through the runtime, that is a missing runtime API — ask, don't bypass.** `GuiApiBoundaryTest` fails the
  build on any other `org.yapyap.*` import."
- GuiApiBoundaryTest.kt: `allowedSubtrees = ["org.yapyap.orchestrator.runtime."]` plus `allowedExactImports` (~35 types:
  Orchestrator, OrchestratorState, NodeMode, SetupIntent/Result, PeerId, RoomId, MessageId, TorEndpoint,
  Setting/ConfigValue/UpdateResult …); a separate test bans star imports of core packages.
- settings.gradle.kts: exactly two modules, `:core` and `:composeApp`; composeApp declares
  `implementation(project(":core"))`; core's dependency lists contain no Compose or GUI dependencies.
- code: 4 of 4 sampled GUI files import only allowlisted types (ChatController, MainShell, YapSession, YapButton — the
  latter has no core imports at all).

```kotlin
import org.yapyap.orchestrator.runtime.message.SendMessageResult  // allowed
import org.yapyap.routing.*                                        // build failure
```

### Service calls are suspend; refusals are values, never exceptions

All runtime service calls are `suspend`; infrastructure failures throw, but domain refusals are sealed outcome values,
never exceptions (e.g. `RoomEventOutcome.Refused`; SendRefusal values on the messaging path). GUI code must not catch
exceptions for domain refusals.

Sources: docs (gui-api.md) · confidence 90.
Evidence:

- gui-api.md: "All service calls are `suspend` (infra failures throw); domain refusals are sealed outcome values, never
  exceptions."
- gui-api.md (rooms service): "Refusals are values (`RoomEventOutcome.Refused`); infra failures throw."

### Flows are hot; the GUI collects in its own scope; :core leaks no dependencies

Core flows are hot — the GUI collects them from its own coroutine scope (`:composeApp` declares its own
kotlinx-coroutines-core for this; `:core` does not leak its dependencies).

Sources: docs (gui-api.md) · confidence 89.
Evidence:

- gui-api.md: "Flows are hot — collect them from a coroutine scope (`:composeApp` declares its own
  `kotlinx-coroutines-core` for this; `:core` does not leak its dependencies)."

### Reuse core types; a new enum value should break the GUI's when

The GUI reuses the core's shared identifiers and chain enums; it does not mirror them in a GUI package. A new enum value
should break the GUI's `when`. Add a separate API type only when the two sides really differ (the GUI needs a value the
core doesn't have, or must not see one the core has).

Sources: docs (gui-api.md) · confidence 90.
Evidence:

- gui-api.md: "The GUI reuses these core types; it does not mirror them in a GUI package … a new enum value should break
  the GUI's `when`. Add a separate API type only when the two sides really differ."

### Display strings are a GUI concern; the backend returns typed values

`RoomType`/`DeviceType` wireValues, `InviteDefect` and refusal reasons: display strings are a GUI concern; the backend
returns typed values, never copy. Message ordering for the chat list is composed in the GUI from
`MessagingService.roomPreview` (the room service owns no message reads).

Sources: docs (gui-api.md) · confidence 88.
Evidence:

- gui-api.md (Non-goals / where things deliberately live elsewhere): "`RoomType`/`DeviceType` `wireValue`s,
  `InviteDefect`, refusal reasons: display strings are a GUI concern; the backend returns typed values, never copy."
- gui-api.md: "Message ordering for the chat list is composed in the GUI from `MessagingService.roomPreview` (the room
  service owns no message reads)."

### GLOBAL never listed; UNKNOWN rooms filtered until genesis; invites opaque

`RoomId.GLOBAL` is the control room and is never listed in the room list. `RoomType.UNKNOWN` is a provisional marker —
filter it until the genesis folds (RoomService filters UNKNOWN rooms from the GUI-facing room list). Invites travel as
opaque bytes; the GUI never inspects invite contents.

Sources: docs (gui-api.md) · confidence 88.
Evidence:

- gui-api.md (Notes): "`RoomId.GLOBAL` is the control room (never listed). `RoomType.UNKNOWN` is a provisional marker —
  filter it until genesis folds (see `RoomService.rooms` docs)."
- gui-api.md: "Invites travel as opaque bytes (`SetupResult.inviteBytes` → QR → `sponsorNewcomer(inviteBytes)`);
  undecodable bytes are refused as `MalformedInvite(MALFORMED_BYTES)`. The GUI never inspects invite contents."

### Loopback GUI must show a visible LOCAL marker

Any GUI running loopback (dev transport) must show a visible "LOCAL — no anonymity" marker so a dev build is never
mistaken for a real one. Loopback swaps only the Tor daemon for localhost-TCP; the envelope codec, routing and E2EE
above it are identical to production; `WEBRTC`/`ALL` loopback values are reserved and fail fast.

Sources: docs (gui-api.md — Local dev profile) · confidence 88.
Evidence:

- gui-api.md: "Any GUI running loopback must show a visible 'LOCAL — no anonymity' marker so a dev build is never
  mistaken for a real one."
- gui-api.md: "`loopback = LoopbackTransport.TOR` swaps the Tor daemon for a localhost-TCP backend … The envelope codec,
  routing, and E2EE above it are identical to production; only anonymity and transport-layer authenticity are absent.
  `WEBRTC`/`ALL` are reserved and fail fast."

### resetApp state gates and post-reset sequence

`resetApp()` wipes local persistence and lands in `Stopped`; it is callable from `ResetRequired`, `Stopped`, `Failed` or
`SetupRequired` only — never from `Running`/`Starting`. After reset, call `start()` (it reopens/recreates the database,
schema and logs/tor, and re-diagnoses into `SetupRequired` on the fresh directory) — only then call `completeSetup()`.
Calling `completeSetup()` directly after `resetApp()` on the same instance fails fast (the in-memory DB handle is dead).
After a self-removal or self-ban the fold flips state to `ResetRequired`; observe state until `resetApp()`. A second
process on the same `dataDirectory` fails fast (single-instance file lock, held until `resetApp()` wipes); `resetApp()`
refuses while held elsewhere.

Sources: docs (gui-api.md) · confidence 86.
Evidence:

- gui-api.md: "`resetApp()` wipes local persistence and lands in `Stopped`. Callable from `ResetRequired`, `Stopped`,
  `Failed` or `SetupRequired` only — never from `Running`/`Starting`. Call `start()` next … — only then call
  `completeSetup()`. (Calling `completeSetup()` directly after `resetApp()` on the same instance fails fast: the
  in-memory DB handle is dead after the wipe.)"
- gui-api.md: "A second process on the same `dataDirectory` fails fast into `Failed` with 'already using this data
  directory' (single-instance file lock, held until `resetApp()` wipes); `resetApp()` refuses while held elsewhere."

### OWNER implies admin everywhere; gate admin UI on live localIsAdmin

`RoomMemberView` / `AccountView` / `DeviceView` carry chain-derived role/status; OWNER implies admin everywhere.
Admin-gated GUI surfaces must gate on the live `localIsAdmin` value.

Sources: docs (gui-api.md; db schema.mmd) · confidence 87.
Evidence:

- gui-api.md (Notes): "`RoomMemberView` / `AccountView` / `DeviceView` carry chain-derived `role`/`status`; `OWNER`
  implies admin everywhere."
- gui-api.md (admin service): "Admin-gated global mutations. Gate admin UI on `localIsAdmin` (live)."
- db schema.mmd (ACCOUNTS.role): "MEMBER, ADMIN, or OWNER (owner = repair path, irrevocable)."

### GUI module uses default visibility (no explicitApi)

In contrast to `:core`'s explicit-API discipline (see architecture/api-surface.md), composeApp app code uses Kotlin's
default (implicit public) visibility; `explicitApi()` is not applied to the GUI module. The core boundary stays sealed
by the import allowlist tests instead of visibility.

Sources: code · confidence 80.
Evidence:

- 46 of 47 sampled GUI files have no explicit public/internal top-level modifier.
- `explicitApi()` is declared only in core/build.gradle.kts (grep of all build scripts); GuiApiBoundaryTest enforces the
  boundary instead.

```kotlin
class ChatController(private val scope: CoroutineScope /* , … */)  // GUI: implicit public, no explicitApi
```
