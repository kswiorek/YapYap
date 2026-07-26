package org.yapyap.crypto.signature

import org.yapyap.crypto.identity.AccountId
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
        val publicKey = identityResolver.resolvePeerIdentityRecord(deviceId).signing.publicKey

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

    override suspend fun verifyMessageAuthorship(
        accountId: AccountId,
        authorDeviceId: PeerId,
        signedBytes: ByteArray,
        signature: ByteArray,
    ): Boolean {
        val verified = verify(authorDeviceId, signedBytes, signature)
        if (!verified) {
            logger.warn(
                component = LogComponent.CRYPTO,
                event = LogEvent.AUTHOR_SIGNATURE_VERIFICATION_FAILED,
                message = "Author signature verification failed",
                fields = mapOf(
                    "accountId" to accountId,
                    "authorDeviceId" to authorDeviceId,
                    "signedBytesLength" to signedBytes.size,
                ),
            )
            return false
        }
        val peerCandidates = identityResolver.getAllPeerDevicesForAccount(accountId)

        if (authorDeviceId !in peerCandidates) {
            logger.warn(
                component = LogComponent.CRYPTO,
                event = LogEvent.AUTHOR_SIGNATURE_VERIFICATION_FAILED,
                message = "Author device not found in account's roster",
                fields = mapOf(
                    "accountId" to accountId,
                    "authorDeviceId" to authorDeviceId,
                    "peerCandidates" to peerCandidates,
                ),
            )
            return false
        }
        // TODO Sprint 4: Verify device belongs to account via signed roster (global events DAG).
        // For now, the signature proves the message was signed by the claimed device key.
        // The account→device binding will be verified once the typed global events are implemented.
        logger.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.AUTHOR_SIGNATURE_VERIFIED,
            message = "Author signature verified",
            fields = mapOf(
                "accountId" to accountId,
                "authorDeviceId" to authorDeviceId,
            ),
        )
        return true
    }
}