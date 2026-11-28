## Concurrency

How the core's coroutines are shaped so one slow or hung piece of work never stalls its neighbours. This file derives
from docs/file/cleanup.md (§2.5.1, §2.6, §2.11.1 and the §7 post-landing review), which settled the rules while
restructuring the file-transfer tree; they apply to any long-lived or per-item work in the core.

### Long-lived per-item work runs as a session over a mux, never on a shared serial loop

Each outgoing transfer is an actor session (`OutgoingTransferSession`; on the large path `LargeFileRequester` /
`LargeFileHolder`) with its own mailbox and coroutine owning its state, and it ends itself. The owning engine is only a
registry, start-time recovery, and a mux that posts inputs into session mailboxes. A session that hangs blocks only
itself. A periodic tick is a fixed cadence — a ticker feeding a `CONFLATED` channel that the loop selects ahead of the
mailbox — never an idle timeout on the mailbox, so a busy mailbox cannot starve expiry.

Sources: docs (docs/file/cleanup.md §2.5.1, §7) · confidence 90.
Evidence:

- cleanup.md §2.5.1: each outgoing transfer "becomes an `OutgoingTransferSession` — its own mailbox … its own guarded
  coroutine"; "the engine shrinks to the registry + start-time recovery + a mux"; "a session that hangs … blocks only
  itself".
- cleanup.md §7: "The transfer session's tick is a fixed cadence. It was an idle timeout on the mailbox, so a steady
  stream of messages … starved expiry"; a ticker now feeds a conflated tick channel selected ahead of the mailbox.
- routing/file/transfer/OutgoingTransferSession.kt (`mailbox` + `Channel<SessionMsg.Tick>(Channel.CONFLATED)`),
  routing/file/transfer/TransferEngine.kt (registry + mux).

### Nothing slow on a shared path

Crypto, plaintext file IO and transport sends run in their own guarded coroutine; an outbox enqueue (a DB write plus a
wake) may stay inline. Locks guard planning only: plan under the mutex, execute outside it — the seal registry's
two-phase fanouts, with an in-memory in-flight guard (a per-operation lease) instead of a placeholder row. No per-call
timeouts layered on local work; expiry and sweeps are the backstop.

Sources: docs (docs/file/cleanup.md §2.6, §2.11.1) · confidence 90.
Evidence:

- cleanup.md §2.11.1: "route all sends through separate coroutines … unless it is just an enqueue, which is fast and
  involves no actual send"; `outbox.enqueueAndWake` stays inline.
- cleanup.md §2.6: "plan under the mutex, execute outside it, per device, in guarded coroutines"; "The in-flight guard
  is
  in-memory, not a placeholder row"; "No per-call timeouts on top" — the sweep, expiry and the pull path recover.
- routing/file/seal/SealRegistry.kt (two-phase push/serve/cancel/deposit).

### Structured children, not stored scopes; one catch-and-log shape

A component's coroutines are children of the call or of the `runIn` scope (`coroutineScope`), never a late-init scope
field. Catch-and-log goes through one helper that rethrows `CancellationException`, catches `Exception` (not
`Throwable`) and logs the throwable — `fileGuarded` in routing/file/FileGuard.kt — instead of hand-rolled try/catch
copies.

Sources: docs (docs/file/cleanup.md §7) · confidence 90.
Evidence:

- cleanup.md §7: "The large path has no late-init … a pull runs its session loop as a child of the pull call, a serve
  runs on the coroutine the mux launched for its PULL, so nothing needs a stored scope."
- cleanup.md §7: "One catch-and-log shape (`routing/file/FileGuard.kt`, `fileGuarded`) replaces the hand-rolled
  try/catch copies"; "`DefaultRouter`'s collectors catch `Exception`, not `Throwable`, and log the stack trace."
- routing/file/large/LargeFileRequester.kt, routing/file/large/LargeFileMux.kt, routing/file/FileGuard.kt,
  routing/router/DefaultRouter.kt collectors (`catch (e: Exception)`).
