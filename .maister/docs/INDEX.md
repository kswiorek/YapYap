# Documentation Index

**IMPORTANT**: Read this file at the beginning of any development task to understand available documentation and
standards.

## Quick Reference

### Project Documentation

Project-level documentation covering vision, goals, architecture, and technology choices.

### Technical Standards

Coding standards, conventions, and best practices organized by domain.

---

## Project Documentation

Located in `.maister/docs/project/`

Generated from project analysis on 2026-11-25 (maister-init Phase 6). These four documents describe YapYap as it
actually is; read the ones relevant to your task. Standing caveat that appears throughout: the file-transfer
implementation is freshly redesigned and **unreviewed** — do not mine it for conventions.

### Vision (`project/vision.md`)

YapYap's mission and design premises: a decentralized, serverless messenger for small trusted networks (20–40 people) —
every device runs the same Kotlin core, all traffic is E2EE (X3DH + Double Ratchet) over Tor with store-and-forward
relays, and WebRTC (signaled over Tor) is the low-latency / large-file path. Covers current state (pre-release v0.0.6;
desktop works end-to-end, mobile behaviors not done), owner-selected goals (review the unreviewed file branch; wire the
GUI file-transfer surface), and project evolution.

### Roadmap (`project/roadmap.md`)

Where the project stands and what's next: working features (Tor/WebRTC transports, router with outbox/relays/ACK/sync,
1:1 and group E2EE, DAG-based history, sponsor-QR onboarding, SQLCipher persistence, Compose GUI); owner-selected high
priorities (review & finalize the unreviewed file-transfer branch; wire the GUI file-transfer surface); medium
priorities (mobile behaviors, E2EE backlog, docs drift fixes); technical debt; future considerations (AV/media,
hardening).

### Tech Stack (`project/tech-stack.md`)

Full technology choices with versions and rationale: Kotlin 2.3.20 (KMP) + Compose Multiplatform 1.10.3 GUI; kmp-tor
2.6.0 (embedded Tor), webrtc-java 0.14.0, cryptography 0.6.0 (X3DH + Double Ratchet); SQLDelight 2.3.2 over SQLCipher
with a keyring-held master key; Gradle 8.14.3 with a single version catalog; governance tests as build gates;
deliberately framework-light core.

### Architecture (`project/architecture.md`)

System architecture: layered like a small network stack (GUI → Orchestrator → Router → Transports → Protocol) with
strict vocabulary separation enforced by governance tests (`ArchitectureTest`, `PublicApiSurfaceTest`,
`GuiApiBoundaryTest`); per-layer component map (orchestrator services/DAG engine, router carriers, two-phase wiring);
send/receive data flow with pull-based effectively-once delivery; external integrations (Tor, WebRTC, OS keyring,
SQLCipher).

---

## Technical Standards

The project-specific standards below derive from YapYap's own documentation (docs/guide.md is the rules-of-the-code),
its code patterns, and its build configuration. Each file carries per-standard provenance (`Sources:` and `Evidence:`
lines) citing the authoritative in-repo documents.

### Architecture Standards

Located in `.maister/docs/standards/architecture/`

Structure, dependency, ownership and assembly rules for the core — the largest and most binding rule set, from
docs/guide.md §2–§5 and the §9 checklist. Every module (including the GUI-facing boundary) must obey them.

#### Layering (`standards/architecture/layering.md`)

Layer vocabulary separation (orchestrator speaks accounts/rooms/payloads, router speaks devices/envelopes/retries,
transports move bytes), orchestrator never addresses devices, router treats payloads as opaque, transport types stop at
the router, router as lifecycle plus one service per concern, per-room/per-account fan-out split, protocol as the bottom
of the graph with hand-written codecs (kotlinx.serialization for config only).

#### Dependencies (`standards/architecture/dependencies.md`)

