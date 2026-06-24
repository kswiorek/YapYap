package org.yapyap.backend.crypto

import org.yapyap.backend.crypto.e2ee.OneTimePreKeyStore
import org.yapyap.backend.db.AccountStatus
import org.yapyap.backend.db.DeviceType
import org.yapyap.backend.db.IdentityPublicKeyRepository
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint

/**
 * Builds a [DeviceIdentityRecord] with a valid encryption-key attestation and signed prekey,
 * using the same signing payload layout as [DefaultIdentityProvisioning] / [DefaultIdentityResolver].
 */
internal suspend fun buildAttestedDeviceIdentity(
    crypto: CryptoProvider,
    label: String,
): DeviceIdentityRecord {
    val signing = crypto.generateSigningKeyPair()
    val encryption = crypto.generateEncryptionKeyPair()
    val spk = crypto.generateEncryptionKeyPair()
    val deviceId = crypto.peerIdFromPublicKey(signing.publicKey)
    val encryptionKeyId = "encryption-$label"
    val spkId = "spk-$label"
    val keySignature = crypto.signDetached(
        signing.privateKey,
        encryption.publicKey + encryptionKeyId.encodeToByteArray(),
    )
    val spkSignature = crypto.signDetached(signing.privateKey, spk.publicKey)
    return DeviceIdentityRecord(
        deviceId = deviceId,
        signing = IdentityPublicKeyRecord(
            keyId = "signing-$label",
            keyVersion = 0,
            purpose = IdentityKeyPurpose.SIGNING,
            publicKey = signing.publicKey,
        ),
        encryption = IdentityPublicKeyRecord(
            keyId = encryptionKeyId,
            keyVersion = 0,
            purpose = IdentityKeyPurpose.ENCRYPTION,
            publicKey = encryption.publicKey,
        ),
        signedPreKey = SignedPreKeyRecord(
            keyId = spkId,
            publicKey = spk.publicKey,
            signature = spkSignature,
        ),
        keySignature = keySignature,
    )
}

/** In-memory [KeyStore] for unit tests. */
internal class InMemoryKeyStore : KeyStore {
    private val keys = mutableMapOf<KeyReference, ByteArray>()

    override suspend fun putKey(ref: KeyReference, key: ByteArray) {
        keys[ref] = key.copyOf()
    }

    override suspend fun getKey(ref: KeyReference): ByteArray? =
        keys[ref]?.copyOf()

    override suspend fun deleteKey(ref: KeyReference) {
        keys.remove(ref)
    }
}

/**
 * Minimal [IdentityPublicKeyRepository] backing store for [DefaultIdentityResolver] /
 * [DefaultIdentityProvisioning] contract tests.
 */
