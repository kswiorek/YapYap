package org.yapyap.crypto.signature

import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.logging.AppLogger
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.logging.NoopAppLogger
import org.yapyap.protocol.PeerId

class DefaultSignatureProvider(
    private val identityResolver: IdentityResolver,
    private val cryptoProvider: CryptoProvider,
    private val logger: AppLogger = NoopAppLogger,
) : SignatureProvider {

    override suspend fun sign(message: ByteArray): ByteArray {
        val privateKey = identityResolver.getLocalDevicePrivateKey(
            purpose = IdentityKeyPurpose.SIGNING,
        )
        logger.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.SIGNATURE_SIGNED,
            message = "Signing detached payload",
            fields = mapOf("messageLength" to message.size),
        )
        return cryptoProvider.signDetached(privateKey, message)
    }

    override suspend fun verify(deviceId: PeerId, message: ByteArray, signature: ByteArray): Boolean {
        val publicKey = identityResolver.resolvePeerIdentityRecord(deviceId)?.signing?.publicKey
        if (publicKey == null) {
            val e = CryptoException.MissingPeerRecord(deviceId.id)
            logger.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.KEY_LOOKUP_MISS,
                message = "Missing peer signing key for signature verification",
                fields = mapOf("deviceId" to deviceId),
                throwable = e,
            )
            throw e
        }

        val verified = cryptoProvider.verifyDetached(publicKey, message, signature)
        if (!verified) {
            logger.warn(
                component = LogComponent.CRYPTO,
                event = LogEvent.SIGNATURE_VERIFICATION_FAILED,
                message = "Detached signature verification failed",
                fields = mapOf("deviceId" to deviceId, "messageLength" to message.size),
            )
        } else {
            logger.debug(
                component = LogComponent.CRYPTO,
                event = LogEvent.SIGNATURE_VERIFIED,
                message = "Detached signature verification succeeded",
                fields = mapOf("deviceId" to deviceId, "messageLength" to message.size),
            )
        }
        return verified
    }
}