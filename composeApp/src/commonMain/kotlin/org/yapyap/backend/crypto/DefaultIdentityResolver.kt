package org.yapyap.backend.crypto

import org.yapyap.backend.crypto.e2ee.X3dhRemotePeerKeys
import org.yapyap.backend.db.IdentityPublicKeyRepository
import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.logging.NoopAppLogger
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint

class DefaultIdentityResolver(
    private val cryptoProvider: CryptoProvider,
    private val publicKeyRepository: IdentityPublicKeyRepository,
    private val privateKeyStore: KeyStore,
    private val config: IdentityKeyServiceConfig,
    private val logger: AppLogger = NoopAppLogger,
) : IdentityResolver {

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

            val signingKeyId = config.defaultDeviceLocalKeyPrefix + IdentityKeyPurpose.SIGNING.name.lowercase()
            val privateSigningKey = privateKeyStore.getKey(
                ref = KeyReference(
                    keyId = signingKeyId,
                    purpose = IdentityKeyPurpose.SIGNING,
                    type = KeyType.PRIVATE,
                )
            ) ?: error("Missing local signing private key")

            val publicSigningKey = privateKeyStore.getKey(
                ref = KeyReference(
                    keyId = signingKeyId,
                    purpose = IdentityKeyPurpose.SIGNING,
                    type = KeyType.PUBLIC,
                )
            ) ?: cryptoProvider.privateSigningKeyToPublicKey(privateSigningKey)

            val deviceId = cryptoProvider.peerIdFromPublicKey(publicSigningKey)

            val encryptionKeyId = config.defaultDeviceLocalKeyPrefix + IdentityKeyPurpose.ENCRYPTION.name.lowercase()
            val privateEncryptionKey = privateKeyStore.getKey(
                ref = KeyReference(
                    keyId = encryptionKeyId,
                    purpose = IdentityKeyPurpose.ENCRYPTION,
                    type = KeyType.PRIVATE,
                )
            ) ?: error("Missing local encryption private key")

            val publicEncryptionKey = privateKeyStore.getKey(
                ref = KeyReference(
                    keyId = encryptionKeyId,
                    purpose = IdentityKeyPurpose.ENCRYPTION,
                    type = KeyType.PUBLIC,
                )
            ) ?: cryptoProvider.privateEncryptionKeyToPublicKey(privateEncryptionKey)
            val keySignature = cryptoProvider.signDetached(
                privateSigningKey,
                publicEncryptionKey + encryptionKeyId.encodeToByteArray(),
            )

            val identity = DeviceIdentityRecord(
                deviceId,
                IdentityPublicKeyRecord(
                    keyId = signingKeyId,
                    keyVersion = 0,
                    purpose = IdentityKeyPurpose.SIGNING,
                    publicKey = publicSigningKey,
                ),
                IdentityPublicKeyRecord(
                    keyId = encryptionKeyId,
                    keyVersion = 0,
                    purpose = IdentityKeyPurpose.ENCRYPTION,
                    publicKey = publicEncryptionKey,
                ),
                keySignature = keySignature
            )
            val accountRecord = getLocalAccountIdentityRecord()
            publicKeyRepository.insertLocalDevice(
                accountRecord.accountId,
                identity,
            )
            logger.info(
                component = LogComponent.CRYPTO,
                event = LogEvent.IDENTITY_DEVICE_RECORD_CREATED,
                message = "Created and persisted local device identity record",
                fields = mapOf("deviceId" to deviceId, "accountId" to accountRecord.accountId),
            )
            return identity
        }
    }

    override suspend fun getLocalAccountIdentityRecord(): AccountIdentityRecord {
        val publicSigningKey = privateKeyStore.getKey(
            ref = KeyReference(
                keyId = config.defaultAccountLocalKeyPrefix + IdentityKeyPurpose.SIGNING.name.lowercase(),
                purpose = IdentityKeyPurpose.SIGNING,
                type = KeyType.PUBLIC,
            )
        ) ?: error("Missing local signing private key")

        val accountId = cryptoProvider.accountIdFromPublicKey(publicSigningKey)

        val accountRecord = publicKeyRepository.getAccountPublicKey(accountId)

        if (accountRecord != null) {
            logger.info(
                component = LogComponent.CRYPTO,
                event = LogEvent.IDENTITY_ACCOUNT_RECORD_FOUND,
                message = "Resolved local account identity record",
                fields = mapOf("accountId" to accountId),
            )
            return accountRecord
        }

        error("Missing local account identity record for accountId=$accountId")
    }


    override suspend fun getLocalDevicePrivateKey(purpose: IdentityKeyPurpose): ByteArray {
        val keyId = config.defaultDeviceLocalKeyPrefix + purpose.name.lowercase()
        return privateKeyStore.getKey(
            ref = KeyReference(
                keyId = config.defaultDeviceLocalKeyPrefix + purpose.name.lowercase(),
                purpose = purpose,
                type = KeyType.PRIVATE,
            )
        ) ?: error("Missing local private key for keyId=$keyId, purpose=$purpose")
    }

    override suspend fun getLocalAccountPrivateKey(purpose: IdentityKeyPurpose): ByteArray {
        val keyId = config.defaultDeviceLocalKeyPrefix + purpose.name.lowercase()
        return privateKeyStore.getKey(
            ref = KeyReference(
                keyId = config.defaultAccountLocalKeyPrefix + purpose.name.lowercase(),
                purpose = purpose,
                type = KeyType.PRIVATE,
            )
        ) ?: error("Missing local private key for keyId=$keyId, purpose=$purpose")
    }

    override suspend fun getLocalDeviceId(): PeerId {
        val record = publicKeyRepository.getLocalDeviceRecord()
        return record?.deviceId ?: error("Missing local device identity record")
    }

    override suspend fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord? {
        val device = publicKeyRepository.getDeviceRecord(deviceId) ?: return null
        if (device.keySignature == null) return null
        if (!cryptoProvider.verifyDetached(device.signing.publicKey, (device.encryption.publicKey + device.encryption.keyId.encodeToByteArray()), device.keySignature)) return null
        return device
    }

    override fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint {
        return publicKeyRepository.resolveDeviceKey(deviceId, IdentityKeyPurpose.ENCRYPTION)?.let {
            publicKeyRepository.resolveTorEndpointForDevice(deviceId)
        } ?: error("Missing encryption key for deviceId=$deviceId, cannot resolve Tor endpoint")
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
            ?: error("Missing peer identity record for deviceId=$deviceId")
        val signedPreKey = when {
            signedPreKeyId != null -> {
                val stored = publicKeyRepository.getSignedPreKey(signedPreKeyId)
                    ?: error("Signed prekey not found: $signedPreKeyId")
                require(stored.deviceId == deviceId) {
                    "Signed prekey $signedPreKeyId does not belong to deviceId=$deviceId"
                }
                stored
            }
            else -> device.signedPreKey
                ?: error("Missing signed prekey on roster for deviceId=$deviceId")
        }
        require(cryptoProvider.verifyDetached(device.signing.publicKey, signedPreKey.publicKey, signedPreKey.signature)) {
            "failed to verify signed prekey signature"
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
            ?: error("Local device ${device.deviceId} has no signed prekey published")
        return resolveLocalSignedPreKey(activeId)
    }

    override suspend fun resolveLocalSignedPreKey(signedPreKeyId: String): SignedPreKeyRecord {
        val device = getLocalDeviceIdentityRecord()
        val stored = publicKeyRepository.getSignedPreKey(signedPreKeyId)
            ?: error("Signed prekey not found: $signedPreKeyId")
        require(stored.deviceId == device.deviceId) {
            "Signed prekey $signedPreKeyId does not belong to local device ${device.deviceId}"
        }
        require(cryptoProvider.verifyDetached(device.signing.publicKey, stored.publicKey, stored.signature)) {
            "failed to verify local signed prekey signature"
        }
        privateKeyStore.getKey(
            ref = KeyReference(
                keyId = signedPreKeyId,
                purpose = IdentityKeyPurpose.ENCRYPTION,
                type = KeyType.PRIVATE,
            )
        ) ?: error("Missing local signed prekey private key for keyId=$signedPreKeyId")

        return stored
    }
}
