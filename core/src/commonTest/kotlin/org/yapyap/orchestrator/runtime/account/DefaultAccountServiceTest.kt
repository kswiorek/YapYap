package org.yapyap.orchestrator.runtime.account

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
    override val stateChanges: Flow<IdentityStateChange> = emptyFlow()
    val removedDevices = mutableListOf<PeerId>()
    val removedAccounts = mutableListOf<AccountId>()
    val removedSuccessors = mutableListOf<AccountId?>()
    var frontierUnavailable = false
    var survivors: List<PeerId> = emptyList()

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
    override suspend fun publishRemoveAccount(targetAccountId: AccountId, successorAccountId: AccountId?) {
        if (frontierUnavailable) throw DagException.FrontierUnavailable(RoomId.GLOBAL)
        removedAccounts += targetAccountId
        removedSuccessors += successorAccountId
    }

    override suspend fun publishRemoveDevice(targetDeviceId: PeerId) {
        if (frontierUnavailable) throw DagException.FrontierUnavailable(RoomId.GLOBAL)
        removedDevices += targetDeviceId
    }

    override suspend fun activeDevicesAddedBy(authorDeviceId: PeerId): List<PeerId> = survivors
}

private class FakeProvisioning : IdentityProvisioning {
    var recoveryKeyValid = false

    override suspend fun createNewDeviceIdentity(): DeviceIdentityRecord = error("not used")
    override suspend fun createNewAccountIdentity(
        displayName: String,
        role: AccountRole,
    ): AccountIdentityRecord = error("not used")

    override suspend fun createPlaceholderAccountIdentity(): AccountIdentityRecord = error("not used")
    override suspend fun exportLocalAccountRecoveryKey(): String = error("not used")
    override suspend fun verifyRecoveryKey(recoveryKey: String): Boolean = recoveryKeyValid
    override suspend fun importLocalAccountFromRecovery(recoveryKey: String): AccountIdentityRecord =
        error("not used")

    override suspend fun provisionSignedPreKey(): SignedPreKeyRecord = error("not used")
}

class DefaultAccountServiceTest {

    private val localAccount = AccountId("local-account")
    private val otherAccount = AccountId("other-account")
    private val successorAccount = AccountId("successor-account")
    private val localDevice = PeerId("local-device")
    private val siblingDevice = PeerId("sibling-device")
    private val successorDevice = PeerId("successor-device")
    private val strangerDevice = PeerId("stranger-device")
    private val unknownDevice = PeerId("unknown-device")

    private inner class Fixture(owner: Boolean = false) {
        val projector = RecordingProjector()
        val resolver = FakeIdentityResolver(
            localAccountId = localAccount,
            localDeviceId = localDevice,
            accountByDevice = mapOf(
                localDevice to localAccount,
                siblingDevice to localAccount,
                strangerDevice to otherAccount,
            ),
            localOwner = owner,
        )
        val repo = InMemoryIdentityKeyRepository()
        val provisioning = FakeProvisioning()
        val onboardingState = MutableStateFlow(OnboardingState.IDLE)
        val service = DefaultAccountService(projector, resolver, repo, provisioning, onboardingState)

        suspend fun seedLocal(provisional: Boolean) {
            repo.insertLocalDevice(localAccount, dummyDevice(localDevice), provisional)
        }

        suspend fun seedSibling(provisional: Boolean) {
            repo.insertPeerDevice(
                localAccount,
                DeviceType.DESKTOP,
                dummyDevice(siblingDevice),
                TorEndpoint(onionAddress = "sibling.onion", port = 80),
                provisional,
            )
        }

        suspend fun seedAccount(
            account: AccountId,
            device: PeerId,
            provisional: Boolean = false,
            withDevice: Boolean = true,
        ) {
            repo.insertPeerAccount(
                identity = AccountIdentityRecord(
                    accountId = account,
                    displayName = account.id,
                    key = IdentityPublicKeyRecord("k", 0, IdentityKeyPurpose.SIGNING, byteArrayOf(1)),
                ),
                role = AccountRole.MEMBER,
                status = IdentityStatus.ACTIVE,
                displayName = account.id,
                provisional = provisional,
            )
            if (withDevice) {
                repo.insertPeerDevice(
                    account,
                    DeviceType.DESKTOP,
                    dummyDevice(device),
                    TorEndpoint(onionAddress = "${device.id}.onion", port = 80),
                    provisional,
                )
            }
        }

        private fun dummyDevice(id: PeerId) = DeviceIdentityRecord(
            deviceId = id,
            signing = IdentityPublicKeyRecord("k", 0, IdentityKeyPurpose.SIGNING, byteArrayOf(1)),
            encryption = IdentityPublicKeyRecord("k", 0, IdentityKeyPurpose.ENCRYPTION, byteArrayOf(2)),
        )
    }

