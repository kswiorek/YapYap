package org.yapyap.testfixtures

import kotlinx.coroutines.flow.*
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.router.*
import org.yapyap.transport.tor.backend.TorHealth
import org.yapyap.transport.tor.backend.TorPublishState
import org.yapyap.transport.tor.backend.TorRecoveryStep
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** [Router] that does nothing; tests override what they observe or drive. */
internal open class NoopRouter : Router {
    override val incomingMessages: Flow<MessagePayload> = MutableSharedFlow()
    override val typingIndicators: Flow<TypingIndicatorEvent> = MutableSharedFlow()
    override val bootstrapPackets: Flow<BootstrapPacketEvent> = MutableSharedFlow()
    override val pingPayloads: Flow<PingFrontiers> = MutableSharedFlow()
    override val onlineAccounts: Flow<Set<AccountId>> = emptyFlow()
    override val localPublishState: StateFlow<TorPublishState> = MutableStateFlow(TorPublishState.PUBLISHING)
    override val localTransportHealth: StateFlow<TorHealth> = MutableStateFlow(TorHealth.Ready)
    override val localTransportRecovery: StateFlow<TorRecoveryStep?> = MutableStateFlow(null)
    override val deliveries: Flow<Delivery> = emptyFlow()

    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override fun isRunning(): Boolean = true
    override suspend fun announceOnline() = Unit
    override fun localTorEndpoint(): org.yapyap.protocol.TorEndpoint =
        org.yapyap.protocol.TorEndpoint("local.onion", 80)

    override suspend fun deviceLastSeen(): Map<org.yapyap.protocol.PeerId, kotlin.time.Instant> = emptyMap()

    override suspend fun sendMessage(target: AccountId, payload: MessagePayload): AccountPushReport =
        AccountPushReport(devicesTotal = 1, devicesQueued = 1, devicesDeferred = 0, devicesFailed = 0)

    override suspend fun sendTypingIndicator(targets: Collection<AccountId>, roomId: RoomId, interval: Duration) = Unit

    override suspend fun sendBootstrap(
        payload: BootstrapPayload,
        target: PeerId,
        targetEndpoint: TorEndpoint?,
        sharedSecret: ByteArray?,
    ): Uuid = Uuid.random()

    override suspend fun startSync(syncId: Uuid, firstAttemptAt: Instant) = Unit
    override suspend fun reopenSync(syncId: Uuid, account: AccountId) = Unit
}