The layer dependency table (import rules), config/persistence never import a layer back, lower layers declare their own
ports (never import upper-layer types), exceptions cross a layer boundary only as the receiving layer's vocabulary,
ArchitectureTest enforcement with a debt list that only shrinks.

#### Data Ownership (`standards/architecture/data-ownership.md`)

Every table has exactly one owning layer, folds are the only writers of chain-derived state (services publish events),
router reachability data never enters the identity model, the full IdentityStore is held only by the composition root,
generated SQLDelight queries stay in their owner's package.

#### Data Types (`standards/architecture/data-types.md`)

Keys, ids and entities are typed with fail-fast `require()` validation (never raw bytes/strings), keyring entries are
KeySlots with stable names, one entity per table named for what it is, generated SQLDelight classes never leave their
store.

#### Composition Root (`standards/architecture/composition-root.md`)

Two-phase Vault/Node composition built fresh every start, orchestrator never constructs (wiring implements its lifecycle
ports), components subscribe to their own inputs (wiring has no launch/collect), constructor injection only (no service
locators, no lateinit), routing is its own composition root below wiring.

#### Config and Limits (`standards/architecture/config-and-limits.md`)

Each layer defines its own config type next to its code, size limits are chained by the composition root from the
transport limits, content-lifetime config invariants (content lifetime < supersededSessionRetention) are checked at
wiring.

#### API Surface (`standards/architecture/api-surface.md`)

Explicit API mode for :core — explicitApi () plus the PublicApiSurfaceTest two-tier whitelist (GUI API and
shared-internal vocabulary); internal by default, unlisted public types fail the build.

### Protocol Standards

Located in `.maister/docs/standards/protocol/`

The wire-and-delivery contract from docs/guide.md §6, docs/envelope-redesign.md (design of record, 2026-11-24) and
docs/e2ee.md. The orchestrator and routing layers must honor these rules together.

#### Delivery (`standards/protocol/delivery.md`)

Effectively-once delivery rests on the pull path, sync split (orchestrator owns what, router owns asking), outbox
outcomes reported once by tag, only push-path ACKs reported per account, relay backup is a message mechanism only,
two-valued protection verdicts (PERMANENT/DEFER), silent DEFER (never NACK a DEFER), ACK means processed, hop signature
before dedup, no inbound hold queue, NACK semantics as a pure function of reason, sends never open connections,
hop-level dedup keyed by (source, packetId).

#### Envelope (`standards/protocol/envelope.md`)

One BinaryEnvelope v2 for every packet family, every hop signs (content copied verbatim, never re-signed), contentId =
SHA-256 (content) derived off-wire, expiry is the content's and never extended, relay admission only from the origin
(one hop, ever), ratchet Replay ACKed as a duplicate, honest observability profiles, plain pre-fleet schema/wire
changes.

#### Files (`standards/protocol/files.md`)

File grains (fileId orchestrator, sealId/transferId routing), one seal per (file, target device) with fresh seals on
serve, seal key never leaves routing and protection, chunk AEAD binds the byte range, relays forward only whole
transfers, HANDED_OFF/DELIVERED completion, incoming seal row written at open, seal registry owns seals, fileId stays
off the wire — per envelope-redesign.md (docs/file/ is provisional and unreviewed).

#### E2EE (`standards/protocol/e2ee.md`)

Session epochs (3-DH first contact, optional in-band 4-DH upgrade), YapYap-specific HKDF labels, Signal-aligned ratchet
bootstrap, ratchet header authenticated via AEAD AAD, canonical session invariant and sessionGeneration, handshake
re-attach until reply, wire metadata cross-validation, OPK lifecycle, device-id tie-break for simultaneous init,
transactional ratchet decrypt, decode bounds as protocol constants, immutable device identity, cleartext outerHandshake
as a conscious choice.

### Build Standards

Located in `.maister/docs/standards/build/`

Gradle build-system conventions — in this project the build scripts are the linter config: explicitApi, check-task
gates, conditional targets.

#### Toolchain (`standards/build/toolchain.md`)

