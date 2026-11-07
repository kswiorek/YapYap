package org.yapyap.orchestrator.dag

import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.envelopes.MessagePayload.RoomEvent
import org.yapyap.protocol.envelopes.RoomEventPayload
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Self-certifying room ids: a room's id is the hash of its genesis node's canonical bytes
 * ([RoomEvent.derivationBytes]). The DAG is the only place genesis nodes are created and
 * checked, so the derivation lives beside it.
 */
internal object RoomIds {

    /** Verify path: the id [genesis] must carry. */
    suspend fun derive(crypto: CryptoProvider, genesis: RoomEvent): RoomId =
        RoomId(crypto.hashedUuid(genesis.genesisDerivationBytes()))

    /** Append path: self-certifying id before construction. */
    suspend fun derive(
        crypto: CryptoProvider,
        genesisMessageId: Uuid,
        senderAccountId: AccountId,
        authorDeviceId: PeerId,
        createdAt: Instant,
        eventBytes: ByteArray,
    ): RoomId = RoomId(
        crypto.hashedUuid(
            RoomEvent.derivationBytes(
                genesisMessageId = genesisMessageId,
                senderAccountId = senderAccountId,
                authorDeviceId = authorDeviceId,
                createdAt = createdAt,
                eventBytes = eventBytes,
            ),
        ),
    )

    /**
     * Sole genesis append path. Derives the self-certifying id
     * internally — the caller never supplies a roomId, so
     * derive-for-A-but-construct-with-B is unrepresentable.
     * Returns the *unsigned* payload; signing follows via
     * [RoomEvent.withSignature] like every other payload type.
     */
    suspend fun createGenesis(
        crypto: CryptoProvider,
        genesisMessageId: Uuid,
        senderAccountId: AccountId,
        authorDeviceId: PeerId,
        createdAt: Instant,
        event: RoomEventPayload,
    ): RoomEvent {
        val eventBytes = event.encode()
        val roomId = derive(
            crypto = crypto,
            genesisMessageId = genesisMessageId,
            senderAccountId = senderAccountId,
            authorDeviceId = authorDeviceId,
            createdAt = createdAt,
            eventBytes = eventBytes,
        )
        return RoomEvent(
            messageId = genesisMessageId,
            roomId = roomId,
            senderAccountId = senderAccountId,
            authorDeviceId = authorDeviceId,
            prevIds = emptyList(),
            createdAt = createdAt,
            eventBytes = eventBytes,
        )
    }
}
