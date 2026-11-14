# File transfer: implementation plan

This plan turns [`design.md`](design.md) into a sequence of reviewable changes. The design stays the source of
truth for *what* is built. This plan only orders the work and says where each piece goes. If a step and the
design disagree, fix one of them before you write the code. [`../sprints.mmd`](../sprints.mmd) stays
authoritative for sprint sequencing: this plan covers s5c/s5d and the file half of s6a/s6b at a finer grain.

## Ground rules for every step

These come from [`../guide.md`](../guide.md) and apply to every step below.

**Layers and dependencies**

- **One layer per piece, picked by vocabulary** (guide §9.1).
    - Files, accounts and the `fileId` are orchestrator code.
    - Devices, transfers, envelopes, the outbox and relays are routing code.
    - Bytes on a channel are transport code.
- **Dependency table** (guide §3). When routing needs the orchestrator, routing declares the interface in its
  own package and the orchestrator implements it. This applies to `FileChunkSource`, the ingest port and
  `FileCarrier`. `ArchitectureTest` must stay green after every step, with no new entries in its debt list.

**Data**

- **One owner per table** (guide §4). Each table is written only by the owner in the guide's ownership table.
  Generated `*Queries` stay inside the owning store. Column enums live with the owning store, not in
  `persistence`.
- **Typed data** (guide §4, data types). `FileId` and `TransferId` are value types in `protocol` ids.
    - Raw strings and bytes appear only at codecs and column adapters.
    - Generated SQLDelight classes never leave their store.
- **Migrations.** Every schema change ships with a numbered `.sqm` migration (the next one is `6.sqm`). The
  migration runs with foreign keys off and is checked with `foreign_key_check`.

**Wiring and delivery**

- **Composition root.**
    - Components take interfaces through their constructors and subscribe to their own inputs in `start()`.
    - `wiring` contains no `launch`/`collect`.
    - Config types live next to their layer (`FileServiceConfig` with the file service, fields on
      `RouterConfig`). New config fields land in the step that first uses them.
- **Delivery contract** (guide §6).
    - Never ACK before handling.
    - Never NACK a `DEFER`.
    - Never delete on an unknown reason.
    - Every outbox entry someone waits on carries a tag.

**Docs**

- **Docs stay honest.** A step that moves a rule in the guide updates the guide in the same change. Steps 4,
  19 and 23 do this. A step that settles a detail the design left open writes it back into `design.md`.

Each step lists its packages, its tables, what it changes, the tests it brings, and when it counts as done.
"Done" always also means: `ArchitectureTest` passes, all existing tests pass, and nothing is wired that isn't
used yet unless the step says so.

---

## Phase A — Foundations (no behavior yet)

### Step 1 — Incremental SHA-256 (`crypto`)

- **Changes:** `CryptoProvider` gains a streaming hash, for example `sha256Hasher(): Sha256Hasher` with
  `update(bytes, offset, length)` and `digest()`. `DefaultCryptoProvider` implements it on cryptography-kotlin's
  hash function (`hasher().createHashFunction()`, already on the classpath at 0.6.0). The one-shot `sha256`
  stays.
- **Tests:** streaming over a split input equals one-shot `sha256`; the empty input; input fed in 1-byte and in
  odd-sized pieces.
- **Done when:** the design's §20 `CryptoProvider` row is true.

### Step 2 — Ids and the `fileId` rule (`protocol`, `orchestrator/runtime/file`)

- **Changes:**
    - `protocol` gains `FileId` and `TransferId` value types, plus the `fileId` preimage layout and the
      block-size rule as pure functions over bytes (design §2):
        - `blockSizeFor(totalBytes)`;
        - the preimage `"yapyap-file-v1" ‖ u64 totalBytes ‖ u32 blockSize ‖ h₀ … hₙ₋₁`.
    - Hashing happens outside `protocol` (guide §2: "codecs encode and decode; hashing happens elsewhere", the
      `RoomIds` pattern). A `FileIds` helper in `orchestrator/runtime/file` computes block hashes and the
      `fileId` through `CryptoProvider`, and checks a block list against a `fileId`.
- **Tests:** fixed vectors for an empty file, one short block, exactly one block, an exact multiple, and a
  short last block; the block-size doubling at 1024 blocks; a block list that doesn't match its `fileId` is
  rejected.

