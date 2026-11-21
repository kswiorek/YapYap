package org.yapyap.routing.file.push

import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.RelayBackup
import org.yapyap.routing.store.RetryClass
import kotlin.uuid.Uuid

internal interface FileOutbox {
    suspend fun enqueue(
        envelope: BinaryEnvelope,
        tag: OutboxTag,
        relayBackup: RelayBackup = RelayBackup.NONE,
        retryClass: RetryClass = RetryClass.STANDARD,
    )

    suspend fun enqueueDirect(envelope: BinaryEnvelope)
    suspend fun deleteByTag(tag: OutboxTag)

    /** Packet ids still queued for [tag] (restart recovery of the window). */
    suspend fun packetIdsByTag(tag: OutboxTag): List<Uuid>
}
