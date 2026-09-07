# Onboarding / bootmap handoff (sprint 4)

Where we left off — a working scratchpad, not full docs. See `global events.md` (§3, §8) for the
agreed design; this is the "what's done / what's left" list.

## Current state

Main code compiles (`:core:compileKotlinJvm`). Tests are intentionally broken (renames pending).

**Protocol** (`protocol/envelopes/BootstrapEnvelope.kt`)

- `BootstrapEnvelope` header carries a **protection scheme** (`BootstrapSecurityScheme`:
  `SECRET_AEAD`, `ACCOUNT_SIGNED`), AAD-bound — mirrors `MessageEnvelope.securityScheme`.
  The payload *kind* lives inside the payload, not the header.
- `BootstrapPayload` is a **sealed interface**: `Intro` (sponsor→newcomer, AEAD), `Invite`
  (newcomer→sponsor, out-of-band QR/CLI only), `RecoveryRequest` (recovering device→node,
  plaintext + account signature). Per-subtype codecs, `accountSignedDeviceBindingBytes()`.

**Protection** (`protection/envelope/BootstrapProtection.kt`)

- One symmetric `protect(payload, source, target, createdAt)` / `open(envelope)` pair, dispatching
  internally on kind/scheme (Intro→SECRET_AEAD, RecoveryRequest→ACCOUNT_SIGNED, Invite→error).
- `EnvelopeProtectionService.protectBootstrap(BootstrapPayload)` / `openBootstrap(envelope)`.

**Router**

- Unified flow `Router.bootstrapPackets: Flow<BootstrapPacketEvent>` (one per packet type; consumers
  dispatch by payload subtype).
- `BootstrapInboundHandler` authenticates by scheme (AEAD / account-sig) then emits; ACK/NACK/deferred
  handled by `InboundEnvelopeProcessor`.
- `Router.sendBootstrap(payload, target, targetEndpoint?)` → `BootstrapSender` (outbox,
  `dispositionRequested`, short lifetime). `targetEndpoint` is the out-of-band endpoint override
  for targets with no local row (persisted on the outbox row, preferred at dispatch).
- `outbox` / `dedup` deliberately carry **no FK to `devices`** (packet plumbing for not-yet-known
  peers; both tables are keyed scans, never joined). ACK/NACKs back to unknown sources use the
  transport-proven inbound endpoint as the override (`InboundEnvelopeProcessor` threads
  `TorIncomingEnvelope.source` through).

**Orchestrator** (`orchestrator.onboarding`)

- `OnboardingProvider` (newcomer; filters `Intro`; body filled: provisional account row
  (check-then-insert, admin=false/ACTIVE) + `seedProvisionalPeerDevice` (insert-only,
  `provisional = true`) + GLOBAL membership + `requestRangeSync(GLOBAL, dagHeadLamport)`;
  COMPLETE still awaits the projector's fold),   `RecoveryResponder` (standing; filters `RecoveryRequest`; body
  implemented except the
  append: `TODO()` helper `appendRecoveryAddDevice`, then the intro reply built from local
  identity + `maxLamportInRoom(GLOBAL)` and sent with the request's endpoint override and
  secret — stateless, concurrent-safe), both wired/started in all modes.
  The provider is the sole writer of the session store (`beginSession`; dormant at start
  unless a persisted session exists — no collector on a normal boot). `completeSetup` and the
  sponsor service never touch the store; the sponsor is stateless (explicit secret on the
  send path). Boot resume fully in place: persisted session → AWAITING_INTRO + re-armed timer, past deadline
  → burn + TIMED_OUT, fold-anchored check still a seam; `cancelOnboarding()` implemented
  (burn + IDLE, cancels the timer) with a service passthrough for the GUI.
  Every provider transition is logged (ORCHESTRATOR/SESSION_STATE_CHANGED) and mirrored onto
  `Orchestrator.onboardingState` (stable flow; the provider is recreated per start) — the
  headless onboarding UX until the CLI entrypoint exists.
- Runtime `OnboardingService.sponsorNewcomer(invite: Invite, admin)` (**TODO**).
- `completeSetup` for all four intents (Genesis / NewAccountFirstDevice / ImportAccountRecoveryKey /
  AddDeviceToExistingAccount), with secret generation + invites into `SetupResult(invite, recoveryKey)`.

## Open items (next work)

