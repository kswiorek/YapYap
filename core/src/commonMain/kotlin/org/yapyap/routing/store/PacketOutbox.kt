package org.yapyap.routing.store

import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal interface PacketOutbox {
    /**
     * @param targetEndpoint out-of-band endpoint override for targets with no local devices row
     *   (bootstrap: recovery request / intro). Persisted on the row so retries — including after
     *   a restart — resolve it without the DB. Null for regular peers (resolved via the devices
     *   table at dispatch).
     */
    suspend fun enqueue(
        envelope: BinaryEnvelope,
        nextRetryAt: Instant,
        relayMessage: Boolean = false,
        targetEndpoint: TorEndpoint? = null,
        relayBackup: RelayBackup = RelayBackup.NONE,
        tag: OutboxTag? = null,
        retryClass: RetryClass = RetryClass.STANDARD,
    )

    /**
     * Moves [packetId]'s relay backup from [RelayBackup.PENDING] to [RelayBackup.DEPOSITED];
     * true exactly once per row, for the caller that must now make the relay copies. False
     * when the row is gone (delivered, expired) or its backup is done or not applicable.
     */
    suspend fun claimRelayBackup(packetId: Uuid): Boolean

    /** Removes [packetId]'s row; returns it, or null when it was already gone. */
    suspend fun remove(packetId: Uuid): OutboxEntry?
    suspend fun setDueForTarget(target: PeerId, nextRetryAt: Instant)
    suspend fun recordAttempt(packetId: Uuid, nextRetryAt: Instant, at: Instant)

    /** Moves [packetId]'s next retry to [nextRetryAt] without counting an attempt. */
    suspend fun deferRetry(packetId: Uuid, nextRetryAt: Instant)
    suspend fun listAllForTarget(target: PeerId): List<OutboxEntry>

    /**
     * Due rows *without* envelope blobs (design §6.2): the due pass selects ids, targets,
     * tags and retry classes — ordered non-file rows first, then file rows, each oldest-first
     * and bounded — and each claimed row's envelope loads alone at claim time. Messages (and
     * ACKs, pings, system traffic) are therefore never stuck behind a full chunk window.
     * Targets already at their per-target in-flight cap are hidden via [excludeTargets], so
     * one capped peer never starves the rest.
     */
    suspend fun listDueIds(
        now: Instant,
        nonFileLimit: Int,
        fileLimit: Int,
        excludeTargets: Collection<PeerId> = emptyList(),
    ): List<DueOutboxRow>

    /** Loads one row with its envelope blob (the only blob-loading read on the due path). */
    suspend fun getByPacketId(packetId: Uuid): OutboxEntry?

    /** Drops every queued row carrying [tag] (transfer teardown, resume clean-slate). */
    suspend fun deleteByTag(tag: OutboxTag)

    /** Removes every row expired at [now]; returns them. */
    suspend fun pruneExpired(now: Instant): List<OutboxEntry>

    /** Packet ids still queued for [tag] (restart recovery of per-transfer windows). */
    suspend fun packetIdsByTag(tag: OutboxTag): List<Uuid>

    /** Whether a row carrying [tag] is still queued. */
    suspend fun hasTagged(tag: OutboxTag): Boolean
    suspend fun earliestPendingRetryAt(): Instant?

    /** Earliest retry time strictly after [after], ignoring entries already due. */
    suspend fun earliestPendingRetryAfter(after: Instant): Instant?
    suspend fun relayCacheBytes(): Long
    suspend fun pruneRelayOverCapacity(maxBytes: Long): Int
}

/**
 * Store-and-forward backup of a message sent straight to its recipient: relays get copies only
 * when the direct path shows it may not deliver (no link, or no ACK in time), at most once.
 * Messages only: file transfers make their own deposits (the file engine's triggers).
 */
internal enum class RelayBackup(val code: Long) {
    /** Not a message to its final recipient (relay copies, system packets, bootstrap). */
    NONE(0),

    /** Copies not made yet; made once delivery looks doubtful. */
    PENDING(1),

    /** Copies made (or the message was already covered). */
    DEPOSITED(2),
}

internal data class OutboxEntry(
    val packetId: Uuid,
    val envelope: BinaryEnvelope,
    val nextRetryAt: Instant?,
    val attempts: Long,
    /** Out-of-band endpoint override, present exactly when the target has no devices row (bootstrap). */
    val targetEndpoint: TorEndpoint? = null,
    val tag: OutboxTag? = null,
    val retryClass: RetryClass = RetryClass.STANDARD,
    /** The row's backup state when loaded; [PacketOutbox.claimRelayBackup] stays the gate. */
    val relayBackup: RelayBackup = RelayBackup.NONE,
)

/** A due outbox row without its envelope blob: id, target, attempt count, tag and retry class. */
internal data class DueOutboxRow(
    val packetId: Uuid,
    val target: PeerId,
    val attempts: Long,
    val tag: OutboxTag?,
    val retryClass: RetryClass = RetryClass.STANDARD,
)

/**
 * What an outbox packet is for, so its outcome (ACK, terminal NACK, expiry) reaches whoever
 * waits on it. Untagged packets (ACKs, pings, relay copies, packets relayed for others) have
 * nobody waiting.
 */
internal data class OutboxTag(val kind: Kind, val id: Uuid) {
    enum class Kind(val code: Long) {
        /** A direct push of a DAG message to one of its recipients; [id] is the message id. */
        MESSAGE(0),

        /** A bootstrap-family packet; [id] is the packet id [org.yapyap.routing.router.BootstrapCarrier.sendBootstrap] returned. */
        BOOTSTRAP(1),

        /** One attempt of a sync request; [id] is the sync id. */
        SYNC(2),

        /**
         * One attempt of a file REQUEST; [id] is the attempt id. The outcome
         * routes to the file pull processor, which correlates it to the
         * file through its attempts row.
         */
        FILE_REQUEST(4),

        /**
         * One packet of an outgoing file transfer (OFFER or chunk); [id] is the transfer id. A
         * transfer is one hop, ours or a relay forward alike. Chunks retry at a flat delay
         * ([RetryClass.FLAT]); OFFERs use the message schedule.
         */
        FILE_TRANSFER(5),
        ;

        companion object {
            fun fromCode(code: Long): Kind? = entries.firstOrNull { it.code == code }

            /** File transfer traffic vs everything else in the due query. */
            val FILE_KINDS: Set<Kind> = setOf(FILE_TRANSFER)
        }
    }
}

/** Retry schedule for one outbox row (design §6.2): OFFERs and REQUESTs use the message schedule. */
internal enum class RetryClass(val code: Long) {
    STANDARD(0),
    FLAT(1);

    companion object {
        fun fromCode(code: Long): RetryClass = entries.firstOrNull { it.code == code } ?: STANDARD
    }
}