### Step 3 — Message attachments on the wire (`protocol`, `wiring`)

- **Changes:**
    - `MessagePayload.Text` gains `attachments: List<Attachment>` (`fileId`, `fileName`, `mimeType`,
      `totalBytes`, design §3.1), with codec support and round-trip tests.
    - The `Text` header reserve grows to cover attachments, and `wiring/MessageLimits` threads the new budget
      through, so the orchestrator's max text size shrinks by what attachments cost.
- **Tests:** round trip with 0, 1 and many attachments; a payload at the size limit with attachments still
  fits the transport frame.
- **Done when:** old messages (no attachments) decode unchanged, so existing DAG content stays valid.

### Step 4 — `FileEnvelope` rework (`protocol`)

- **Changes:** per design §3.2:
    - OFFER with a public half (chunk size, total bytes, start index) and an opaque sealed half;
    - CHUNK without `chunkCount`;
    - ACK in the transfer's chunk indices;
    - COMPLETE with no fields;
    - sealed REQUEST;
    - CANCEL with a reason.

  Every packet but REQUEST is addressed by `TransferId`. The sealed half's plaintext layout (`fileId`, block
  list, key, name, mime) is a separate codec, encoded before protection seals it. Drop `FileChunk`,
  `FileControlPayload`, `FileTransferClass` and `FileTransportPreference`. Update the `FileEnvelope`
  observability profile: no `fileId` outside sealed bytes.
- **Tests:** rework `FileEnvelopeCodecTest`; add a codec-level check that no envelope kind carries a `fileId`
  in clear (design §19).

### Step 5 — Schema reset for files (`persistence`, stores in their owners)

- **Changes:**
    - Migration `6.sqm` drops the placeholder `file_transfers` and `file_chunk_tracker`, and their indexes,
      from `Schema.sq`. Nothing reads them yet.
    - It creates `message_attachments` (design §13).
    - `FileTransferStatus` and `FileChunkStatus` leave `persistence/DatabaseEnums.kt`; the owning stores
      define their own enums when their tables land.

  The remaining file tables are created by the steps that first write them, each with its own migration, so
  no table exists without an owner and a writer.
- **Tests:** migration test from schema 5 with placeholder rows present, then `foreign_key_check`.
- **Done when:** the guide §4 rows still match: `message_attachments` is owned by the DAG engine, and the
  file tables listed there have no `.sq` yet.

### Step 6 — Attachment index at DAG ingest (`orchestrator/dag`)

- **Changes:**
    - The DAG engine writes `message_attachments` in the same transaction that inserts a message, for both
      locally authored and ingested messages.
    - Add a read-only lookup `fileId → messageIds` for the serve gate (step 13).
    - The message send API (`orchestrator/runtime/message`) accepts attachment references; it doesn't move
      bytes yet.
- **Tests:** insert, orphan arrival, and duplicate ingest each produce one index row per (message, file).

---

## Phase B — The file store and local I/O (orchestrator)

### Step 7 — File I/O module (`orchestrator/runtime/file/io`)

- **Changes:** the chunk-shaped I/O boundary (design §11):
    - **Append-only writer:** append, fsync, and truncate-to-length at open. kotlinx.io.
    - **Linear reader with a positioned start:** `expect`/`actual` — JVM and Android over `FileChannel`, iOS
      over `pread`.
    - **Free-space query** for the volume.

  Reference mode on Android needs content-URI access, which the core can't do alone. The orchestrator declares
  a small `FileAccess` interface in its own package; `Platform` supplies the implementation and the
  composition root connects them. The orchestrator never imports `wiring` (guide §3).
- **Tests (JVM):**
    - read from an offset inside a large sparse file without reading the prefix;
    - truncate a torn append back to a length;
    - fsync is called once per flush.

### Step 8 — The file store and send-side import (`orchestrator/runtime/file`)

- **Tables:** `file_descriptors` (migration `7.sqm`).
- **Changes:**
    - `FileStore`: import in copy mode (copy and hash in one pass) or reference mode (hash in place); the
      directory layout; the quota with LRU over copies only; `SOURCE_LOST`.
    - `FileServiceConfig` with `smallFileMaxBytes` and `copyIntoStoreMaxBytes`.
    - The import result is a descriptor carrying the `fileId` and block list. The GUI only gets paths and
      metadata vended back (guide §2, design §10).
