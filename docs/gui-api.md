## YapYap GUI API (PoC handoff)

`:core` is a library. The GUI in `:composeApp` talks to it through exactly three types —
`OrchestratorFactory`, `Orchestrator`, `OrchestratorRuntime` — plus the service interfaces
the runtime exposes and the vocabulary types in their signatures. Everything else in `:core`
is `internal` and invisible to this module. **If you need something that isn't reachable
through the runtime, that is a missing runtime API — ask, don't bypass.**
`GuiApiBoundaryTest` fails the build on any other `org.yapyap.*` import.

### Bring-up sequence

```kotlin
val orchestrator = OrchestratorFactory(
    dataDirectory,
    NodeMode.FULL_CLIENT,
    // loopback defaults to NONE (real Tor/WebRTC), keyringNamespace to null (legacy keyring).
).create()
orchestrator.start()                    // boot recovery → router → domain loops
val runtime: OrchestratorRuntime = orchestrator.runtime()
```

- `dataDirectory` holds `vault.db*`, `tor/`, `logs/`, `state.toml`, `userSettings.toml`
  (per platform). Key material lives in the OS keyring under service `org.yapyap`
  (suffixed per `keyringNamespace` when set) — never in the data directory.
- `orchestrator.state: StateFlow<OrchestratorState>` drives top-level UI:
  `Created → SetupRequired → Starting → Running`, plus `Stopping/Stopped`,
  `ResetRequired(reason)` and `Failed(cause)`. While `Starting`,
  `orchestrator.bootProgress: StateFlow<BootProgress>` reports the phase (`BootPhase`: transports → database → router →
  folds → ready) plus
  `roomsDone/roomsTotal` during the room-fold sweep — render it instead of a
  static spinner. A second process on the same `dataDirectory` fails fast
  into `Failed` with "already using this data directory" (single-instance
  file lock, held until `resetApp()` wipes); `resetApp()` refuses while held
  elsewhere.
- First launch lands in `SetupRequired`: call
  `completeSetup(intent): SetupResult` with one of `SetupIntent.Genesis`
  (new standalone network), `.NewAccountFirstDevice` (join with a new account),
  `.AddDeviceToExistingAccount`, or `.ImportAccountRecoveryKey` (recovery).
  The result carries the `Invite?` to render as a sponsor QR (null where there is
  no sponsor) and the account `recoveryKey` (show once, tell the user to store it).
- `resetApp()` wipes local persistence and lands in `Stopped`. Callable from
  `ResetRequired`, `Stopped`, `Failed` or `SetupRequired` only — never from
  `Running`/`Starting`. Call `start()` next: it reopens the database,
  recreates `vault.db`/schema + `logs/`/`tor/`, and re-diagnoses into
  `SetupRequired` on the fresh directory — only then call `completeSetup()`.
  (Calling `completeSetup()` directly after `resetApp()` on the same instance
  fails fast: the in-memory DB handle is dead after the wipe.)
  After a self-removal or self-ban the fold flips state to
  `ResetRequired`; observe `state` until `resetApp()`.
- `orchestrator.onboardingState: StateFlow<OnboardingState>` mirrors newcomer progress
  on every node (also headless relays, which have no runtime).

### Local dev profile (two instances, no Tor)

For GUI work without a reachable Tor network, each instance gets its own isolated
profile — separate data dirs plus loopback transport and a distinct keyring
namespace. On desktop, pass CLI flags (or use the shared `YapYap dev-a (local)` /
`YapYap dev-b (local)` run configurations, which bake them in):

```
YapYap --data-dir .yapyap-dev-a --loopback --keyring-ns dev-a
YapYap --data-dir .yapyap-dev-b --loopback --keyring-ns dev-b
```

This is equivalent to constructing the factory directly (the only way on
Android/iOS, which have no CLI):

```kotlin
OrchestratorFactory(dirA, NodeMode.FULL_CLIENT, LoopbackTransport.TOR, "dev-a")
OrchestratorFactory(dirB, NodeMode.FULL_CLIENT, LoopbackTransport.TOR, "dev-b")
```

- `loopback = LoopbackTransport.TOR` swaps the Tor daemon for a localhost-TCP
  backend (`loopback-<port>.onion` endpoints, `start()` returns in milliseconds,
  nothing written to disk). The envelope codec, routing, and E2EE above it are
  identical to production; only anonymity and transport-layer authenticity are absent.
  `WEBRTC`/`ALL` are reserved and fail fast — WebRTC stays real (localhost
  candidates work without Tor).
