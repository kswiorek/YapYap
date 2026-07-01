package org.yapyap.persistence.key

import org.yapyap.crypto.identity.*
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.persistence.db.AccountStatus
import org.yapyap.persistence.db.DeviceType
import org.yapyap.persistence.db.OpkStatus
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.time.EpochSecondsProvider
import org.yapyap.time.SystemEpochSecondsProvider

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
 * Minimal [IdentityKeyRepository] backing store for [org.yapyap.crypto.identity.DefaultIdentityResolver] /
 * [org.yapyap.crypto.identity.DefaultIdentityProvisioning] contract tests.
 */
internal class InMemoryIdentityKeyRepository(
    private val defaultLocalTor: TorEndpoint = TorEndpoint(onionAddress = "unknown.onion", port = 80),
) : IdentityKeyRepository {

    val accounts = mutableMapOf<String, AccountIdentityRecord>()
    val devices = mutableMapOf<String, DeviceIdentityRecord>()
    var localDevice : DeviceIdentityRecord? = null
    private val signedPreKeys = mutableMapOf<String, SignedPreKeyRecord>()
    private val activeSignedPreKeyByDevice = mutableMapOf<String, String>()
    private val deviceToAccount = mutableMapOf<String, String>()
    private val peersForAccount = mutableMapOf<String, MutableSet<String>>()
    private val torForDevice = mutableMapOf<String, TorEndpoint>()

    override fun getAccountPublicKey(accountId: AccountId): AccountIdentityRecord? =
        accounts[accountId.id]

    override fun getDeviceRecord(deviceId: PeerId): DeviceIdentityRecord? =
        devices[deviceId.id]?.let { device ->
            val activeSpkId = activeSignedPreKeyByDevice[deviceId.id]
            val activeSpk = activeSpkId?.let { signedPreKeys[it] } ?: device.signedPreKey
            if (activeSpk == device.signedPreKey) device else device.copy(signedPreKey = activeSpk)
        }

    override fun insertLocalDevice(
        accountId: AccountId,
        identity: DeviceIdentityRecord,
    ) {
        devices[identity.deviceId.id] = identity
        localDevice = identity
        deviceToAccount[identity.deviceId.id] = accountId.id
        peersForAccount.getOrPut(accountId.id) { mutableSetOf() }.add(identity.deviceId.id)
        torForDevice[identity.deviceId.id] = defaultLocalTor
        identity.signedPreKey?.let { spk ->
            persistSignedPreKey(
                deviceId = identity.deviceId,
                signedPreKey = spk,
                isActive = true,
                createdAtEpochSeconds = 0L,
            )
        }
    }

    override fun getLocalDeviceRecord(): DeviceIdentityRecord? = localDevice

    fun clearLocalDeviceRecord() {
        localDevice = null
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
                    keyId = spk.keyId,
                    keyVersion = 0,
                    purpose = IdentityKeyPurpose.SIGNED_PREKEY,
                    publicKey = spk.publicKey,
                )
            }
        }
    }

    override fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint? =
        torForDevice[deviceId.id]
            ?: return null

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

    override fun getSignedPreKey(spkId: String): SignedPreKeyRecord = signedPreKeys[spkId]!!

    override fun getActiveSignedPreKeyForDevice(deviceId: PeerId): SignedPreKeyRecord? =
        activeSignedPreKeyByDevice[deviceId.id]?.let { signedPreKeys[it]!! }

    override fun insertSignedPreKey(spk: SignedPreKeyRecord) {
        signedPreKeys[spk.keyId] = spk
        if (spk.isActive) {
            activeSignedPreKeyByDevice[spk.deviceId.id] = spk.keyId
        }
    }

    override fun upsertDeviceSignedPreKey(spk: SignedPreKeyRecord) {
        val deviceId = spk.deviceId
        val existing = devices[deviceId.id] ?: error("Device not found: $deviceId")
        signedPreKeys.values
            .filter { it.deviceId == deviceId && it.isActive }
            .forEach { signedPreKeys[it.keyId] = it.copy(isActive = false) }
        devices[deviceId.id] = existing.copy(signedPreKey = spk)
        persistSignedPreKey(
            deviceId = deviceId,
            signedPreKey = spk,
            isActive = true,
            createdAtEpochSeconds = spk.createdAtEpochSeconds?:0L,
        )
    }

    private fun persistSignedPreKey(
        deviceId: PeerId,
        signedPreKey: SignedPreKeyRecord,
        isActive: Boolean,
        createdAtEpochSeconds: Long,
    ) {
        signedPreKeys.values
            .filter { it.deviceId == deviceId && it.isActive }
            .forEach { signedPreKeys[it.keyId] = it.copy(isActive = false) }
        val stored = SignedPreKeyRecord(
            deviceId = deviceId,
            keyId = signedPreKey.keyId,
            publicKey = signedPreKey.publicKey,
            privateKey = signedPreKey.privateKey,
            signature = signedPreKey.signature,
            isActive = isActive,
            createdAtEpochSeconds = createdAtEpochSeconds,
        )
        signedPreKeys[stored.keyId] = stored
        if (isActive) {
            activeSignedPreKeyByDevice[deviceId.id] = stored.keyId
        }
    }

    /** Seeds Tor for a device already stored (e.g. after [insertLocalDevice]). */
    fun seedTorEndpoint(deviceId: PeerId, endpoint: TorEndpoint) {
        torForDevice[deviceId.id] = endpoint
    }
}