- **Tests:**
    - copy then delete the original: still served;
    - reference then edit the original: the block check fails and the file becomes `SOURCE_LOST`;
    - quota eviction never touches references.

### Step 9 — The block-checking chunk source (`orchestrator/runtime/file`)

- **Changes:** implement the `FileChunkSource` behaviour (the port itself is declared in step 11).
    - Read plaintext at any offset, starting from the start of the block that contains it.
    - Hash each block and compare it to the list before handing out its bytes.
    - Stop on a mismatch.
- **Tests:**
    - a serve that starts mid-block;
    - a rotten block mid-file stops at that block and marks the descriptor;
    - a 1 GiB file is served from 900 MiB without reading the prefix.

---

## Phase C — Small files, direct push

### Step 10 — Real `FileProtection` (`protection`)

- **Changes:**
    - Seal and open the OFFER's sealed half and the REQUEST through the existing ratchet session machinery,
      like messages.
    - Chunk AEAD through `encryptAead`/`decryptAead` with AAD (`transferId`, `chunkIndex`).
    - Generate per-transfer keys with `randomBytes`.
    - Map failures to `ProtectionDisposition` (`PERMANENT` / `DEFER`) so crypto exceptions never cross into
      routing (guide §3).
    - Remove `PlaintextFileProtection`.
- **Tests:**
    - AAD binding: a chunk replayed under another index or transfer fails;
    - the same plaintext under two transfers gives different ciphertext;
    - a sealed OFFER half decrypts after a session supersede within retention;
    - a replay of a sealed half fails.

### Step 11 — Router ports, outbox extensions, incoming transfers (`routing`)

- **Tables:** `file_incoming_transfers` (migration `8.sqm`), with its store in `routing/store`.
- **Ports declared by routing:**
    - `FileChunkSource` (implemented by the orchestrator, step 9);
    - `FileIngestPort` (synchronous: `(fileId, byteOffset, plaintext) → Staged | Duplicate | Defer | Refused`,
      implemented in step 12);
    - `FileCarrier` on `Router` (account-level ops and file events, design §12).
- **Outbox:** `OutboxTag.Kind.FILE`; `packetId` on `OutboxOutcome`.
- **`FileInboundHandler`, local target:**
    - OFFER: open the sealed half, check the block list via the port, create the incoming-transfer row, ACK.
      The free-space and accept verdicts come through the port.
    - CHUNK: look up the row, decrypt, call the ingest port, then ACK / DEFER (silent) / DECLINED as design
      §3.5–§3.6 and §8 say.
    - COMPLETE / CANCEL: end the row.
    - An unknown `transferId` gets DECLINED.

  Foreign targets are still rejected in this step; relays come in Phase E.
- **Tests:**
    - a chunk before its OFFER is never ACKed;
    - an unknown `transferId` is DECLINED;
    - DEFER from the port produces no ACK or NACK and clears dedup;
    - rows are dropped on COMPLETE, CANCEL and expiry.

### Step 12 — The receive engine (`orchestrator/runtime/file`)

- **Tables:** `file_receives`, `file_receive_chunks` (migration `9.sqm`).
- **Changes:** design §8:
    - byte coverage and staging bounded by `maxStagedBytes`;
    - checkpoints (`fileCheckpointBytes`/`fileCheckpointInterval`: append, one fsync, one transaction advancing
      P);
    - a running block hash, with P never committed past an unverified block;
    - a block mismatch truncates to the block start (`hash_mismatches`, `fileHashRetryLimit`);
    - the free-space reserve;
    - READY by atomic rename, then COMPLETE to the live authors through `FileCarrier`;
    - restart truncates to P and re-hashes the partial block.

  Implements `FileIngestPort`.
- **Tests:**
    - mixed chunkings complete one file;
    - a racing transfer hits the staging bound and gets DEFER;
    - crash between the append and the P commit;
    - crash between checkpoints;
    - a block mismatch keeps the earlier blocks;
    - ENOSPC is a clean failure;
    - an already-READY file answers COMPLETE at its OFFER.

### Step 13 — The pusher, direct only (`routing`, file push package)

