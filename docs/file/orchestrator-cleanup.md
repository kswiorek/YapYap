# File-branch cleanup: the orchestrator side

This document records the review of the orchestrator half of the file branch (`orchestrator/file`, the
`[File cleanup]` TODOs, `runtime/file` and the file-send path of `DefaultMessagingService`) and the cleanup
that review ordered. It is the sibling of [`cleanup.md`](cleanup.md), which did the same for `routing/file`,
and it follows the same workflow: every point was discussed until the owner agreed to it. [`design.md`](design.md)
describes the steady state; this file records the agreed decisions and how the code gets there.
Where this file changes a mechanism (admission, retention, deletion), `design.md` is updated in the same
change.

**Status: agreed, not landed** (review of 28 November 2026). Line references are pre-cleanup.

Review scope: `orchestrator/file` (the store, descriptors, the receive engine and its `receive/` helpers,
the request coordinator, the request and plaintext sources, `FileSendService`, `FilePins`,
`FileRetention`), `runtime/file`, the file-send part of `runtime/message`, `OrchestratorConfig`'s file
fields and `wiring/Settings.kt`.

## 1. What the review found

The receive engine's model (byte coverage, durable-before-ACK staging, checkpoints, block verification) and
the trigger/routing split are sound. What the review found was retention with no clear rule, a send path
coupled to the message service, and duplicated mechanics:

- **Retention had three mechanisms with three rules.** A TTL sweep deleted received files no message
  referenced after `unreferencedFileRetention` (`FileRetention.kt:51`); a quota LRU evicted copies above
  `storeQuotaBytes`; the send path kept a `releasable` map, an `ImportResult.created` flag and shared
  `FilePins` (`FilePins.kt:13`) so a failed send did not delete a file another send was preparing. The pins
  were shared through wiring, and `FileRetention` defaulted its own `FilePins()`, which would silently not
  be shared.
- **An auto-download bypass.** `ReceiveEngine.offerVerdict` accepts an OFFER for an unknown file with no
  reference check (`ReceiveEngine.kt:421-447`). A pushed OFFER above `autoDownloadMaxBytes` (but within the
  small-file limit) that arrives before its message is accepted and downloaded; when the message lands,
  `markDeclined` → `declineUnseen` (`FileRequestCoordinator.kt:256`) is a no-op because a receive row
  already exists. The same gap is the only source of unreferenced receives, which the TTL existed to clean.
- **The send path.** `FileSendService` is named like a runtime service but is internal machinery
  (`FileSendService.kt:37`); one refused file fails the whole send and files hash one after another (`:91`); pushes run
  sequentially and **before** the message fan-out (`:173`, `DefaultMessagingService`
  `sendText`), so per-device sealing delays the text; a refused send is shown for a fixed 5 s and then
  disappears (`DefaultMessagingService.kt:348`); the messaging service owns a `tmpCleanup` job for the
  store's tmp files.
- **Four forms of "a path or a content URI"**: `PickedFileSource` (public, path as `String`,
  `MessagingTypes.kt:71`), `FileImportSource` (internal, `Path`), `FileDescriptor.path` + `FilePathKind`,
  and four `when` branches that size or open a source.
- **`MAX_ATTACHMENTS = 16`** (`DagPayload.kt:220`) is an invented number: it bounds the decoder, the
  send check and reserves ~7.4 KB of the frame in `ENCODED_HEADER_RESERVE_BYTES`.
- **The store layout is known in three places** (`FileStore`, `ReceivePaths.final`,
  `FileSendService.release`), and the evict sequence is duplicated (`FileRetention.enforceQuota`,
  `ReceiveEngine.evicted`).
- **Duplicated mechanics**: the receive engine's commit tail (three copies), its no-space decline (two),
  the "read until full" loop (five copies) and range hashing (four); the "does this device want the file"
  decision (two copies in the coordinator, one in the engine); hand-rolled catch-and-log (about a dozen,
  while routing already has `fileGuarded`); attachment lookups that load and parse every message.
- **Dead or loose types**: `FileReceiveStatus.CANCELLED` is never written; `FileReceiveRow.blockSize` and
  `blockHashes` are separately nullable, papered over with impossible fallbacks; a stray KDoc (`ReceiveEngine.kt:813`);
  doc/code disagreement on accepting an in-flight file.