JVM 11 bytecode everywhere with Temurin JDK 21 toolchain and auto-provisioned JDKs, official Kotlin code style,
project-wide experimental opt-ins (ExperimentalUuidApi, -Xexpect-actual-classes).

#### Gradle Conventions (`standards/build/gradle-conventions.md`)

Single-source version catalog (libs.versions.toml via libs.* accessors), typesafe project accessors, Foojay toolchain
auto-provisioning, configuration-cache-compatible build logic (explicit opt-out with justification).

#### Android (`standards/build/android.md`)

Conditional Android target — SDK-free by default (applied only when an SDK is configured or -PenableAndroid=true),
manifest in a non-conventional path for SDK-free IDE sync.

#### Dev Environments (`standards/build/dev-environments.md`)

Isolated multi-instance dev runs: dedicated --data-dir under working-dir/ plus --keyring-ns namespace, --loopback for
local-only runs.

#### Release (`standards/build/release.md`)

Tag-driven multi-OS desktop release (v* tag, ubuntu+windows matrix, jpackage, GitHub Release with notes);
packaging-build-only gate recorded truthfully with a TODO to add a ./gradlew check step before the next tagged release.

### Global Standards

Located in `.maister/docs/standards/global/`

These standards apply across the entire codebase, regardless of frontend/backend context.

#### Error Handling (`standards/global/error-handling.md`)

Clear user-facing messages, fail fast, typed exceptions, centralized handling, graceful degradation, retry with backoff,
resource cleanup. Project application: two-valued typed verdicts (PERMANENT/DEFER) at boundaries, lower-layer exceptions
translated into the receiving layer's vocabulary.

#### Validation (`standards/global/validation.md`)

Server-side validation always, client-side validation for feedback, validate early, specific error messages, allowlists
over blocklists, type and format checks, input sanitization, business rules, consistent enforcement. Project
application: fail-fast typed ids with require (), wire decode bounds as protocol constants.

#### Conventions (`standards/global/conventions.md`)

Predictable project structure, up-to-date documentation, clean version control, environment variables, minimal
dependencies, consistent reviews, testing standards, feature flags, changelog updates, build only what's needed.

#### Coding Style (`standards/global/coding-style.md`)

Naming consistency, automatic formatting, descriptive names, focused functions, uniform indentation, no dead code, no
backward compatibility unless required, DRY, no star imports in Kotlin (explicit per-key imports; enforced via
.editorconfig).

#### Commenting (`standards/global/commenting.md`)

Let code speak for itself, comment sparingly and only for non-obvious logic, no change-history comments, KDoc on types
and contracts explaining semantics and failure modes.

#### Minimal Implementation (`standards/global/minimal-implementation.md`)

Build what you need, clear purpose, delete exploration artifacts, no future stubs, no speculative abstractions, review
before commit, unused code is debt.

#### Documentation (`standards/global/documentation.md`)

guide.md is the rules-of-the-code (code/doc disagreement is a bug), same-change rule for doc updates,
envelope-redesign.md as design of record for envelope + file topics (docs/file/ provisional), check the §8 not-done list
before assuming a surface exists, AI assistants ground in .maister docs and run maister workflows via the skill tool.

#### Design Constraints (`standards/global/design-constraints.md`)

Correctness, resilience and maintainability win over scale (small trusted network), no dependent servers (the same core
runs on every device, headless relays included), relay retention and selection premises, two-tiered account/device
identity.

#### Reactive State (`standards/global/reactive-state.md`)

StateFlow/Flow as the observable surface: backing MutableStateFlow/MutableSharedFlow fields exposed via asStateFlow ()
/asSharedFlow (), used by core components, ConfigStore distribution, and GUI controllers alike.

#### Logging (`standards/global/logging.md`)

AppLog facade with typed LogComponent/LogEvent closed enums, platform AppLogger delegate, no println or logging
framework in common code; the GUI surfaces failures as toasts/state instead of logging.

