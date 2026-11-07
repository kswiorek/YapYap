package org.yapyap.orchestrator.runtime.message

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.persistence.messaging.MessageDeliveryRepository
import org.yapyap.persistence.messaging.MessageRepository
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.RoomId
import org.yapyap.routing.router.Delivery
import org.yapyap.routing.router.DeliverySubject
import org.yapyap.routing.router.PingFrontiers
import org.yapyap.routing.router.Router
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Records which member accounts hold the local account's messages, from two kinds of evidence:
 *
 * - a push-path ACK ([Router.deliveries]): fast, but only for messages pushed straight to a device;
 * - a ping frontier that descends from the message: slower, but it covers every way a message
 *   arrives (push, relay, sync), since a device advertises only tips whose ancestry it holds.
 *
 * Only "delivered" is ever recorded. While the pull path exists a message never fails to
 * deliver; it just isn't known to be delivered yet.
 */
internal class DeliveryTracker(
    private val router: Router,
    private val messageRepository: MessageRepository,
    private val deliveryRepository: MessageDeliveryRepository,
    private val identityResolver: IdentityResolver,
    private val clock: Clock = Clock.System,
) {
    private val updateFlow = MutableSharedFlow<MessageDelivery>(extraBufferCapacity = 64)
    val updates: SharedFlow<MessageDelivery> = updateFlow.asSharedFlow()

    fun start(scope: CoroutineScope): List<Job> = listOf(
        scope.launch {
            router.deliveries.collect { delivery -> guarded("push ACK") { onPushAck(delivery) } }
        },
        scope.launch {
            router.pingPayloads.collect { ping -> guarded("ping frontiers") { onFrontiers(ping) } }
        },
    )

    suspend fun deliveredTo(messageId: Uuid): Set<AccountId> = deliveryRepository.deliveredTo(messageId)

    private suspend fun onPushAck(delivery: Delivery) {
        val messageId = (delivery.subject as? DeliverySubject.Message)?.messageId ?: return
        val account = delivery.account ?: return
        val local = identityResolver.getLocalAccountId()
        if (account == local) return
        val payload = messageRepository.findById(messageId)?.payload ?: return
        if (payload.senderAccountId != local) return
        record(payload.roomId, messageId, account)
    }

    /**
     * The sender holds every message its advertised tips descend from. Walks down from the
     * tips we know over parent edges, looking for our messages not yet known delivered to it.
     */
    private suspend fun onFrontiers(ping: PingFrontiers) {
        val account = ping.senderAccount ?: return
        val local = identityResolver.getLocalAccountId()
        if (account == local) return
        for ((roomId, tips) in ping.roomFrontiers) {
            val pending = deliveryRepository
                .undelivered(roomId, authorAccountId = local, accountId = account, limit = MAX_PENDING_CHECKED)
                .toMutableSet()
            if (pending.isEmpty()) continue
            // Stop descending below the oldest pending message (with slack for author clock skew).
            val floor = pending.mapNotNull { messageRepository.findById(it)?.payload?.createdAt }.minOrNull()
                ?.minus(CLOCK_SKEW_SLACK) ?: continue
            val visited = HashSet<Uuid>()
            val queue = ArrayDeque(tips)
            while (queue.isNotEmpty() && pending.isNotEmpty() && visited.size < MAX_WALK) {
                val id = queue.removeFirst()
                if (!visited.add(id)) continue
                val payload = messageRepository.findById(id)?.payload ?: continue
                if (id in pending) {
                    pending.remove(id)
                    record(roomId, id, account)
                }
                if (payload.createdAt < floor) continue
                queue.addAll(payload.prevIds)
            }
        }
    }

    private suspend fun record(roomId: RoomId, messageId: Uuid, account: AccountId) {
        if (deliveryRepository.markDelivered(messageId, account, clock.now())) {
            updateFlow.emit(MessageDelivery(roomId, messageId, account))
        }
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn(
                component = LogComponent.ORCHESTRATOR,
                event = LogEvent.DELIVERY_TRACKING_FAILED,
                message = "Delivery tracking from $what failed",
                fields = mapOf("error" to e.toString()),
            )
        }
    }

    private companion object {
        /** Newest undelivered messages checked per ping and room; older ones wait for later pings. */
        const val MAX_PENDING_CHECKED = 200

        /** Messages visited per ping and room. */
        const val MAX_WALK = 5_000

        val CLOCK_SKEW_SLACK = 10.minutes
    }
}
