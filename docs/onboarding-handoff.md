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
- `Router.sendBootstrap(payload)` → `BootstrapSender` (outbox, `dispositionRequested`, short lifetime).

**Orchestrator** (`orchestrator.onboarding`)

- `OnboardingProvider` (newcomer; filters `Intro`; **body is TODO**), `RecoveryResponder`
  (standing; filters `RecoveryRequest`; **body is TODO**), both wired/started in all modes.
- Runtime `OnboardingService.sponsorNewcomer(invite: Invite, admin)` (**TODO**).
- `completeSetup` for all four intents (Genesis / NewAccountFirstDevice / ImportAccountRecoveryKey /
  AddDeviceToExistingAccount), with secret generation + invites into `SetupResult(invite, recoveryKey)`.

## Open items (next work)

1. **ACK/NACK seam (design pending).** The ACK is sent when the handler returns `Success`, i.e. after
   auth + flow-emit, *before* the collector (provider/responder) does its work. A failed insert/relay
   is invisible to the sender. `InboundHandleResult.Deferred` exists (clears dedup → retry isn't
   swallowed) and is the natural landing spot for a registered-sink fix (handler awaits an orchestrator
   callback; maps failure → Rejected/Deferred). Convert both bootstrap flavors together.

2. **`sendBootstrap` targets the wrong device (correctness bug).** `BootstrapSender` sets
   `target = payload.device.deviceId`, but for an `Intro` `payload.device` is the *sender's* own device
   (handler checks `envelope.source == payload.device.deviceId`). So the intro is addressed to the
   sponsor itself. The target is out-of-band knowledge (newcomer's id from the `Invite`, or the
   responder's id from the composite endpoint) → fix is an explicit `target: PeerId` param on
   `sendBootstrap` / `Router`.

3. **Recovery-request delivery (parked "discuss later").** Even with the right target, the outbox
   resolves the target onion from the DB, and the requester has no row for the bootstrap node.
   Options: direct-to-endpoint send (`TorTransport.send(target: TorEndpoint, ...)`), or seed a
   provisional row from the composite endpoint. Same signature reshape as #2.

4. **`ImportAccountRecoveryKey` intent reshape.** `bootstrapTorEndpoint: TorEndpoint? = null` → a
   non-null composite (peerId + onion). `completeSetup` doesn't yet build/sign/send the
   `RecoveryRequest` (account key is in the keystore after recovery import, so the accountSignature is
   producible). Composite-endpoint string encoding assessed later.

5. **`BootstrapSessionStore` persistence.** Secret not persisted to the keystore → restart
   mid-onboarding loses it (TODO in the store).

6. **Handler hardening TODO.** Cross-check `payload.torEndpoint` vs transport-proven Tor onion once
   `handleTorInbound` plumbs the connection source through.

7. **Test debt.** commonTest broken across all renames (BootstrapEnvelope/Messages codec, sender,
   handler, Router fakes). Needs a sweep before the onboarding TODOs land, since their implementations
   will want those fixtures.

## Details deferred

- `Invite` CLI/base64 convenience + composite endpoint string encoding.
- Genesis `AddAccount` (prevId == null) append — lands with global events / projector.
- §3 recovery rule (account-key-authorized `AddDevice`) — fold-side enforcement, lands with projector.