package org.yapyap.orchestrator.runtime.identity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.*
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.fold.global.GlobalEventProjector
import org.yapyap.orchestrator.fold.global.IdentityStateChange
import org.yapyap.persistence.db.AccountRole
import org.yapyap.persistence.db.IdentityStatus
import org.yapyap.persistence.key.InMemoryIdentityKeyRepository
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.protocol.envelopes.Invite
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.RecoveryRequest
import org.yapyap.routing.router.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private class FakeRouter : Router {
    val onlineAccountsFlow = MutableStateFlow<Set<AccountId>>(emptySet())
    override val onlineAccounts: Flow<Set<AccountId>> = onlineAccountsFlow
    override val incomingMessages: Flow<MessagePayload> = emptyFlow()
    override val typingIndicators: Flow<TypingIndicatorEvent> = emptyFlow()
    override val pingPayloads: Flow<PingFrontiers> = emptyFlow()
    override val bootstrapPackets: Flow<BootstrapPacketEvent> = emptyFlow()
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override fun isRunning(): Boolean = true
    override suspend fun announceOnline() = Unit
    override suspend fun sendMessage(target: AccountId, payload: MessagePayload): AccountPushReport =
        error("not used")

    override suspend fun sendTypingIndicator(
        targets: Collection<AccountId>,
        roomId: RoomId,
        interval: Duration,
    ) = Unit

    override suspend fun sendBootstrap(
        payload: BootstrapPayload,
        target: PeerId,
        targetEndpoint: TorEndpoint?,
        sharedSecret: ByteArray?,
    ) = Unit
}

private class FakeProjector : GlobalEventProjector {
    val stateChangesFlow = MutableSharedFlow<IdentityStateChange>(extraBufferCapacity = 64)
    override val stateChanges: Flow<IdentityStateChange> = stateChangesFlow
    override fun start(scope: CoroutineScope) = Unit
    override suspend fun stop() = Unit
    override suspend fun publishGenesisAccount(
        account: AccountIdentityRecord,
        device: DeviceIdentityRecord,
        deviceType: DeviceType,
        torEndpoint: TorEndpoint,
        accountKeySignature: ByteArray,
    ) = Unit

    override suspend fun publishSponsoredNewAccount(invite: Invite, grantAdmin: Boolean) = Unit
    override suspend fun publishOwnAccountDevice(invite: Invite) = Unit
    override suspend fun publishRelayedDevice(request: RecoveryRequest) = Unit
    override suspend fun publishGrantAdmin(targetAccountId: AccountId) = Unit
    override suspend fun publishRemoveAdmin(targetAccountId: AccountId) = Unit
    override suspend fun publishRemoveAccount(targetAccountId: AccountId, successorAccountId: AccountId?) = Unit
    override suspend fun publishRemoveDevice(targetDeviceId: PeerId) = Unit
    override suspend fun activeDevicesAddedBy(authorDeviceId: PeerId): List<PeerId> = emptyList()
}

class DefaultIdentityServiceTest {

    private val localAccount = AccountId("local-account")
    private val memberAccount = AccountId("member-account")
    private val adminAccount = AccountId("admin-account")
    private val ownerAccount = AccountId("owner-account")
    private val bannedAccount = AccountId("banned-account")
    private val ghostAccount = AccountId("ghost-account")
    private val localDevice = PeerId("local-device")
    private val memberDevice = PeerId("member-device")
    private val memberDevice2 = PeerId("member-device-2")
    private val ownerDevice = PeerId("owner-device")
    private val bannedDevice = PeerId("banned-device")

    private val seenAt = Instant.parse("2026-05-01T12:00:00Z")
    private val earlierAt = Instant.parse("2026-04-01T12:00:00Z")