#### Vocabularies (`standards/global/vocabularies.md`)

Sealed interfaces with data class/object variants for result and state vocabularies; exhaustive when-statements are
deliberate (no strings/booleans as vocabulary, no else branch on closed types).

### Frontend Standards

Located in `.maister/docs/standards/frontend/`

These standards apply to the Compose Multiplatform GUI (:composeApp) — component design, state, resources, and the
boundary against :core.

#### GUI Boundary (`standards/frontend/gui-boundary.md`)

The GUI reaches the core through exactly three types (OrchestratorFactory, Orchestrator, OrchestratorRuntime — ask,
don't bypass; GuiApiBoundaryTest enforces the allow-list), suspend service calls with sealed-value refusals, hot flows
collected in the GUI's own scope, reuse core types (a new enum value should break the GUI's when), display strings are a
GUI concern, GLOBAL never listed / UNKNOWN filtered / invites opaque, visible LOCAL marker for loopback, resetApp state
gates, GUI module uses default visibility.

#### Components (`standards/frontend/components.md`)

Single responsibility, reusability, composability, clear interface, encapsulation, consistent naming, local state,
minimal props, documentation, Yap-prefixed design system with variant enums (YapButton/YapTheme/yapColors; variants as
small enums, not boolean flags).

#### State (`standards/frontend/state.md`)

Controller pattern: screen-scoped controllers with constructor scope + services, private MutableStateFlow backing
exposed as StateFlow, process-level YapSession owning the app scope and Orchestrator, runCatching error surfacing as
toasts/state — never thrown into composition.

#### Resources (`standards/frontend/resources.md`)

Single-line Compose string resource values (newlines render as blank lines in CMP); enforced by the
checkComposeStringValues Gradle task wired into check, with .editorconfig keeping formatters from re-wrapping.

#### Accessibility (`standards/frontend/accessibility.md`)

Semantic HTML, keyboard navigation, color contrast, alt text and labels, screen reader testing, ARIA when needed,
heading structure, focus management.

#### Responsive Design (`standards/frontend/responsive.md`)

Mobile-first, standard breakpoints, fluid layouts, relative units, cross-device testing, touch-friendly targets, mobile
performance, readable typography, content priority.

### Backend Standards

Located in `.maister/docs/standards/backend/`

These standards apply to the Kotlin core (:core) — identity, persistence, the event chains, transports, and code
structure. Generic database guidance (models, queries, migrations) is retained from the baseline.

#### Identity (`standards/backend/identity.md`)

Account signing key exists only on the first device, private keys via LocalKeys / peers' public keys via PeerKeys (throw
on missing), keys live in the OS keyring never in the data directory.

#### Persistence (`standards/backend/persistence.md`)

Schema changes ship with a migration (FKs off + foreign_key_check), persistence is infrastructure not a layer (stores
live with their owner), the log is the source of truth (no FKs from messages to identity tables).

#### Events (`standards/backend/events.md`)

The event-chain rules from docs/global events.md and docs/room events.md: GLOBAL room as the source of truth for
identity/RBAC, canonical-order folds, authenticity-only verdicts, verificationPolicy tiers, engine as sole verdict
writer, revocation seals the frontier, validity-paradox doctrine, self-certifying ids, removal as status flip,
store-don't-drop, no positional checks, merge commits, render-time membership visibility in SQL, read direction, sync
candidate rules, sibling folds with paired fuzzers, dedicated genesis method, out-of-band INVITE.

#### Transport (`standards/backend/transport.md`)

One SessionTransport contract (sends only on open sessions), links never closed by a keeper, backend as single writer of
session state (observers in start ()), never dial an unpublished onion (publish localTorEndpoint once).

#### Structure (`standards/backend/structure.md`)

Interface + Default* implementation pairs (Default* referenced only by wiring/tests), PascalCase file-per-type
with <Area>Types.kt vocabulary files.

#### Models (`standards/backend/models.md`)

