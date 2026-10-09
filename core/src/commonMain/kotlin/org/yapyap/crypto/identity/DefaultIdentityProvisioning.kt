package org.yapyap.crypto.identity

import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.db.AccountRole
import org.yapyap.persistence.key.*
import org.yapyap.protocol.PeerId
import kotlin.time.Clock
import kotlin.time.Instant

class DefaultIdentityProvisioning(
    private val cryptoProvider: CryptoProvider,
    private val publicKeyRepository: IdentityKeyRepository,
    private val keyStore: KeyStore,
    private val identityResolver: IdentityResolver,
    private val clock: Clock,
) : IdentityProvisioning {
    override suspend fun createNewDeviceIdentity(): DeviceIdentityRecord {
        AppLog.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.STARTED,
            message = "Creating new local device identity",
        )
        val signingKey = cryptoProvider.generateSigningKeyPair()
        val encryptionKey = cryptoProvider.generateEncryptionKeyPair()
        val deviceId = cryptoProvider.peerIdFromPublicKey(signingKey.publicKey)

        val signingKeyRecord = IdentityPublicKeyRecord(
            LOCAL_DEVICE_KEY_PREFIX + "signing",
            0,
            IdentityKeyPurpose.SIGNING,
            signingKey.publicKey
        )
        val privateSigningKeyRef =
            KeyReference(keyId = signingKeyRecord.keyId, purpose = IdentityKeyPurpose.SIGNING, type = KeyType.PRIVATE)
        val encryptionKeyRecord = IdentityPublicKeyRecord(
            LOCAL_DEVICE_KEY_PREFIX + "encryption",
            0,
            IdentityKeyPurpose.ENCRYPTION,
            encryptionKey.publicKey
        )
        val privateEncryptionKeyRef = KeyReference(
            keyId = encryptionKeyRecord.keyId,
            purpose = IdentityKeyPurpose.ENCRYPTION,
            type = KeyType.PRIVATE
        )

        keyStore.putKey(privateSigningKeyRef, signingKey.privateKey)
        keyStore.putKey(
            KeyReference(keyId = signingKeyRecord.keyId, purpose = IdentityKeyPurpose.SIGNING, type = KeyType.PUBLIC),
            signingKey.publicKey,
        )

        keyStore.putKey(privateEncryptionKeyRef, encryptionKey.privateKey)
        keyStore.putKey(
            KeyReference(
                keyId = encryptionKeyRecord.keyId,
                purpose = IdentityKeyPurpose.ENCRYPTION,
                type = KeyType.PUBLIC
            ),
            encryptionKey.publicKey,
        )

        val keySignature = cryptoProvider.signDetached(
            signingKey.privateKey,
            encryptionKey.publicKey + encryptionKeyRecord.keyId.encodeToByteArray()
        )

        val signedPreKey = provisionInitialSignedPreKey(
            signingPrivateKey = signingKey.privateKey,
            createdAt = clock.now(),
            deviceId = deviceId,
        )

        val identity = DeviceIdentityRecord(
            deviceId,
            signingKeyRecord,
            encryptionKeyRecord,
            signedPreKey = signedPreKey,
            keySignature = keySignature
        )

        val accountRecord = identityResolver.getLocalAccountIdentityRecord()

        publicKeyRepository.insertLocalDevice(
            accountId = accountRecord.accountId,
            identity = identity
        )
        val spkRef = signedPreKeyPrivateRef(signedPreKey.keyId)

        keyStore.putKey(spkRef, signedPreKey.privateKey!!)

        AppLog.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.IDENTITY_DEVICE_RECORD_CREATED,
            message = "Created and persisted new local device identity",
            fields = mapOf("deviceId" to deviceId, "accountId" to accountRecord.accountId),
        )
        return identity
    }

    private suspend fun provisionInitialSignedPreKey(
        signingPrivateKey: ByteArray,
        createdAt: Instant,
        deviceId: PeerId
    ): SignedPreKeyRecord {
        val spkPair = cryptoProvider.generateEncryptionKeyPair()
        val spkId = SPK_KEY_PREFIX + cryptoProvider.sha256(spkPair.publicKey).take(SPK_ID_BYTES)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val signature = cryptoProvider.signDetached(signingPrivateKey, spkPair.publicKey)
        val record = SignedPreKeyRecord(
            deviceId = deviceId,
            keyId = spkId,
            publicKey = spkPair.publicKey,
            signature = signature,
            privateKey = spkPair.privateKey,
            createdAt = createdAt,
        )
        return record
    }

    override suspend fun provisionSignedPreKey(): SignedPreKeyRecord {
        val spkPair = cryptoProvider.generateEncryptionKeyPair()
        val spkId = SPK_KEY_PREFIX + cryptoProvider.sha256(spkPair.publicKey).take(SPK_ID_BYTES)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val signingPrivateKey = identityResolver.getLocalDevicePrivateKey(purpose = IdentityKeyPurpose.SIGNING)
        val signature = cryptoProvider.signDetached(signingPrivateKey, spkPair.publicKey)
        val deviceId = identityResolver.getLocalDeviceId()
        val record = SignedPreKeyRecord(
            deviceId = deviceId,
            keyId = spkId,
            publicKey = spkPair.publicKey,
            signature = signature,
            privateKey = spkPair.privateKey,
            createdAt = clock.now(),
        )

        val spkRef = signedPreKeyPrivateRef(record.keyId)

        keyStore.putKey(spkRef, record.privateKey!!)

        publicKeyRepository.insertSignedPreKey(record)
        return record
    }

    override suspend fun createNewAccountIdentity(displayName: String, role: AccountRole): AccountIdentityRecord {
        AppLog.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.STARTED,
            message = "Creating new local account identity",
            fields = mapOf("displayName" to displayName),
        )
        val signingKey = cryptoProvider.generateSigningKeyPair()
        val accountId = cryptoProvider.accountIdFromPublicKey(signingKey.publicKey)
        val accountKeyRecord = IdentityPublicKeyRecord(
            LOCAL_ACCOUNT_KEY_PREFIX + "signing",
            0,
            IdentityKeyPurpose.SIGNING,
            signingKey.publicKey
        )

        val privateAccountKeyRef =
            KeyReference(keyId = accountKeyRecord.keyId, purpose = IdentityKeyPurpose.SIGNING, type = KeyType.PRIVATE)
        val publicAccountKeyRef =
            KeyReference(keyId = accountKeyRecord.keyId, purpose = IdentityKeyPurpose.SIGNING, type = KeyType.PUBLIC)

        keyStore.putKey(privateAccountKeyRef, signingKey.privateKey)
        keyStore.putKey(publicAccountKeyRef, signingKey.publicKey)

        val accountRecord = AccountIdentityRecord(accountId, displayName, key = accountKeyRecord)
        publicKeyRepository.insertLocalAccount(accountRecord, role = role)
        AppLog.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.IDENTITY_ACCOUNT_RECORD_CREATED,
            message = "Created and persisted new local account identity",
            fields = mapOf("accountId" to accountId, "displayName" to displayName),
        )
        return accountRecord
    }

    override suspend fun createPlaceholderAccountIdentity(): AccountIdentityRecord {
        val accountRecord = AccountIdentityRecord(AccountId("placeholder"), "placeholder")
        publicKeyRepository.insertLocalAccount(accountRecord)
        AppLog.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.IDENTITY_ACCOUNT_RECORD_CREATED,
            message = "Created and persisted placeholder local account identity",
        )
        return accountRecord
    }

    override suspend fun exportLocalAccountRecoveryKey(): String {
        val account = identityResolver.getLocalAccountIdentityRecord()
        val privateKey = identityResolver.getLocalAccountPrivateKey(IdentityKeyPurpose.SIGNING)
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
        val storedPublicKey = identityResolver.getLocalAccountIdentityRecord().key?.publicKey
            ?: return false
        return derivedPublicKey.contentEquals(storedPublicKey)
    }

    override suspend fun importLocalAccountFromRecovery(recoveryKey: String): AccountIdentityRecord {
        val material = AccountRecoveryKeyCodec.decode(recoveryKey)
        AppLog.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.STARTED,
            message = "Importing local account identity from recovery key",
            fields = mapOf("displayName" to material.displayName),
        )

        val publicKey = cryptoProvider.privateSigningKeyToPublicKey(material.privateSigningKey)
        val accountId = cryptoProvider.accountIdFromPublicKey(publicKey)
        val accountKeyRecord = IdentityPublicKeyRecord(
            LOCAL_ACCOUNT_KEY_PREFIX + "signing",
            0,
            IdentityKeyPurpose.SIGNING,
            publicKey,
        )

        val privateAccountKeyRef =
            KeyReference(keyId = accountKeyRecord.keyId, purpose = IdentityKeyPurpose.SIGNING, type = KeyType.PRIVATE)
        val publicAccountKeyRef =
            KeyReference(keyId = accountKeyRecord.keyId, purpose = IdentityKeyPurpose.SIGNING, type = KeyType.PUBLIC)

        keyStore.putKey(privateAccountKeyRef, material.privateSigningKey)
        keyStore.putKey(publicAccountKeyRef, publicKey)

        val accountRecord = AccountIdentityRecord(accountId, material.displayName, key = accountKeyRecord)
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