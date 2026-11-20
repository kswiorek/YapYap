package org.yapyap.testfixtures

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.file.port.FileCarrier
import org.yapyap.routing.router.AccountPushReport
import org.yapyap.routing.router.AccountReach
import org.yapyap.routing.router.BootstrapCarrier
import org.yapyap.routing.router.BootstrapPacketEvent
import org.yapyap.routing.router.Delivery
import org.yapyap.routing.router.LocalLinkStatus
import org.yapyap.routing.router.MessageCarrier
import org.yapyap.routing.router.PingFrontiers
import org.yapyap.routing.router.Presence
import org.yapyap.routing.router.Router
import org.yapyap.routing.router.SyncCarrier
import org.yapyap.routing.router.TypingIndicatorEvent
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** [MessageCarrier] that does nothing; tests override what they observe or drive. */
internal open class NoopMessageCarrier : MessageCarrier {
    override val incomingMessages: Flow<MessagePayload> = MutableSharedFlow()
    override val deliveries: Flow<Delivery> = emptyFlow()
    override val typingIndicators: Flow<TypingIndicatorEvent> = MutableSharedFlow()

    override suspend fun sendMessage(target: AccountId, payload: MessagePayload): AccountPushReport =
        AccountPushReport(devicesTotal = 1, devicesQueued = 1, devicesDeferred = 0, devicesFailed = 0)

    override suspend fun sendTypingIndicator(targets: Collection<AccountId>, roomId: RoomId, interval: Duration) = Unit
}

/** [SyncCarrier] that does nothing. */
internal open class NoopSyncCarrier : SyncCarrier {
    override val pingPayloads: Flow<PingFrontiers> = MutableSharedFlow()
    override suspend fun announceOnline() = Unit
    override suspend fun startSync(syncId: Uuid, firstAttemptAt: Instant) = Unit
    override suspend fun reopenSync(syncId: Uuid, account: AccountId) = Unit
}

/** [BootstrapCarrier] that does nothing. */
internal open class NoopBootstrapCarrier : BootstrapCarrier {
    override val bootstrapPackets: Flow<BootstrapPacketEvent> = MutableSharedFlow()

    override suspend fun sendBootstrap(
        payload: BootstrapPayload,
        target: PeerId,
        targetEndpoint: TorEndpoint?,
        sharedSecret: ByteArray?,
    ): Uuid = Uuid.random()
}

/** [Presence] with nobody online. */
internal open class NoopPresence : Presence {
    override val onlineAccounts: Flow<Set<AccountId>> = emptyFlow()
    override val localLinkStatus: StateFlow<LocalLinkStatus> = MutableStateFlow(LocalLinkStatus.WarmingUp)
    override suspend fun accountReach(accounts: Collection<AccountId>): Map<AccountId, AccountReach> = emptyMap()
    override fun localTorEndpoint(): TorEndpoint = TorEndpoint("local.onion", 80)
}

/** [Router] built from no-op services; pass the ones a test observes or drives. */
internal open class NoopRouter(
    override val messages: MessageCarrier = NoopMessageCarrier(),
    override val syncs: SyncCarrier = NoopSyncCarrier(),
    override val bootstrap: BootstrapCarrier = NoopBootstrapCarrier(),
    override val presence: Presence = NoopPresence(),
    override val files: FileCarrier = NoopFileCarrier(),
) : Router {
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override fun isRunning(): Boolean = true
}
