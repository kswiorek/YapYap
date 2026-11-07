package org.yapyap.crypto.identity


import org.yapyap.crypto.store.*

import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.keys.KeySlot
import org.yapyap.crypto.keys.SPK_ID_PREFIX
import org.yapyap.crypto.keys.SignedPreKeyId
import org.yapyap.crypto.keys.SigningPrivateKey
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.AccountRole
import org.yapyap.protocol.PeerId
import kotlin.time.Clock
import kotlin.time.Instant

internal class DefaultIdentityProvisioning(
    private val cryptoProvider: CryptoProvider,
    private val publicKeyRepository: LocalIdentityStore,
    private val keyStore: KeyStore,
    private val localIdentity: LocalIdentity,
    private val localKeys: LocalKeys,
    private val clock: Clock,
) : IdentityProvisioning {
    override suspend fun createNewDeviceIdentity(): DeviceIdentity {
        AppLog.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.STARTED,
            message = "Creating new local device identity",
        )
        val signingKey = cryptoProvider.generateSigningKeyPair()
        val encryptionKey = cryptoProvider.generateEncryptionKeyPair()
        val deviceId = cryptoProvider.peerIdFromPublicKey(signingKey.publicKey)

        keyStore.putKey(KeySlot.DeviceSigningPrivate, signingKey.privateKey.bytes)
        keyStore.putKey(KeySlot.DeviceSigningPublic, signingKey.publicKey.bytes)
        keyStore.putKey(KeySlot.DeviceEncryptionPrivate, encryptionKey.privateKey.bytes)
        keyStore.putKey(KeySlot.DeviceEncryptionPublic, encryptionKey.publicKey.bytes)

        val signedPreKey = newSignedPreKey(deviceId, signingKey.privateKey)
        val identity = DeviceIdentity(
            deviceId = deviceId,
            signingKey = signingKey.publicKey,
            encryptionKey = encryptionKey.publicKey,
            signedPreKey = signedPreKey.preKey,
        )

        val accountRecord = localIdentity.getLocalAccountIdentity()

        publicKeyRepository.insertLocalDevice(
            accountId = accountRecord.accountId,
            identity = identity
        )
        keyStore.putKey(KeySlot.SignedPreKeyPrivate(signedPreKey.id), signedPreKey.privateKey.bytes)

        AppLog.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.IDENTITY_DEVICE_RECORD_CREATED,
            message = "Created and persisted new local device identity",
            fields = mapOf("deviceId" to deviceId, "accountId" to accountRecord.accountId),
        )
        return identity
    }

    private suspend fun newSignedPreKey(deviceId: PeerId, signingKey: SigningPrivateKey): LocalSignedPreKey {
        val spkPair = cryptoProvider.generateEncryptionKeyPair()
        val spkId = SignedPreKeyId(
            SPK_ID_PREFIX + cryptoProvider.toHex(cryptoProvider.sha256(spkPair.publicKey.bytes).copyOf(SPK_ID_BYTES)),
        )
        val signature = cryptoProvider.signDetached(signingKey, spkPair.publicKey.bytes)
        return LocalSignedPreKey(
            preKey = SignedPreKey(
                id = spkId,
                deviceId = deviceId,
                publicKey = spkPair.publicKey,
                signature = signature
            ),
            privateKey = spkPair.privateKey,
        )
    }

    override suspend fun provisionSignedPreKey(): LocalSignedPreKey {
        val spk = newSignedPreKey(localIdentity.getLocalDeviceId(), localKeys.localDeviceSigningKey())
        keyStore.putKey(KeySlot.SignedPreKeyPrivate(spk.id), spk.privateKey.bytes)
        publicKeyRepository.insertSignedPreKey(spk.preKey, createdAt = clock.now())
        return spk
    }

    override suspend fun createNewAccountIdentity(displayName: String, role: AccountRole): AccountIdentity {
        AppLog.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.STARTED,
            message = "Creating new local account identity",
            fields = mapOf("displayName" to displayName),
        )
        val signingKey = cryptoProvider.generateSigningKeyPair()
        val accountId = cryptoProvider.accountIdFromPublicKey(signingKey.publicKey)
        keyStore.putKey(KeySlot.AccountSigningPrivate, signingKey.privateKey.bytes)
        keyStore.putKey(KeySlot.AccountSigningPublic, signingKey.publicKey.bytes)

        val accountRecord = AccountIdentity(accountId, displayName, signingKey = signingKey.publicKey)
        publicKeyRepository.insertLocalAccount(accountRecord, role = role)
        AppLog.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.IDENTITY_ACCOUNT_RECORD_CREATED,
            message = "Created and persisted new local account identity",
            fields = mapOf("accountId" to accountId, "displayName" to displayName),
        )
        return accountRecord
    }

    override suspend fun createPlaceholderAccountIdentity(): AccountIdentity {
        val accountRecord = AccountIdentity(AccountId("placeholder"), "placeholder")
        publicKeyRepository.insertLocalAccount(accountRecord)
        AppLog.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.IDENTITY_ACCOUNT_RECORD_CREATED,
            message = "Created and persisted placeholder local account identity",
        )
        return accountRecord
    }

    override suspend fun exportLocalAccountRecoveryKey(): String {
        val account = localIdentity.getLocalAccountIdentity()
        val privateKey = localKeys.localAccountSigningKey()
        return AccountRecoveryKeyCodec.encode(
            displayName = account.displayName,
            privateSigningKey = privateKey,
        )
    }

    override suspend fun verifyRecoveryKey(recoveryKey: String): Boolean {
        val material = try {
            AccountRecoveryKeyCodec.decode(recoveryKey)
        } catch (_: CryptoException.InvalidRecoveryKey) {
            return false
        }
        val derivedPublicKey = cryptoProvider.privateSigningKeyToPublicKey(material.privateSigningKey)
        val storedPublicKey = localIdentity.getLocalAccountIdentity().signingKey
            ?: return false
        return derivedPublicKey == storedPublicKey
    }

    override suspend fun importLocalAccountFromRecovery(recoveryKey: String): AccountIdentity {
        val material = AccountRecoveryKeyCodec.decode(recoveryKey)
        AppLog.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.STARTED,
            message = "Importing local account identity from recovery key",
            fields = mapOf("displayName" to material.displayName),
        )

        val publicKey = cryptoProvider.privateSigningKeyToPublicKey(material.privateSigningKey)
        val accountId = cryptoProvider.accountIdFromPublicKey(publicKey)
        keyStore.putKey(KeySlot.AccountSigningPrivate, material.privateSigningKey.bytes)
        keyStore.putKey(KeySlot.AccountSigningPublic, publicKey.bytes)

        val accountRecord = AccountIdentity(accountId, material.displayName, signingKey = publicKey)
        // role = MEMBER (default): not derivable from the recovery code alone; the global-room fold
        // (genesis AddAccount / GrantAdmin / owner handover) corrects the role after sync.
        publicKeyRepository.insertLocalAccount(accountRecord)
        AppLog.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.IDENTITY_ACCOUNT_RECORD_CREATED,
            message = "Imported and persisted local account identity from recovery key",
            fields = mapOf("accountId" to accountId, "displayName" to material.displayName),
        )
        return accountRecord
    }

    companion object {
        private const val SPK_ID_BYTES = 8
    }
}