    private inner class Fixture(
        orchestratorConfig: MutableStateFlow<OrchestratorConfig> = MutableStateFlow(OrchestratorConfig()),
    ) {
        val router = FakeRouter()
        val projector = FakeProjector()
        val repo = InMemoryIdentityKeyRepository()
        val service = DefaultIdentityService(repo, router, projector, orchestratorConfig)

        suspend fun seedLocal(role: AccountRole = AccountRole.MEMBER) {
            repo.insertLocalAccount(accountRecord(localAccount, "Zoe"), role, provisional = false)
            repo.insertLocalDevice(localAccount, dummyDevice(localDevice), provisional = false)
        }

        suspend fun seedAccount(
            account: AccountId,
            displayName: String = account.id,
            role: AccountRole = AccountRole.MEMBER,
            status: IdentityStatus = IdentityStatus.ACTIVE,
        ) {
            repo.insertPeerAccount(
                identity = accountRecord(account, displayName),
                role = role,
                status = status,
                displayName = displayName,
                provisional = false,
            )
        }

        suspend fun seedDevice(
            account: AccountId,
            device: PeerId,
            provisional: Boolean = false,
            lastSeen: Instant? = null,
        ) {
            repo.insertPeerDevice(
                account,
                DeviceType.DESKTOP,
                dummyDevice(device),
                TorEndpoint(onionAddress = "${device.id}.onion", port = 80),
                provisional,
            )
            lastSeen?.let { repo.seedLastSeen(device, it) }
        }

        private fun accountRecord(account: AccountId, displayName: String) = AccountIdentityRecord(
            accountId = account,
            displayName = displayName,
            key = IdentityPublicKeyRecord("k", 0, IdentityKeyPurpose.SIGNING, byteArrayOf(1)),
        )

        private fun dummyDevice(id: PeerId) = DeviceIdentityRecord(
            deviceId = id,
            signing = IdentityPublicKeyRecord("k", 0, IdentityKeyPurpose.SIGNING, byteArrayOf(1)),
            encryption = IdentityPublicKeyRecord("k", 0, IdentityKeyPurpose.ENCRYPTION, byteArrayOf(2)),
        )
    }

    @Test
    fun accounts_emptyPreOnboarding() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture()
        fixture.service.start(backgroundScope)

