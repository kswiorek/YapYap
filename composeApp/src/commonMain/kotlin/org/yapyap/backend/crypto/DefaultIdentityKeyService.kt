package org.yapyap.backend.crypto

import org.yapyap.backend.protocol.DeviceAddress

class DefaultIdentityKeyService(
    private val localAddress: DeviceAddress,
    private val cryptoProvider: CryptoProvider,
    private val publicKeyRepository: IdentityPublicKeyRepository,
    private val privateKeyStore: PrivateKeyStore,
) : IdentityKeyService {
    override fun getOrCreateLocalIdentity(address: DeviceAddress): LocalIdentityRecord {
        require(address == localAddress) {
            "IdentityKeyService is bound to $localAddress but was asked for $address"
        }

        publicKeyRepository.ensureAccountExists(address.accountId)
        publicKeyRepository.ensureDeviceExists(address)

        val existingSigning = publicKeyRepository.resolveDeviceKey(address.deviceId, IdentityKeyPurpose.SIGNING)
        val existingEncryption = publicKeyRepository.resolveDeviceKey(address.deviceId, IdentityKeyPurpose.ENCRYPTION)

        val signing = when {
            existingSigning == null -> createSigningRecord(address.deviceId, nextVersion = 1L)
            privateKeyExists(address.deviceId, existingSigning.keyId, IdentityKeyPurpose.SIGNING) -> existingSigning
            else -> error("Missing private signing key for existing keyId=${existingSigning.keyId}")
        }

        val encryption = when {
            existingEncryption == null -> createEncryptionRecord(address.deviceId, nextVersion = 1L)
            privateKeyExists(address.deviceId, existingEncryption.keyId, IdentityKeyPurpose.ENCRYPTION) -> existingEncryption
            else -> error("Missing private encryption key for existing keyId=${existingEncryption.keyId}")
        }

        val identity = LocalIdentityRecord(
            address = address,
            signing = signing,
            encryption = encryption,
        )

        if (existingSigning == null || existingEncryption == null) {
            publicKeyRepository.upsertLocalIdentity(identity)
        }

        return identity
    }

    override fun resolveLocalSigningKeyId(address: DeviceAddress): String {
        return getOrCreateLocalIdentity(address).signing.keyId
    }

    override fun loadLocalPrivateKey(keyId: String, purpose: IdentityKeyPurpose): ByteArray {
        return privateKeyStore.getPrivateKey(
            ref = PrivateKeyRef(
                deviceId = localAddress.deviceId,
                keyId = keyId,
                purpose = purpose,
            )
        ) ?: error("Missing local private key for keyId=$keyId, purpose=$purpose")
    }

    override fun resolvePeerPublicKey(source: DeviceAddress, purpose: IdentityKeyPurpose): ByteArray? {
        return publicKeyRepository.resolveDeviceKey(source.deviceId, purpose)?.publicKey
    }

    private fun createSigningRecord(deviceId: String, nextVersion: Long): IdentityPublicKeyRecord {
        val keyPair = cryptoProvider.generateSigningKeyPair()
        val keyId = keyId(IdentityKeyPurpose.SIGNING, nextVersion, keyPair.publicKey)
        privateKeyStore.putPrivateKey(
            ref = PrivateKeyRef(deviceId = deviceId, keyId = keyId, purpose = IdentityKeyPurpose.SIGNING),
            privateKey = keyPair.privateKey,
        )
        return IdentityPublicKeyRecord(
            keyId = keyId,
            keyVersion = nextVersion,
            purpose = IdentityKeyPurpose.SIGNING,
            publicKey = keyPair.publicKey,
        )
    }

    private fun createEncryptionRecord(deviceId: String, nextVersion: Long): IdentityPublicKeyRecord {
        val keyPair = cryptoProvider.generateEncryptionKeyPair()
        val keyId = keyId(IdentityKeyPurpose.ENCRYPTION, nextVersion, keyPair.publicKey)
        privateKeyStore.putPrivateKey(
            ref = PrivateKeyRef(deviceId = deviceId, keyId = keyId, purpose = IdentityKeyPurpose.ENCRYPTION),
            privateKey = keyPair.privateKey,
        )
        return IdentityPublicKeyRecord(
            keyId = keyId,
            keyVersion = nextVersion,
            purpose = IdentityKeyPurpose.ENCRYPTION,
            publicKey = keyPair.publicKey,
        )
    }

    private fun privateKeyExists(deviceId: String, keyId: String, purpose: IdentityKeyPurpose): Boolean {
        return privateKeyStore.getPrivateKey(
            ref = PrivateKeyRef(deviceId = deviceId, keyId = keyId, purpose = purpose)
        ) != null
    }

    private fun keyId(purpose: IdentityKeyPurpose, version: Long, publicKey: ByteArray): String {
        val fingerprint = cryptoProvider.toHex(cryptoProvider.sha256(publicKey)).take(16)
        return "${purpose.name.lowercase()}-v$version-$fingerprint"
    }
}
