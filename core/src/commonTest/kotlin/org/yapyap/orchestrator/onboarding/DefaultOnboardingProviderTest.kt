package org.yapyap.orchestrator.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.AccountIdentityRecord
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.orchestrator.globalevent.GlobalEventProjector
import org.yapyap.orchestrator.globalevent.IdentityStateChange
import org.yapyap.orchestrator.sync.SyncCoordinator
import org.yapyap.persistence.db.DeviceType
import org.yapyap.persistence.key.BootstrapSessionStore
import org.yapyap.persistence.key.IdentityKeyRepository
import org.yapyap.persistence.key.InMemoryIdentityKeyRepository
import org.yapyap.persistence.key.InMemoryKeyStore
import org.yapyap.persistence.messaging.RoomRepository
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.protocol.envelopes.Invite
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.RecoveryRequest
import org.yapyap.routing.router.*
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.FakeRoomRepository
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private class FakeOnboardingRouter(
    override val bootstrapPackets: Flow<BootstrapPacketEvent>,
) : Router {
    override val incomingMessages: Flow<MessagePayload> = emptyFlow()
    override val typingIndicators: Flow<TypingIndicatorEvent> = emptyFlow()
    override val pingPayloads: Flow<List<Pair<RoomId, List<Uuid>>>> = emptyFlow()
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override fun isRunning(): Boolean = true
    override suspend fun announceOnline() = Unit
    override suspend fun sendMessage(
        target: AccountId,
        payload: MessagePayload,
        forceTransport: RouterTransport?,
    ): SendMessageResult = error("not used")

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

private class NoopSyncCoordinator : SyncCoordinator {
    override fun start(scope: CoroutineScope) = Unit
    override suspend fun stop() = Unit
    override suspend fun requestFrontierSync(roomId: RoomId, tips: List<Uuid>) = Unit
}

private class NoopGlobalEventProjector : GlobalEventProjector {
    override val stateChanges: Flow<IdentityStateChange> = emptyFlow()
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
    override suspend fun publishRemoveAccount(targetAccountId: AccountId) = Unit
    override suspend fun publishRemoveDevice(targetDeviceId: PeerId) = Unit
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DefaultOnboardingProviderTest {

    private val start = epochSeconds(10_000L)

    private fun providerUnderTest(
        clock: FakeClock,
        sessionStore: BootstrapSessionStore,
        packets: MutableSharedFlow<BootstrapPacketEvent> = MutableSharedFlow(extraBufferCapacity = 64),
        routerConfig: RouterConfig = RouterConfig(),
        identityKeyRepository: IdentityKeyRepository = InMemoryIdentityKeyRepository(),
        roomRepository: RoomRepository = FakeRoomRepository(),
    ): DefaultOnboardingProvider =
        DefaultOnboardingProvider(
            router = FakeOnboardingRouter(packets),
            sessionStore = sessionStore,
            syncCoordinator = NoopSyncCoordinator(),
            identityKeyRepository = identityKeyRepository,
            roomRepository = roomRepository,
            projector = NoopGlobalEventProjector(),
            clock = clock,
            routerConfig = MutableStateFlow(routerConfig),
        )

    @Test
    fun start_noSession_staysIdle() = runTest {
        val provider = providerUnderTest(FakeClock(start), BootstrapSessionStore(InMemoryKeyStore()))

        provider.start(this)
        runCurrent()

        assertEquals(OnboardingState.IDLE, provider.state.value)
        provider.stop()
    }

    @Test
    fun start_liveSession_resumesAwaitingIntro_thenTimesOutAndBurns() = runTest {
        val clock = FakeClock(start)
        val sessionStore = BootstrapSessionStore(InMemoryKeyStore())
        val window = 5.minutes
        sessionStore.setActiveSecret(ByteArray(32) { 1 }, start + window)
        val provider = providerUnderTest(clock, sessionStore)

        provider.start(this)
        // runCurrent, not advanceUntilIdle: the latter fast-forwards through the armed delay,
        // and the stale-timer re-arm (gated on the separately-advanced FakeClock) would
        // reschedule forever under virtual time.
        runCurrent()
        assertEquals(OnboardingState.AWAITING_INTRO, provider.state.value)

        // Still inside the window: nothing happens. Both clocks advance together.
        clock.advanceBy(window - 1.minutes)
        advanceTimeBy((window - 1.minutes).inWholeMilliseconds)
        runCurrent()
        assertEquals(OnboardingState.AWAITING_INTRO, provider.state.value)

        // Past the deadline: burn + TIMED_OUT.
        clock.advanceBy(2.minutes)
        advanceTimeBy(2.minutes.inWholeMilliseconds)
        runCurrent()
        assertEquals(OnboardingState.TIMED_OUT, provider.state.value)
        assertNull(sessionStore.session())
        provider.stop()
    }

    @Test
    fun start_expiredSession_timesOutImmediately() = runTest {
        val clock = FakeClock(start)
        val sessionStore = BootstrapSessionStore(InMemoryKeyStore())
        sessionStore.setActiveSecret(ByteArray(32) { 1 }, start - 1.minutes)
        val provider = providerUnderTest(clock, sessionStore)

        provider.start(this)
        runCurrent()

        assertEquals(OnboardingState.TIMED_OUT, provider.state.value)
        assertNull(sessionStore.session())
        provider.stop()
    }

    @Test
    fun cancelOnboarding_burnsAndIdles_andTimerNeverFires() = runTest {
        val clock = FakeClock(start)
        val sessionStore = BootstrapSessionStore(InMemoryKeyStore())
        sessionStore.setActiveSecret(ByteArray(32) { 1 }, start + 5.minutes)
        val provider = providerUnderTest(clock, sessionStore)

        provider.start(this)
        runCurrent()
        assertEquals(OnboardingState.AWAITING_INTRO, provider.state.value)

        provider.cancelOnboarding()
        assertEquals(OnboardingState.IDLE, provider.state.value)

        clock.advanceBy(10.minutes)
        advanceTimeBy(10.minutes.inWholeMilliseconds)
        runCurrent()
        assertEquals(OnboardingState.IDLE, provider.state.value)
        provider.stop()
    }
}
