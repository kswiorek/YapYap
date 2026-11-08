package org.yapyap.testfixtures

import org.yapyap.crypto.identity.DefaultLocalIdentity
import org.yapyap.crypto.identity.DefaultLocalKeys
import org.yapyap.crypto.identity.DefaultPeerKeys
import org.yapyap.crypto.identity.LocalIdentity
import org.yapyap.crypto.identity.LocalKeys
import org.yapyap.crypto.identity.LocalOneTimePreKey
import org.yapyap.crypto.identity.PeerKeys
import org.yapyap.crypto.keys.KeySlot
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.store.KeyStore
import org.yapyap.crypto.store.OpkRepository
import org.yapyap.crypto.store.OpkStatus
import org.yapyap.orchestrator.identity.Account
import org.yapyap.orchestrator.identity.AccountDevice
import org.yapyap.orchestrator.identity.IdentityStore
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.AccountRole
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.identity.AccountIdentity
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.OneTimePreKeyId
import org.yapyap.protocol.identity.SignedPreKey
import org.yapyap.protocol.identity.SignedPreKeyId
import org.yapyap.protocol.identity.SigningPublicKey
import kotlin.time.Clock
import kotlin.time.Instant

/** In-memory [KeyStore] for unit tests. */
internal class InMemoryKeyStore : KeyStore {
    private val keys = mutableMapOf<KeySlot, ByteArray>()

    override suspend fun putKey(slot: KeySlot, key: ByteArray) {
        keys[slot] = key.copyOf()
    }

    override suspend fun getKey(slot: KeySlot): ByteArray? =
        keys[slot]?.copyOf()

    override suspend fun deleteKey(slot: KeySlot) {
        keys.remove(slot)
    }

    override suspend fun deleteAll() {
        keys.clear()
    }
}

/**
 * Minimal [IdentityStore] backing store for [org.yapyap.crypto.identity.DefaultIdentityResolver] /
 * [org.yapyap.crypto.identity.DefaultIdentityProvisioning] contract tests.
 */