- **Tables:** `file_pushes` (migration `10.sqm`).
- **Changes:** design §6.1–§6.2 and §6.5:
    - per-device fan-out of an account-level send;
    - the OFFER marker (DECLINED split, expiry);
    - chunk outbox rows in a `maxInFlightChunks` window;
    - resume from rows plus the outbox after restart;
    - COMPLETE resolves the transfer; a chunk DECLINED ends it quietly;
    - user CANCEL.

  No deposits yet.
- **Tests:** restart mid-push resumes without re-sending ACKed chunks; a target DECLINED on the OFFER is
  terminal; a DECLINED on a chunk resolves the transfer as COMPLETED.

### Step 14 — Send API and the first end-to-end (`orchestrator/runtime/file`, `wiring`)

- **Tables:** `file_transfers` (orchestrator, migration `11.sqm`).
- **Changes:**
    - `FileTransferService.send(peer, picked file, keepCopy)`: import, classify, publish the message with
      attachments, start the per-device push for small files.
    - Wire everything in `NodeGraph`.
    - `FileLimits` in `wiring` derives the chunk size from `WebRtcBackendConfig`/Tor limits, like
      `MessageLimits`.
- **Tests:** two-node integration test (the existing router integration harness): a small file over a direct
  link reaches READY with an identical `fileId`.
- **Done when:** a small file moves between two online devices.

---

## Phase D — The pull path

### Step 15 — Extract the candidate core from sync (`routing/sync`, refactor only)

- **Changes:** pull the candidate-asking loop out of `SyncRetryProcessor` into a shared core with a callback
  seam for "on success" (design §9). Sync behaviour doesn't change.
- **Tests:** the existing `SyncRetryProcessorTest` passes unchanged.

### Step 16 — File requests (`routing`, `orchestrator/runtime/file`)

- **Tables:** routing `file_request_attempts`, `file_request_declined_devices`; orchestrator
  `pending_file_requests` (migrations `12.sqm`, `13.sqm` — one per owner).
- **Routing:** the file request processor on the shared core (one candidate at a time, ACK, then WAITING for
  the OFFER, DECLINED moves to the next candidate); the sealed REQUEST is direct-only.
- **Orchestrator:**
    - the REQUEST trigger on message ingest, with the grace period and re-requestable vs refused states;
    - the serve gate through `message_attachments` and the sync gate's visibility check;
    - serve-on-REQUEST through the pusher, with a fresh transfer and the floor-aligned start;
    - one live transfer per (`fileId`, target device), where a REQUEST supersedes a push;
    - after a hash mismatch, the same device first.
- **Tests:**
    - history sync on a fresh device pulls the file;
    - a non-targeted sibling pulls after the grace period;
    - the serve gate refuses an account with no visible message;
    - all candidates declining gives UNAVAILABLE;
    - a block mismatch triggers a re-request from the same device, then FAILED at the limit.

---

## Phase E — Relays and stalls (small path)

### Step 17 — Extract the deposit-trigger policy (`routing/outbound`, refactor only)

- **Changes:** move the relay-backup triggers out of `OutboxProcessor`/`RelayDepositor` into a shared policy,
  and generalize the MESSAGE-only no-ACK check (design §5). Message behaviour doesn't change.
- **Tests:** `OutboxRelayBackupTest` and `OutboxProcessorTest` pass unchanged.

### Step 18 — The relay store (`routing`, file relay package)

- **Tables:** `file_relay_transfers`, `file_relay_chunks` (migration `14.sqm`).
- **Changes:** design §7:
    - signature check at the door;
    - DECLINED for a dead `transferId` or an out-of-range index;
    - admission against the file share of `relayStoragePledgeBytes` (the split happens in `wiring`);
    - the wholeness gate;
    - forwarding with the same pusher over a DB chunk source;
    - whole-transfer LRU eviction;
    - forget after delivery.

  `FileInboundHandler` handles foreign targets.
- **Tests:**
    - a bad signature is never stored;
    - a partial transfer is never forwarded;
    - eviction mid-deposit leads to a DECLINED at the next chunk;
    - a target's DECLINED on the forwarded OFFER drops the transfer;
    - a forwarding relay never re-deposits.

### Step 19 — Deposits, stalls and the gap trigger (`routing`, `orchestrator/runtime/file`)

