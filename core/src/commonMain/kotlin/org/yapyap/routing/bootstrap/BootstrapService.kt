package org.yapyap.routing.bootstrap

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.yapyap.logging.LogComponent
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.routing.outbound.OutboxProcessor
import org.yapyap.routing.router.BootstrapAck
import org.yapyap.routing.router.BootstrapCarrier
import org.yapyap.routing.router.BootstrapPacketEvent
import org.yapyap.routing.router.OutboxOutcome
import org.yapyap.routing.router.OutcomeConsumer
import org.yapyap.routing.router.OutcomeMailbox
import org.yapyap.routing.router.RouterLifecycle
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.store.OutboxTag

/**
 * The onboarding bootstrap family: intros and recovery requests. Owns its flows, its inbound
 * face and its own outbox outcomes (the BOOTSTRAP tag kind).
 */
internal class BootstrapService(
    private val ctx: RoutingContext,
    private val lifecycle: RouterLifecycle,
    outboxProcessor: OutboxProcessor,
) : BootstrapCarrier, OutcomeConsumer {

    override val outcomeTags: Set<OutboxTag.Kind> = setOf(OutboxTag.Kind.BOOTSTRAP)

    private val bootstrapPacketFlow = MutableSharedFlow<BootstrapPacketEvent>(extraBufferCapacity = 64)
    private val deliveryFlow = MutableSharedFlow<BootstrapAck>(extraBufferCapacity = 64)
    private val outcomes = OutcomeMailbox()

    private val sender = BootstrapSender(ctx, outboxProcessor)

    val inboundHandler = BootstrapInboundHandler(ctx, bootstrapPacketFlow)

    override val bootstrapPackets: Flow<BootstrapPacketEvent> = bootstrapPacketFlow.asSharedFlow()
    override val deliveries: Flow<BootstrapAck> = deliveryFlow.asSharedFlow()

    override suspend fun sendBootstrap(
        payload: BootstrapPayload,
        target: PeerId,
        targetEndpoint: TorEndpoint?,
        sharedSecret: ByteArray?,
    ) {
        lifecycle.requireStarted("sending a bootstrap packet")
        sender.sendBootstrap(payload, target, targetEndpoint, sharedSecret)
    }

    override fun offerOutcome(outcome: OutboxOutcome) = outcomes.offer(outcome)

    override fun runIn(scope: CoroutineScope): List<Job> = listOf(
        outcomes.runIn(scope, LogComponent.ROUTER) { outcome ->
            check(outcome.tag == OutboxTag.Bootstrap) { "Not a bootstrap outcome: ${outcome.tag.kind}" }
            if (outcome is OutboxOutcome.Acked) {
                val account = ctx.devices.getAccountIdForDevice(outcome.target)
                deliveryFlow.emit(BootstrapAck(outcome.target, account))
            }
        },
    )
}
