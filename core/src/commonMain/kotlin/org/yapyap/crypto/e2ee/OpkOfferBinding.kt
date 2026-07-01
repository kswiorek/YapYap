package org.yapyap.crypto.e2ee

import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.PeerId

object OpkOfferBinding {
    const val BINDING_LENGTH = 32

    private val KDF_INFO = "YapYapOpkOfferBinding".encodeToByteArray()

    suspend fun compute(
        crypto: CryptoProvider,
        localDeviceId: PeerId,
        peerDeviceId: PeerId,
        sessionEpoch: Int,
        sessionGeneration: Int,
        handshakeSpkId: String,
        initiatorEphemeralPublicKey: ByteArray,
    ): ByteArray {
        val (firstPeer, secondPeer) = orderedPeerIds(localDeviceId, peerDeviceId)
        val ikm = ByteWriter(256).apply {
            writeBytes(handshakeSpkId.encodeToByteArray())
            writeByte(0)
            writeBytes(initiatorEphemeralPublicKey)
        }.toByteArray()
        val info = ByteWriter(256).apply {
            writeBytes(KDF_INFO)
            writeBytes(firstPeer.encodeToByteArray())
            writeByte(0)
            writeBytes(secondPeer.encodeToByteArray())
            writeByte(0)
            writeBytes(sessionEpoch.toString().encodeToByteArray())
            writeByte(0)
            writeBytes(sessionGeneration.toString().encodeToByteArray())
        }.toByteArray()
        return crypto.hkdf(
            ikm = ikm,
            salt = null,
            info = info,
            outputLength = BINDING_LENGTH,
        )
    }

    private fun orderedPeerIds(localDeviceId: PeerId, peerDeviceId: PeerId): Pair<String, String> =
        if (localDeviceId.id <= peerDeviceId.id) {
            localDeviceId.id to peerDeviceId.id
        } else {
            peerDeviceId.id to localDeviceId.id
        }
}