## 2. The points and their agreed end states

Recorded at the depth they were agreed. Where this section and any later summary (including §3) disagree,
this section wins. Numbering follows the review: the flagged TODOs first (the two retention TODOs are one
point), then the issues found in the rest of the tree, then the orchestrator-side naming that
[`cleanup.md`](cleanup.md) §6 had deferred to this pass.

### 2.1 Point 1 — retention: gate at admission, no TTL, no quota, a deliberate delete

The owner's position: there is no mechanism that transfers a file without a message, so the only reason to
delete unreferenced files would be a member spamming files, and a TTL is not the way to solve that. Files
live until the user deletes them.

**2.1.1 Admission requires a referencing message.** `checkOffer` answers `Defer` when no stored message
references the file (`message_attachments`). `Defer` is silent, so the sender's outbox retries; the sender
sends the message before it pushes (Point 6), so in practice the message is already there, and in the rare
race the retry, or the pull that the message's arrival starts, covers it. This applies to both callers of
`checkOffer` (the small OFFER in `TargetSide` and the large path's `LargeFileRequester`); large pulls only
start from a message or a user accept, so they always pass.

- **Accepted cost:** a deferred OFFER sends no ACK, so in the race the sender sees "no ACK in time before
  the OFFER lands" and deposits the seal at relays; when the message lands the retried OFFER is accepted and
  the COMPLETE cancels the deposits. Bounded by the deposit-once rule, and rare.
- **Measured:** this deferral gets its own `LogEvent` (`FILE_OFFER_UNREFERENCED`), distinct from the
  generic defers, so how often it happens can be read from the logs.

**2.1.2 The auto-download decision is made at admission too.** With a referencing message present,
`checkOffer` applies the same auto-download predicate the coordinator uses on ingest: no receive row and
`totalBytes > autoDownloadMaxBytes` → the file is recorded DECLINED and the OFFER refused `NOT_RETRYABLE`.
One predicate, used by both, so there is no race between the DAG insert and the coordinator's
`declineUnseen`. This closes the bypass in §1.

**2.1.3 No time-based cleanup.** An unreferenced receive can no longer exist, so these are deleted: the
sweep loop, `OrchestratorConfig.unreferencedFileRetention`, the `origin_message_id` column, the
`updateOrigin` verb, `forgetUnreferenced`, `declineUnseen`'s `originMessageId` parameter,
`LogEvent.FILE_UNREFERENCED_DELETED`, and design invariant 25 (rewritten as the admission rule). No
receive-side startup collection is needed either.

**2.1.4 No quota.** Removed with it: `FileQuota`, `enforceQuota`,
`FileDescriptorReader.copyDescriptorsLeastRecentlyUsed`
and `copyBytesTotal`, the `last_access_at` column and every `touch` (`FileStore.touch`,
`FileDescriptorStore.touch`, the serve's touch on EOF, `localFilePath`'s touch), `FileReceiveStatus.EVICTED`
and the engine's `evicted` verb, `OrchestratorConfig.storeQuotaBytes` with its `storeQuotaBytes >=
autoDownloadMaxBytes` invariant, the `storeQuotaBytes` entry in `wiring/Settings.kt`, `LogEvent.FILE_EVICTED`,
and the "spare the file that just arrived" rule. `FileRetention` as a class disappears (what remains of
retention is the send side, 2.1.5, and the delete, 2.1.6).

**2.1.5 The send side: kept while referenced or being prepared.** The rule: *a file is kept while a message
references it or a send is preparing it.*

- A failed or cancelled send releases its attachments: each is unpinned, and on the last unpin, if no
  message references it, `FileStore.delete(fileId)` (Point 7) removes it. Who created the descriptor no
  longer matters, so the `releasable` map and `ImportResult.created` are deleted.
- `FilePins` stays as the guard against concurrent sends of the same file, **private to
  `OutgoingAttachments`** (Point 2) — no longer shared through wiring, no default instance.
- One startup pass, owned by `OutgoingAttachments`: the store's tmp files (`cleanTmp`) and descriptors no
  message references (a crash between import and append), read in one statement. It runs before any send
  is accepted: `prepare` waits on the pass internally, so `DefaultMessagingService` loses its `tmpCleanup`
  job and knows nothing about tmp files.

**2.1.6 A deliberate delete is a status of the file, on its descriptor.** A file the user deleted must not
be re-downloaded as if it were merely missing. Deletion is per file, not per transfer, so it lives in
`file_descriptors` (what we hold), not in `file_receives` (getting the file, or refused to get it).

- `FileDescriptorStatus` becomes `HELD`, `SOURCE_LOST`, `DELETED`. A DELETED descriptor stays as a marker
  of the deletion: metadata and block list are kept, the bytes are gone.
- `FileStore.markDeleted(fileId)` (the store is the table's only writer): DELETED first, then the bytes if
  the file is a COPY. A REFERENCE never has the user's original deleted.
- `FileTransferService.deleteFile(fileId)` → `FileUserControl.deleteFile` (the renamed
  `FileRequestControl`, Point 16), carried out by the pull coordinator, in this order:
    1. `store.markDeleted(fileId)` — first, because it is what blocks every later fetch;
    2. cancel the transfers: abort a live large pull, drop the pending request, and answer the transfer
       authors with `endIncoming(fileId, Cancelled(DECLINED))` so senders free their transfers;
    3. the engine drops the file's receive row, `.part` and staged rows.
- Only a held file (one with a descriptor) can be deleted; for anything else `deleteFile` is a no-op, and
  `declineFile` (unchanged) covers files not held. DECLINED stays a receive status, since a file never held
  has no descriptor.
- Effects: `checkOffer` checks the descriptor first and refuses DELETED `NOT_RETRYABLE`, before the
  `forgetLostReady` logic, so a deleted file is never mistaken for a lost one; `maybeRequest`, the sweep and
  `heldCoverage` treat DELETED as nothing to request; serving (`describe`, `canServe`, `localFile`) already
  takes only HELD.
- Undo: only `acceptFile`, whose `reopen` also removes the DELETED marker (descriptor removed), after which
  the pull starts. Re-sending the same bytes needs no special case: the import re-holds a DELETED
  descriptor of the same `fileId` exactly as it re-holds a SOURCE_LOST one.
- Crash repair: `recover()` checks every DELETED descriptor and removes any receive row, `.part`, staged
  rows and copy bytes still left.
- A file removed by hand from the app directory is *missing*, not deleted: it is re-fetched as today (the
  serve marks SOURCE_LOST, `forgetLostReady` lets it be received again, `localFile` returns null).

**2.1.7 The delete lands now; the rest of the GUI part is a TODO.** The core contract is complete in this
cleanup: `FileTransferService.deleteFile(fileId)`, per file id, as 2.1.6 describes, implemented and tested.
What is not built is the screen around it: listing held files, the delete action, progress,
accept/decline and open. `//TODO [File GUI]: list held files; delete, accept/decline, progress and open
on a screen` lands on `FileTransferService`, and guide §8's not-done list keeps the GUI file surface: files
are not ready until it lands. No listing read is added to the core until a screen uses it.

### 2.2 Point 2 — `FileSendService` becomes `OutgoingAttachments`

It is not a runtime service; it manages the lifecycle of an outgoing message's attachments. Renamed to **
`OutgoingAttachments` / `DefaultOutgoingAttachments`** (staying in `orchestrator/file`), with the verbs
`prepare`, `confirm`, `release`, `startPushes`, and a `start(scope)` that runs the startup pass of 2.1.5.
`cleanTmp` leaves its interface; `storeDirectory` leaves its constructor (Point 7).

### 2.3 Point 3 — preparing files: per-file results, a concurrency cap, no attachment count

**2.3.1 Per-file results and partial success.**

- `prepare` returns one result per file: `Prepared(attachment)` or `Refused(refusal)`
  (`FileUnreadable`, `NoSpace`, `SendFailed` for an unexpected exception).
- The message is appended with the files that succeeded; the refused ones stay visible to the GUI (Point 4) so it can
  offer a re-pick.
- If every file is refused, nothing is sent, even when there is text: the text was written about those
  files, and the GUI can put it back in the composer.
- The `enforceQuota` call at the end of `prepare` is gone (2.1.4).

**2.3.2 Concurrency.** Each file imports in its own child (`coroutineScope { … async … }`), at most
`maxConcurrentImports` at once (a semaphore). Unbounded parallel hashing of many large files on one disk
thrashes an HDD or slow phone storage. `OrchestratorConfig.maxConcurrentImports` is a plain field — local
mechanics, deliberately not in the settings `FIELDS` — with an `init` require `> 0`; its default is set at
implementation from what the import path can sustain, not invented to sound right.

**2.3.3 `MAX_ATTACHMENTS` is removed; the limit is size.** It was proposed to make it a network setting;
the agreed design removes the count instead:

- A network value enforced by the receiver cannot work for DAG content: a message is history that every
  device must accept, and lowering the value would make old, valid messages fail on devices syncing them
  later. A count could only be checked at send time, where the network part adds nothing.
- What actually limits a message is the frame: text plus attachments must fit. So:
    - `ENCODED_HEADER_RESERVE_BYTES` reserves only the fixed header; the attachment term goes, and the text
      maximum grows by those ~7.4 KB (still capped by the u16 text prefix).
    - `sendMessage` checks `text bytes + Σ encoded attachment sizes ≤ frame budget` and returns `TooLarge`
      **before any hashing**: the trimmed names, MIME types and the fixed-size `fileId` are all known up
      front. The encoded size of an attachment comes from the codec in `protocol`, never computed by hand.
      `wiring/MessageLimits` hands the messaging service the frame budget; `maxTextMessageBytes` stays for
      the GUI's text counter.
    - The decoder rejects `count > remaining bytes / minimum encoded attachment size`, a bound from the
      format that still stops the preallocation attack of a huge count; `Text`'s `require` on the count goes.
- Resource use stays bounded by `maxConcurrentImports` on the send side and by the receive side's own
  limits (auto-download, staging). A count limit, if ever wanted for UI reasons, is a GUI decision.

### 2.4 Point 4 — preparing-send state does not depend on a timer

A failed send stays in `preparingSends` until the GUI removes it:

```kotlin
public data class PreparingSend(
    val sendId: Uuid, val roomId: RoomId, val content: String,
    val files: List<PreparingFile>, val state: PreparingState,
)
public data class PreparingFile(
    val fileName: String, val hashedBytes: Long, val totalBytes: Long, val refusal: SendRefusal?,
)
public sealed interface PreparingState {
    data object Hashing : PreparingState

    /** A send-level reason, or null when only files were refused. */
    data class Failed(val refusal: SendRefusal?) : PreparingState
}
```

- Progress is per file (replacing the send-wide `hashedBytes`/`totalBytes`).
- When the message lands with no file refused, the entry is removed; with some refused, it becomes
  `Failed(null)` holding only the refused files; with all refused (nothing sent, 2.3.1), `Failed(null)`
  with all of them and the `content` for the composer. A send-level failure (e.g. `HistoryIncomplete` at
  the append) is `Failed(refusal)`.
- `cancelPreparingSend` becomes **`discardPreparingSend`**: it cancels a send still hashing and dismisses
  a failed one. `PREPARING_ERROR_SHOWN` and its `delay` are deleted.
- The registry (map, jobs, mutex — about 120 lines) moves out of `DefaultMessagingService` into a
  `PreparingSends` class in `runtime/message`.

### 2.5 Point 5 — one file-source type, as a kotlinx.io `Path`

- One public `sealed interface FileSource { LocalPath(val path: Path); ContentUri(val uri: String) }` in
  `orchestrator/file`, used by `PickedFile.source`, `FileStore.import` and `FileDescriptor.source`.
- Deleted: `PickedFileSource`, `FileImportSource`, `FileDescriptor.path`/`pathKind` and `FilePathKind` as
  a domain type. The schema keeps a path and its kind; `DefaultFileDescriptorStore` maps the two columns to
  `FileSource`. The misnamed `storage_kind` column is renamed `path_kind`.
- `FileAccess` takes the source: `size(source)` and `openReader(source): PositionedHandle`, which throws
  `IOException` uniformly (the JVM's content-URI reader throws instead of returning null). This removes the
  four `when` branches (`FileSendService.sourceSize`, `FileStore.sourceSize` and `openSource`,
  `DefaultFilePlaintextSource.open`) and the plaintext source's double failure handling (catch plus null
  check).
- `FileTransferService.localFilePath(fileId): Path?` becomes `localFile(fileId): FileSource?`, so the GUI
  can open sent content-URI references too.
- Schema changes amend the branch's `6.sqm` directly — no new migration file while the branch is unmerged (the rule [
  `cleanup.md`](cleanup.md) §2.5.3 records).

### 2.6 Point 6 — pushes run after the message, concurrently

`startPushes` moves after the message fan-out in `sendText`, so per-device sealing no longer delays the
text. Each (file, member) pair runs as its own guarded child in a `coroutineScope`. The orchestrator still
calls once per pair; the router decides small or large.

### 2.7 Point 7 — the store owns its directory

- `FileStore` owns the store directory and its layout: `delete(fileId)` removes the descriptor and its
  copy bytes in the crash-safe order (stop serving, then bytes, then the row; a failed delete is retried by
  the startup pass), never touching a REFERENCE's original; `adoptReceived(part, manifest)` does the
  rename into place, the directory fsync and the descriptor, keeping the engine's idempotent crash repair (a missing
  `.part` with the final file present completes); `markDeleted` per 2.1.6.
- `ReceivePaths` shrinks to `incoming/`. `OutgoingAttachments` no longer builds store paths.
- The duplicated evict sequence disappears with the quota (2.1.4).

### 2.8 Point 8 — the receive engine's duplicated commit logic

- One `commitPrefix(row, newP, out): DrainOutcome` holds the shared tail of `appendBytes`,
  `commitFetchPrefix` and `drainRow`: sync, `firstBadBlock`, `mismatch`, then either commit P and complete,
  or commit P, `deleteStagedBelow` and emit Progress.
- The "free space → `declineRow` → log" check becomes one helper.
- The stray KDoc at `ReceiveEngine.kt:813` goes; `reserve` stops filtering `active()` for IN_FLIGHT/STALLED (already
  guaranteed); `Announcements` becomes a plain `MutableList<FileReceiveEvent>`.

### 2.9 Point 9 — read, hash and write loops written once

Rule from the owner: **no extension functions on types we control**; each helper goes where it belongs.

- `readFully(offset, buf, len)` becomes a member of `PositionedHandle`, implemented by the platform handle.
  It replaces `FileStore.readFully`, the three loops in `DefaultFilePlaintextSource` and the one in
  `PartWriter.verifyBlock`.
- Range hashing becomes one function in the orchestrator's block-hash code next to `FileIds`, used by the
  store import, the plaintext source and `PartWriter`.
- `PartWriter.writeAt` merges into `appendAt(path, at, bytes, sync: Boolean)`.

### 2.10 Point 10 — one "does this device want the file" decision

- One `requestNeed(fileId): Held | Receiving | Ended | Wanted` in the coordinator, used by `maybeRequest`
  and the sweep: `Held` (descriptor HELD or receive READY), `Receiving` (IN_FLIGHT) and `Ended` (DECLINED,
  FAILED, or a DELETED descriptor) drop the pending row; `Wanted` (no receive, or STALLED) arms it.
- `progressGuardLocked` becomes `withLock`; `lastProgress` and `lastContiguous` become one
  `Map<FileId, GapMark>`; the sweep reads `receives.active()` once.

### 2.11 Point 11 — one catch-and-log shape for both layers

Routing's `fileGuarded` (`routing/file/FileGuard.kt`) moves to `logging` as `guarded(component, event,
message, fields) { … }` — `logging` is importable from every layer, so no new dependency — and replaces
every hand-rolled copy in the orchestrator (the coordinator's five inline blocks, the engine's three,
`startPushes`; `FileRetention.runTask` and the messaging `tmpCleanup` go away anyway). Routing's call sites
switch to it.

### 2.12 Point 12 — attachment fields as columns

- `message_attachments` gains `file_name`, `mime_type` and `total_bytes` (amending `6.sqm`).
- With `messages.sender_account_id`, `requestCandidates` becomes one `selectFileSenders(fileId)` join, and
  `findFileAttachment` and `requestTotalBytes` become single-row queries; no payload parsing.
- This is a first step of the owner's direction to map every message field to a column, which makes
  migrations and lookups easier. For now the payload blob stays the source of truth and the columns are an
  index. Constraint recorded for the later move: messages are signed and served byte-for-byte in sync, so
  once columns replace the blob, the codec must rebuild the exact signed bytes from them (deterministic
  encoding), or the signed bytes must stay stored.

### 2.13 Point 13 — dead vocabulary

`FileReceiveStatus.CANCELLED` is never written (its KDoc says "Reserved") and is removed with its branches.
`EVICTED` goes per 2.1.4. `FileReceiveStatus` ends as `IN_FLIGHT`, `STALLED`, `READY`, `FAILED`,
`DECLINED`.

### 2.14 Point 14 — a receive's block info is one nullable value

`FileReceiveRow.blockSize` and `blockHashes` (null together only on a DECLINED row created without an
OFFER) become one `manifest: ReceiveManifest?`. READY paths take it with a single `checkNotNull`;
`receivedDescriptor`'s fallbacks (`blockSize ?: blockSizeFor(...)`, `blockHashes ?: emptyList()`) go. The
`fileName ?: fileId.value` fallback goes too: `fileName` becomes non-null if every writer supplies it (the
OFFER and the attachment both carry one), checked at implementation.

### 2.15 Point 15 — accepting an in-flight file is a no-op

The docs disagreed (`FileTransferService.acceptFile`: in-flight "(re)starts the request";
`FileRequestControl.acceptFile`: "no-op when READY") and the code did a third thing (`reopen` returns true
for an active receive and the pull restarts `immediate = true`). Agreed: accepting a file that is READY or
already being received (IN_FLIGHT or STALLED) is a no-op; the stall trigger already re-asks stalled files.
`reopen` returns false for those, and both KDocs say so.

### 2.16 Point 16 — the orchestrator side takes the pull umbrella

[`cleanup.md`](cleanup.md) §6 deferred the orchestrator side of §2.5.3's naming; it lands here. The ask loop's
umbrella is **pull** on both sides of the seam (routing's `FilePullProcessor`, `FilePullAttemptStore`,
`FileCarrier.startFilePull`; the sync precedent is `pending_syncs` / `DefaultSyncCoordinator`):

- `DefaultFileRequestCoordinator` → `DefaultFilePullCoordinator` (file `FilePullCoordinator.kt`), the
  wiring field `fileRequestCoordinator` → `filePullCoordinator`, its private `maybeRequest` → `maybePull`
  and Point 10's `requestNeed` → `pullNeed`.
- Table `pending_file_requests` → `pending_file_pulls`; `PendingFileRequest.sq` → `PendingFilePull.sq`
  (generated `pendingFilePullQueries`); `PendingFileRequestStore` / `DefaultPendingFileRequestStore` /
  `PendingFileRequest` / `PendingFileRequestStatus` → `PendingFilePullStore` / `DefaultPendingFilePullStore`
  / `PendingFilePull` / `PendingFilePullStatus`; the store field `pending` keeps its name. Amends `6.sqm`.
- `FileRequestControl` → **`FileUserControl`**: with `deleteFile` it is no longer about requests but the
  user's say over a file (accept, decline, delete), named like its sibling `FileReceiveControl`.
- Log messages and KDocs say "pull" where they meant the trigger ("File request trigger …" → "File pull
  trigger …").
- Unchanged on purpose, for [`cleanup.md`](cleanup.md) §2.5.3's reasons: `FileRequestSource` and its
  implementation `DefaultFileRequestSource` (routing's port name), `FileHeader.Request`, and the REQUEST
  wording where it means that wire packet.
- Governance: `ArchitectureTest`'s `queryOwners`, guide §4's ownership table, `PublicApiSurfaceTest`
  (`PendingFileRequestStatus` → `PendingFilePullStatus`), and `FileMigrationJvmTest` follow the rename.

## 3. The change steps

Each step lands test-green on its own, with its docs per the guide's same-change rule. The agreed detail
lives in §2; the steps say only what moves when. Later steps use the steady-state names from step 1.

### Step 1 — Renames (Points 2, 16)

`FileSendService` → `OutgoingAttachments`; the pull umbrella on the orchestrator side (coordinator,
`pending_file_pulls` amending `6.sqm`, `PendingFilePull*`, `FileUserControl`); `ArchitectureTest`, guide §4,
`PublicApiSurfaceTest` and `FileMigrationJvmTest` with them.
Gate: the full existing suite, unchanged (renames and file moves only).

### Step 2 — Mechanical dedupe (Points 8, 9, 10, 11, 13, 14)

`guarded` into `logging` and both layers switched; `PositionedHandle.readFully`, range hashing,
`PartWriter.appendAt(sync)`; the engine's `commitPrefix` and no-space helper; the coordinator's
`pullNeed`, `withLock`, `GapMark`; `CANCELLED` removed; `ReceiveManifest`.
Gate: the existing file suites, behavior unchanged.

### Step 3 — Sources and the store's directory (Points 5, 7)

`FileSource` through `PickedFile`, the store, descriptors and `FileAccess`; `localFile`; `path_kind` in
`6.sqm`; `FileStore.delete`/`adoptReceived`; `ReceivePaths` shrunk.
Gate: `FileStoreJvmTest`, `FilePlaintextSourceJvmTest`, `JvmFileAccessTest`, `FileReceiveEngineJvmTest`.

### Step 4 — Retention and delete (Points 1, 15)

Quota removed (2.1.4, including `Settings.kt`); TTL removed (2.1.3, `origin_message_id` and
`last_access_at` out of `6.sqm`); admission gate with `FILE_OFFER_UNREFERENCED` and the auto-download
predicate in `checkOffer` (2.1.1–2.1.2); `DELETED`, `markDeleted`, `FileTransferService.deleteFile` /
`FileUserControl.deleteFile`, the recovery repair, the reopen path (2.1.6); accept-in-flight no-op (Point 15); the
`[File GUI]` TODO and guide §8 line (2.1.7).
Gate: new tests for the deferred unreferenced OFFER, the bypass fix (an OFFER above the limit after its
message), delete → refuse → accept again, delete of a COPY and of a REFERENCE (original untouched), a
delete's cancel reaching the transfer authors, delete crash recovery, `deleteFile` on a file not held;
`FileRetentionTest` replaced.

### Step 5 — Outgoing attachments and pushes (Points 2.1.5, 6)

Private pins, release through `FileStore.delete`, the startup pass and the gate `prepare` waits on;
`tmpCleanup` out of the messaging service; `startPushes` after the fan-out, concurrent.
Gate: the outgoing-attachments tests (renamed in step 1), the messaging send tests.

### Step 6 — Preparing sends and the size limit (Points 3, 4)

Per-file `prepare` with `maxConcurrentImports`; partial success; `PreparingSends`, the sealed state and
`discardPreparingSend`; `MAX_ATTACHMENTS` removed with the frame-budget check in `sendMessage`, the
decoder bound and the `MessageLimits` change.
Gate: new tests for partial send, all-refused, and the failed state persisting until discarded; codec
round-trip and bound tests for `DagPayload.Text`; `PublicApiSurfaceTest`.

### Step 7 — Attachment columns (Point 12)

The columns in `6.sqm`, the insert, `selectFileSenders`, the single-row lookups.
Gate: `FilePullCoordinatorTest`, the request-source tests.

## 4. The rename table

| Today (pre-cleanup)                                                           | Steady state                                                         |
|-------------------------------------------------------------------------------|----------------------------------------------------------------------|
| `FileSendService` / `DefaultFileSendService`                                  | `OutgoingAttachments` / `DefaultOutgoingAttachments`                 |
| `DefaultFileRequestCoordinator` (`FileRequestCoordinator.kt`)                 | `DefaultFilePullCoordinator` (`FilePullCoordinator.kt`)              |
| `FileRequestControl`                                                          | `FileUserControl`                                                    |
| table `pending_file_requests` / `PendingFileRequest.sq`                       | `pending_file_pulls` / `PendingFilePull.sq` (in `6.sqm`)             |
| `PendingFileRequestStore` / `PendingFileRequest` / `PendingFileRequestStatus` | `PendingFilePullStore` / `PendingFilePull` / `PendingFilePullStatus` |
| coordinator `maybeRequest`                                                    | `maybePull`                                                          |
| `PickedFileSource`, `FileImportSource`                                        | `FileSource` (`LocalPath`, `ContentUri`)                             |
| `FileDescriptor.path` + `pathKind` / `FilePathKind`                           | `FileDescriptor.source: FileSource`                                  |
| column `file_descriptors.storage_kind`                                        | `path_kind`                                                          |
| `FileTransferService.localFilePath(): Path?`                                  | `localFile(): FileSource?`                                           |
| `MessagingService.cancelPreparingSend`                                        | `discardPreparingSend`                                               |
| `PreparingSend.hashedBytes/totalBytes/error`                                  | `files: List<PreparingFile>`, `state: PreparingState`                |
| `routing/file/FileGuard.kt` `fileGuarded`                                     | `logging` `guarded`                                                  |
| `PartWriter.writeAt`                                                          | `appendAt(…, sync = false)`                                          |
| `FileReceiveRow.blockSize` + `blockHashes`                                    | `manifest: ReceiveManifest?`                                         |

Unchanged on purpose: `FileRequestSource` / `DefaultFileRequestSource`, `FileHeader.Request` (Point 16).

Deleted: `FileRetention`, `FileQuota`, `FileImportSource`, `ImportResult.created`,
`DagPayload.Attachment.MAX_ATTACHMENTS`,
`FileReceiveStatus.CANCELLED`/`EVICTED`, `FileReceiveControl.evicted`/`forgetUnreferenced`/`updateOrigin`,
`OrchestratorConfig.unreferencedFileRetention`/`storeQuotaBytes`, columns `origin_message_id` and
`last_access_at`, `LogEvent.FILE_UNREFERENCED_DELETED`/`FILE_EVICTED`, `PREPARING_ERROR_SHOWN`.

Added: `FileDescriptorStatus.DELETED`, `FileStore.delete`/`markDeleted`/`adoptReceived`,
`FileTransferService.deleteFile` / `FileUserControl.deleteFile`, `OrchestratorConfig.maxConcurrentImports`,
`PreparingFile`, `PreparingState`, `PreparingSends`, `LogEvent.FILE_OFFER_UNREFERENCED`,
`message_attachments.file_name/mime_type/total_bytes`.

## 5. Governance and docs touched in this change

- `PublicApiSurfaceTest`: `FileSource`, `PreparingFile`, `PreparingState` added; `PickedFileSource`,
  `FilePathKind` removed; `PendingFileRequestStatus` renamed `PendingFilePullStatus`. The GUI uses neither
  `preparingSends` nor `PickedFileSource` yet, so `composeApp` needs no change beyond `Settings.kt`'s
  removed field.
- `ArchitectureTest`: `queryOwners` follows the `pending_file_pulls` rename; no ownership change.
- Schema: all changes amend `6.sqm` (no new migration while the branch is unmerged); `FileMigrationJvmTest`
  follows.
- Docs: this file; [`cleanup.md`](cleanup.md) §6 (the orchestrator naming is no longer deferred: points
  here); [`design.md`](design.md) §8 (admission gate replaces the unreferenced paragraph), §9 (pull naming),
  §10 (no quota, the deleted-file marker, the delete verb, manual removal), §12–§13 (schema), §14 (states:
  no EVICTED or CANCELLED; DELETED on the descriptor), §15 (config), §16 (invariant 25 rewritten), §19 (test matrix); [
  `../guide.md`](../guide.md) §4 (the renamed table and the writers of `file_descriptors`
  and `file_receives`) and §8 (the GUI file surface still not done; the core delete is); the `.maister`
  roadmap and vision lines that point at the `[File cleanup]` TODOs.
- Tests are adapted to the new code, never the other way round.
- maister standards (`/maister/standards-update`) once it lands: admission gates over after-the-fact
  cleanup; no extension functions on types we control; limits by size from the frame, not invented counts.

## 6. Deferred (not in this cleanup)

- The GUI file surface beyond the core delete: listing held files, the delete action on a screen,
  progress, accept/decline and open (2.1.7, marked with the `[File GUI]` TODO).
- Storing every message field as a column instead of the opaque payload (2.12 is the first step).
- Revisiting the deferred-OFFER relay deposit if `FILE_OFFER_UNREFERENCED` shows it happens often (2.1.1).
