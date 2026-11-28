package org.yapyap.routing.store

import org.yapyap.protection.envelope.FileProtection
import org.yapyap.protocol.ContentId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SealKey
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.FileSeal
import org.yapyap.protocol.envelopes.TransferShape
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Why a seal exists: our own send, or a serve answering a REQUEST. */
internal enum class FileSealKind {
    PUSH,
    SERVE,
}

/**
 * A seal's delivery state at its origin (docs/envelope-redesign.md §5.6). SENDING and HANDED_OFF
 * are live; the rest are final.
 */
internal enum class FileSealStatus {
    /** Transfers are still carrying it. */
    SENDING,

    /** The target's prefix plus one relay's whole remainder cover the file: relays deliver. */
    HANDED_OFF,

    /** The target holds the file: its COMPLETE arrived, or the direct transfer finished. */
    DELIVERED,

    /** The target refused the file. */
    DECLINED,

    /** Every transfer ended without delivery or hand-off; the pull path recovers. */
    ENDED,

    CANCELLED,
    EXPIRED;

    val isLive: Boolean get() = this == SENDING || this == HANDED_OFF
}

/** One file sealed for one target device at its origin (us). */
internal class OutgoingSeal(
    val sealId: ContentId,
    val fileId: FileId,
    val targetDevice: PeerId,
    val kind: FileSealKind,
    /** The seal's ratchet frame, attached unchanged to every transfer of it. */
    val sealBytes: ByteArray,
    val key: SealKey,
    val totalBytes: Long,
    val status: FileSealStatus,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/** One outgoing transfer's state. OFFERED and STREAMING are live; the rest are final. */
internal enum class FileTransferStatus {
    /** The OFFER is queued; nothing else goes until it is ACKed. */
    OFFERED,

    /** The OFFER was ACKed: chunks flow. */
    STREAMING,

    /** The peer holds every chunk of the transfer. */
    DONE,

    /** The peer refused the OFFER (a target: no space; a relay: no room). */
    DECLINED,

    /** Gone at the other end, or failed here; the outcome there is unknown. */
    ENDED,

    EXPIRED,
    CANCELLED;

    val isLive: Boolean get() = this == OFFERED || this == STREAMING
}

/**
 * One hop this device sends (docs/envelope-redesign.md §5.3): [shape]'s chunks of seal [sealId]
 * to [peer]. Ours have no [sourceTransferId]; a relay forward names the incoming hop whose stored
 * ciphertext it sends. [contiguousAcked] is the peer's coverage in this transfer's chunk indices.
 */
internal data class OutgoingTransfer(
    val transferId: TransferId,
    val sealId: ContentId,
    val sourceTransferId: TransferId?,
    val peer: PeerId,
    val shape: TransferShape,
    val nextChunkIndex: Int,
    val contiguousAcked: Int,
    val offerPacketId: Uuid?,
    val status: FileTransferStatus,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    val isForward: Boolean get() = sourceTransferId != null
}

/** A seal opened here: what [origin] sealed for us, kept until it expires. */
internal class IncomingSeal(
    val sealId: ContentId,
    val origin: PeerId,
    val seal: FileSeal,
    val expiresAt: Instant,
)

/**
 * A hop delivering chunks of seal [sealId] to us from [sender]. [finalTarget] is the seal's
 * target: us, or another device when we relay the hop (docs/envelope-redesign.md §5.4).
 */
internal data class IncomingTransfer(
    val transferId: TransferId,
    val sealId: ContentId,
    val sender: PeerId,
    val finalTarget: PeerId,
    val shape: TransferShape,
    val expiresAt: Instant,
)

/**
 * An incoming hop we relay, with what we hold of it: [storedChunks] of its chunks and a seal of
 * [sealSize] bytes, last fed at [lastStoredAt]. Its sender is the seal's origin (one hop, ever).
 */
internal class RelayHop(
    val transfer: IncomingTransfer,
    val sealSize: Int,
    val storedChunks: Int,
    val lastStoredAt: Instant,
) {
    /** Every chunk of the hop is here: it can be forwarded. */
    val isWhole: Boolean get() = storedChunks >= transfer.shape.chunkCount

    /** What the hop holds once whole, counted against `RouterConfig.fileRelayBudgetBytes`. */
    val reservedBytes: Long get() = relayReservedBytes(transfer.shape, sealSize)
}

/** The storage a relay hop of [shape] with a [sealSize]-byte seal takes once every chunk is here. */
internal fun relayReservedBytes(shape: TransferShape, sealSize: Int): Long =
    sealSize + shape.byteCount + shape.chunkCount.toLong() * FileProtection.CHUNK_OVERHEAD_BYTES

/** What a relay holds of a hop after storing a chunk: its contiguous prefix, and whether it is whole. */
internal data class RelayCoverage(val highestContiguousIndex: Int, val isWhole: Boolean)

internal data class AdmitResult(val admitted: Boolean, val evicted: List<TransferId>)

internal enum class FilePullClass {
    SMALL,
    LARGE,
}

internal data class FilePullAttempt(
    val fileId: FileId,
    val attempts: Int,
    val declinedDevices: Set<PeerId>,
    /** ACK received, OFFER pending: the device asked and the wait deadline, else null. */
    val waitingDevice: PeerId?,
    val waitingUntil: Instant?,
    /** In-flight REQUEST's attempt id for outcome correlation, else null. */
    val attemptId: Uuid?,
    val pullClass: FilePullClass,
)