    @Test
    fun removeOwnDevice_sibling_publishes() = runTest {
        val fixture = Fixture()
        fixture.seedLocal(provisional = false)
        fixture.seedSibling(provisional = false)

        val outcome = fixture.service.removeOwnDevice(siblingDevice)

        assertEquals(GlobalEventOutcome.Published, outcome)
        assertEquals(listOf(siblingDevice), fixture.projector.removedDevices)
    }

    @Test
    fun removeOwnDevice_unknownDevice_refusesTargetNotFound() = runTest {
        val fixture = Fixture()
        fixture.seedLocal(provisional = false)

        val outcome = fixture.service.removeOwnDevice(unknownDevice)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.TargetNotFound), outcome)
        assertTrue(fixture.projector.removedDevices.isEmpty())
    }

    @Test
    fun removeOwnDevice_strangerDevice_refusesNotOwnDevice() = runTest {
        val fixture = Fixture()
        fixture.seedLocal(provisional = false)

        val outcome = fixture.service.removeOwnDevice(strangerDevice)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.NotOwnDevice), outcome)
        assertTrue(fixture.projector.removedDevices.isEmpty())
    }

    @Test
    fun removeOwnDevice_localDevice_delegatesToRemoveThisDevice() = runTest {
        val fixture = Fixture()
        fixture.seedLocal(provisional = false)
        fixture.seedSibling(provisional = false)

        val outcome = fixture.service.removeOwnDevice(localDevice)

        assertEquals(GlobalEventOutcome.Published, outcome)
        assertEquals(listOf(localDevice), fixture.projector.removedDevices)
    }

    @Test
    fun removeOwnDevice_provisionalTarget_refusesNotReady() = runTest {
        val fixture = Fixture()
        fixture.seedLocal(provisional = false)
        fixture.seedSibling(provisional = true)

        val outcome = fixture.service.removeOwnDevice(siblingDevice)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.NotReady), outcome)
        assertTrue(fixture.projector.removedDevices.isEmpty())
    }

    @Test
    fun removeOwnDevice_frontierUnavailable_refusesNotReady() = runTest {
        val fixture = Fixture()
        fixture.seedLocal(provisional = false)
        fixture.seedSibling(provisional = false)
        fixture.projector.frontierUnavailable = true

        val outcome = fixture.service.removeOwnDevice(siblingDevice)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.NotReady), outcome)
        assertTrue(fixture.projector.removedDevices.isEmpty())
    }

    @Test
    fun removeOwnDevice_whileSyncing_refusesOnboardingActive() = runTest {
        val fixture = Fixture()
        fixture.seedLocal(provisional = false)
        fixture.seedSibling(provisional = false)
        fixture.onboardingState.value = OnboardingState.SYNCING

        val outcome = fixture.service.removeOwnDevice(siblingDevice)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.OnboardingActive), outcome)
        assertTrue(fixture.projector.removedDevices.isEmpty())
    }

    @Test
    fun removeThisDevice_publishes() = runTest {
        val fixture = Fixture()
        fixture.seedLocal(provisional = false)
        fixture.seedSibling(provisional = false)

        val outcome = fixture.service.removeThisDevice()

        assertEquals(GlobalEventOutcome.Published, outcome)
        assertEquals(listOf(localDevice), fixture.projector.removedDevices)
    }

    @Test
    fun removeThisDevice_provisionalLocal_refusesNotReady() = runTest {
        val fixture = Fixture()
        fixture.seedLocal(provisional = true)

        val outcome = fixture.service.removeThisDevice()

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.NotReady), outcome)
        assertTrue(fixture.projector.removedDevices.isEmpty())
    }

    @Test
    fun removeThisDevice_whileAwaitingIntro_refusesOnboardingActive() = runTest {
        val fixture = Fixture()
        fixture.seedLocal(provisional = false)
        fixture.onboardingState.value = OnboardingState.AWAITING_INTRO

        val outcome = fixture.service.removeThisDevice()

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.OnboardingActive), outcome)
        assertTrue(fixture.projector.removedDevices.isEmpty())
    }

    @Test
    fun removeOwnAccount_publishes() = runTest {
        val fixture = Fixture()
        fixture.seedLocal(provisional = false)

        val outcome = fixture.service.removeOwnAccount()

        assertEquals(GlobalEventOutcome.Published, outcome)
        assertEquals(listOf(localAccount), fixture.projector.removedAccounts)
    }

    @Test
    fun removeOwnAccount_provisionalLocal_refusesNotReady() = runTest {
        val fixture = Fixture()
        fixture.seedLocal(provisional = true)

        val outcome = fixture.service.removeOwnAccount()

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.NotReady), outcome)
        assertTrue(fixture.projector.removedAccounts.isEmpty())
    }

    @Test
    fun removeOwnAccount_whileSyncing_refusesOnboardingActive() = runTest {
        val fixture = Fixture()
        fixture.seedLocal(provisional = false)
        fixture.onboardingState.value = OnboardingState.SYNCING

        val outcome = fixture.service.removeOwnAccount()

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.OnboardingActive), outcome)
        assertTrue(fixture.projector.removedAccounts.isEmpty())
    }

    @Test
    fun removeThisDevice_lastDeviceWithoutKey_refusesRecoveryKeyRequired() = runTest {
        val fixture = Fixture()
        fixture.seedLocal(provisional = false)

        val outcome = fixture.service.removeThisDevice()

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.RecoveryKeyRequired), outcome)
        assertTrue(fixture.projector.removedDevices.isEmpty())
    }

    @Test
    fun removeThisDevice_lastDeviceWithValidKey_publishes() = runTest {
        val fixture = Fixture()
        fixture.seedLocal(provisional = false)
        fixture.provisioning.recoveryKeyValid = true

        val outcome = fixture.service.removeThisDevice(recoveryKey = "YYR1.valid.key")

        assertEquals(GlobalEventOutcome.Published, outcome)
        assertEquals(listOf(localDevice), fixture.projector.removedDevices)
    }

    @Test
    fun removeThisDevice_lastDeviceWithInvalidKey_refusesRecoveryKeyRequired() = runTest {
        val fixture = Fixture()
        fixture.seedLocal(provisional = false)

        val outcome = fixture.service.removeThisDevice(recoveryKey = "not-a-key")

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.RecoveryKeyRequired), outcome)
        assertTrue(fixture.projector.removedDevices.isEmpty())
    }

    @Test
    fun removeOwnDevice_lastConfirmedDeviceWithoutKey_refusesRecoveryKeyRequired() = runTest {
        val fixture = Fixture()
        fixture.seedLocal(provisional = true)
        fixture.seedSibling(provisional = false)

        val outcome = fixture.service.removeOwnDevice(siblingDevice)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.RecoveryKeyRequired), outcome)
        assertTrue(fixture.projector.removedDevices.isEmpty())
    }

    @Test
    fun removeOwnAccount_ownerWithoutSuccessor_refusesInvalidSuccessor() = runTest {
        val fixture = Fixture(owner = true)
        fixture.seedLocal(provisional = false)

        val outcome = fixture.service.removeOwnAccount()

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.InvalidSuccessor), outcome)
        assertTrue(fixture.projector.removedAccounts.isEmpty())
    }

    @Test
    fun removeOwnAccount_ownerWithSelfSuccessor_refusesInvalidSuccessor() = runTest {
        val fixture = Fixture(owner = true)
        fixture.seedLocal(provisional = false)

        val outcome = fixture.service.removeOwnAccount(localAccount)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.InvalidSuccessor), outcome)
        assertTrue(fixture.projector.removedAccounts.isEmpty())
    }

    @Test
    fun removeOwnAccount_ownerWithUnknownSuccessor_refusesInvalidSuccessor() = runTest {
        val fixture = Fixture(owner = true)
        fixture.seedLocal(provisional = false)

        val outcome = fixture.service.removeOwnAccount(AccountId("ghost-account"))

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.InvalidSuccessor), outcome)
        assertTrue(fixture.projector.removedAccounts.isEmpty())
    }

    @Test
    fun removeOwnAccount_ownerWithDeviceLessSuccessor_refusesInvalidSuccessor() = runTest {
        val fixture = Fixture(owner = true)
        fixture.seedLocal(provisional = false)
        fixture.seedAccount(successorAccount, successorDevice, withDevice = false)

        val outcome = fixture.service.removeOwnAccount(successorAccount)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.InvalidSuccessor), outcome)
        assertTrue(fixture.projector.removedAccounts.isEmpty())
    }

    @Test
    fun removeOwnAccount_ownerWithValidSuccessor_publishesHandover() = runTest {
        val fixture = Fixture(owner = true)
        fixture.seedLocal(provisional = false)
        fixture.seedAccount(successorAccount, successorDevice)

        val outcome = fixture.service.removeOwnAccount(successorAccount)

        assertEquals(GlobalEventOutcome.Published, outcome)
        assertEquals(listOf(localAccount), fixture.projector.removedAccounts)
        assertEquals(listOf<AccountId?>(successorAccount), fixture.projector.removedSuccessors)
    }

    @Test
    fun removeOwnAccount_nonOwnerWithSuccessor_refusesInvalidSuccessor() = runTest {
        val fixture = Fixture()
        fixture.seedLocal(provisional = false)
        fixture.seedAccount(successorAccount, successorDevice)

        val outcome = fixture.service.removeOwnAccount(successorAccount)

        assertEquals(GlobalEventOutcome.Refused(GlobalEventRefusal.InvalidSuccessor), outcome)
        assertTrue(fixture.projector.removedAccounts.isEmpty())
    }

    @Test
    fun devicesAddedBy_delegatesToProjector() = runTest {
        val fixture = Fixture()
        fixture.projector.survivors = listOf(siblingDevice)

        assertEquals(listOf(siblingDevice), fixture.service.devicesAddedBy(localDevice))
    }
}
