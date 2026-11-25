# System Architecture

## Overview

YapYap is a peer-to-peer messenger: every device runs the same Kotlin core (`:core`); the GUI (`:composeApp`) sits on
top through a deliberately tiny boundary; there is no server. The authoritative rules-of-the-code are `docs/guide.md`
(dependency table §3, data ownership §4, composition-root rules §5, delivery contract §6, new-code checklist §9), backed
by per-layer deep docs (`transport.md`, `router.md`, `e2ee.md`, `envelope-redesign.md`, `global events.md`,
`room events.md`, `gui-api.md`) and ~20 mermaid diagrams in `docs/`. This file is the overview; the deep docs win on
detail.

## Architecture Pattern

**Pattern**: layered like a small network stack with vocabulary separation — explicitly **not** three-tier.

Each layer speaks only its own vocabulary:

| Layer              | Vocabulary                | Knows about                                                                         |
|--------------------|---------------------------|-------------------------------------------------------------------------------------|
| GUI (`composeApp`) | screens, sessions         | Orchestrator's 3 public types                                                       |
| Orchestrator       | accounts, rooms, payloads | DAGs, folds; never envelopes/packet ids/transport types                             |
| Router             | devices, envelopes        | outbox, relays, ACK/NACK, dedup; reads identity only via the `DeviceDirectory` port |
| Transports         | remotes, bytes            | nothing of DB/accounts/crypto                                                       |
| Protocol           | one binary envelope       | wire ids, observability profiles                                                    |

Lower layers declare ports that upper layers implement (`DeviceDirectory`, `SyncPayloadProvider`,
`FrontierSnapshotProvider`) instead of importing upward. The layer table is **enforced by tests**, not prose:
`ArchitectureTest` (import rules + per-table query ownership, with an only-shrinks `knownDebt` list),
`PublicApiSurfaceTest` (`explicitApi()`), `GuiApiBoundaryTest` (GUI may import only `OrchestratorFactory`,
`Orchestrator`, `OrchestratorRuntime` + service interfaces and vocabulary types).

## System Structure

### GUI (`composeApp/`, `org.yapyap.gui.*`)

- **Location**: `composeApp/src/commonMain/kotlin/org/yapyap/gui/`
- **Purpose**: Compose Multiplatform app — screens (shell, setup, settings, createroom, quickswitcher, reset),
  session/CLI-args/data-dir handling, markdown + mentions rendering, theme from `docs/gui/` design tokens
- **Key boundary**: `GuiApiBoundaryTest`

### Orchestrator (`org.yapyap.orchestrator.*`)

- **Location**: `core/src/commonMain/kotlin/org/yapyap/orchestrator/`
- **Purpose**: owns accounts/rooms/message history. `OrchestratorRuntime` exposes 8 services (messaging, config,
  onboarding, identity, rooms, files, admin, account); `DagEngine` keeps per-room DAGs (all messages flow through them);
  global + room folds project event chains into state; `InboundMessagePipeline` appends router output to DAGs;
  onboarding/recovery; sync decisions; `MaintenanceScheduler` prunes stale rows
- **Key files**: `OrchestratorRuntime.kt`, `DagEngine.kt`, `fold/`, `pipeline/`, `onboarding/`, `sync/`, `runtime/`

### Router (`org.yapyap.routing.*`)

- **Location**: `core/src/commonMain/kotlin/org/yapyap/routing/`
- **Purpose**: device-level delivery. Router = lifecycle + 4 carriers (messages, bootstrap, presence, files);
  `OutboxProcessor` retries until ACK; relay store-and-forward with uptime-based peer selection; pings advertise
  self-reported reliability and DAG frontiers; `PeerAvailabilityRegistry` tracks uptime; `sync/` handles sync
  request/retry/payload
- **Key files**: `Router.kt`, `outbound/`, `inbound/`, `sync/`, `DefaultRouter.kt`

### Transports (`org.yapyap.transport.*`)

- **Location**: `core/src/commonMain/kotlin/org/yapyap/transport/`
- **Purpose**: `SessionTransport<K>` implementations — Tor (long-lived streams via kmp-tor) and WebRTC (data channels
  via webrtc-java, signaling over Tor)

### Protocol (`org.yapyap.protocol.*`)

