package org.yapyap.routing.message

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.MessageId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.SyncId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.DagPayload
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SyncPayload
import org.yapyap.routing.policy.DefaultSyncPeerPolicy
import org.yapyap.routing.router.InMemorySyncAttemptStore
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.RouterLifecycle
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class SyncHandlerTest {

    private val localDevice = PeerId("handler-local-device")
    private val remoteDevice = PeerId("handler-remote-device")
    private val remoteAccount = AccountId("handler-remote-account")
    private val localAccount = AccountId("handler-local-account")
    private val roomId = RoomId(Uuid.random())

    private fun textMsg(): DagPayload.Text =
        DagPayload.Text(
            messageId = MessageId(Uuid.random()),
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDevice,
            authorSignature = byteArrayOf(1),
            prevIds = emptyList(),
            createdAt = epochSeconds(0L),
            text = "m",
        )

    private fun syncRequest(): SyncPayload.Request =
        SyncPayload.Request(
            roomId = roomId,
            syncId = SyncId(Uuid.random()),
            missingIds = emptyList(),
            knownIds = emptyList(),
        )

    private fun stack() = buildSyncRoutingStack(
        localDevice = testDeviceIdentity(localDevice),
        peersByAccount = mapOf(remoteAccount to listOf(remoteDevice)),
        torByPeer = mutableMapOf(remoteDevice to TorEndpoint("handler-remote.onion", 80)),
    )

    private fun service(stack: SyncRoutingStack, provider: RecordingSyncPayloadProvider): MessageService {
        val lifecycle = RouterLifecycle().apply { markStarted() }
        return MessageService(
            ctx = stack.ctx,
            lifecycle = lifecycle,
            outboxProcessor = stack.outboxProcessor,
            systemSender = stack.systemSender,
            syncAttemptStore = InMemorySyncAttemptStore(),
            syncProvider = provider,
            peerPolicy = DefaultSyncPeerPolicy(stack.ctx),
            maxIdlePoll = MutableStateFlow(1.seconds),
            torLinkKeeper = stack.torLinkKeeper,
            webRtcLinkKeeper = stack.webRtcLinkKeeper,
            frontiers = emptyFlow(),
        )
    }

    @Test
    fun messagesFor_asksForTheRequestingDevicesAccount() = runTest {
        val stack = stack()
        val provider = RecordingSyncPayloadProvider(messages = listOf(textMsg()))
        val svc = service(stack, provider)

        val result = svc.onSyncRequested(syncRequest(), sourceDevice = remoteDevice)

        assertIs<InboundHandleResult.Success>(result)
        assertEquals(1, provider.requests.size)
        assertEquals(listOf(remoteAccount), provider.requesters)
    }

    @Test
    fun messagesFor_unknownDevice_isDeclinedWithoutAsking() = runTest {
        val stack = stack()
        val provider = RecordingSyncPayloadProvider(messages = listOf(textMsg()))
        val svc = service(stack, provider)

        val result = svc.onSyncRequested(syncRequest(), sourceDevice = PeerId("stranger"))

        assertEquals(
            InboundHandleResult.Rejected(PacketNackReason.DECLINED).reason,
            assertIs<InboundHandleResult.Rejected>(result).reason
        )
        assertTrue(provider.requests.isEmpty())
    }

    @Test
    fun reply_withMessages_sendsEachToSourcePeer() = runTest {
        val stack = stack()

        // Responses route through the outbox; the dispatch loop must run
        // (production always runs it via router.start()).
        val loopJob = stack.outboxProcessor.runIn(this)
        try {
            val request = syncRequest()
            // Serve two messages: seed the provider via a fresh service call.
            val serving = service(stack, RecordingSyncPayloadProvider(messages = listOf(textMsg(), textMsg())))
            serving.onSyncRequested(request, remoteDevice)

            withTimeout(10.seconds) { stack.tor.awaitMessageSendCount(2) }
            assertEquals(2, stack.outbox.enqueued.size)
            assertTrue(stack.outbox.enqueued.all { it.target == remoteDevice })
        } finally {
            loopJob.cancel()
        }
    }

    @Test
    fun reply_withNoMessages_declinesWithoutEnqueue() = runTest {
        val stack = stack()

        val result = service(stack, RecordingSyncPayloadProvider()).onSyncRequested(syncRequest(), remoteDevice)

        assertEquals(PacketNackReason.DECLINED, assertIs<InboundHandleResult.Rejected>(result).reason)
        assertEquals(0, stack.outbox.enqueued.size)
        assertEquals(0, stack.tor.sends.size)
    }

    @Test
    fun inboundRequest_askingForADisposition_isNackedDeclinedWhenNotServed() = runTest {
        val local = stack()
        val remote = buildSyncRoutingStack(
            localDevice = testDeviceIdentity(remoteDevice),
            peersByAccount = mapOf(localAccount to listOf(localDevice)),
        )
        val svc = service(local, RecordingSyncPayloadProvider(messages = emptyList()))
        val inbound = MessageInboundHandler(
            local.ctx,
            MutableSharedFlow(),
            svc,
        )

        val envelope = remote.outboundMessenger.buildEnvelope(localDevice, syncRequest(), 90.seconds)
        val result = inbound.handle(envelope)

        assertEquals(PacketNackReason.DECLINED, assertIs<InboundHandleResult.Rejected>(result).reason)
    }

    @Test
    fun inboundRequest_served_isAckedAndQueuedForThePeer() = runTest {
        val local = stack()
        val remote = buildSyncRoutingStack(
            localDevice = testDeviceIdentity(remoteDevice),
            peersByAccount = mapOf(localAccount to listOf(localDevice)),
        )
        val served = listOf(textMsg())
        val svc = service(local, RecordingSyncPayloadProvider(messages = served))
        val inbound = MessageInboundHandler(
            local.ctx,
            MutableSharedFlow(),
            svc,
        )

        val envelope = remote.outboundMessenger.buildEnvelope(localDevice, syncRequest(), 90.seconds)
        val result = inbound.handle(envelope)

        assertIs<InboundHandleResult.Success>(result)
        // The reply is queued for the requesting device through the outbox.
        assertEquals(served.size, local.outbox.enqueued.size)
        assertTrue(local.outbox.enqueued.all { it.target == remoteDevice })
        // And the queued bytes decode back to the served messages.
        val decoded = local.outbox.enqueued.map {
            MessagePayload.decode(it.content) as DagPayload
        }
        assertEquals(served, decoded)
    }
}
