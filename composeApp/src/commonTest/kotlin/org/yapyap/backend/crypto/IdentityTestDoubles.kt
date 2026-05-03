package org.yapyap.backend.crypto

import org.yapyap.backend.db.AccountStatus
import org.yapyap.backend.db.DeviceType
import org.yapyap.backend.db.IdentityPublicKeyRepository
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint

/** In-memory [PrivateKeyStore] for unit tests. */
internal class InMemoryPrivateKeyStore : PrivateKeyStore {
    private val keys = mutableMapOf<KeyReference, ByteArray>()

    override fun putKey(ref: KeyReference, key: ByteArray) {
        keys[ref] = key.copyOf()
    }

    override fun getKey(ref: KeyReference): ByteArray? =
        keys[ref]?.copyOf()
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
    private val deviceToAccount = mutableMapOf<String, String>()
    private val peersForAccount = mutableMapOf<String, MutableSet<String>>()
    private val torForDevice = mutableMapOf<String, TorEndpoint>()

    override fun getAccountPublicKey(accountId: AccountId): AccountIdentityRecord? =
        accounts[accountId.id]

    override fun getDevicePublicKey(deviceId: PeerId): DeviceIdentityRecord? =
        devices[deviceId.id]

    override fun insertLocalDevice(accountId: AccountId, identity: DeviceIdentityRecord) {
        devices[identity.deviceId.id] = identity
        deviceToAccount[identity.deviceId.id] = accountId.id
        peersForAccount.getOrPut(accountId.id) { mutableSetOf() }.add(identity.deviceId.id)
        torForDevice[identity.deviceId.id] = defaultLocalTor
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
    }

    override fun insertLocalAccount(displayName: String, identity: AccountIdentityRecord) {
        accounts[identity.accountId.id] = identity
    }

    override fun resolveDeviceKey(deviceId: PeerId, purpose: IdentityKeyPurpose): IdentityPublicKeyRecord? {
        val d = devices[deviceId.id] ?: return null
        return when (purpose) {
            IdentityKeyPurpose.SIGNING -> d.signing
            IdentityKeyPurpose.ENCRYPTION -> d.encryption
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

    /** Seeds Tor for a device already stored (e.g. after [insertLocalDevice]). */
    fun seedTorEndpoint(deviceId: PeerId, endpoint: TorEndpoint) {
        torForDevice[deviceId.id] = endpoint
    }
}
