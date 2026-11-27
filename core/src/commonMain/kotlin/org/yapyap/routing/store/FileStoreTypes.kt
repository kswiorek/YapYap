package org.yapyap.routing.store

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
 * to [peer]. Ours have no [relayTransferId]; a relay forward names the inbound relay transfer it
 * forwards. [contiguousAcked] is the peer's coverage in this transfer's chunk indices.
 */
internal data class OutgoingTransfer(
    val transferId: TransferId,
    val sealId: ContentId,
    val relayTransferId: TransferId?,
    val peer: PeerId,
    val shape: TransferShape,
    val nextChunkIndex: Int,
    val contiguousAcked: Int,
    val offerPacketId: Uuid?,
    val status: FileTransferStatus,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    val isForward: Boolean get() = relayTransferId != null
}

/** A seal opened here: what [origin] sealed for us, kept until it expires. */
internal class IncomingSeal(
    val sealId: ContentId,
    val origin: PeerId,
    val seal: FileSeal,
    val expiresAt: Instant,
)

/** A hop delivering chunks of seal [sealId] to us from [sender]. */
internal data class IncomingTransfer(
    val transferId: TransferId,
    val sealId: ContentId,
    val sender: PeerId,
    val shape: TransferShape,
    val expiresAt: Instant,
)

/** RESERVED (admitted, chunks landing) → WHOLE (every chunk of the transfer stored). */
internal enum class FileRelayStatus {
    RESERVED,
    WHOLE,
}

/**
 * A transfer deposited here by [origin] for [finalTarget]: the seal bytes to pass on and the
 * transfer's [shape], whose chunks the relay stores until the transfer is whole.
 */
internal class RelayTransfer(
    val transferId: TransferId,
    val sealId: ContentId,
    val origin: PeerId,
    val finalTarget: PeerId,
    val shape: TransferShape,
    val sealBytes: ByteArray,
    val status: FileRelayStatus,
    /** Bytes reserved against `RouterConfig.fileRelayBudgetBytes` at admission. */
    val reservedBytes: Long,
    val createdAt: Instant,
    val lastChunkAt: Instant,
    val expiresAt: Instant,
)

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