internal class InMemoryIdentityPublicKeyRepository(
    private val defaultLocalTor: TorEndpoint = TorEndpoint(onionAddress = "unknown.onion", port = 80),
) : IdentityPublicKeyRepository {

    val accounts = mutableMapOf<String, AccountIdentityRecord>()
    val devices = mutableMapOf<String, DeviceIdentityRecord>()
    private val signedPreKeys = mutableMapOf<String, StoredSignedPreKey>()
    private val activeSignedPreKeyByDevice = mutableMapOf<String, String>()
    private val deviceToAccount = mutableMapOf<String, String>()
    private val peersForAccount = mutableMapOf<String, MutableSet<String>>()
    private val torForDevice = mutableMapOf<String, TorEndpoint>()

    override fun getAccountPublicKey(accountId: AccountId): AccountIdentityRecord? =
        accounts[accountId.id]

    override fun getDevicePublicKey(deviceId: PeerId): DeviceIdentityRecord? =
        devices[deviceId.id]?.let { device ->
            val activeSpkId = activeSignedPreKeyByDevice[deviceId.id]
            val activeSpk = activeSpkId?.let { signedPreKeys[it]?.toRecord() } ?: device.signedPreKey
            if (activeSpk == device.signedPreKey) device else device.copy(signedPreKey = activeSpk)
        }

    override fun insertLocalDevice(
        accountId: AccountId,
        identity: DeviceIdentityRecord,
        localSignedPreKeyPrivateKey: ByteArray?,
    ) {
        devices[identity.deviceId.id] = identity
        deviceToAccount[identity.deviceId.id] = accountId.id
        peersForAccount.getOrPut(accountId.id) { mutableSetOf() }.add(identity.deviceId.id)
        torForDevice[identity.deviceId.id] = defaultLocalTor
        identity.signedPreKey?.let { spk ->
            persistSignedPreKey(
                deviceId = identity.deviceId,
                signedPreKey = spk,
                privateKey = localSignedPreKeyPrivateKey,
                isActive = true,
                createdAtEpochSeconds = 0L,
            )
        }
    }

    override fun insertPeerDevice(
        accountId: AccountId,
        deviceType: DeviceType,
        identity: DeviceIdentityRecord,
        torEndpoint: TorEndpoint,
    ) {
        devices[identity.deviceId.id] = identity
        deviceToAccount[identity.deviceId.id] = accountId.id
        peersForAccount.getOrPut(accountId.id) { mutableSetOf() }.add(identity.deviceId.id)
        torForDevice[identity.deviceId.id] = torEndpoint
        identity.signedPreKey?.let { spk ->
            persistSignedPreKey(
                deviceId = identity.deviceId,
                signedPreKey = spk,
                privateKey = null,
                isActive = true,
                createdAtEpochSeconds = 0L,
            )
        }
    }

    override fun insertLocalAccount(displayName: String, identity: AccountIdentityRecord) {
        accounts[identity.accountId.id] = identity
    }

    override fun resolveDeviceKey(deviceId: PeerId, purpose: IdentityKeyPurpose): IdentityPublicKeyRecord? {
        val d = devices[deviceId.id] ?: return null
        return when (purpose) {
            IdentityKeyPurpose.SIGNING -> d.signing
            IdentityKeyPurpose.ENCRYPTION -> d.encryption
            IdentityKeyPurpose.SIGNED_PREKEY -> getActiveSignedPreKeyForDevice(deviceId)?.let { spk ->
                IdentityPublicKeyRecord(
                    keyId = spk.spkId,
                    keyVersion = 0,
                    purpose = IdentityKeyPurpose.SIGNED_PREKEY,
                    publicKey = spk.publicKey,
                )
            }
        }
    }

    override fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint =
        torForDevice[deviceId.id]
            ?: error("No Tor endpoint for deviceId=${deviceId.id}")

    override fun insertPeerAccount(
        identity: AccountIdentityRecord,
        admin: Boolean,
        status: AccountStatus,
        displayName: String,
    ) {
        accounts[identity.accountId.id] = identity
    }

    override fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> =
        peersForAccount[accountId.id]?.map { PeerId(it) }?.sortedBy { it.id }.orEmpty()

    override fun upsertPeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) {
        torForDevice[deviceId.id] = torEndpoint
    }

    override fun getSignedPreKey(spkId: String): StoredSignedPreKey? = signedPreKeys[spkId]

    override fun getActiveSignedPreKeyForDevice(deviceId: PeerId): StoredSignedPreKey? =
        activeSignedPreKeyByDevice[deviceId.id]?.let { signedPreKeys[it] }

    override fun insertSignedPreKey(stored: StoredSignedPreKey) {
        signedPreKeys[stored.spkId] = stored
        if (stored.isActive) {
            activeSignedPreKeyByDevice[stored.deviceId.id] = stored.spkId
        }
    }

    override fun upsertDeviceSignedPreKey(
        deviceId: PeerId,
        signedPreKey: SignedPreKeyRecord,
        privateKey: ByteArray?,
        createdAtEpochSeconds: Long,
    ) {
        val existing = devices[deviceId.id] ?: error("Device not found: $deviceId")
        signedPreKeys.values
            .filter { it.deviceId == deviceId && it.isActive }
            .forEach { signedPreKeys[it.spkId] = it.copy(isActive = false) }
        devices[deviceId.id] = existing.copy(signedPreKey = signedPreKey)
        persistSignedPreKey(
            deviceId = deviceId,
            signedPreKey = signedPreKey,
            privateKey = privateKey,
            isActive = true,
            createdAtEpochSeconds = createdAtEpochSeconds,
        )
    }

    private fun persistSignedPreKey(
        deviceId: PeerId,
        signedPreKey: SignedPreKeyRecord,
        privateKey: ByteArray?,
        isActive: Boolean,
        createdAtEpochSeconds: Long,
    ) {
        signedPreKeys.values
            .filter { it.deviceId == deviceId && it.isActive }
            .forEach { signedPreKeys[it.spkId] = it.copy(isActive = false) }
        val stored = StoredSignedPreKey(
            spkId = signedPreKey.keyId,
            deviceId = deviceId,
            publicKey = signedPreKey.publicKey,
            privateKey = privateKey,
            signature = signedPreKey.signature,
            isActive = isActive,
            createdAtEpochSeconds = createdAtEpochSeconds,
        )
        signedPreKeys[stored.spkId] = stored
        if (isActive) {
            activeSignedPreKeyByDevice[deviceId.id] = stored.spkId
        }
    }

    /** Seeds Tor for a device already stored (e.g. after [insertLocalDevice]). */
    fun seedTorEndpoint(deviceId: PeerId, endpoint: TorEndpoint) {
        torForDevice[deviceId.id] = endpoint
    }
}

/** In-memory [org.yapyap.backend.crypto.e2ee.OneTimePreKeyStore] for unit tests. */
internal class InMemoryOneTimePreKeyStore(
    private val crypto: CryptoProvider,
) : OneTimePreKeyStore {
    private val keys = mutableMapOf<String, LocalOneTimePreKey>()
    private val consumed = mutableSetOf<String>()
    private var counter = 0

    override suspend fun allocate(): LocalOneTimePreKey {
        val keyPair = crypto.generateEncryptionKeyPair()
        val opkId = "opk-test-${++counter}"
        val opk = LocalOneTimePreKey(
            keyId = opkId,
            publicKey = keyPair.publicKey,
            privateKey = keyPair.privateKey,
        )
        keys[opkId] = opk
        return opk
    }

    override suspend fun consume(opkId: String): LocalOneTimePreKey? {
        if (opkId in consumed) return null
        val opk = keys[opkId] ?: return null
        consumed.add(opkId)
        return opk
    }
}