- **Routing:**
    - the stall detector (`chunkAckTimeout`);
    - deposit-on-stall: one round, one transfer and one sealed OFFER per relay returned by
      `RelaySelectionPolicy`;
    - no chasing of a failing relay.
- **Orchestrator:** the gap trigger (`fileStallTimeout`): CANCEL the live transfers, then STALLED, then
  re-REQUEST.
- **Guide:** state in §6 that FILE markers and chunks use the shared deposit triggers.
- **Tests:**
    - target offline: deposit, wholeness, forward, READY;
    - a link break mid-send: the remainder from the contiguous ACKed prefix;
    - two relays: two `transferId`s, both decrypt;
    - sender killed after the deposit: the relays still deliver.
- **Done when:** design §19's small-file rows are covered.

---

## Phase F — Large files

### Step 20 — WebRTC file channels (`transport/webrtc`)

- **Changes:**
    - A per-transfer file channel kind with labels, alongside the envelope and AV channels.
    - `bufferedAmount` low-threshold events.
    - Channel open and close per label on an existing peer connection, without renegotiation.
- **Tests:** two channels stream at once without starving the envelope channel; a low-threshold callback
  fires; channels close when the session ends.

### Step 21 — The stream (`routing`, file stream package)

- **Changes:** design §6.6:
    - the holder side: open the channel after the OFFER's ACK, stream in order, credit window (`fileStreamWindowBytes`)
      plus `bufferedAmount`;
    - the receiver side: in-order chunks straight to the ingest port, ACK at checkpoints;
    - the transfer ends with its session on both ends;
    - stream control packets travel on the channel and never go through the outbox;
    - `file_pushes.kind = STREAM` (a migration if the enum's check constraint needs one).
- **Tests:**
    - a slow-disk receiver bounds the holder at the window;
    - a session drop ends both rows;
    - no chunk crosses Tor (transport assertion).

### Step 22 — Large requests and the accept decision (`routing`, `orchestrator/runtime/file`)

- **Routing:**
    - the request processor opens a WebRTC session for a large request (`SessionTransport.openSession`,
      timeout `fileSessionOpenTimeout`);
    - it sends the REQUEST only over that session;
    - an unreachable candidate is not a decline, so the request stays PENDING until a candidate comes online.
- **Orchestrator:**
    - the auto-download decision (`autoDownloadMaxBytes`);
    - the user accept/decline API (a local DECLINED, re-openable);
    - large files skip the push in `send`.
- **Guide:** §6 says "Sends never open a connection", with bootstrap as the only exception. Add large file
  requests as the second exception, in this change.
- **Tests:**
    - auto-download end to end;
    - above the limit nothing is requested until accepted;
    - a session drop mid-file resumes as a new serve from P with a positioned read;
    - all holders offline: PENDING, then fetched when one comes online.

---

## Phase G — Surface and hardening

### Step 23 — GUI API contract and settings (`docs/gui-api.md`, `wiring`)

- **Changes:**
    - Write the send / progress / accept / decline / open contract into `gui-api.md`: the keep-a-copy flag,
      attachment states, `SOURCE_LOST`, and "preparing" while hashing.
    - Map the user-facing settings (`autoDownloadMaxBytes`, `copyIntoStoreMaxBytes`, the pledge) in the
      settings registry.
    - Update the guide's §8 feature status.
- **Done when:** the GUI can build the feature against `gui-api.md` alone.

### Step 24 — Test matrix and soak (`jvmTest`)

- **Changes:** fill the remaining design §19 rows. Add a soak test that moves a multi-GB file through a
  stream with forced session drops and a receiver restart, and checks memory stays bounded and the resume
  count is right.
- **Done when:** every §19 row maps to a named test.

---

## Order and parallelism

- **The trunk:** Phase A (steps 1–6), then Phase B (7–9), then Phase C (10–14).
- **After step 14**, Phase D (15–16) and Phase E (17–19) only meet in the pusher and can go in either order.
  D comes first because the pull path is the safety net that makes every later failure recoverable.
- **Phase F** needs step 16 (requests) and step 12 (the receive engine). It doesn't depend on Phase E.
- **Refactors run alone.** Steps 15 and 17 are pure refactors. Land them on their own, with existing tests
  green, before the features that need them.