1. **ACK/NACK seam (done — no sink callback).** The ACK stays "authenticated + dispatched"
   (same contract as the message path: auth + decode + flow-emit, before the collector acts).
   Two mitigations instead of a router→orchestrator callback:
    - `BootstrapInboundHandler` answers responder policy itself, pre-emit: own newcomer session
      active → `Rejected(DECLINED)`; requester's account present-but-not-ACTIVE →
      `Rejected(DECLINED)`; account absent → `Deferred` (absence asserts nothing; dedup cleared
      so the sender's retry re-runs the check). New `PacketNackReason.DECLINED` stops the
      sender's retry schedule (`SystemInboundHandler` maps it to `RemoveFromOutbox`, like EXPIRED).
    - The newcomer's wait is bounded by a persisted absolute deadline stored with the secret in
      `BootstrapSessionStore` (magic-prefixed entry: old bare-secret entries fail closed as
      absent and are repaired away). In-memory timer while alive; boot check enforces the same
      instant after a restart. Expiry burns the secret and surfaces `OnboardingState.TIMED_OUT`
      (intro arrival extends the deadline once for the sync phase).
    - `bootstrapIntroLifetime` is now 5 min and doubles as the onboarding budget (packet lifetime
        + session deadline set at `completeSetup`), so late responders can't append chain entries
          for already-failed onboardings and stale replies hit a burned secret.
    - AEAD key split by direction: `protect` takes the sender's in-memory secret explicitly
      (sponsor QR scan / responder request copy) — `Router.sendBootstrap(..., sharedSecret)` —
      while `open` keeps the store-backed gate. Sponsors and responders are stateless (no
      session, no timer; concurrent sponsors/requests can't clobber a shared slot); the store
      is the newcomer's inbound gate only, and the provider is its sole writer
      (`beginSession`; `completeSetup`/service never touch the store).

2. **`sendBootstrap` targets the wrong device (done).** `BootstrapSender` sets
   `target = payload.device.deviceId`, but for an `Intro` `payload.device` is the *sender's* own device
   (handler checks `envelope.source == payload.device.deviceId`). So the intro is addressed to the
   sponsor itself. The target is out-of-band knowledge (newcomer's id from the `Invite`, or the
   responder's id from the composite endpoint) → fix is an explicit `target: PeerId` param on
   `sendBootstrap` / `Router`.

3. **Recovery-request delivery (done).** All outbound bootstrap packets ride the same outbox
   path: the requester (no `devices` row for the bootstrap node) enqueues with the composite
   endpoint (`SetupIntent.BootstrapEndpoint`: peerId + onion) as the endpoint override — no
   direct-to-endpoint special case, no provisional-row seeding (rejected: `devices.account_id`
   is NOT NULL, so a keyless row would force a fake account that leaks into broadcast joins).
   Decided by the two structural facts: `outbox`/`dedup` carry no `devices` FK, and the endpoint
   override is preferred at dispatch.

4. **`ImportAccountRecoveryKey` intent reshape (done).** `bootstrapTorEndpoint: TorEndpoint? = null`
   → mandatory `bootstrapEndpoint: BootstrapEndpoint` (peerId + onion). `completeSetup` builds the
   `RecoveryRequest` (account from the import, fresh device, own onion, fresh secret into
   `BootstrapSessionStore`), signs the device binding with the account key, and sends via
   `router.sendBootstrap`. Composite-endpoint string encoding assessed later.

5. **`BootstrapSessionStore` persistence (done).** Secret + absolute deadline live directly in the keyring-backed
   `KeyStore` as one magic-prefixed entry (no cache — same as the signing path, which does uncached roundtrips per
   packet on
   a hotter path; `introKey()` only fires for SECRET_AEAD bootstrap packets anyway).
   `setActiveSecret` persists first (fail loudly, never hold a secret we couldn't durably keep);
   `burn()` = `deleteKey` (no in-memory copy to zeroize). `completeSetup` burns any stale secret
   first, so wipe-the-dir stays the supported reset: identity keys self-heal by
   deterministic-keyId overwrite, master-key reuse is benign, and the secret was the only entry
   fresh provisioning doesn't regenerate (Genesis sets none). Cancel: all-modes
   `OnboardingProvider.cancelOnboarding()` (burn + IDLE) with an `OnboardingService` passthrough
   for the GUI button; headless CLI calls the same provider method (wipe + re-setup until that
   channel exists). Boot resume: provider re-enters AWAITING_INTRO on a persisted session (timer
   re-armed against the same deadline) or burns + TIMED_OUT past it; the
   already-anchored → burn + COMPLETE half is a seam awaiting the projector's fold. Deferred:
   NACK-vs-retry for keyring-blip protection failures (keystore read failure still surfaces as
   null → gate closed → protection failure dispositions back); any keystore measurement (needs a running app —
   availability-under-headless
   first, latency second).

6. **Test debt.** commonTest broken across all renames (BootstrapEnvelope/Messages codec, sender,
   handler, Router fakes). Needs a sweep before the onboarding TODOs land, since their implementations
   will want those fixtures. New tests written with this change (not yet runnable — the module
   doesn't compile): `BootstrapInboundHandlerTest` rewritten to the Intro/RecoveryRequest API
   incl. DECLINED/Deferred policy cases; new `BootstrapSessionStoreTest` (codec roundtrip,
   burn/extend, corrupt repair) and `DefaultOnboardingProviderTest` (boot resume, timer expiry,
   cancel). Still to sweep: `BootstrapIntroProtectionTest`, `BootstrapEnvelopeCodecTest`,
   `BootstrapSenderTest`, `BootstrapInboundIntegrationTest`, the `RecordingRouter` fakes in the
   sync/messaging tests, `DefaultEnvelopeProtectionServiceTest`. The fake identity repo additionally
   needs `seedProvisionalPeerAccount` / `isDeviceProvisional` / `isLocalAccountAdmin` (new
   interface methods).

## Details deferred

- `Invite` CLI/base64 convenience + composite endpoint string encoding.
- Genesis `AddAccount` (prevId == null) append — lands with global events / projector.
- §3 recovery rule (account-key-authorized `AddDevice`) — fold-side enforcement, lands with projector.
- Single commit API for global events (the projector — name TBD — exposes one `append` that
  signs, DAG-appends, broadcasts, and derives the tables; callers stop writing identity tables
  directly; sponsor/responder do zero direct writes).
- Projector commit must clear `provisional` on both tables, correct the placeholder-bound
  device's `account_id` to the chain account, and drop the placeholder account row (never
  chain-derived); take over GLOBAL `room_members` writes (the provider's membership insert is
  the pre-projector stand-in). `provisional=false` on the local device row is the anchor
  signal the provider already reads for COMPLETE.