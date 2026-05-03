package org.yapyap.backend.crypto

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
    private val privateKeyStore: PrivateKeyStore,
    private val config: IdentityKeyServiceConfig,
    private val logger: AppLogger = NoopAppLogger,
) : IdentityResolver {

    override fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord {

        val publicSigningKey = privateKeyStore.getKey(
            ref = KeyReference(
                keyId = config.defaultDeviceLocalKeyPrefix + IdentityKeyPurpose.SIGNING.name.lowercase(),
                purpose = IdentityKeyPurpose.SIGNING,
                type = KeyType.PUBLIC,
            )
        ) ?: error("Missing local signing private key")

        val publicEncryptionKey = privateKeyStore.getKey(
            ref = KeyReference(
                keyId = config.defaultDeviceLocalKeyPrefix + IdentityKeyPurpose.ENCRYPTION.name.lowercase(),
                purpose = IdentityKeyPurpose.ENCRYPTION,
                type = KeyType.PUBLIC,
            )
        ) ?: error("Missing local encryption private key")

        val deviceId = cryptoProvider.peerIdFromPublicKey(publicSigningKey)

        val deviceRecord = publicKeyRepository.getDevicePublicKey(deviceId)

        if (deviceRecord != null) {
            logger.info(
                component = LogComponent.CRYPTO,
                event = LogEvent.IDENTITY_DEVICE_RECORD_FOUND,
                message = "Resolved local device identity record",
                fields = mapOf("deviceId" to deviceId),
            )
            return deviceRecord
        } else {
            logger.warn(
                component = LogComponent.CRYPTO,
                event = LogEvent.IDENTITY_DEVICE_RECORD_MISSING,
                message = "Local device identity record missing, creating from local keys",
                fields = mapOf("deviceId" to deviceId),
            )
            val identity = DeviceIdentityRecord(
                deviceId,
                IdentityPublicKeyRecord(
                    keyId = config.defaultDeviceLocalKeyPrefix + IdentityKeyPurpose.SIGNING.name.lowercase(),
                    keyVersion = 0,
                    purpose = IdentityKeyPurpose.SIGNING,
                    publicKey = publicSigningKey,
                ),
                IdentityPublicKeyRecord(
                    keyId = config.defaultDeviceLocalKeyPrefix + IdentityKeyPurpose.ENCRYPTION.name.lowercase(),
                    keyVersion = 0,
                    purpose = IdentityKeyPurpose.ENCRYPTION,
                    publicKey = publicEncryptionKey,
                )
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

    override fun getLocalAccountIdentityRecord(): AccountIdentityRecord {
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


    override fun loadLocalPrivateKey(purpose: IdentityKeyPurpose): ByteArray {
        val keyId = config.defaultDeviceLocalKeyPrefix + purpose.name.lowercase()
        return privateKeyStore.getKey(
            ref = KeyReference(
                keyId = config.defaultDeviceLocalKeyPrefix + purpose.name.lowercase(),
                purpose = purpose,
                type = KeyType.PRIVATE,
            )
        ) ?: error("Missing local private key for keyId=$keyId, purpose=$purpose")
    }

    override fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord? {
        return publicKeyRepository.getDevicePublicKey(deviceId)
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
}
