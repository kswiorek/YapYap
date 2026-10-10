package org.yapyap.orchestrator.runtime.admin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.*
import org.yapyap.orchestrator.dag.DagException
import org.yapyap.orchestrator.fold.global.GlobalEventProjector
import org.yapyap.orchestrator.fold.global.IdentityStateChange
import org.yapyap.orchestrator.onboarding.OnboardingState
import org.yapyap.orchestrator.runtime.globalevent.GlobalEventOutcome
import org.yapyap.orchestrator.runtime.globalevent.GlobalEventRefusal
import org.yapyap.persistence.db.AccountRole
import org.yapyap.persistence.db.IdentityStatus
import org.yapyap.persistence.key.InMemoryIdentityKeyRepository
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.Invite
import org.yapyap.protocol.envelopes.RecoveryRequest
import org.yapyap.testfixtures.FakeIdentityResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RecordingProjector : GlobalEventProjector {
    val stateChangesFlow = MutableSharedFlow<IdentityStateChange>(extraBufferCapacity = 64)
    override val stateChanges: Flow<IdentityStateChange> = stateChangesFlow
    val grantedAdmins = mutableListOf<AccountId>()
    val revokedAdmins = mutableListOf<AccountId>()
    val removedAccounts = mutableListOf<AccountId>()
    val removedDevices = mutableListOf<PeerId>()
    var frontierUnavailable = false

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
    override suspend fun publishGrantAdmin(targetAccountId: AccountId) {
        if (frontierUnavailable) throw DagException.FrontierUnavailable(RoomId.GLOBAL)
        grantedAdmins += targetAccountId
    }

    override suspend fun publishRemoveAdmin(targetAccountId: AccountId) {
        if (frontierUnavailable) throw DagException.FrontierUnavailable(RoomId.GLOBAL)
        revokedAdmins += targetAccountId
    }

    override suspend fun publishRemoveAccount(targetAccountId: AccountId, successorAccountId: AccountId?) {
        if (frontierUnavailable) throw DagException.FrontierUnavailable(RoomId.GLOBAL)
        removedAccounts += targetAccountId
    }

    override suspend fun publishRemoveDevice(targetDeviceId: PeerId) {
        if (frontierUnavailable) throw DagException.FrontierUnavailable(RoomId.GLOBAL)
        removedDevices += targetDeviceId
    }

    override suspend fun activeDevicesAddedBy(authorDeviceId: PeerId): List<PeerId> = emptyList()
}

class DefaultAdminServiceTest {

    private val localAccount = AccountId("local-account")
    private val memberAccount = AccountId("member-account")
    private val adminAccount = AccountId("admin-account")
    private val ownerAccount = AccountId("owner-account")
    private val bannedAccount = AccountId("banned-account")
    private val ghostAccount = AccountId("ghost-account")
    private val localDevice = PeerId("local-device")
    private val siblingDevice = PeerId("sibling-device")
    private val memberDevice = PeerId("member-device")
    private val ownerDevice = PeerId("owner-device")
    private val unknownDevice = PeerId("unknown-device")

    private inner class Fixture(private val localAdmin: Boolean = true) {
        val projector = RecordingProjector()
        val resolver = FakeIdentityResolver(
            localAccountId = localAccount,
            localDeviceId = localDevice,
            accountByDevice = mapOf(
                localDevice to localAccount,
                siblingDevice to localAccount,
                memberDevice to memberAccount,
                ownerDevice to ownerAccount,
            ),
            localAdmin = localAdmin,
        )
        val repo = InMemoryIdentityKeyRepository()
        val onboardingState = MutableStateFlow(OnboardingState.IDLE)
        val service = DefaultAdminService(projector, resolver, repo, onboardingState)

        suspend fun seedLocal(provisional: Boolean = false, admin: Boolean = localAdmin) {
            repo.insertLocalAccount(
                accountRecord(localAccount),
                role = if (admin) AccountRole.ADMIN else AccountRole.MEMBER,
                provisional = provisional,
            )
            repo.insertLocalDevice(localAccount, dummyDevice(localDevice), provisional)
        }

        suspend fun seedAccount(
            account: AccountId,
            role: AccountRole = AccountRole.MEMBER,
            status: IdentityStatus = IdentityStatus.ACTIVE,
        ) {
            repo.insertPeerAccount(
                identity = accountRecord(account),
                role = role,
                status = status,
                displayName = account.id,
                provisional = false,
            )
        }

        suspend fun seedDevice(account: AccountId, device: PeerId, provisional: Boolean = false) {
            repo.insertPeerDevice(
                account,
                DeviceType.DESKTOP,
                dummyDevice(device),
                TorEndpoint(onionAddress = "${device.id}.onion", port = 80),
                provisional,
            )
        }

        suspend fun seedMember() {
            seedAccount(memberAccount)
            seedDevice(memberAccount, memberDevice)
        }

        suspend fun seedAdmin() = seedAccount(adminAccount, role = AccountRole.ADMIN)

        suspend fun seedOwner() {
            seedAccount(ownerAccount, role = AccountRole.OWNER)
            seedDevice(ownerAccount, ownerDevice)
        }

        suspend fun seedBanned() = seedAccount(bannedAccount, status = IdentityStatus.BANNED)

        suspend fun promoteLocalToAdmin() {
            repo.insertLocalAccount(accountRecord(localAccount), role = AccountRole.ADMIN, provisional = false)
        }

        private fun accountRecord(account: AccountId) = AccountIdentityRecord(
            accountId = account,
            displayName = account.id,
            key = IdentityPublicKeyRecord("k", 0, IdentityKeyPurpose.SIGNING, byteArrayOf(1)),
        )

        private fun dummyDevice(id: PeerId) = DeviceIdentityRecord(
            deviceId = id,
            signing = IdentityPublicKeyRecord("k", 0, IdentityKeyPurpose.SIGNING, byteArrayOf(1)),
            encryption = IdentityPublicKeyRecord("k", 0, IdentityKeyPurpose.ENCRYPTION, byteArrayOf(2)),
        )
    }

