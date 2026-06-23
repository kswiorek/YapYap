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
    NEVER,
    OFFER_OPK_ON_FIRST_EPOCH1_REPLY,
}