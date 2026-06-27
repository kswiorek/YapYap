package org.yapyap.backend.crypto.e2ee

import org.yapyap.backend.crypto.CryptoProvider
import org.yapyap.backend.protocol.PeerId

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
        val ikm = SessionWireCodec.concatBytes(
            handshakeSpkId.encodeToByteArray(),
            byteArrayOf(0),
            initiatorEphemeralPublicKey,
        )
        val info = SessionWireCodec.concatBytes(
            KDF_INFO,
            firstPeer.encodeToByteArray(),
            byteArrayOf(0),
            secondPeer.encodeToByteArray(),
            byteArrayOf(0),
            sessionEpoch.toString().encodeToByteArray(),
            byteArrayOf(0),
            sessionGeneration.toString().encodeToByteArray(),
        )
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
