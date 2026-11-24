package org.yapyap.protection.envelope

import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.MessagePayload

/** MESSAGE content: a [MessagePayload] ratchet-encrypted from its origin to its final target. */
internal interface MessageProtection {
    suspend fun seal(payload: MessagePayload, finalTarget: PeerId): ByteArray

    suspend fun open(content: ByteArray, origin: PeerId): MessagePayload
}

internal class RatchetMessageProtection(private val ratchet: RatchetSealer) : MessageProtection {
    override suspend fun seal(payload: MessagePayload, finalTarget: PeerId): ByteArray =
        ratchet.seal(payload.encode(), finalTarget, what = "message")

    override suspend fun open(content: ByteArray, origin: PeerId): MessagePayload {
        val plaintext = ratchet.open(content, origin, what = "message")
        return ratchet.decode("message content") { MessagePayload.decode(plaintext) }
    }
}
