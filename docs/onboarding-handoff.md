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

- `OnboardingProvider` (newcomer; filters `Intro`; **body is TODO**), `RecoveryResponder`
  (standing; filters `RecoveryRequest`; **body is TODO**), both wired/started in all modes.
  Boot resume partially in place (persisted secret → AWAITING_INTRO at provider start; the
  fold-anchored check is a seam); `cancelOnboarding()` implemented (burn + IDLE) with a service
  passthrough for the GUI.
- Runtime `OnboardingService.sponsorNewcomer(invite: Invite, admin)` (**TODO**).
- `completeSetup` for all four intents (Genesis / NewAccountFirstDevice / ImportAccountRecoveryKey /
  AddDeviceToExistingAccount), with secret generation + invites into `SetupResult(invite, recoveryKey)`.

## Open items (next work)

1. **ACK/NACK seam (design pending).** The ACK is sent when the handler returns `Success`, i.e. after
   auth + flow-emit, *before* the collector (provider/responder) does its work. A failed insert/relay
   is invisible to the sender. `InboundHandleResult.Deferred` exists (clears dedup → retry isn't
   swallowed) and is the natural landing spot for a registered-sink fix (handler awaits an orchestrator
   callback; maps failure → Rejected/Deferred). Convert both bootstrap flavors together.

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

5. **`BootstrapSessionStore` persistence (done).** Secret lives directly in the keyring-backed
   `KeyStore` (no cache — same as the signing path, which does uncached roundtrips per packet on
   a hotter path; `introKey()` only fires for SECRET_AEAD bootstrap packets anyway).
   `setActiveSecret` persists first (fail loudly, never hold a secret we couldn't durably keep);
   `burn()` = `deleteKey` (no in-memory copy to zeroize). `completeSetup` burns any stale secret
   first, so wipe-the-dir stays the supported reset: identity keys self-heal by
   deterministic-keyId overwrite, master-key reuse is benign, and the secret was the only entry
   fresh provisioning doesn't regenerate (Genesis sets none). Cancel: all-modes
   `OnboardingProvider.cancelOnboarding()` (burn + IDLE) with an `OnboardingService` passthrough
   for the GUI button; headless CLI calls the same provider method (wipe + re-setup until that
   channel exists). Boot resume: provider re-enters AWAITING_INTRO on a persisted secret; the
   already-anchored → burn + COMPLETE half is a seam awaiting the projector's fold. Deferred:
   NACK-vs-retry for keyring-blip protection failures (lands with the item-#1 ACK-seam
   conversion); any keystore measurement (needs a running app — availability-under-headless
   first, latency second).

6. **Test debt.** commonTest broken across all renames (BootstrapEnvelope/Messages codec, sender,
   handler, Router fakes). Needs a sweep before the onboarding TODOs land, since their implementations
   will want those fixtures.

## Details deferred

- `Invite` CLI/base64 convenience + composite endpoint string encoding.
- Genesis `AddAccount` (prevId == null) append — lands with global events / projector.
- §3 recovery rule (account-key-authorized `AddDevice`) — fold-side enforcement, lands with projector.