    @Test
    fun grantAdmin_member_publishes() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedMember()

        val outcome = fixture.service.grantAdmin(memberAccount)

        assertEquals(GlobalEventOutcome.Published, outcome)
        assertEquals(listOf(memberAccount), fixture.projector.grantedAdmins)
    }

    @Test
    fun grantAdmin_notAdmin_refusesNotAdmin() = runTest {
        val fixture = Fixture(localAdmin = false)
        fixture.seedLocal(admin = false)
        fixture.seedMember()

        val outcome = fixture.service.grantAdmin(memberAccount)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.NotAdmin), outcome)
        assertTrue(fixture.projector.grantedAdmins.isEmpty())
    }

    @Test
    fun grantAdmin_unknownAccount_refusesTargetNotFound() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()

        val outcome = fixture.service.grantAdmin(ghostAccount)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.TargetNotFound), outcome)
        assertTrue(fixture.projector.grantedAdmins.isEmpty())
    }

    @Test
    fun grantAdmin_bannedAccount_refusesTargetNotEligible() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedBanned()

        val outcome = fixture.service.grantAdmin(bannedAccount)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.TargetNotEligible), outcome)
        assertTrue(fixture.projector.grantedAdmins.isEmpty())
    }

    @Test
    fun grantAdmin_alreadyAdmin_refusesTargetNotEligible() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedAdmin()

        val outcome = fixture.service.grantAdmin(adminAccount)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.TargetNotEligible), outcome)
        assertTrue(fixture.projector.grantedAdmins.isEmpty())
    }

    @Test
    fun grantAdmin_owner_refusesTargetNotEligible() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedOwner()

        val outcome = fixture.service.grantAdmin(ownerAccount)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.TargetNotEligible), outcome)
        assertTrue(fixture.projector.grantedAdmins.isEmpty())
    }

    @Test
    fun grantAdmin_whileSyncing_refusesOnboardingActive() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedMember()
        fixture.onboardingState.value = OnboardingState.SYNCING

        val outcome = fixture.service.grantAdmin(memberAccount)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.OnboardingActive), outcome)
        assertTrue(fixture.projector.grantedAdmins.isEmpty())
    }

    @Test
    fun grantAdmin_provisionalLocal_refusesNotReady() = runTest {
        val fixture = Fixture()
        fixture.seedLocal(provisional = true)
        fixture.seedMember()

        val outcome = fixture.service.grantAdmin(memberAccount)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.NotReady), outcome)
        assertTrue(fixture.projector.grantedAdmins.isEmpty())
    }

    @Test
    fun grantAdmin_frontierUnavailable_refusesNotReady() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedMember()
        fixture.projector.frontierUnavailable = true

        val outcome = fixture.service.grantAdmin(memberAccount)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.NotReady), outcome)
        assertTrue(fixture.projector.grantedAdmins.isEmpty())
    }

    @Test
    fun revokeAdmin_admin_publishes() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedAdmin()

        val outcome = fixture.service.revokeAdmin(adminAccount)

        assertEquals(GlobalEventOutcome.Published, outcome)
        assertEquals(listOf(adminAccount), fixture.projector.revokedAdmins)
    }

    @Test
    fun revokeAdmin_selfStepDown_publishes() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()

        val outcome = fixture.service.revokeAdmin(localAccount)

        assertEquals(GlobalEventOutcome.Published, outcome)
        assertEquals(listOf(localAccount), fixture.projector.revokedAdmins)
    }

    @Test
    fun revokeAdmin_owner_refusesOwnerIrrevocable() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedOwner()

        val outcome = fixture.service.revokeAdmin(ownerAccount)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.OwnerIrrevocable), outcome)
        assertTrue(fixture.projector.revokedAdmins.isEmpty())
    }

    @Test
    fun revokeAdmin_member_refusesTargetNotEligible() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedMember()

        val outcome = fixture.service.revokeAdmin(memberAccount)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.TargetNotEligible), outcome)
        assertTrue(fixture.projector.revokedAdmins.isEmpty())
    }

    @Test
    fun revokeAdmin_banned_refusesTargetNotEligible() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedBanned()

        val outcome = fixture.service.revokeAdmin(bannedAccount)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.TargetNotEligible), outcome)
        assertTrue(fixture.projector.revokedAdmins.isEmpty())
    }

    @Test
    fun revokeAdmin_unknown_refusesTargetNotFound() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()

        val outcome = fixture.service.revokeAdmin(ghostAccount)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.TargetNotFound), outcome)
        assertTrue(fixture.projector.revokedAdmins.isEmpty())
    }

    @Test
    fun removeAccount_member_publishes() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedMember()

        val outcome = fixture.service.removeAccount(memberAccount)

        assertEquals(GlobalEventOutcome.Published, outcome)
        assertEquals(listOf(memberAccount), fixture.projector.removedAccounts)
    }

    @Test
    fun removeAccount_local_refusesSelfServiceRequired() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedMember()

        val outcome = fixture.service.removeAccount(localAccount)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.SelfServiceRequired), outcome)
        assertTrue(fixture.projector.removedAccounts.isEmpty())
    }

    @Test
    fun removeAccount_owner_refusesOwnerIrrevocable() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedOwner()

        val outcome = fixture.service.removeAccount(ownerAccount)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.OwnerIrrevocable), outcome)
        assertTrue(fixture.projector.removedAccounts.isEmpty())
    }

    @Test
    fun removeAccount_banned_refusesTargetNotEligible() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedBanned()

        val outcome = fixture.service.removeAccount(bannedAccount)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.TargetNotEligible), outcome)
        assertTrue(fixture.projector.removedAccounts.isEmpty())
    }

    @Test
    fun removeAccount_unknown_refusesTargetNotFound() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()

        val outcome = fixture.service.removeAccount(ghostAccount)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.TargetNotFound), outcome)
        assertTrue(fixture.projector.removedAccounts.isEmpty())
    }

    @Test
    fun removeDevice_memberDevice_publishes() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedMember()

        val outcome = fixture.service.removeDevice(memberDevice)

        assertEquals(GlobalEventOutcome.Published, outcome)
        assertEquals(listOf(memberDevice), fixture.projector.removedDevices)
    }

    @Test
    fun removeDevice_ownSiblingDevice_refusesSelfServiceRequired() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedDevice(localAccount, siblingDevice)

        val outcome = fixture.service.removeDevice(siblingDevice)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.SelfServiceRequired), outcome)
        assertTrue(fixture.projector.removedDevices.isEmpty())
    }

    @Test
    fun removeDevice_localDevice_refusesSelfServiceRequired() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()

        val outcome = fixture.service.removeDevice(localDevice)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.SelfServiceRequired), outcome)
        assertTrue(fixture.projector.removedDevices.isEmpty())
    }

    @Test
    fun removeDevice_ownerDevice_refusesOwnerIrrevocable() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedOwner()

        val outcome = fixture.service.removeDevice(ownerDevice)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.OwnerIrrevocable), outcome)
        assertTrue(fixture.projector.removedDevices.isEmpty())
    }

    @Test
    fun removeDevice_unknown_refusesTargetNotFound() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()

        val outcome = fixture.service.removeDevice(unknownDevice)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.TargetNotFound), outcome)
        assertTrue(fixture.projector.removedDevices.isEmpty())
    }

    @Test
    fun removeDevice_provisionalTarget_refusesNotReady() = runTest {
        val fixture = Fixture()
        fixture.seedLocal()
        fixture.seedAccount(memberAccount)
        fixture.seedDevice(memberAccount, memberDevice, provisional = true)

        val outcome = fixture.service.removeDevice(memberDevice)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.NotReady), outcome)
        assertTrue(fixture.projector.removedDevices.isEmpty())
    }

    @Test
    fun removeDevice_notAdmin_refusesNotAdmin() = runTest {
        val fixture = Fixture(localAdmin = false)
        fixture.seedLocal(admin = false)
        fixture.seedMember()

        val outcome = fixture.service.removeDevice(memberDevice)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.NotAdmin), outcome)
        assertTrue(fixture.projector.removedDevices.isEmpty())
    }

    @Test
    fun localIsAdmin_reflectsFoldStateChanges() = runTest(UnconfinedTestDispatcher()) {
        val fixture = Fixture(localAdmin = false)
        fixture.seedLocal(admin = false)
        fixture.service.start(backgroundScope)
        advanceUntilIdle()
        assertEquals(false, fixture.service.localIsAdmin.value)

        fixture.promoteLocalToAdmin()
        fixture.projector.stateChangesFlow.emit(IdentityStateChange.AdminGranted(localAccount))
        advanceUntilIdle()

        assertEquals(true, fixture.service.localIsAdmin.value)
        fixture.service.stop()
    }
}