internal class InMemoryIdentityStore(
    private val defaultLocalTor: TorEndpoint = TorEndpoint(onionAddress = "unknown.onion", port = 80),
    private val defaultLocalDeviceType: DeviceType = DeviceType.DESKTOP,
) : IdentityStore {

    val accounts = mutableMapOf<String, AccountIdentity>()
    val devices = mutableMapOf<String, DeviceIdentity>()
    val identityStatuses = mutableMapOf<String, IdentityStatus>()
    val accountRoles = mutableMapOf<String, AccountRole>()
    val deviceStatuses = mutableMapOf<String, IdentityStatus>()
    val provisionalDevices = mutableSetOf<String>()
    val provisionalAccounts = mutableSetOf<String>()
    var localDevice: DeviceIdentity? = null
    var localAccount: AccountIdentity? = null
    var localRole: AccountRole = AccountRole.MEMBER
    private val signedPreKeys = mutableMapOf<SignedPreKeyId, SignedPreKey>()
    private val activeSignedPreKeyByDevice = mutableMapOf<String, SignedPreKeyId>()
    private val deviceToAccount = mutableMapOf<String, String>()
    private val peersForAccount = mutableMapOf<String, MutableSet<String>>()
    private val torForDevice = mutableMapOf<String, TorEndpoint>()
    private val deviceTypes = mutableMapOf<String, DeviceType>()

    override suspend fun getAccountIdentity(accountId: AccountId): AccountIdentity? =
        accounts[accountId.id]

    override suspend fun getAccountStatus(accountId: AccountId): IdentityStatus? =
        identityStatuses[accountId.id]

    override suspend fun getDeviceStatus(deviceId: PeerId): IdentityStatus? =
        deviceStatuses[deviceId.id]

    override suspend fun upsertChainAccount(
        accountId: AccountId,
        accountSigningPublicKey: SigningPublicKey?,
        role: AccountRole,
        status: IdentityStatus,
        displayName: String,
    ) {
        val key = accountSigningPublicKey ?: accounts[accountId.id]?.signingKey
        accounts[accountId.id] = AccountIdentity(accountId, displayName, key)
        identityStatuses[accountId.id] = status
        accountRoles[accountId.id] = role
        provisionalAccounts.remove(accountId.id)
        if (localAccount?.accountId == accountId) localRole = role
    }

    override suspend fun upsertChainDevice(
        deviceId: PeerId,
        accountId: AccountId,
        deviceType: DeviceType,
        torEndpoint: TorEndpoint,
        signingPublicKey: SigningPublicKey,
        encryptionPublicKey: EncryptionPublicKey,
        status: IdentityStatus,
    ) {
        val existing = devices[deviceId.id]
        val confirmed = existing != null && deviceId.id !in provisionalDevices
        if (!confirmed) torForDevice[deviceId.id] = torEndpoint
        val merged = DeviceIdentity(
            deviceId = deviceId,
            signingKey = signingPublicKey,
            encryptionKey = encryptionPublicKey,
            signedPreKey = existing?.signedPreKey,
        )
        devices[deviceId.id] = merged
        deviceStatuses[deviceId.id] = status
        deviceTypes[deviceId.id] = deviceType
        provisionalDevices.remove(deviceId.id)
        deviceToAccount[deviceId.id] = accountId.id
        peersForAccount.getOrPut(accountId.id) { mutableSetOf() }.add(deviceId.id)
        if (localDevice?.deviceId == deviceId) {
            localDevice = merged
            rebindLocalAccount(accountId)
        }
    }

    override suspend fun rebindLocalAccountToLocalDevice(): AccountId? {
        val device = localDevice ?: return null
        if (device.deviceId.id in provisionalDevices) return null
        val accountId = deviceToAccount[device.deviceId.id]?.let { AccountId(it) } ?: return null
        return rebindLocalAccount(accountId)
    }

    /** Mirrors the SQLDelight rebind: the confirmed local device's account becomes local; the stand-in goes. */
    private fun rebindLocalAccount(accountId: AccountId): AccountId? {
        val previous = localAccount?.accountId
        if (previous == accountId) return null
        val record = accounts[accountId.id] ?: return null
        localAccount = record
        localRole = accountRoles[accountId.id] ?: AccountRole.MEMBER
        if (previous != null && deviceToAccount.values.none { it == previous.id }) {
            accounts.remove(previous.id)
            accountRoles.remove(previous.id)
            identityStatuses.remove(previous.id)
            provisionalAccounts.remove(previous.id)
        }
        return previous
    }

    override suspend fun tombstoneAccount(accountId: AccountId) {
        if (!accounts.containsKey(accountId.id)) return
        identityStatuses[accountId.id] = IdentityStatus.BANNED
        accountRoles[accountId.id] = AccountRole.MEMBER
        if (localAccount?.accountId == accountId) localRole = AccountRole.MEMBER
        provisionalAccounts.remove(accountId.id)
    }

    override suspend fun tombstoneDevice(deviceId: PeerId) {
        if (!devices.containsKey(deviceId.id)) return
        deviceStatuses[deviceId.id] = IdentityStatus.BANNED
        provisionalDevices.remove(deviceId.id)
    }

    override suspend fun getDeviceIdentity(deviceId: PeerId): DeviceIdentity? =
        devices[deviceId.id]?.let { device ->
            val activeSpkId = activeSignedPreKeyByDevice[deviceId.id]
            val activeSpk = activeSpkId?.let { signedPreKeys[it] } ?: device.signedPreKey
            if (activeSpk == device.signedPreKey) device else device.copy(signedPreKey = activeSpk)
        }

    override suspend fun insertLocalDevice(
        accountId: AccountId,
        identity: DeviceIdentity,
        provisional: Boolean,
    ) {
        devices[identity.deviceId.id] = identity
        deviceStatuses[identity.deviceId.id] = IdentityStatus.ACTIVE
        deviceTypes[identity.deviceId.id] = defaultLocalDeviceType
        if (provisional) provisionalDevices.add(identity.deviceId.id) else provisionalDevices.remove(identity.deviceId.id)
        localDevice = identity
        deviceToAccount[identity.deviceId.id] = accountId.id
        peersForAccount.getOrPut(accountId.id) { mutableSetOf() }.add(identity.deviceId.id)
        torForDevice[identity.deviceId.id] = defaultLocalTor
        identity.signedPreKey?.let { spk ->
            persistSignedPreKey(spk)
        }
    }

    override suspend fun getLocalDeviceIdentity(): DeviceIdentity? = localDevice
    override suspend fun getLocalAccountIdentity(): AccountIdentity? = localAccount

    fun clearLocalDeviceRecord() {
        localDevice = null
    }

    override suspend fun insertPeerDevice(
        accountId: AccountId,
        deviceType: DeviceType,
        identity: DeviceIdentity,
        torEndpoint: TorEndpoint,
        provisional: Boolean,
    ) {
        devices[identity.deviceId.id] = identity
        deviceStatuses[identity.deviceId.id] = IdentityStatus.ACTIVE
        deviceTypes[identity.deviceId.id] = deviceType
        if (provisional) provisionalDevices.add(identity.deviceId.id) else provisionalDevices.remove(identity.deviceId.id)
        deviceToAccount[identity.deviceId.id] = accountId.id
        peersForAccount.getOrPut(accountId.id) { mutableSetOf() }.add(identity.deviceId.id)
        torForDevice[identity.deviceId.id] = torEndpoint
        identity.signedPreKey?.let { spk ->
            persistSignedPreKey(spk)
        }
    }

    override suspend fun insertLocalAccount(
        identity: AccountIdentity,
        role: AccountRole,
        provisional: Boolean,
    ) {
        localAccount = identity
        localRole = role
        accounts[identity.accountId.id] = identity
        accountRoles[identity.accountId.id] = role
        // Mirrors the SQLDelight putAccount, which hardcodes ACTIVE.
        identityStatuses[identity.accountId.id] = IdentityStatus.ACTIVE
        if (provisional) provisionalAccounts.add(identity.accountId.id) else provisionalAccounts.remove(identity.accountId.id)
    }

    override suspend fun isLocalAccountAdmin(): Boolean = localRole.isAdmin

    override suspend fun isLocalAccountOwner(): Boolean = localRole == AccountRole.OWNER

    override suspend fun isAccountAdmin(accountId: AccountId): Boolean =
        accountRoles[accountId.id]?.isAdmin ?: false

    override suspend fun isAccountOwner(accountId: AccountId): Boolean =
        accountRoles[accountId.id] == AccountRole.OWNER

    override suspend fun getOwnerAccountId(): AccountId? =
        accountRoles.entries.firstOrNull { it.value == AccountRole.OWNER }?.let { AccountId(it.key) }

    override suspend fun getDeclaredTorEndpoint(deviceId: PeerId): TorEndpoint? = torForDevice[deviceId.id]

    override suspend fun insertPeerAccount(
        identity: AccountIdentity,
        role: AccountRole,
        status: IdentityStatus,
        displayName: String,
        provisional: Boolean,
    ) {
        accounts[identity.accountId.id] = identity
        identityStatuses[identity.accountId.id] = status
        accountRoles[identity.accountId.id] = role
        if (provisional) provisionalAccounts.add(identity.accountId.id) else provisionalAccounts.remove(identity.accountId.id)
    }

    override suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> =
        peersForAccount[accountId.id]?.map { PeerId(it) }?.sortedBy { it.id }.orEmpty()

    override suspend fun getAllPeerDevicesForAccounts(accountIds: Collection<AccountId>): List<PeerId> =
        accountIds.flatMap { getAllPeerDevicesForAccount(it) }.distinct()

    override suspend fun getAllDeviceIds(): List<PeerId> =
        devices.keys.map { PeerId(it) }.sortedBy { it.id }

    override suspend fun getAllActiveDeviceIds(): List<PeerId> =
        getAllDeviceIds().filter { getDeviceStatus(it) != IdentityStatus.BANNED }

    override suspend fun getAllAccounts(): List<Account> =
        accounts.keys.map { id ->
            Account(
                identity = accounts.getValue(id),
                role = accountRoles[id] ?: AccountRole.MEMBER,
                status = identityStatuses[id] ?: IdentityStatus.ACTIVE,
                isLocal = localAccount?.accountId?.id == id,
                devices = devices.keys.filter { deviceToAccount[it] == id }.map { deviceId ->
                    AccountDevice(
                        identity = devices.getValue(deviceId),
                        declaredEndpoint = torForDevice[deviceId] ?: defaultLocalTor,
                        deviceType = deviceTypes[deviceId] ?: defaultLocalDeviceType,
                        status = deviceStatuses[deviceId] ?: IdentityStatus.ACTIVE,
                        isLocal = localDevice?.deviceId?.id == deviceId,
                        provisional = provisionalDevices.contains(deviceId),
                    )
                },
            )
        }

    override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? =
        deviceToAccount[deviceId.id]?.let { AccountId(it) }

    override suspend fun getSignedPreKey(id: SignedPreKeyId): SignedPreKey? = signedPreKeys[id]

    override suspend fun getSignedPreKeyIds(deviceId: PeerId): List<SignedPreKeyId> =
        signedPreKeys.values.filter { it.deviceId == deviceId }.map { it.id }.sortedBy { it.value }

    override suspend fun getActiveSignedPreKeyForDevice(deviceId: PeerId): SignedPreKey? =
        activeSignedPreKeyByDevice[deviceId.id]?.let { signedPreKeys[it]!! }

    override suspend fun insertSignedPreKey(spk: SignedPreKey, createdAt: Instant) {
        signedPreKeys[spk.id] = spk
        activeSignedPreKeyByDevice[spk.deviceId.id] = spk.id
    }

    override suspend fun upsertDeviceSignedPreKey(spk: SignedPreKey) {
        val existing = devices[spk.deviceId.id] ?: error("Device not found: ${spk.deviceId}")
        devices[spk.deviceId.id] = existing.copy(signedPreKey = spk)
        persistSignedPreKey(spk)
    }

    private fun persistSignedPreKey(signedPreKey: SignedPreKey) {
        signedPreKeys[signedPreKey.id] = signedPreKey
        activeSignedPreKeyByDevice[signedPreKey.deviceId.id] = signedPreKey.id
    }

    /** Seeds Tor for a device already stored (e.g. after [insertLocalDevice]). */
    fun seedTorEndpoint(deviceId: PeerId, endpoint: TorEndpoint) {
        torForDevice[deviceId.id] = endpoint
    }


    override suspend fun seedProvisionalPeerDevice(
        accountId: AccountId,
        deviceType: DeviceType,
        identity: DeviceIdentity,
        torEndpoint: TorEndpoint,
    ) {
        if (devices.containsKey(identity.deviceId.id)) return
        insertPeerDevice(accountId, deviceType, identity, torEndpoint)
        provisionalDevices.add(identity.deviceId.id)
    }

    override suspend fun seedProvisionalPeerAccount(
        identity: AccountIdentity,
        role: AccountRole,
        displayName: String,
    ) {
        if (accounts.containsKey(identity.accountId.id)) return
        insertPeerAccount(identity, role, IdentityStatus.ACTIVE, displayName, provisional = true)
        provisionalAccounts.add(identity.accountId.id)
    }

    override suspend fun isDeviceProvisional(deviceId: PeerId): Boolean =
        !devices.containsKey(deviceId.id) || provisionalDevices.contains(deviceId.id)
}

