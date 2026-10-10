## YapYap GUI API (PoC handoff)

`:core` is a library. The GUI in `:composeApp` talks to it through exactly three types —
`OrchestratorFactory`, `Orchestrator`, `OrchestratorRuntime` — plus the service interfaces
the runtime exposes and the vocabulary types in their signatures. Everything else in `:core`
is `internal` and invisible to this module. **If you need something that isn't reachable
through the runtime, that is a missing runtime API — ask, don't bypass.**
`GuiApiBoundaryTest` fails the build on any other `org.yapyap.*` import.

### Bring-up sequence

```kotlin
val orchestrator = OrchestratorFactory(dataDirectory, NodeMode.FULL_CLIENT).create()
orchestrator.start()                    // boot recovery → router → domain loops
val runtime: OrchestratorRuntime = orchestrator.runtime()
```

- `dataDirectory` holds `vault.db*`, keyring entries, `tor/`, `state.toml` (per platform).
- `orchestrator.state: StateFlow<OrchestratorState>` drives top-level UI:
  `Created → SetupRequired → Starting → Running`, plus `Stopping/Stopped`,
  `ResetRequired(reason)` and `Failed(cause)`.
- First launch lands in `SetupRequired`: call
  `completeSetup(intent): SetupResult` with one of `SetupIntent.Genesis`
  (new standalone network), `.NewAccountFirstDevice` (join with a new account),
  `.AddDeviceToExistingAccount`, or `.ImportAccountRecoveryKey` (recovery).
  The result carries the `Invite?` to render as a sponsor QR (null where there is
  no sponsor) and the account `recoveryKey` (show once, tell the user to store it).
- `resetApp()` wipes local persistence and returns to `SetupRequired`. Callable from
  `ResetRequired`, `Stopped`, `Failed` or `SetupRequired` only — never from
  `Running`/`Starting`. After a self-removal or self-ban the fold flips state to
  `ResetRequired`; observe `state` until `resetApp()`.
- `orchestrator.onboardingState: StateFlow<OnboardingState>` mirrors newcomer progress
  on every node (also headless relays, which have no runtime).

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

Identifiers and shared enums in `org.yapyap.protocol`: `PeerId`, `RoomId`, `TorEndpoint`,
`AccountId`, `DeviceType`, `RoomType`, `RoomMemberRole`, `RoomMemberStatus`,
`AccountRole`, `IdentityStatus`, `OnboardingState`, `ResetReason`, and the settings trio (`Setting`, `ConfigValue`,
`UpdateResult`, `Number/Text/Toggle/PeriodSetting`).

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