- **Location**: `core/src/commonMain/kotlin/org/yapyap/protocol/`
- **Purpose**: one signed `BinaryEnvelope` for all 5 packet families (MESSAGE/SIGNAL/FILE/SYSTEM/BOOTSTRAP): signed hop
  header + public routing header + opaque E2EE content. Hand-written binary codecs (`ByteReaderWriter`, explicit wire
  values, round-trip tests) — not a serialization framework
- **Design of record**: `docs/envelope-redesign.md` (2026-11-24)

### Crypto (`org.yapyap.crypto.*`)

- **Purpose**: X3DH + Double Ratchet; typed key hierarchy (private keys are never printed); identity services
  (`LocalIdentity`, `LocalKeys`, `PeerKeys`)

### Protection (`org.yapyap.protection.*`)

- **Purpose**: `HopProtection` (every envelope hop-signed) + per-family content protection; two-valued
  `ProtectionDisposition` (PERMANENT/DEFER) — no retryable verdict; retry policy is owned by the outbox; NACK reasons
  are a pure function decided in one place

### Persistence (`org.yapyap.persistence.*`)

- **Purpose**: SQLDelight database (`YapYapDatabase`), SQLCipher-encrypted with keyring-held master key; strict
  one-owner-per-table

### Config (`org.yapyap.config.*`)

- **Purpose**: TOML config (tomlkt): `OrchestratorConfig`, `RouterConfig`, `TorBackendConfig`, `WebRtcBackendConfig`,
  `CryptoSessionConfig`, `RuntimeConfig`; injected as `StateFlow`

### Wiring (`org.yapyap.wiring.*`)

- **Purpose**: two-phase composition root: `VaultGraph` (pre-identity) → `NodeGraph` (post-identity);
  `OrchestratorFactory` is the only public entry. Manual constructor injection of interfaces (~28 constructor deps in
  `DefaultOrchestratorRuntime`); components subscribe to their own inputs in `start()`; wiring contains no
  launch/collect; no service locators

## Data Flow

**Send**: runtime service → DAG append → router fan-out per account → protect (hop-sign) → outbox (retry + relay
backup) → Tor/WebRTC.

**Receive**: transport → `InboundEnvelopeProcessor` → hop-signature check → dedup → packet handler → pipeline → DAG
append.

**Delivery model**: effectively-once rests on the **pull path** — pings advertise DAG frontiers, peers issue sync
requests and pull missing messages; the push path is a latency optimization. ACK happens only after successful
decrypt-and-handle; DEFER is silent (never NACKed).

## External Integrations

- **Tor network** (embedded kmp-tor) — default transport for all traffic incl. WebRTC signaling
- **WebRTC** (webrtc-java) — low-latency messaging and large file transfers
- **OS keyring** (java-keyring) — DB master key and key store
- **SQLCipher** (SQLDelight JDBC driver build) — encrypted at-rest storage

## Database Schema

Schema diagram: `docs/db schema.mmd`. Sources of truth are the 23 `.sq`/`.sqm` files under `core` persistence with 6
numbered migrations. Per-table data ownership (guide §4) is enforced by `ArchitectureTest`.

## Configuration

TOML files parsed via tomlkt into typed config objects, injected as `StateFlow`. Desktop CLI flags: `--data-dir`,
`--loopback`, `--keyring-ns`.

## Deployment Architecture

- Desktop installers via GitHub Actions `desktop-release.yml` (`jpackage`: Msi/Deb/Rpm/AppImage/Exe on tag)
- Headless relay nodes run the same core (e.g. on Raspberry Pis) to anchor availability
- Android/iOS packaging exists (`iosApp` Xcode shell; conditional Android target); mobile behaviors incomplete
  (`docs/guide.md` §8)

---
**Quality caveat (owner note)**: the file-transfer implementation (`orchestrator/file/*`, `routing/file/*`,
`docs/file/`, `File group diagram.mmd`) is **unreviewed and provisional**; `docs/envelope-redesign.md` is its design of
record. Do not mine file code for conventions or quality examples. The reviewed exemplar is the core excluding file:
routing (message/outbox/presence/sync), orchestrator (dag/fold/pipeline/boot), crypto, protocol, persistence, wiring.

*Based on codebase analysis performed 2026-11-25*