/** In-memory [OpkRepository] for unit tests. */
internal class InMemoryOpkRepository(
    private val crypto: CryptoProvider,
    private val clock: Clock = Clock.System,
) : OpkRepository {
    private data class OpkEntry(
        val opk: LocalOneTimePreKey,
        var status: OpkStatus,
        var offeredAt: Instant? = null,
    )

    private val keys = mutableMapOf<OneTimePreKeyId, OpkEntry>()
    private var counter = 0

    override suspend fun allocate(): LocalOneTimePreKey {
        val keyPair = crypto.generateEncryptionKeyPair()
        val opkId = OneTimePreKeyId("opk-test-${++counter}")
        val opk = LocalOneTimePreKey(
            id = opkId,
            publicKey = keyPair.publicKey,
            privateKey = keyPair.privateKey,
        )
        keys[opkId] = OpkEntry(opk = opk, status = OpkStatus.ALLOCATED)
        return opk
    }

    override suspend fun markOffered(opkId: OneTimePreKeyId) {
        val entry = keys[opkId] ?: error("Unknown OPK id=$opkId")
        require(entry.status == OpkStatus.ALLOCATED) { "OPK $opkId is not ALLOCATED" }
        entry.status = OpkStatus.OFFERED
        entry.offeredAt = clock.now()
    }

    override suspend fun consume(opkId: OneTimePreKeyId): LocalOneTimePreKey? {
        val entry = keys[opkId] ?: return null
        if (entry.status != OpkStatus.OFFERED) return null
        entry.status = OpkStatus.CONSUMED
        return entry.opk
    }

    override suspend fun loadOffered(opkId: OneTimePreKeyId): LocalOneTimePreKey? {
        val entry = keys[opkId] ?: return null
        return when (entry.status) {
            OpkStatus.ALLOCATED, OpkStatus.OFFERED -> entry.opk
            OpkStatus.CONSUMED -> null
        }
    }

    override suspend fun pruneExpiredOffers(cutoff: Instant): List<OneTimePreKeyId> {
        val expired = keys.filterValues {
            it.status == OpkStatus.OFFERED &&
                    it.offeredAt != null &&
                    it.offeredAt!! < cutoff
        }.keys.toList()
        for (opkId in expired) {
            keys.remove(opkId)
        }
        return expired
    }

    override suspend fun opkIds(): List<OneTimePreKeyId> = keys.keys.sortedBy { it.value }

    fun status(opkId: OneTimePreKeyId): OpkStatus? = keys[opkId]?.status
}

