package org.yapyap.crypto.e2ee.manager
import org.yapyap.protocol.PeerId

enum class SessionUpgradePolicy {
    NEVER,
    OFFER_OPK_ON_FIRST_EPOCH1_REPLY,
}

/**
 * Boundary for 1-on-1 encrypted messaging. The wire format (SessionWireFrame + codec) is fully
 * encapsulated here: callers pass plaintext bytes and receive encoded frame bytes (and vice versa),
 * without knowing the crypto session wire format.
 */
interface CryptoSessionManager {
    /** Encrypt [bytes] for [remoteDeviceId] and return the encoded session wire frame bytes. */
    suspend fun encryptMessage(
        remoteDeviceId: PeerId,
        bytes: ByteArray,
    ): ByteArray

    /** Decrypt an encoded session wire frame ([frameBytes]) from [remoteDeviceId] into plaintext. */
    suspend fun decryptMessage(
        remoteDeviceId: PeerId,
        frameBytes: ByteArray,
    ): ByteArray
}
