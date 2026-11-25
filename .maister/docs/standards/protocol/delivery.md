## Delivery

The wire-and-delivery contract: ACK/NACK semantics, outbox, sync, and protection verdicts. This file derives from
docs/guide.md §6 (the authoritative delivery contract) with mechanism detail from docs/envelope-redesign.md §3 and §5.8.
The orchestrator and routing layers honor these rules together; the GUI only observes their outcomes (see
frontend/gui-boundary.md).

### Effectively-once delivery rests on the pull path

Every sent message is appended to the local DAG first; pings advertise DAG tips (frontiers); peers pull what they miss
with sync requests. The outbox push (dispatch + ACK/NACK retry) is a latency optimization only — a deferred or expired
push is a latency problem, never a loss. There is no "failed" user-visible delivery state while the pull path exists,
only "not known delivered yet".

Sources: docs (guide.md §6; envelope-redesign.md §5.6) · confidence 92.
Evidence:

- guide.md §6: "Effectively-once delivery rests on the **pull path**, not the push path … The outbox push (dispatch +
  ACK/NACK retry) is a latency optimization — a deferred or expired push is a latency problem, never a loss."
- guide.md §6 (Outcomes and delivery state): "There is no 'failed' state while the pull path exists — only 'not known
  delivered yet'."
- envelope-redesign.md §5.6: the GUI shows "sent" at hand-off and "delivered" only when it knows; there is no failure
  state while the pull path exists.

### Sync split: orchestrator owns what, router owns asking

The orchestrator owns what is missing, which accounts may hold it, and what may be served to whom (`pending_syncs`, the
serve gate). The router owns asking: which candidate device, when, and carrying the request and reply. Only candidate
devices with an open link are asked. A `DECLINED` NACK rules that device out for the sync; expiry or any other NACK only
moves it behind the other candidates (the next one is asked at once); an ACK leaves the backoff to schedule the next
attempt. The router owns pings as a mechanism (liveness, link adoption, availability); frontiers come from the
orchestrator per account, and what they mean (new syncs, reopening declined ones, re-pushing a removal) is decided
there. A frontier naming an unseen room gets a provisional `UNKNOWN` room row so a sync row can be created.

Sources: docs (guide.md §6) · confidence 91.
Evidence:

- guide.md §6: "The orchestrator owns **what** is missing … The router owns **asking**: which candidate device, when,
  and carrying the request and reply."
- guide.md §6: the DECLINED/expiry/ACK candidate handling; "The router owns pings as a mechanism … The frontiers inside
  a ping come from the orchestrator (`FrontierSnapshotProvider`, per account)."
- guide.md §6: "A frontier can name a room this device has never seen …: the room gets a provisional `UNKNOWN` row … so
  a sync row can be created."

### Outbox outcomes are reported once, by tag

Every outbox entry that someone waits on carries a tag saying what it is for (message push, bootstrap packet, sync
attempt, `FILE_TRANSFER` for every transfer we send and relays forward alike, `FILE_REQUEST` for REQUESTs). However the
entry leaves the outbox — ACK, terminal NACK, expiry — the outcome is reported once, by tag, to whoever waits on it. New
outbox traffic that needs its outcome gets a tag; it does not add another callback path. The due query loads only ids,
targets, tags and retry classes (never blobs), claims non-file rows first, hides targets already at their per-target
in-flight cap, and FILE chunk rows retry at their own flat delay (`FLAT`) instead of the message backoff (`STANDARD`).

Sources: docs (guide.md §6; envelope-redesign.md §5.8) · confidence 90.
Evidence:

- guide.md §6 (Outcomes and delivery state): "the outcome is reported once, by tag, to whoever waits on it. New outbox
  traffic that needs its outcome gets a tag; it does not add another callback path."
- guide.md §6: the due query loads only ids, targets, tags and retry classes; FILE chunk rows use the flat delay while
  OFFERs and REQUESTs keep the message schedule.
- envelope-redesign.md §5.8: tag kinds — `FILE_PUSH` and `FILE_FORWARD` collapse into `FILE_TRANSFER`; `FILE_REQUEST`
  stays.

### Only push-path ACKs are reported, per account

Upward, the router reports only push-path ACKs, per account (`MessageCarrier.deliveries` for messages,
`BootstrapCarrier.deliveries` for bootstrap packets). An ACK proves delivery; its absence proves nothing, since relays
and the pull path never ACK to the original sender. The user-facing delivery tracker records an account as holding a
message on a push ACK, or when that account's ping frontier descends from the message.

Sources: docs (guide.md §6) · confidence 92.
Evidence:

- guide.md §6: "Upward, the router reports only push-path ACKs, per account (`MessageCarrier.deliveries` for messages,
  `BootstrapCarrier.deliveries` for bootstrap packets). An ACK proves delivery; its absence proves nothing, since relays
  and the pull path never ACK to the original sender."

### Relay backup is a message mechanism; files never use it

Relay backup (`RelayBackup.PENDING` — copying an envelope's content to relays) applies to messages only. A message row
owing one gets the same routing header and content in a new envelope to each relay the reliability policy returns, once,
keeping the original's expiry. Files never use the outbox's relay backup: a seal's direct transfer deposits its
remainder itself when its peer looks gone, one deposit round per seal, the same seal at every relay, and a forwarding
relay never re-deposits (one relay hop, ever).

Sources: docs (guide.md §6; envelope-redesign.md §5.8) · confidence 90.
Evidence:

- guide.md §6: "Relay backup is a message mechanism … Files never use it: a seal's direct transfer deposits its
  remainder itself when its peer looks gone … one round per seal, the same seal at every relay, and a forwarding relay
  never re-deposits (one relay hop, ever)."
- envelope-redesign.md §5.8: "**The outbox carries no file logic.** `RelayBackup.PENDING` means 'copy this envelope's
  content to relays' and only messages use it."

### Protection verdicts are two-valued; retry policy is owned by the outbox

`ProtectionDisposition` is two-valued only: `PERMANENT` — these bytes are dead (bad signature, violation, corrupt, or
already opened); `DEFER` — prerequisites missing (session/identity not ready). There is no "retryable" protection
verdict; retry policy is owned by the outbox. The same two-valued discipline governs every boundary vocabulary in the
core: `PeerSendOutcome` (Queued/Deferred/PermanentFailure), `InboundHandleResult` (Success/Deferred/Rejected),
`OutboxOutcome` (Acked/Nacked/Expired), `NackAction` (REMOVE/KEEP), and the GUI-facing result enums
(`SendMessageResult`, `CreateRoomResult`/`CreateRoomRefusal`, `UpdateResult`, `InspectInviteResult`) so callers branch
exhaustively — "deferred" is a first-class verdict rendered as pending, never a failure.

Sources: docs (guide.md §6), code · confidence 92.
Evidence:

- guide.md §6 (Protection verdicts): "There is no 'retryable' protection verdict: retry policy is owned by the outbox."
- code: `ProtectionException` subclasses each pin a disposition — corrupt/auth/session-violation → PERMANENT;
  identity/session not ready/gap → DEFER; 8 distinct two-valued/outcome vocabularies found in the sampled core.
- code: `nackActionFor(reason)` is "exhaustive on purpose: a new `PacketNackReason` must decide here at compile time" —
  no else branch on closed types.

### DEFER is silent: never NACK a DEFER

A `DEFER` disposition is silent by design: no ACK, no NACK, and the dedup row is cleared, so the sender's resend is
processed fresh once the prerequisite lands. A NACK here would either replay a stale verdict for later duplicates or
falsely ACK the packet through the duplicate-replay path. Outbound DEFER (session not ready at protect time) surfaces
only as a deferred device count in the router's account report; the message stays in the DAG and delivery falls back to
the pull path; the orchestrator shows that member as pending, never as failed; there is deliberately no staging table.

Sources: docs (guide.md §6, §9) · confidence 92.
Evidence:

- guide.md §6 (Inbound and outbound dispositions): "`DEFER` is silent by design: no ACK, no NACK, dedup row cleared … A
  NACK here would either replay a stale verdict for later duplicates or falsely ACK the packet through the
  duplicate-replay path."
- guide.md §9 step 6: "Respect the delivery contract (§6): never ACK before handling, never NACK a `DEFER`, never delete
  on an unknown reason."
- guide.md §6: "Outbound `DEFER` … surfaces as a deferred device count in the router's account report … the orchestrator
  shows that member as pending, never as failed. There is deliberately no staging table."

### ACK means processed — never before decrypt-and-handle

ACK is sent only after a successful decrypt-and-handle, never before. Content opened before (a ratchet replay — the same
content by two routes) is `ProtectionException.Duplicate` and is ACKed: it was processed when it was first opened, so
the ACK is honest.

Sources: docs (guide.md §6, §9) · confidence 92.
Evidence:

- guide.md §6: "`ACK` means processed — never sent before a successful decrypt-and-handle. Content opened before (a
  ratchet replay: the same content by two routes) is `ProtectionException.Duplicate`, and is ACKed."

### Hop signature verified before dedup and handlers; unknown sources dropped

