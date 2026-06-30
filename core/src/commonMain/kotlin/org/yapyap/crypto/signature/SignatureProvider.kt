package org.yapyap.crypto.signature

import org.yapyap.protocol.PeerId

interface SignatureProvider {
    suspend fun sign(message: ByteArray): ByteArray

    suspend fun verify(deviceId: PeerId, message: ByteArray, signature: ByteArray): Boolean
}