- Distinct `keyringNamespace` values (`"dev-a"` vs `"dev-b"`) give each instance its
  own OS-keyring service (`org.yapyap.dev-a`, …). Without this, the second
  instance's genesis overwrites the first's master/device keys while each keeps its
  own `vault.db`. `null` keeps the legacy shared service (production default).
- Pair the instances through the normal invite flow: genesis on A, join on B via
  invite bytes — endpoints propagate over the loopback transport, no manual entry
  needed. Switching back to real backends heals persisted loopback endpoints from
  authenticated inbound traffic (`PEER_ENDPOINT_HEALED`).
- Any GUI running loopback must show a visible "LOCAL — no anonymity" marker so a
  dev build is never mistaken for a real one.

### The seven services (`runtime.*`)

| Service      | Owns                                                                                                                                                                                                             |
|--------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `messaging`  | Send text, room windows (`openRoom` + `close`), previews, typing, incoming events. Max length: `maxTextMessageBytes`. Re-pull `roomPreview` on `incomingMessageEvents` (the event carries no content by design). |
| `rooms`      | Room list (`rooms`), headers (`room`), creation, membership/admin ops, leave (+owner handover via `successorAccountId`). Refusals are values (`RoomEventOutcome.Refused`); infra failures throw.                 |
| `identity`   | Read-only roster: `accounts`, `localAccount`, `account(id)` (null → render "unknown author"). Mutations live in `admin`/`account`.                                                                               |
| `admin`      | Admin-gated global mutations. Gate admin UI on `localIsAdmin` (live).                                                                                                                                            |
| `account`    | Self-service: remove own devices / this device / own account. Last-device removal requires the recovery key (`RecoveryKeyRequired`).                                                                             |
| `onboarding` | Sponsor side: `sponsorNewcomer(inviteBytes, admin)` for scanned QR bytes; `newcomerCancelOnboarding()` for the cancel button. Newcomer progress is `newcomerState`.                                              |
| `config`     | Live user settings (`settings`) + `update(id, value)` (`null` clears the override).                                                                                                                              |

All service calls are `suspend` (infra failures throw); domain refusals are sealed
outcome values, never exceptions. Flows are hot — collect them from a coroutine scope (`:composeApp` declares its own
`kotlinx-coroutines-core` for this; `:core` does not
leak its dependencies).

### Vocabulary types (importable)

Orchestrator entry points and companions: `Orchestrator`, `OrchestratorFactory`,
`OrchestratorState`, `BootProgress`, `BootPhase`, `NodeMode`, `LoopbackTransport`, `SetupIntent`, `SetupResult`,
`BootstrapEndpoint`, `OnboardingState`, `ResetReason`, the `runtime.*` service
interfaces, and the shared identifiers/enums: `PeerId`, `RoomId`, `TorEndpoint`,
`AccountId`, `DeviceType`, `RoomType`, `RoomMemberRole`, `RoomMemberStatus`,
`AccountRole`, `IdentityStatus`, plus the settings types (`Setting`, `ConfigValue`,
`UpdateResult`, `Number/Text/Toggle/PeriodSetting`). This list mirrors the
`GuiApiBoundaryTest` allow-list exactly — importing anything else fails the build.

Notes:

- `RoomId.GLOBAL` is the control room (never listed). `RoomType.UNKNOWN` is a
  provisional marker — filter it until genesis folds (see `RoomService.rooms` docs).
- Invites travel as opaque bytes (`SetupResult.inviteBytes` → QR →
  `sponsorNewcomer(inviteBytes)`); undecodable bytes are refused as
  `MalformedInvite(MALFORMED_BYTES)`. The GUI never inspects invite contents.
- `RoomMemberView` / `AccountView` / `DeviceView` carry chain-derived `role`/`status`;
  `OWNER` implies admin everywhere.

### Non-goals / where things deliberately live elsewhere

- Message ordering for the chat list is composed in the GUI from
  `MessagingService.roomPreview` (the room service owns no message reads).
- `RoomType`/`DeviceType` `wireValue`s, `InviteDefect`, refusal reasons: display
  strings are a GUI concern; the backend returns typed values, never copy.
- Anything under `org.yapyap.persistence`, `routing`, `transport`, `crypto`,
  `protection`, `logging`, or the orchestrator's `dag`/`fold`/`pipeline`/`sync`
  packages is unreachable by design. A few shared-vocabulary types are technically
  `public` outside the list above (SQLDelight-generated code references them);
  they are still forbidden to the GUI by `GuiApiBoundaryTest`.
