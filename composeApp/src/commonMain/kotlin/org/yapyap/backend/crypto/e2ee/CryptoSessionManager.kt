package org.yapyap.backend.crypto.e2ee
import org.yapyap.backend.protocol.PeerId

interface CryptoSessionManager {
    suspend fun encryptMessage(
        remoteDeviceId: PeerId,
        bytes: ByteArray,
    ): SessionWireFrame

    suspend fun decryptMessage(
        remoteDeviceId: PeerId,
        frame: SessionWireFrame,
    ): ByteArray
}

enum class SessionUpgradePolicy {
    /** Epoch-1 only; never piggyback OPK offers. */
    NEVER,

    /** Bob offers an OPK on the first epoch-1 outbound message when epoch 2 does not exist yet. */
    OFFER_OPK_ON_FIRST_EPOCH1_REPLY,
}