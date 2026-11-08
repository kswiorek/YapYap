package org.yapyap.orchestrator.message

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.yapyap.orchestrator.dag.VerificationState
import org.yapyap.orchestrator.runtime.message.DeliveryTracker
import org.yapyap.orchestrator.runtime.message.MessageDelivery
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.router.Delivery
import org.yapyap.routing.router.DeliverySubject
import org.yapyap.routing.router.PingFrontiers
import org.yapyap.testfixtures.*
import org.yapyap.testfixtures.FakeIdentityResolver
import org.yapyap.testfixtures.FakeMessageRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DeliveryTrackerTest {

    private val localAccount = AccountId("tracker-local")
    private val remoteAccount = AccountId("tracker-remote")
    private val localDevice = PeerId("tracker-local-device")
    private val roomId = RoomId(Uuid.random())

    private class DrivenRouter : NoopRouter() {
        val deliveryFlow = MutableSharedFlow<Delivery>(extraBufferCapacity = 16)
        val pingFlow = MutableSharedFlow<PingFrontiers>(extraBufferCapacity = 16)
        override val deliveries: Flow<Delivery> = deliveryFlow
        override val pingPayloads: Flow<PingFrontiers> = pingFlow
    }

    private val router = DrivenRouter()
    private val messages = FakeMessageRepository()
    private val deliveries = InMemoryMessageDeliveryRepository(messages)
    private val tracker = DeliveryTracker(
        router,
        messages,
        deliveries,
        FakeIdentityResolver(localAccount, localDevice),
    )
    private var tick = 0L

    private suspend fun message(sender: AccountId, prevIds: List<Uuid> = emptyList()): MessagePayload.Text {
        val msg = MessagePayload.Text(
            messageId = Uuid.random(),
            roomId = roomId,
            senderAccountId = sender,
            authorDeviceId = localDevice,
            authorSignature = byteArrayOf(1),
            prevIds = prevIds,
            createdAt = epochSeconds(1_000L + tick++),
            text = "m",
        )
        messages.insert(
            msg,
            isOrphaned = false,
            ancestryComplete = true,
            verificationState = VerificationState.VERIFIED
        )
        return msg
    }

    private fun TestScope.started(): MutableList<MessageDelivery> {
        val updates = mutableListOf<MessageDelivery>()
        tracker.start(backgroundScope)
        backgroundScope.launch { tracker.updates.collect { updates += it } }
        return updates
    }

    @Test
    fun pushAck_forOwnMessage_isRecordedOnce() = runTest(UnconfinedTestDispatcher()) {
        val updates = started()
        val own = message(localAccount)

        repeat(2) { router.deliveryFlow.emit(Delivery(DeliverySubject.Message(own.messageId), remoteAccount)) }

        assertEquals(setOf(remoteAccount), tracker.deliveredTo(own.messageId))
        assertEquals(listOf(MessageDelivery(roomId, own.messageId, remoteAccount)), updates)
    }

    @Test
    fun pushAck_forSomeoneElsesMessage_orFromOwnAccount_isIgnored() = runTest(UnconfinedTestDispatcher()) {
        val updates = started()
        val theirs = message(remoteAccount)
        val own = message(localAccount)

        router.deliveryFlow.emit(Delivery(DeliverySubject.Message(theirs.messageId), AccountId("third")))
        router.deliveryFlow.emit(Delivery(DeliverySubject.Message(own.messageId), localAccount))

        assertTrue(updates.isEmpty())
    }

    @Test
    fun pingFrontier_descendingFromOwnMessages_marksThemDelivered() = runTest(UnconfinedTestDispatcher()) {
        val updates = started()
        val first = message(localAccount)
        val second = message(localAccount, prevIds = listOf(first.messageId))
        val reply = message(remoteAccount, prevIds = listOf(second.messageId))
        val unseen = message(localAccount, prevIds = listOf(second.messageId))

        // The remote's tip is its reply: it holds everything below it, not the sibling it never saw.
        router.pingFlow.emit(PingFrontiers(remoteAccount, listOf(roomId to listOf(reply.messageId))))

        assertEquals(setOf(first.messageId, second.messageId), updates.map { it.messageId }.toSet())
        assertTrue(tracker.deliveredTo(unseen.messageId).isEmpty())
    }
}