/** [OpkRepository] that fails allocation for fail-soft offer tests. */
internal class FailingAllocateOpkRepository : OpkRepository {
    override suspend fun allocate(): LocalOneTimePreKey =
        throw IllegalStateException("OPK pool exhausted")

    override suspend fun markOffered(opkId: OneTimePreKeyId) = Unit

    override suspend fun consume(opkId: OneTimePreKeyId): LocalOneTimePreKey? = null

    override suspend fun loadOffered(opkId: OneTimePreKeyId): LocalOneTimePreKey? = null

    override suspend fun pruneExpiredOffers(cutoff: Instant): List<OneTimePreKeyId> = emptyList()

    override suspend fun opkIds(): List<OneTimePreKeyId> = emptyList()
}

/** The crypto identity services as production wires them, over one store — for tests that exercise them together. */
internal class TestIdentityStack private constructor(
    localIdentity: LocalIdentity,
    localKeys: LocalKeys,
    peerKeys: PeerKeys,
) : LocalIdentity by localIdentity, LocalKeys by localKeys, PeerKeys by peerKeys {
    companion object {
        operator fun invoke(crypto: CryptoProvider, store: IdentityStore, keyStore: KeyStore): TestIdentityStack {
            val localIdentity = DefaultLocalIdentity(crypto, store, keyStore)
            return TestIdentityStack(
                localIdentity,
                DefaultLocalKeys(localIdentity, store, keyStore),
                DefaultPeerKeys(crypto, store),
            )
        }
    }
}
