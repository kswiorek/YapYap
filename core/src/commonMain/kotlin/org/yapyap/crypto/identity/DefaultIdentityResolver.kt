package org.yapyap.crypto.identity

import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.e2ee.X3dhRemotePeerKeys
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.logging.AppLogger
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.logging.NoopAppLogger
import org.yapyap.persistence.key.IdentityKeyRepository
import org.yapyap.persistence.key.KeyReference
import org.yapyap.persistence.key.KeyStore
import org.yapyap.persistence.key.KeyType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint

class DefaultIdentityResolver(
    private val cryptoProvider: CryptoProvider,
    private val publicKeyRepository: IdentityKeyRepository,
    private val privateKeyStore: KeyStore,
    private val config: IdentityKeyServiceConfig,
    private val logger: AppLogger = NoopAppLogger,
) : IdentityResolver {

    private data class LocalSigningKeyMaterial(
        val keyId: String,
        val privateKey: ByteArray,
        val publicKey: ByteArray,
    )

    private suspend fun resolveLocalSigningKeyMaterial(
        keyPrefix: String,
        onMissingPrivateKey: () -> CryptoException,
    ): LocalSigningKeyMaterial {
        val signingKeyId = keyPrefix + IdentityKeyPurpose.SIGNING.name.lowercase()
        val privateSigningKey = privateKeyStore.getKey(
            ref = KeyReference(
                keyId = signingKeyId,
                purpose = IdentityKeyPurpose.SIGNING,
                type = KeyType.PRIVATE,
            ),
        ) ?: throw onMissingPrivateKey()

        val publicSigningKey = privateKeyStore.getKey(
            ref = KeyReference(
                keyId = signingKeyId,
                purpose = IdentityKeyPurpose.SIGNING,
                type = KeyType.PUBLIC,
            ),
        ) ?: cryptoProvider.privateSigningKeyToPublicKey(privateSigningKey)

        return LocalSigningKeyMaterial(
            keyId = signingKeyId,
            privateKey = privateSigningKey,
            publicKey = publicSigningKey,
        )
    }

    private suspend fun buildLocalDeviceIdentityRecordFromKeys(): DeviceIdentityRecord {
        val signingKeys = resolveLocalSigningKeyMaterial(config.defaultDeviceLocalKeyPrefix) {
            CryptoException.MissingDeviceRecord("local device")
        }
        val deviceId = cryptoProvider.peerIdFromPublicKey(signingKeys.publicKey)

        val encryptionKeyId = config.defaultDeviceLocalKeyPrefix + IdentityKeyPurpose.ENCRYPTION.name.lowercase()
        val privateEncryptionKey = privateKeyStore.getKey(
            ref = KeyReference(
                keyId = encryptionKeyId,
                purpose = IdentityKeyPurpose.ENCRYPTION,
                type = KeyType.PRIVATE,
            ),
        ) ?: throw CryptoException.MissingDeviceRecord("local device")

        val publicEncryptionKey = privateKeyStore.getKey(
            ref = KeyReference(
                keyId = encryptionKeyId,
                purpose = IdentityKeyPurpose.ENCRYPTION,
                type = KeyType.PUBLIC,
            ),
        ) ?: cryptoProvider.privateEncryptionKeyToPublicKey(privateEncryptionKey)

        val keySignature = cryptoProvider.signDetached(
            signingKeys.privateKey,
            publicEncryptionKey + encryptionKeyId.encodeToByteArray(),
        )

        return DeviceIdentityRecord(
            deviceId,
            IdentityPublicKeyRecord(
                keyId = signingKeys.keyId,
                keyVersion = 0,
                purpose = IdentityKeyPurpose.SIGNING,
                publicKey = signingKeys.publicKey,
            ),
            IdentityPublicKeyRecord(
                keyId = encryptionKeyId,
                keyVersion = 0,
                purpose = IdentityKeyPurpose.ENCRYPTION,
                publicKey = publicEncryptionKey,
            ),
            keySignature = keySignature,
        )
    }

    private suspend fun buildLocalAccountIdentityRecordFromKeys(): AccountIdentityRecord {
        val signingKeys = resolveLocalSigningKeyMaterial(config.defaultAccountLocalKeyPrefix) {
            CryptoException.MissingAccountRecord("local account")
        }
        val accountId = cryptoProvider.accountIdFromPublicKey(signingKeys.publicKey)
        return AccountIdentityRecord(
            accountId,
            IdentityPublicKeyRecord(
                keyId = signingKeys.keyId,
                keyVersion = 0,
                purpose = IdentityKeyPurpose.SIGNING,
                publicKey = signingKeys.publicKey,
            ),
        )
    }

    override suspend fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord {

        val deviceRecord = publicKeyRepository.getLocalDeviceRecord()

        if (deviceRecord != null) {
            logger.info(
                component = LogComponent.CRYPTO,
                event = LogEvent.IDENTITY_DEVICE_RECORD_FOUND,
                message = "Resolved local device identity record",
                fields = mapOf("deviceId" to deviceRecord.deviceId),
            )
            return deviceRecord
        } else {

            logger.warn(
                component = LogComponent.CRYPTO,
                event = LogEvent.IDENTITY_DEVICE_RECORD_MISSING,
                message = "Local device identity record missing, creating from local keys",
            )

            val identity = buildLocalDeviceIdentityRecordFromKeys()
            val accountRecord = getLocalAccountIdentityRecord()
            publicKeyRepository.insertLocalDevice(
                accountRecord.accountId,
                identity,
            )
            logger.info(
                component = LogComponent.CRYPTO,
                event = LogEvent.IDENTITY_DEVICE_RECORD_CREATED,
                message = "Created and persisted local device identity record",
                fields = mapOf("deviceId" to identity.deviceId, "accountId" to accountRecord.accountId),
            )
            return identity
        }
    }

    override suspend fun getLocalAccountIdentityRecord(): AccountIdentityRecord {
        val identity = buildLocalAccountIdentityRecordFromKeys()
        val accountRecord = publicKeyRepository.getAccountPublicKey(identity.accountId)

        if (accountRecord != null) {
            logger.info(
                component = LogComponent.CRYPTO,
                event = LogEvent.IDENTITY_ACCOUNT_RECORD_FOUND,
                message = "Resolved local account identity record",
                fields = mapOf("accountId" to identity.accountId),
            )
            return accountRecord
        }

        logger.warn(
            component = LogComponent.CRYPTO,
            event = LogEvent.IDENTITY_ACCOUNT_RECORD_MISSING,
            message = "Local account identity record missing, creating from local keys",
        )
        publicKeyRepository.insertLocalAccount(identity.accountId.id, identity)
        logger.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.IDENTITY_ACCOUNT_RECORD_CREATED,
            message = "Created and persisted local account identity record",
            fields = mapOf("accountId" to identity.accountId),
        )
        return identity
    }


    override suspend fun getLocalDevicePrivateKey(purpose: IdentityKeyPurpose): ByteArray {
        val keyId = config.defaultDeviceLocalKeyPrefix + purpose.name.lowercase()
        return privateKeyStore.getKey(
            ref = KeyReference(
                keyId = keyId,
                purpose = purpose,
                type = KeyType.PRIVATE,
            )
        ) ?: throw CryptoException.MissingKey(keyId, purpose, KeyType.PRIVATE)
    }

    override suspend fun getLocalAccountPrivateKey(purpose: IdentityKeyPurpose): ByteArray {
        val keyId = config.defaultDeviceLocalKeyPrefix + purpose.name.lowercase()
        return privateKeyStore.getKey(
            ref = KeyReference(
                keyId = config.defaultAccountLocalKeyPrefix + purpose.name.lowercase(),
                purpose = purpose,
                type = KeyType.PRIVATE,
            )
        ) ?: throw CryptoException.MissingKey(keyId, purpose, KeyType.PRIVATE)
    }

    override suspend fun getLocalDeviceId(): PeerId {
        return publicKeyRepository.getLocalDeviceRecord()?.deviceId
            ?: buildLocalDeviceIdentityRecordFromKeys().deviceId
    }

    override suspend fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord {
        val device = publicKeyRepository.getDeviceRecord(deviceId)
        if (device == null) {
            val e = CryptoException.MissingDeviceRecord(deviceId.id)
            logger.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.IDENTITY_DEVICE_RECORD_MISSING,
                message = "Missing peer record",
                fields = mapOf("deviceId" to deviceId),
                throwable = e,
            )
            throw e
        }
        if (device.keySignature == null) {
            val e = CryptoException.IncompleteRecord("Missing keySignature for device ${deviceId.id}, cannot verify device identity.")
            logger.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.IDENTITY_DEVICE_RECORD_MISSING,
                message = "Incomplete peer record",
                fields = mapOf("deviceId" to deviceId),
                throwable = e,
            )
            throw e
        }

        val verifyMessage = device.encryption.publicKey + device.encryption.keyId.encodeToByteArray()
        if (!cryptoProvider.verifyDetached(device.signing.publicKey, verifyMessage, device.keySignature)) {
            val e = CryptoException.IncompleteRecord("Device ${deviceId.id} keySignature verification failed, cannot verify device identity.")
            logger.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.IDENTITY_DEVICE_RECORD_MISSING,
                message = "Incomplete peer record, keySignature verification failed",
                fields = mapOf("deviceId" to deviceId),
                throwable = e,
            )
            throw e
        }

        return device
    }

    override fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint {
        val torEndpoint = publicKeyRepository.resolveTorEndpointForDevice(deviceId)

        if (torEndpoint == null) {
            val e = CryptoException.MissingDeviceRecord(deviceId.id)
            logger.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.IDENTITY_DEVICE_RECORD_MISSING,
                message = "Tor endpoint for device ${deviceId.id} not found",
                fields = mapOf("deviceId" to deviceId),
                throwable = e,
            )
            throw e
        }
        return torEndpoint
    }

    override fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> {
        return publicKeyRepository.getAllPeerDevicesForAccount(accountId)
    }

    override fun updatePeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) {
        publicKeyRepository.upsertPeerTorEndpoint(deviceId, torEndpoint)
    }

    override suspend fun resolvePeerX3dhRemoteKeys(
        deviceId: PeerId,
        signedPreKeyId: String?,
    ): X3dhRemotePeerKeys {
        val device = resolvePeerIdentityRecord(deviceId)
        val signedPreKey = when {
            signedPreKeyId != null -> {
                val stored = publicKeyRepository.getSignedPreKey(signedPreKeyId)
                    ?: throw  CryptoException.MissingKey(signedPreKeyId, IdentityKeyPurpose.SIGNED_PREKEY, KeyType.PUBLIC).also {
                        logger.error(
                            component = LogComponent.CRYPTO,
                            event = LogEvent.KEY_LOOKUP_MISS,
                            message = "Key lookup failed for signed prekey $signedPreKeyId for device $deviceId, cannot verify device identity.",
                            fields = mapOf("deviceId" to deviceId),
                            throwable = it,
                        )
                    }
                require(stored.deviceId == deviceId) {
                    "Signed prekey $signedPreKeyId does not belong to deviceId=$deviceId"
                }
                stored
            }
            else -> device.signedPreKey
                ?: throw CryptoException.MissingKey(device.signedPreKey?.keyId ?: "", IdentityKeyPurpose.SIGNED_PREKEY, KeyType.PUBLIC).also {
                    logger.error(
                        component = LogComponent.CRYPTO,
                        event = LogEvent.KEY_LOOKUP_MISS,
                        message = "Key lookup failed for signed prekey for device $deviceId, cannot verify device identity.",
                        fields = mapOf("deviceId" to deviceId),
                        throwable = it,
                    )
                }
        }
        if (!cryptoProvider.verifyDetached(device.signing.publicKey, signedPreKey.publicKey, signedPreKey.signature)) {
            val e = CryptoException.IncompleteRecord("Device ${deviceId.id} keySignature verification failed, cannot verify device identity.")
            logger.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.IDENTITY_DEVICE_RECORD_MISSING,
                message = "Incomplete peer record, signedPreKey verification failed",
                fields = mapOf("deviceId" to deviceId),
                throwable = e,
            )
            throw e
        }

        return X3dhRemotePeerKeys(
            identityEncryptionPublicKey = device.encryption.publicKey,
            signedPreKeyPublicKey = signedPreKey.publicKey,
            signedPreKeyId = signedPreKey.keyId,
        )
    }

    override suspend fun getCurrentLocalSignedPreKey(): SignedPreKeyRecord {
        val device = getLocalDeviceIdentityRecord()
        val activeId = publicKeyRepository.getActiveSignedPreKeyForDevice(device.deviceId)?.keyId
            ?: device.signedPreKey?.keyId
            ?: throw CryptoException.MissingKey(device.signedPreKey?.keyId ?: "", IdentityKeyPurpose.SIGNED_PREKEY, KeyType.PUBLIC).also {
                logger.error(
                    component = LogComponent.CRYPTO,
                    event = LogEvent.KEY_LOOKUP_MISS,
                    message = "Key lookup failed for signed prekey for device ${device.deviceId}, cannot verify device identity.",
                    fields = mapOf("deviceId" to device.deviceId),
                    throwable = it,
                )
            }
        return resolveLocalSignedPreKey(activeId)
    }

    override suspend fun resolveLocalSignedPreKey(signedPreKeyId: String): SignedPreKeyRecord {
        val device = getLocalDeviceId()
        val stored = publicKeyRepository.getSignedPreKey(signedPreKeyId)
            ?: throw CryptoException.MissingKey(signedPreKeyId, IdentityKeyPurpose.SIGNED_PREKEY, KeyType.PUBLIC).also {
                logger.error(
                    component = LogComponent.CRYPTO,
                    event = LogEvent.KEY_LOOKUP_MISS,
                    message = "Key lookup failed for signed prekey $signedPreKeyId for device ${device}, cannot verify device identity.",
                    fields = mapOf("deviceId" to device),
                    throwable = it,
                )
            }
        require(stored.deviceId == device) {
            "Signed prekey $signedPreKeyId does not belong to local device ${device}"
        }
        privateKeyStore.getKey(
            ref = KeyReference(
                keyId = signedPreKeyId,
                purpose = IdentityKeyPurpose.ENCRYPTION,
                type = KeyType.PRIVATE,
            )
        ) ?: throw CryptoException.MissingKey(signedPreKeyId, IdentityKeyPurpose.ENCRYPTION, KeyType.PRIVATE).also {
            logger.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.KEY_LOOKUP_MISS,
                message = "Key lookup failed for signed prekey $signedPreKeyId for device ${device}, cannot verify device identity.",
                fields = mapOf("deviceId" to device),
                throwable = it,
            )
        }

        return stored
    }
}