Every envelope is signed by its hop's source. A known source's signature is checked before dedup and before any handler
runs; a packet failing it is dropped unanswered. Only BOOTSTRAP (its content carries its own authentication) and an
ACK/NACK answering a bootstrap packet in our outbox addressed to exactly that device are accepted from a source we have
no key for; everything else from an unknown source is dropped.

Sources: docs (guide.md §6; envelope-redesign.md §3.1, §11) · confidence 91.
Evidence:

- guide.md §6: "A known source's signature is checked before dedup and before any handler; a packet that fails it is
  dropped unanswered."
- envelope-redesign.md §11: "`InboundEnvelopeProcessor` verifies a known source's signature before dedup, so a forged
  packet never claims a real packet id; a failed check is dropped unanswered."
- envelope-redesign.md §3.1: the two unverified exceptions — a BOOTSTRAP packet and the bootstrap-packet ACK/NACK —
  "Everything else from an unknown source is dropped."

### No inbound hold queue; sender owns until decrypt

The sender owns a message until it decrypts on the receiver: there is no inbound hold queue, and the sender's outbox
retry is the only recovery path for undecryptable packets. No table records opened frames.

Sources: docs (guide.md §6; envelope-redesign.md §3.3, §7) · confidence 90.
Evidence:

- guide.md §6: "The sender owns a message until it decrypts on the receiver: there is no inbound hold queue; the
  sender's outbox retry is the only recovery path for undecryptable packets."
- envelope-redesign.md §3.3: "No table records opened frames." / "**`OpenedFrameCache` is deleted.**"
- envelope-redesign.md §7: the rejected `opened_content` alternative — "a durable table for a window of milliseconds
  that the pull path already recovers."

### NACK semantics: pure function of reason; unrecognized never deletes

A received NACK's meaning is a pure function of its reason, decided in one place in the router. Terminal reasons
(`PERMANENT_PROTECTION_FAILED`, `DECODE_FAILED`, `UNSUPPORTED_TYPE`, `EXPIRED`, `DECLINED`) delete the outbox row;
`WRONG_TARGET` keeps it (endpoint reconciliation self-heals). An unrecognized reason never deletes — never act
destructively on confusion with a newer peer.

Sources: docs (guide.md §6, §9) · confidence 92.
Evidence:

- guide.md §6: "A received NACK's meaning is a pure function of its reason, decided in one place in the router. Terminal
  reasons … delete the outbox row; `WRONG_TARGET` keeps it (endpoint reconciliation self-heals). An unrecognized reason
  never deletes."
- guide.md §9 step 6: "never delete on an unknown reason."

### Outbox has no processing round; sends never open connections

Each due outbox entry is claimed (attempt and next retry recorded first) and sent on its own, so a slow send never
delays what is queued behind it; in-flight sends are capped globally and per target. Sends never open a connection: an
entry whose peer has no open link waits for one, with exactly two exceptions — bootstrap packets with an explicit
endpoint (Tor dials a stream on demand) and large-file fetches (a dedicated WebRTC data channel that never touches the
outbox, the envelope pipeline or Tor payloads).

Sources: docs (guide.md §6) · confidence 89.
Evidence:

- guide.md §6: "The outbox has no processing round: each due entry is claimed … and sent on its own … Sends never open a
  connection: an entry whose peer has no open link waits for one, with two exceptions" — bootstrap with explicit
  endpoint; large-file fetches.

### Unknown-device ACK/NACK accepted only for our bootstrap packet

An ACK/NACK from a device with no row is accepted only when it refers to a bootstrap packet in our outbox addressed to
exactly that device (a recovering device can't verify its sponsor's hop signature yet; the unguessable packet id
authenticates the reply). It is never answered. Accepted bootstrap ACKs are reported to the orchestrator so onboarding
can show "request delivered".

Sources: docs (guide.md §6) · confidence 90.
Evidence:

- guide.md §6: "an ACK/NACK from a device with no row is accepted only when it refers to a bootstrap packet in our
  outbox addressed to exactly that device … It is never answered. Accepted bootstrap ACKs are reported to the
  orchestrator so onboarding can show 'request delivered'."

### Dedup is hop-level, keyed by (source, packetId)

Packet dedup stays hop-level, keyed by (`source`, `packetId`). A sender's retries of one relay copy share its packet id,
so the relay's packet dedup keeps them from making extra copies; a file deposit is keyed by its transfer id.

Sources: docs (envelope-redesign.md §3.1, §3.2) · confidence 88.
Evidence:

- envelope-redesign.md §3.1: "Packet dedup stays as it is: hop-level, keyed by (`source`, `packetId`)."
- envelope-redesign.md §3.2: "A sender's retries of one relay copy share its packet id, so the relay's packet dedup
  already keeps them from making extra copies; a file deposit is keyed by its transfer id."
