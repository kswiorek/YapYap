# Technology Stack

## Overview

This document describes the technology choices and rationale for **YapYap**, a decentralized, serverless messaging app
for small trusted networks (20–40 people), built as a Kotlin Multiplatform project: one shared backend core (`:core`)
with a Compose Multiplatform GUI (`:composeApp`) on top.

The stack is deliberately framework-light: the core has no server framework, no DI framework, and no serialization
framework on the wire — the novel complexity lives in hand-written protocol, routing, and crypto code.

## Languages

### Kotlin (2.3.20)

- **Usage**: the overwhelming majority of the codebase (`core` backend + `composeApp` GUI)
- **Rationale**: Kotlin Multiplatform lets every platform run the exact same core backend package (a README premise:
  "All peers run the same core backend package") while sharing tests in `commonTest`
- **Key Features Used**: coroutines + `StateFlow` as public observable surfaces; sealed classes for protocol/error
  vocabularies; `@JvmInline` value classes for typed ids (`PeerId`, `RoomId`, `AccountId`); `explicitApi()` mode for the
  core library's public API; opt-in `ExperimentalUuidApi`
- **Secondary**: Swift (thin `iosApp` Xcode shell), Python/JS (one-off `tools/discord-capture` design-token scripts, not
  runtime code)

## Frameworks

### Frontend

- **Compose Multiplatform 1.10.3** (Compose compiler plugin 2.3.20, hot-reload 1.0.0) — one declarative GUI across
  desktop/Android/iOS
- **Material3 1.10.0-alpha05**, `compose-icons` FontAwesome 1.1.1, jsvg 2.2.0 (SVG rendering)
- GUI design spec pack in `docs/gui/` (tokens captured from Discord via `tools/discord-capture`)

### Backend

No backend framework — the core is a hand-layered network stack. Key libraries:

- **kmp-tor 2.6.0** (runtime) + resource-exec/noexec-tor 409.5.0 (io.matthewnelson) — embedded Tor, so peers accept
  unsolicited inbound traffic on any network
- **webrtc-java 0.14.0** (dev.onvoid.webrtc) — WebRTC data channels for low-latency messaging and large file transfers
  (JVM, per-OS native classifiers; signaling over Tor)
- **cryptography 0.6.0** (dev.whyoleg; core + provider-optimal, jdk-bc on JVM/Android) — X3DH + Double Ratchet E2EE
- **Ktor 3.4.2** (ktor-network, ktor-io) — network plumbing beneath transports
- **tomlkt 0.5.0** — TOML configuration
- **java-keyring 1.0.4** — OS keychain-backed key storage

### Testing

- **kotlin-test** + **kotlinx-coroutines-test** — deliberately no Kotest and no Mockk; test doubles are hand-written in
  `core/src/commonTest/.../testfixtures` (e.g. `FakeClock`, `NoopRouter`, `NoopFileCarrier`, `TrackingPacketOutbox`)
- **Governance tests as build gates**:
    - `ArchitectureTest` — layer import table + per-table query ownership, with an only-shrinks `knownDebt` list
    - `PublicApiSurfaceTest` — enforces `explicitApi()` on the core API
    - `GuiApiBoundaryTest` — GUI may import only `OrchestratorFactory`, `Orchestrator`, `OrchestratorRuntime` plus
      service interfaces and vocabulary types
- **Opt-in integration tests** with real Tor/WebRTC via `-PintegrationTests=true` (8 excluded by default, listed in
  `core/build.gradle.kts`)
- Volume: 171 test files in `core`, 10 in `composeApp`; test naming `method_state_expectation`

## Database

### SQLDelight (2.3.2) over SQLCipher

- **Type**: relational, Kotlin APIs generated from `.sq`/`.sqm` files (database `YapYapDatabase`, package
  `org.yapyap.persistence`; 23 files, 6 numbered migrations)
- **Client/ORM**: SQLDelight; JVM driver is a SQLCipher build (`willena` sqlite-jdbc 3.51.2.0) so the DB is encrypted at
  rest, with the master key held in the OS keyring
- **Rationale**: type-safe SQL with a strict one-owner-per-table data-ownership rule (guide §4); encryption at rest fits
  the threat model of a messenger storing identity keys and history locally

## Build Tools & Package Management

- **Gradle 8.14.3** (Kotlin DSL), `TYPESAFE_PROJECT_ACCESSORS`, foojay toolchain resolver
- **Version catalog** `gradle/libs.versions.toml` — single source for dependency versions
- JVM 11 bytecode target; Android target conditionally enabled (SDK detection or `-PenableAndroid`) so clones without
  the Android SDK stay in sync

## Infrastructure

### Containerization

None — a distributed P2P app has no server infrastructure by design.

### CI/CD

**GitHub Actions** `desktop-release.yml`: JDK 21, `jpackage` native installers on tag (Msi/Deb/Rpm/AppImage/Exe)

### Hosting

None — peers and optional headless relay nodes (e.g. Raspberry Pi) *are* the infrastructure.

## Development Tools

### Linting & Formatting

`.editorconfig` (no star imports, per-file IntelliJ conventions); `checkComposeStringValues` Gradle task fails the build
on multi-line `strings.xml` values

### Type Checking

Kotlin static typing with `explicitApi()` on `core`; layering rules are enforced by governance tests rather than linters

## Key Dependencies

kotlinx coroutines 1.10.2, kotlinx-serialization 1.11.0 (**config only** — the wire protocol is hand-written binary
codecs, not JSON/serialization), kotlinx-datetime 0.7.1, kotlinx-io 0.8.1; Android: AGP 8.13.2, compileSdk/targetSdk 36,
minSdk 24

## Version Management

Single version catalog (`gradle/libs.versions.toml`); application version currently **0.0.6** (pre-release)

## Migration Path

Not applicable — the stack is current throughout (Kotlin 2.3.20, CMP 1.10.3, SQLDelight 2.3.2, coroutines 1.10.2).
Deliberately pending: ProGuard/R8 is disabled for release with documented reflection issues (`docs/guide.md` §8).

---
*Last Updated*: 2026-11-25 *Auto-detected*: technologies and versions auto-detected from build files and source; "20–40
people" scope user-confirmed.
