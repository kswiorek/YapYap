package org.yapyap.crypto.signature

import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.PeerId

class DefaultSignatureProvider(
    private val identityResolver: IdentityResolver,
    private val cryptoProvider: CryptoProvider,
) : SignatureProvider {

    override suspend fun sign(message: ByteArray): ByteArray {
        val privateKey = identityResolver.getLocalDevicePrivateKey(
            purpose = IdentityKeyPurpose.SIGNING,
        )
        AppLog.debug(
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
            AppLog.warn(
                component = LogComponent.CRYPTO,
                event = LogEvent.SIGNATURE_VERIFICATION_FAILED,
                message = "Detached signature verification failed",
                fields = mapOf("deviceId" to deviceId, "messageLength" to message.size),
            )
        } else {
            AppLog.debug(
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
    ): Boolean = classifyMessageAuthorship(accountId, authorDeviceId, signedBytes, signature) == AuthorshipOutcome.VALID

    override suspend fun classifyMessageAuthorship(
        accountId: AccountId,
        authorDeviceId: PeerId,
        signedBytes: ByteArray,
        signature: ByteArray?,
    ): AuthorshipOutcome {
        if (signature == null) {
            return AuthorshipOutcome.INVALID
        }

        // Resolve the author's key. A missing/insufficient record means identity state has not
        // caught up yet — defer rather than reject (the message may verify once the AddDevice
        // event is known).
        val deviceRecord = try {
            identityResolver.resolvePeerIdentityRecord(authorDeviceId)
        } catch (e: CryptoException) {
            AppLog.debug(
                component = LogComponent.CRYPTO,
                event = LogEvent.AUTHOR_SIGNATURE_VERIFICATION_FAILED,
                message = "Author device identity not yet known — deferring authorship classification",
                fields = mapOf("authorDeviceId" to authorDeviceId),
            )
            return AuthorshipOutcome.UNKNOWN_AUTHOR
        }

        // The device must belong to the claimed account.
        val accountForDevice = identityResolver.getAccountIdForDevice(authorDeviceId)
        if (accountForDevice == null) {
            return AuthorshipOutcome.UNKNOWN_AUTHOR
        }
        if (accountForDevice != accountId) {
            AppLog.warn(
                component = LogComponent.CRYPTO,
                event = LogEvent.AUTHOR_SIGNATURE_VERIFICATION_FAILED,
                message = "Author device belongs to a different account than claimed",
                fields = mapOf(
                    "accountId" to accountId,
                    "authorDeviceId" to authorDeviceId,
                    "actualAccountId" to accountForDevice,
                ),
            )
            return AuthorshipOutcome.INVALID
        }

        // Cryptographic check against the known signing key.
        val verified = cryptoProvider.verifyDetached(deviceRecord.signing.publicKey, signedBytes, signature)
        if (!verified) {
            AppLog.warn(
                component = LogComponent.CRYPTO,
                event = LogEvent.AUTHOR_SIGNATURE_VERIFICATION_FAILED,
                message = "Author signature verification failed",
                fields = mapOf(
                    "accountId" to accountId,
                    "authorDeviceId" to authorDeviceId,
                    "signedBytesLength" to signedBytes.size,
                ),
            )
            return AuthorshipOutcome.INVALID
        }

        // Account→device binding via roster.
        val peerCandidates = identityResolver.getAllPeerDevicesForAccount(accountId)
        if (authorDeviceId !in peerCandidates) {
            AppLog.warn(
                component = LogComponent.CRYPTO,
                event = LogEvent.AUTHOR_SIGNATURE_VERIFICATION_FAILED,
                message = "Author device not found in account's roster",
                fields = mapOf(
                    "accountId" to accountId,
                    "authorDeviceId" to authorDeviceId,
                    "peerCandidates" to peerCandidates,
                ),
            )
            return AuthorshipOutcome.INVALID
        }

        AppLog.debug(
            component = LogComponent.CRYPTO,
            event = LogEvent.AUTHOR_SIGNATURE_VERIFIED,
            message = "Author signature verified",
            fields = mapOf(
                "accountId" to accountId,
                "authorDeviceId" to authorDeviceId,
            ),
        )
        return AuthorshipOutcome.VALID
    }
}