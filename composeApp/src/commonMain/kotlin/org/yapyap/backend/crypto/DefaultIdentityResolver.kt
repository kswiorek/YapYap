package org.yapyap.backend.crypto

class DefaultIdentityResolver(
    private val cryptoProvider: CryptoProvider,
    private val publicKeyRepository: IdentityPublicKeyRepository,
    private val privateKeyStore: PrivateKeyStore,
    private val config: IdentityKeyServiceConfig,
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

        val deviceId = cryptoProvider.idFromPublicKey(publicSigningKey)

        val deviceRecord = publicKeyRepository.getDevicePublicKey(deviceId)

        if(deviceRecord != null) {
            return deviceRecord
        }
        else {
            val identity = DeviceIdentityRecord(deviceId,
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
            publicKeyRepository.insertLocalDevice(
                getLocalAccountIdentityRecord().accountId,
                identity)
            return identity
        }
    }

    override fun getLocalAccountIdentityRecord(): AccountIdentityRecord {
        val publicSigningKey = privateKeyStore.getKey(
            ref = KeyReference(
                keyId = config.defaultDeviceLocalKeyPrefix + IdentityKeyPurpose.SIGNING.name.lowercase(),
                purpose = IdentityKeyPurpose.SIGNING,
                type = KeyType.PUBLIC,
            )
        ) ?: error("Missing local signing private key")

        val accountId = cryptoProvider.idFromPublicKey(publicSigningKey)

        val accountRecord = publicKeyRepository.getAccountPublicKey(accountId)

        return accountRecord ?: error("Missing local account identity record for accountId=$accountId")
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

    override fun resolvePeerIdentityRecord(deviceId: String): DeviceIdentityRecord? {
        return publicKeyRepository.getDevicePublicKey(deviceId)
    }
}