        assertTrue(fixture.service.accounts.value.isEmpty())
        assertNull(fixture.service.localAccount.value)
        fixture.service.stop()
    }

    @Test
    fun accounts_assemblesRosterSortedByDisplayName() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedAccount(memberAccount, displayName = "Amy")
        fixture.seedDevice(memberAccount, memberDevice, lastSeen = seenAt)
        fixture.seedAccount(adminAccount, displayName = "Mike", role = AccountRole.ADMIN)
        fixture.service.start(backgroundScope)

        val views = fixture.service.accounts.value
        assertEquals(listOf("Amy", "Mike", "Zoe"), views.map { it.displayName })
        assertEquals(
            AccountView(
                accountId = memberAccount,
                displayName = "Amy",
                role = AccountRole.MEMBER,
                status = IdentityStatus.ACTIVE,
                isLocal = false,
                availability = AccountAvailability(AvailabilityLabel.OFFLINE, seenAt),
                devices = listOf(
                    DeviceView(
                        deviceId = memberDevice,
                        deviceType = DeviceType.DESKTOP,
                        status = IdentityStatus.ACTIVE,
                        isLocal = false,
                        provisional = false,
                        lastSeen = seenAt,
                    ),
                ),
            ),
            views.first { it.accountId == memberAccount },
        )
        fixture.service.stop()
    }

    @Test
    fun accounts_includesBannedRows() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedAccount(bannedAccount, displayName = "Boris", status = IdentityStatus.BANNED)
        fixture.seedDevice(bannedAccount, bannedDevice)
        // The fold cascade-bans the account's devices with the account.
        fixture.repo.tombstoneDevice(bannedDevice)
        fixture.service.start(backgroundScope)

        val banned = fixture.service.accounts.value.first { it.accountId == bannedAccount }
        assertEquals(IdentityStatus.BANNED, banned.status)
        assertEquals(IdentityStatus.BANNED, fixture.repo.getDeviceStatus(bannedDevice))
        assertEquals(listOf(bannedDevice), banned.devices.map { it.deviceId })
        assertEquals(IdentityStatus.BANNED, banned.devices.single().status)
        fixture.service.stop()
    }

    @Test
    fun availability_onlineWhenInOnlineSet() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedAccount(memberAccount, displayName = "Amy")
        fixture.seedDevice(memberAccount, memberDevice, lastSeen = seenAt)
        fixture.service.start(backgroundScope)
        assertEquals(AvailabilityLabel.OFFLINE, fixture.service.account(memberAccount)?.availability?.label)

        fixture.router.onlineAccountsFlow.value = setOf(memberAccount)

        assertEquals(AvailabilityLabel.ONLINE, fixture.service.account(memberAccount)?.availability?.label)
        fixture.service.stop()
    }

    @Test
    fun availability_unknownWhenNeverSeen() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedAccount(memberAccount, displayName = "Amy")
        fixture.seedDevice(memberAccount, memberDevice)
        fixture.service.start(backgroundScope)

        val view = fixture.service.account(memberAccount)
        assertEquals(AvailabilityLabel.UNKNOWN, view?.availability?.label)
        assertNull(view?.availability?.lastSeen)
        assertNull(view?.devices?.single()?.lastSeen)
        fixture.service.stop()
    }

    @Test
    fun availability_localAlwaysOnline() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedAccount(memberAccount, displayName = "Amy")
        fixture.seedDevice(memberAccount, memberDevice)
        fixture.service.start(backgroundScope)

        // The router never reports the local account — the service labels it
        // ONLINE unconditionally.
        assertTrue(fixture.router.onlineAccountsFlow.value.isEmpty())
        assertEquals(AvailabilityLabel.ONLINE, fixture.service.localAccount.value?.availability?.label)
        fixture.service.stop()
    }

    @Test
    fun availability_lastSeenIsMaxOverDevices() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedAccount(memberAccount, displayName = "Amy")
        fixture.seedDevice(memberAccount, memberDevice, lastSeen = earlierAt)
        fixture.seedDevice(memberAccount, memberDevice2, lastSeen = seenAt)
        fixture.service.start(backgroundScope)

        val view = fixture.service.account(memberAccount)
        assertEquals(AvailabilityLabel.OFFLINE, view?.availability?.label)
        assertEquals(seenAt, view?.availability?.lastSeen)
        assertEquals(listOf(memberDevice, memberDevice2), view?.devices?.map { it.deviceId })
        fixture.service.stop()
    }

    @Test
    fun devices_provisionalFlagSurfaces() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedAccount(memberAccount, displayName = "Amy")
        fixture.seedDevice(memberAccount, memberDevice, provisional = true)
        fixture.service.start(backgroundScope)

        val view = fixture.service.account(memberAccount)
        assertEquals(true, view?.devices?.single()?.provisional)
        assertEquals(AvailabilityLabel.UNKNOWN, view?.availability?.label)
        fixture.service.stop()
    }

    @Test
    fun refresh_onStateChanges() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.service.start(backgroundScope)
        assertEquals(1, fixture.service.accounts.value.size)

        fixture.seedAccount(memberAccount, displayName = "Amy")
        fixture.seedDevice(memberAccount, memberDevice)
        fixture.projector.stateChangesFlow.emit(IdentityStateChange.AccountAdded(memberAccount))

        assertEquals(2, fixture.service.accounts.value.size)
        assertEquals(
            listOf(memberAccount),
            fixture.service.accounts.value.filter { !it.isLocal }.map { it.accountId },
        )
        fixture.service.stop()
    }

    @Test
    fun refresh_onTickerInterval() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture(
            orchestratorConfig = MutableStateFlow(OrchestratorConfig(identityRefreshInterval = 5.seconds)),
        )
        fixture.seedLocal()
        fixture.service.start(backgroundScope)
        assertEquals(1, fixture.service.accounts.value.size)

        // No stateChanges emission and no presence flip — the ticker alone
        // picks up the new row, proving the configured interval drives it.
        fixture.seedAccount(memberAccount, displayName = "Amy")
        advanceTimeBy(6.seconds)

        assertEquals(2, fixture.service.accounts.value.size)
        fixture.service.stop()
    }

    @Test
    fun account_unknownReturnsNull() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.service.start(backgroundScope)

        assertNull(fixture.service.account(ghostAccount))
        fixture.service.stop()
    }

    @Test
    fun account_resolvesBannedAuthor() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedAccount(ownerAccount, displayName = "Otto", role = AccountRole.OWNER)
        fixture.seedDevice(ownerAccount, ownerDevice, lastSeen = seenAt)
        fixture.seedAccount(bannedAccount, displayName = "Boris", status = IdentityStatus.BANNED)
        fixture.seedDevice(bannedAccount, bannedDevice)
        fixture.service.start(backgroundScope)

        // Message rendering needs banned authors resolvable — tombstones keep rows.
        val banned = fixture.service.account(bannedAccount)
        assertEquals(IdentityStatus.BANNED, banned?.status)
        assertEquals("Boris", banned?.displayName)
        val owner = fixture.service.account(ownerAccount)
        assertEquals(AccountRole.OWNER, owner?.role)
        fixture.service.stop()
    }
}