/** In-memory [OpkRepository] for unit tests. */
internal class InMemoryOpkRepository(
    private val crypto: CryptoProvider,
    private val timeProvider: EpochSecondsProvider = SystemEpochSecondsProvider,
) : OpkRepository {
    private data class OpkEntry(
        val opk: LocalOneTimePreKey,
        var status: OpkStatus,
        var offeredAtEpochSeconds: Long? = null,
    )

    private val keys = mutableMapOf<String, OpkEntry>()
    private var counter = 0

    override suspend fun allocate(): LocalOneTimePreKey {
        val keyPair = crypto.generateEncryptionKeyPair()
        val opkId = "opk-test-${++counter}"
        val opk = LocalOneTimePreKey(
            keyId = opkId,
            publicKey = keyPair.publicKey,
            privateKey = keyPair.privateKey,
        )
        keys[opkId] = OpkEntry(opk = opk, status = OpkStatus.ALLOCATED)
        return opk
    }

    override suspend fun markOffered(opkId: String) {
        val entry = keys[opkId] ?: error("Unknown OPK id=$opkId")
        require(entry.status == OpkStatus.ALLOCATED) { "OPK $opkId is not ALLOCATED" }
        entry.status = OpkStatus.OFFERED
        entry.offeredAtEpochSeconds = timeProvider.nowEpochSeconds()
    }

    override suspend fun consume(opkId: String): LocalOneTimePreKey? {
        val entry = keys[opkId] ?: return null
        if (entry.status != OpkStatus.OFFERED) return null
        entry.status = OpkStatus.CONSUMED
        return entry.opk
    }

    override suspend fun loadOffered(opkId: String): LocalOneTimePreKey? {
        val entry = keys[opkId] ?: return null
        return when (entry.status) {
            OpkStatus.ALLOCATED, OpkStatus.OFFERED -> entry.opk
            OpkStatus.CONSUMED -> null
        }
    }

    override suspend fun pruneExpiredOffers(cutoffEpochSeconds: Long): List<String> {
        val expired = keys.filterValues {
            it.status == OpkStatus.OFFERED &&
                it.offeredAtEpochSeconds != null &&
                it.offeredAtEpochSeconds!! < cutoffEpochSeconds
        }.keys.toList()
        for (opkId in expired) {
            keys.remove(opkId)
        }
        return expired
    }

    fun status(opkId: String): OpkStatus? = keys[opkId]?.status
}

/** [OpkRepository] that fails allocation for fail-soft offer tests. */
internal class FailingAllocateOpkRepository : OpkRepository {
    override suspend fun allocate(): LocalOneTimePreKey =
        throw IllegalStateException("OPK pool exhausted")

    override suspend fun markOffered(opkId: String) = Unit

    override suspend fun consume(opkId: String): LocalOneTimePreKey? = null

    override suspend fun loadOffered(opkId: String): LocalOneTimePreKey? = null

    override suspend fun pruneExpiredOffers(cutoffEpochSeconds: Long): List<String> = emptyList()
}