Clear naming, timestamps, database constraints, appropriate types, index foreign keys, multi-layer validation, clear
relationships, practical normalization.

#### Queries (`standards/backend/queries.md`)

Parameterized queries, avoid N+1, select only needed columns, index strategic columns, transactions, query timeouts,
cache expensive queries.

#### Migrations (`standards/backend/migrations.md`)

Reversible migrations, small and focused, zero-downtime awareness, separate schema and data changes, careful indexing,
descriptive names, version control.

### Testing Standards

Located in `.maister/docs/standards/testing/`

These standards apply to all testing code (unit, integration, governance).

#### Test Writing (`standards/testing/test-writing.md`)

Test behavior not implementation, clear names, mock external dependencies, fast execution, risk-based testing, balance
coverage and velocity, critical path focus, appropriate depth.

#### Test Doubles (`standards/testing/test-doubles.md`)

Hand-written Fake*/Noop*/Recording* doubles named by behavior (no mockito/mockk anywhere); shared doubles in
org.yapyap.testfixtures, area fixtures colocated with their tests.

#### Test Style (`standards/testing/test-style.md`)

kotlin.test with method_state_expectation names (no backticks, file name matches class), coroutine tests as
expression-body runTest/runBlocking functions with deterministic doubles controlling time.

#### Governance Tests (`standards/testing/governance-tests.md`)

Codec round-trip tests for every new wire type, negative-test discipline for verification and visibility (per-path
tests), the E2EE required-test matrix, paired fold fuzzers re-run on any seal fix, live integration tests opt-in via
-PintegrationTests (WebRTC natives can be flaky). Layer and GUI-boundary governance live in architecture/dependencies.md
and frontend/gui-boundary.md.

---

## How to Use This Documentation

1. **Start Here**: Always read this INDEX.md first to understand what documentation exists
2. **Project Context**: Read relevant project documentation before starting work
    - Vision and roadmap for understanding project goals
    - Tech stack for understanding technology constraints
    - Architecture for understanding system design
3. **Standards**: This index only points to the standards — open and follow the specific standard files relevant to your
   task; don't rely on the index alone
    - Architecture/protocol/build standards are project-specific and binding
    - Global standards apply to all code
    - Domain-specific standards (frontend/backend/testing) apply to relevant code
4. **Keep Updated**: Update documentation when making significant changes
    - Update project docs when goals, tech stack, or architecture changes
    - Update standards when team conventions evolve
    - Update INDEX.md when adding, removing, or significantly changing documentation
5. **Customize**: Adapt all documentation to your project's actual needs
    - Project documentation should reflect your actual project
    - Standards should reflect your team's conventions

## Updating Documentation

### When to Update

- **Project docs**: When project goals, tech stack, or architecture changes
- **Standards**: When team conventions evolve or new patterns are adopted
- **INDEX.md**: When adding, removing, or significantly changing documentation

### How to Update

1. Edit the relevant documentation file directly
2. Update INDEX.md if the file's purpose or description changes
3. Ensure CLAUDE.md still references this INDEX.md
4. Commit changes to version control

---

## Documentation Priority

When making development decisions, follow this priority order:

1. **Project documentation** in `.maister/docs/` (highest priority)
    - Represents team decisions and project-specific requirements
2. **Code patterns** visible in the codebase
    - Shows how the team actually implements things
3. **User's direct instructions**
    - Specific guidance for the current task
4. **General best practices** (lowest priority)
    - Default to industry standards when no specific guidance exists

**The documentation in `.maister/docs/` represents team decisions and should be followed unless the user explicitly
overrides them.**

---

**Last Generated**: 2026-11-25 — standards-discover Phase 8 (153 standards applied from discovery findings in
config/code/docs/external analysis; three new categories added — architecture/, protocol/, build/; web-only baseline
files backend/api.md and frontend/css.md pruned; INDEX regenerated)
**Maintained by**: Documentation Manager skill
