package org.yapyap.orchestrator.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.AccountIdentityRecord
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.orchestrator.fold.global.GlobalEventProjector
import org.yapyap.orchestrator.fold.global.IdentityStateChange
import org.yapyap.orchestrator.sync.SyncCoordinator
import org.yapyap.persistence.key.BootstrapSessionStore
import org.yapyap.persistence.key.IdentityKeyRepository
import org.yapyap.persistence.key.InMemoryIdentityKeyRepository
import org.yapyap.persistence.key.InMemoryKeyStore
import org.yapyap.persistence.messaging.RoomRepository
import org.yapyap.protocol.*
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.protocol.envelopes.Invite
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.RecoveryRequest
import org.yapyap.routing.router.*
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.FakeRoomRepository
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.backend.TorHealth
import org.yapyap.transport.tor.backend.TorPublishState
import org.yapyap.transport.tor.backend.TorRecoveryStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private class FakeOnboardingRouter(
    override val bootstrapPackets: Flow<BootstrapPacketEvent>,
    override val bootstrapDeliveries: Flow<Uuid> = emptyFlow(),
) : Router {
    override val incomingMessages: Flow<MessagePayload> = emptyFlow()
    override val typingIndicators: Flow<TypingIndicatorEvent> = emptyFlow()
    override val pingPayloads: Flow<PingFrontiers> = emptyFlow()
    override val onlineAccounts: Flow<Set<AccountId>> = emptyFlow()

    override val localPublishState: StateFlow<TorPublishState> =
        MutableStateFlow(TorPublishState.PUBLISHING)
    override val localTransportHealth: StateFlow<TorHealth> =
        MutableStateFlow(TorHealth.Ready)
    override val localTransportRecovery: StateFlow<TorRecoveryStep?> =
        MutableStateFlow(null)
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override fun isRunning(): Boolean = true
    override suspend fun announceOnline() = Unit
    override suspend fun sendMessage(
        target: AccountId,
        payload: MessagePayload,
    ): AccountPushReport = error("not used")

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
    ) = Uuid.random()
}

private class NoopSyncCoordinator : SyncCoordinator {
    override fun start(scope: CoroutineScope) = Unit
    override suspend fun stop() = Unit
    override suspend fun requestFrontierSync(roomId: RoomId, tips: List<Uuid>, senderAccount: AccountId?) = Unit
    override suspend fun refreshCandidatesFor(roomId: RoomId) = Unit
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
    override suspend fun publishRemoveAccount(targetAccountId: AccountId, successorAccountId: AccountId?) = Unit
    override suspend fun publishRemoveDevice(targetDeviceId: PeerId) = Unit
    override suspend fun activeDevicesAddedBy(authorDeviceId: PeerId): List<PeerId> = emptyList()
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
        deliveries: MutableSharedFlow<Uuid> = MutableSharedFlow(extraBufferCapacity = 16),
    ): DefaultOnboardingProvider =
        DefaultOnboardingProvider(
            router = FakeOnboardingRouter(packets, deliveries),
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

    @Test
    fun recoveryRequest_sentThenDelivered_onlyForItsOwnPacket() = runTest {
        val clock = FakeClock(start)
        val sessionStore = BootstrapSessionStore(InMemoryKeyStore())
        val deliveries = MutableSharedFlow<Uuid>(extraBufferCapacity = 16)
        val provider = providerUnderTest(clock, sessionStore, deliveries = deliveries)
        provider.start(this)
        provider.beginSession(ByteArray(32) { 1 })
        val request = Uuid.random()

        provider.awaitRequestDelivery(request)
        runCurrent()
        assertEquals(OnboardingState.REQUEST_SENT, provider.state.value)

        deliveries.emit(Uuid.random())
        runCurrent()
        assertEquals(OnboardingState.REQUEST_SENT, provider.state.value, "someone else's packet changes nothing")

        deliveries.emit(request)
        runCurrent()
        assertEquals(OnboardingState.REQUEST_DELIVERED, provider.state.value)
        provider.stop()
    }

    @Test
    fun requestStates_stillTimeOut() = runTest {
        val clock = FakeClock(start)
        val sessionStore = BootstrapSessionStore(InMemoryKeyStore())
        val config = RouterConfig()
        val provider = providerUnderTest(clock, sessionStore, routerConfig = config)
        provider.start(this)
        provider.beginSession(ByteArray(32) { 1 })
        provider.awaitRequestDelivery(Uuid.random())
        runCurrent()

        clock.advanceBy(config.bootstrapSessionLifetime + 1.minutes)
        advanceTimeBy((config.bootstrapSessionLifetime + 1.minutes).inWholeMilliseconds)
        runCurrent()
        assertEquals(OnboardingState.TIMED_OUT, provider.state.value)
        provider.stop()
    }

    @Test
    fun cancel_afterDelivery_ignoresALateDelivery() = runTest {
        val clock = FakeClock(start)
        val sessionStore = BootstrapSessionStore(InMemoryKeyStore())
        val deliveries = MutableSharedFlow<Uuid>(extraBufferCapacity = 16)
        val provider = providerUnderTest(clock, sessionStore, deliveries = deliveries)
        provider.start(this)
        provider.beginSession(ByteArray(32) { 1 })
        val request = Uuid.random()
        provider.awaitRequestDelivery(request)
        runCurrent()

        provider.cancelOnboarding()
        deliveries.emit(request)
        runCurrent()
        assertEquals(OnboardingState.IDLE, provider.state.value)
        provider.stop()
    }
}
