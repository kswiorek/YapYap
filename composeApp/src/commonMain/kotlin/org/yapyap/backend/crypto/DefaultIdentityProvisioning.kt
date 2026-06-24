package org.yapyap.backend.crypto

import org.yapyap.backend.db.AccountStatus
import org.yapyap.backend.db.IdentityPublicKeyRepository
import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.logging.NoopAppLogger
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint

class DefaultIdentityProvisioning(
    private val cryptoProvider: CryptoProvider,
    private val publicKeyRepository: IdentityPublicKeyRepository,
    private val keyStore: KeyStore,
    private val config: IdentityKeyServiceConfig,
    private val identityResolver: IdentityResolver,
    private val logger: AppLogger = NoopAppLogger,
) : IdentityProvisioning {
    override suspend fun createNewDeviceIdentity(): DeviceIdentityRecord {
        logger.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.STARTED,
            message = "Creating new local device identity",
        )
        val signingKey = cryptoProvider.generateSigningKeyPair()
        val encryptionKey = cryptoProvider.generateEncryptionKeyPair()
        val deviceId = cryptoProvider.peerIdFromPublicKey(signingKey.publicKey)

        val signingKeyRecord = IdentityPublicKeyRecord(
            config.defaultDeviceLocalKeyPrefix + "signing",
            0,
            IdentityKeyPurpose.SIGNING,
            signingKey.publicKey)
        val privateSigningKeyRef = KeyReference(keyId = signingKeyRecord.keyId, purpose = IdentityKeyPurpose.SIGNING, type = KeyType.PRIVATE)
        val publicSigningKeyRef = KeyReference(keyId = signingKeyRecord.keyId, purpose = IdentityKeyPurpose.SIGNING, type = KeyType.PUBLIC)
        val encryptionKeyRecord = IdentityPublicKeyRecord(
            config.defaultDeviceLocalKeyPrefix + "encryption",
            0,
            IdentityKeyPurpose.ENCRYPTION,
            encryptionKey.publicKey)
        val privateEncryptionKeyRef = KeyReference(keyId = encryptionKeyRecord.keyId, purpose = IdentityKeyPurpose.ENCRYPTION, type = KeyType.PRIVATE)
        val publicEncryptionKeyRef = KeyReference(keyId = encryptionKeyRecord.keyId, purpose = IdentityKeyPurpose.ENCRYPTION, type = KeyType.PUBLIC)

        keyStore.putKey(privateSigningKeyRef, signingKey.privateKey)
        keyStore.putKey(publicSigningKeyRef, signingKey.publicKey)

        keyStore.putKey(privateEncryptionKeyRef, encryptionKey.privateKey)
        keyStore.putKey(publicEncryptionKeyRef, encryptionKey.publicKey)

        val keySignature = cryptoProvider.signDetached(
            signingKey.privateKey,
            encryptionKey.publicKey + encryptionKeyRecord.keyId.encodeToByteArray()
        )

        val (signedPreKey, signedPreKeyPrivateKey) = provisionInitialSignedPreKey(
            signingPrivateKey = signingKey.privateKey,
        )

        val identity = DeviceIdentityRecord(deviceId, signingKeyRecord, encryptionKeyRecord, signedPreKey = signedPreKey, keySignature = keySignature)

        val accountRecord = identityResolver.getLocalAccountIdentityRecord()

        publicKeyRepository.insertLocalDevice(
            accountId = accountRecord.accountId,
            identity = identity,
            localSignedPreKeyPrivateKey = signedPreKeyPrivateKey,
        )

        logger.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.IDENTITY_DEVICE_RECORD_CREATED,
            message = "Created and persisted new local device identity",
            fields = mapOf("deviceId" to deviceId, "accountId" to accountRecord.accountId),
        )
        return identity
    }

    private suspend fun provisionInitialSignedPreKey(
        signingPrivateKey: ByteArray,
    ): Pair<SignedPreKeyRecord, ByteArray> {
        val spkPair = cryptoProvider.generateEncryptionKeyPair()
        val spkId = "spk-${cryptoProvider.sha256(spkPair.publicKey).take(SPK_ID_BYTES).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }}"
        val signature = cryptoProvider.signDetached(signingPrivateKey, spkPair.publicKey)
        val record = SignedPreKeyRecord(
            keyId = spkId,
            publicKey = spkPair.publicKey,
            signature = signature,
        )
        keyStore.putKey(
            ref = KeyReference(
                keyId = config.localSignedPreKeyKeyId(spkId),
                purpose = IdentityKeyPurpose.SIGNED_PREKEY,
                type = KeyType.PRIVATE,
            ),
            key = spkPair.privateKey,
        )
        keyStore.putKey(
            ref = KeyReference(
                keyId = config.localSignedPreKeyKeyId(spkId),
                purpose = IdentityKeyPurpose.SIGNED_PREKEY,
                type = KeyType.PUBLIC,
            ),
            key = spkPair.publicKey,
        )
        return record to spkPair.privateKey
    }

    override suspend fun createNewAccountIdentity(displayName: String): AccountIdentityRecord {
        logger.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.STARTED,
            message = "Creating new local account identity",
            fields = mapOf("displayName" to displayName),
        )
        val signingKey = cryptoProvider.generateSigningKeyPair()
        val accountId = cryptoProvider.accountIdFromPublicKey(signingKey.publicKey)
        val accountKeyRecord = IdentityPublicKeyRecord(
            config.defaultAccountLocalKeyPrefix + "signing",
            0,
            IdentityKeyPurpose.SIGNING,
            signingKey.publicKey)

        val privateAccountKeyRef = KeyReference(keyId = accountKeyRecord.keyId, purpose = IdentityKeyPurpose.SIGNING, type = KeyType.PRIVATE)
        val publicAccountKeyRef = KeyReference(keyId = accountKeyRecord.keyId, purpose = IdentityKeyPurpose.SIGNING, type = KeyType.PUBLIC)

        keyStore.putKey(privateAccountKeyRef, signingKey.privateKey)
        keyStore.putKey(publicAccountKeyRef, signingKey.publicKey)

        val accountRecord = AccountIdentityRecord(accountId, key = accountKeyRecord)
        publicKeyRepository.insertLocalAccount(displayName, accountRecord)
        logger.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.IDENTITY_ACCOUNT_RECORD_CREATED,
            message = "Created and persisted new local account identity",
            fields = mapOf("accountId" to accountId, "displayName" to displayName),
        )
        return accountRecord
    }

    override fun provisionDeviceIdentity(accountId: AccountId, deviceIdentity: DeviceIdentityRecord, torEndpoint: TorEndpoint) {
        publicKeyRepository.insertPeerDevice(accountId, config.defaultDeviceType, deviceIdentity, torEndpoint)
        logger.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.IDENTITY_DEVICE_RECORD_CREATED,
            message = "Provisioned local device identity",
            fields = mapOf("deviceId" to deviceIdentity.deviceId, "accountId" to accountId, "torEndpoint" to torEndpoint.toString()),
        )
    }

    override fun provisionAccountIdentity(displayName: String, accountIdentity: AccountIdentityRecord, admin: Boolean, status: AccountStatus) {
        publicKeyRepository.insertPeerAccount(accountIdentity, admin, status, displayName)
        logger.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.IDENTITY_ACCOUNT_RECORD_CREATED,
            message = "Provisioned local account identity",
            fields = mapOf("accountId" to accountIdentity.accountId, "displayName" to displayName),
        )
    }

    companion object {
        private const val SPK_ID_BYTES = 8
    }
}