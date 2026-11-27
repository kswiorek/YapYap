package org.yapyap.routing.message

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.yapyap.logging.LogComponent
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.envelopes.DagPayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SyncPayload
import org.yapyap.routing.link.LinkKeeper
import org.yapyap.routing.message.port.SyncPayloadProvider
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.outbound.SystemSender
import org.yapyap.routing.policy.SyncPeerPolicy
import org.yapyap.routing.router.AccountPushReport
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.MessageAck
import org.yapyap.routing.router.MessageCarrier
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.OutcomeMailbox
import org.yapyap.routing.router.PingFrontiers
import org.yapyap.routing.router.RouterLifecycle
import org.yapyap.routing.router.RouterService
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.TypingIndicatorEvent
import org.yapyap.routing.store.OutboxTag
import org.yapyap.routing.store.SyncAttemptStore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The message concern: push (fan-out, typing, receipts) and pull (asking for and serving missing
 * history). Both directions live here because the pull path recovers messages — the same shape
 * the file service has for file push and REQUEST.
 *
 * Owns its carrier flows and its inbound face ([MessageInbound]); consumes its own outbox
 * outcomes (MESSAGE and SYNC tags) from its own mailbox.
 */
internal class MessageService(
    private val ctx: RoutingContext,
    private val lifecycle: RouterLifecycle,
    private val outboxProcessor: OutboxProcessor,
    systemSender: SystemSender,
    syncAttemptStore: SyncAttemptStore,
    private val syncProvider: SyncPayloadProvider,
    private val peerPolicy: SyncPeerPolicy,
    private val maxIdlePoll: StateFlow<Duration>,
    torLinkKeeper: LinkKeeper,
    webRtcLinkKeeper: LinkKeeper,
    override val frontiers: Flow<PingFrontiers>,
) : MessageCarrier, MessageInbound, RouterService {

    override val outcomeTags: Set<OutboxTag.Kind> = setOf(OutboxTag.Kind.MESSAGE, OutboxTag.Kind.SYNC)

    private val incomingMessageFlow = MutableSharedFlow<DagPayload>(replay = 1, extraBufferCapacity = 64)
    private val deliveryFlow = MutableSharedFlow<MessageAck>(extraBufferCapacity = 64)
    private val typingIndicatorFlow = MutableSharedFlow<TypingIndicatorEvent>(extraBufferCapacity = 64)

    private val outcomes = OutcomeMailbox()

    private val outboundMessenger = OutboundMessenger(ctx, outboxProcessor, webRtcLinkKeeper)
    private val typingDispatcher = TypingIndicatorDispatcher(ctx, systemSender, webRtcLinkKeeper)
    private val pullProcessor = MessagePullProcessor(
        ctx = ctx,
        attempts = syncAttemptStore,
        syncs = syncProvider,
        envelopes = outboundMessenger,
        outboxProcessor = outboxProcessor,
        peerPolicy = peerPolicy,
        maxIdlePoll = maxIdlePoll,
        linkKeeper = torLinkKeeper,
    )

    val inboundHandler = MessageInboundHandler(ctx, incomingMessageFlow, this)

    override val incomingMessages: Flow<DagPayload> = incomingMessageFlow.asSharedFlow()
    override val deliveries: Flow<MessageAck> = deliveryFlow.asSharedFlow()
    override val typingIndicators: Flow<TypingIndicatorEvent> = typingIndicatorFlow.asSharedFlow()

    override suspend fun sendMessage(target: AccountId, payload: DagPayload): AccountPushReport {
        lifecycle.requireStarted("sending messages")
        return outboundMessenger.sendMessage(target, payload)
    }

    override suspend fun sendTypingIndicator(
        targets: Collection<AccountId>,
        roomId: RoomId,
        interval: Duration,
    ) {
        lifecycle.requireStarted("sending typing indicators")
        typingDispatcher.dispatch(targets, roomId, interval)
    }

    override suspend fun startSync(syncId: Uuid, firstAttemptAt: Instant) {
        lifecycle.requireStarted("starting sync requests")
        pullProcessor.start(syncId, firstAttemptAt)
    }

    override suspend fun reopenSync(syncId: Uuid, account: AccountId) {
        lifecycle.requireStarted("reopening sync requests")
        pullProcessor.reopen(syncId, account)
    }

    // --- inbound face -------------------------------------------------------------------

    override suspend fun onTyping(source: PeerId, roomId: RoomId, intervalMillis: Int) {
        val account = ctx.devices.getAccountIdForDevice(source) ?: return
        typingIndicatorFlow.emit(
            TypingIndicatorEvent(
                senderAccountId = account,
                roomId = roomId,
                interval = intervalMillis.milliseconds,
                receivedAt = ctx.clock.now(),
            ),
        )
    }

    override suspend fun onSyncRequested(
        request: SyncPayload.Request,
        sourceDevice: PeerId,
    ): InboundHandleResult {
        val requester = ctx.devices.getAccountIdForDevice(sourceDevice)
            ?: return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        val messages = syncProvider.serveSync(request, requester)
        if (messages.isEmpty()) {
            // Declined (not served to this account, or nothing held): the DECLINED NACK moves the
            // requester on to its next candidate.
            return InboundHandleResult.Rejected(PacketNackReason.DECLINED)
        }
        for (message in messages) {
            // Forwarding in response to a sync request: direct only, no relay deposits needed.
            outboundMessenger.sendMessageToPeer(sourceDevice, message, supplementRelays = false)
        }
        return InboundHandleResult.Success()
    }

    // --- kernel seams -------------------------------------------------------------------

    override fun offerOutcome(outcome: OutboxOutcome) = outcomes.offer(outcome)

    override fun runIn(scope: CoroutineScope): List<Job> = listOf(
        pullProcessor.runIn(scope),
        outcomes.runIn(scope, LogComponent.ROUTER) { outcome ->
            when (outcome.tag.kind) {
                OutboxTag.Kind.MESSAGE -> if (outcome is OutboxOutcome.Acked) {
                    // A device we can't place in an account can't be shown as anyone's delivery.
                    val account = ctx.devices.getAccountIdForDevice(outcome.target) ?: return@runIn
                    deliveryFlow.emit(MessageAck(outcome.tag.id, account))
                }

                OutboxTag.Kind.SYNC -> pullProcessor.onAttemptOutcome(outcome)

                else -> error("Not a message outcome: ${outcome.tag.kind}")
            }
        },
    )
}
