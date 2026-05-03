package org.yapyap.backend.crypto

import org.kotlincrypto.error.SignatureException
import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.logging.NoopAppLogger
import org.yapyap.backend.protocol.PeerId

class DefaultSignatureProvider(
    private val identityResolver: IdentityResolver,
    private val cryptoProvider: CryptoProvider,
    private val logger: AppLogger = NoopAppLogger,
) : SignatureProvider {

    override fun signDetached(message: ByteArray): ByteArray {
        val privateKey = identityResolver.loadLocalPrivateKey(
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

    override fun verifyDetached(deviceId: PeerId, message: ByteArray, signature: ByteArray): Boolean {
        val publicKey = identityResolver.resolvePeerIdentityRecord(deviceId)?.signing?.publicKey
        if (publicKey == null) {
            logger.warn(
                component = LogComponent.CRYPTO,
                event = LogEvent.KEY_LOOKUP_MISS,
                message = "Missing peer signing key for signature verification",
                fields = mapOf("deviceId" to deviceId),
            )
            return false
        }
        try {
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
        catch (e: SignatureException) {
            logger.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.SIGNATURE_VERIFICATION_FAILED,
                message = "Error during detached signature verification",
                fields = mapOf("deviceId" to deviceId, "messageLength" to message.size, "error" to e.toString()),
            )
            return false
        }
    }
}
