package org.yapyap.routing.file.port

import kotlinx.coroutines.flow.Flow
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.LargeFileAbortReason

/**
 * Account-level file operations and events (design §12), declared by routing and implemented
 * by the router. The orchestrator speaks files and accounts; routing fans out per device and
 * signals per transfer. Source devices may appear in events as facts, never as addresses.
 */
internal interface FileCarrier {
    /** Hot event flow: send progress and outcomes, serves and request outcomes, account-level only. */
    val fileEvents: Flow<FileEvent>

    /**
     * Start pushing [fileId] to every device of [target]. A file above the network's small-file
     * limit is a silent no-op — large files are pull-only, not a failed push (no `Failed` event).
     */
    suspend fun pushFile(fileId: FileId, target: AccountId)

    /** End our push of [fileId] to [target]'s devices (user cancel). */
    suspend fun cancelFile(fileId: FileId, target: AccountId)

    /** Per-account send state, computed from our seals of [fileId] (no table of its own). */
    suspend fun sendState(fileId: FileId): Map<AccountId, FileSendState>

    /**
     * Starts asking candidate devices for [fileId] (idempotent). [immediate] expresses the
     * trigger's origin, never a size: automatic triggers omit it, and routing pins the class by
     * the file's total bytes and schedules — a small file waits the push window (`filePullDelay`,
     * the time a direct OFFER gets to arrive), a large one starts at once. Explicit triggers (the
     * user's accept, a stall re-open) set it for now at any size. What to ask for and which
     * accounts may hold it come from the [FileRequestSource]; routing asks one candidate at a
     * time until a seal of the file (small) or PULL_OK (large) arrives, or every candidate
     * declines.
     */
    suspend fun startFilePull(fileId: FileId, immediate: Boolean = false)

    /**
     * Aborts our live large-file pull of [fileId] with [reason] (`STALLED` for the receiver gap
     * trigger, `CANCELLED` for the user), ABORTing and closing only the requester — never our
     * serves of the same file. Idempotent: unknown files are a no-op. The small path needs
     * nothing (its gap trigger cancels no transfer).
     */
    suspend fun abortLargeFile(fileId: FileId, reason: LargeFileAbortReason)

    /**
     * The receive of [fileId] ended ([end]). For [FileReceiveEnd.Ready] routing tells every live
     * incoming transfer's sender that it holds everything and sends each seal's origin a
     * COMPLETE; for [FileReceiveEnd.Cancelled] it CANCELs the live incoming transfers. The
     * incoming transfer rows go either way. Incoming only: our own sends and serves of the same
     * file are separate, ended by their own targets.
     *
     * File-scoped, never peer-scoped: the orchestrator never names devices. The answer is
     * politeness — an unanswered author's transfer ends on its own TTL — so this is idempotent
     * and may be called again for the same file.
     */
    suspend fun endIncoming(fileId: FileId, end: FileReceiveEnd)
}
