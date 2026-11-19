package org.yapyap.routing.policy

import org.yapyap.protocol.PacketType
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.routing.store.OutboxTag

/**
 * When a direct packet's relay backup is made (design §5, §6.3; plan step 17).
 *
 * The outbox owns *when* it checks (retry, no link at claim, no ACK in time);
 * this policy owns *which kinds* back up on each trigger. Message behavior is
 * the baseline: a direct message backs up once the direct path looks doubtful.
 * Step 19 gives FILE OFFER markers the message triggers and FILE chunk rows
 * their own gone-only set — that distinction lands here, keyed off the packet.
 */
internal enum class RelayBackupTrigger {
    /** A retry means the previous attempt went unacknowledged (also after restart). */
    RETRY,

    /** Due entry found no open link at claim time (parked, attempt not counted). */
    NO_LINK,

    /** Sent over a link but no ACK within `relayBackupAfter` (link may only look open). */
    NO_ACK_IN_TIME,
}

/**
 * Shared relay-backup trigger policy (plan steps 17–19).
 *
 * Takes the packet itself so FILE kinds differ by payload (an OFFER marker and a
 * chunk row share [OutboxTag.Kind.FILE] but decode to different
 * [FilePayload] kinds).
 */
internal interface RelayBackupPolicy {
    fun shouldBackUp(
        trigger: RelayBackupTrigger,
        envelope: BinaryEnvelope,
        tag: OutboxTag?,
    ): Boolean
}

/**
 * Message triggers for messages and FILE OFFER markers (step 19); nothing else.
 * Deposit markers (already outer-addressed to a relay) and chunk/control rows
 * never back up here: deposits never chase, chunks use the pusher's gone-only
 * deposit signals, control packets are politeness bounded by the TTL (§5, §6.3).
 * Deposit markers additionally carry `RelayBackup.NONE`, so even the claim gate
 * would refuse them — the kind check in the pusher is the backstop.
 */
internal class DefaultRelayBackupPolicy : RelayBackupPolicy {
    override fun shouldBackUp(
        trigger: RelayBackupTrigger,
        envelope: BinaryEnvelope,
        tag: OutboxTag?,
    ): Boolean {
        if (envelope.packetType == PacketType.MESSAGE) return true
        if (envelope.packetType != PacketType.FILE) return false
        return isOfferMarker(envelope)
    }

    private fun isOfferMarker(envelope: BinaryEnvelope): Boolean = runCatching {
        FileEnvelope.decode(envelope.payload).decodePayload() is FilePayload.Offer
    }.getOrDefault(false)
}
