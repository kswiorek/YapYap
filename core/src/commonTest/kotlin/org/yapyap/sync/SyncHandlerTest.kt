package org.yapyap.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.routing.inbound.handlers.SystemInboundHandler
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.InboundSideEffect
import org.yapyap.routing.sync.SyncHandler
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

    private fun textMsg(): MessagePayload.Text =
        MessagePayload.Text(
            messageId = Uuid.random(),
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDevice,
            authorSignature = byteArrayOf(1),
            prevIds = emptyList(),
            createdAt = epochSeconds(0L),
            text = "m",
        )

    private fun syncRequest(): SystemPayload.SyncRequest =
        SystemPayload.SyncRequest(
            roomId = roomId,
            syncId = Uuid.random(),
            missingIds = emptyList(),
            knownIds = emptyList(),
        )

    private fun stack() = buildSyncRoutingStack(
        localDevice = testDeviceIdentity(localDevice),
        peersByAccount = mapOf(remoteAccount to listOf(remoteDevice)),
        torByPeer = mutableMapOf(remoteDevice to TorEndpoint("handler-remote.onion", 80)),
    )

    private fun handler(stack: SyncRoutingStack, provider: RecordingSyncPayloadProvider) =
        SyncHandler(stack.ctx, stack.outboundMessenger, provider, stack.systemSender)

    @Test
    fun messagesFor_asksForTheRequestingDevicesAccount() = runTest {
        val stack = stack()
        val provider = RecordingSyncPayloadProvider(messages = listOf(textMsg()))

        val messages = handler(stack, provider).messagesFor(syncRequest(), sourceDevice = remoteDevice)

        assertEquals(1, messages.size)
        assertEquals(listOf(remoteAccount), provider.requesters)
    }

    @Test
    fun messagesFor_unknownDevice_isDeclinedWithoutAsking() = runTest {
        val stack = stack()
        val provider = RecordingSyncPayloadProvider(messages = listOf(textMsg()))

        val messages = handler(stack, provider).messagesFor(syncRequest(), sourceDevice = PeerId("stranger"))

        assertTrue(messages.isEmpty())
        assertTrue(provider.requests.isEmpty())
    }

    @Test
    fun reply_withMessages_sendsEachToSourcePeer() = runTest {
        val stack = stack()
        val handler = handler(stack, RecordingSyncPayloadProvider())

        // Responses route through the outbox; the dispatch loop must run
        // (production always runs it via router.start()).
        val loopJob = stack.outboxProcessor.runIn(this)
        try {
            handler.reply(syncRequest(), remoteDevice, listOf(textMsg(), textMsg()))

            withTimeout(10.seconds) { stack.tor.awaitMessageSendCount(2) }
            assertEquals(2, stack.outbox.enqueued.size)
            assertTrue(stack.outbox.enqueued.all { it.target == remoteDevice })
        } finally {
            loopJob.cancel()
        }
    }

    @Test
    fun reply_withNoMessages_sendsOnlyTheLegacySyncNack() = runTest {
        val stack = stack()

        handler(stack, RecordingSyncPayloadProvider()).reply(syncRequest(), remoteDevice, emptyList())

        assertEquals(0, stack.outbox.enqueued.size)
        assertEquals(1, stack.tor.sends.size)
    }

    @Test
    fun inboundRequest_askingForADisposition_isNackedDeclinedWhenNotServed() = runTest {
        val local = stack()
        val remote = buildSyncRoutingStack(
            localDevice = testDeviceIdentity(remoteDevice),
            peersByAccount = mapOf(localAccount to listOf(localDevice)),
        )
        val inbound = SystemInboundHandler(
            local.ctx,
            MutableSharedFlow(),
            handler(local, RecordingSyncPayloadProvider(messages = emptyList())),
        )

        val result = inbound.handle(remote.systemSender.buildForOutbox(localDevice, syncRequest(), 90.seconds))

        assertEquals(PacketNackReason.DECLINED, assertIs<InboundHandleResult.Rejected>(result).reason)
    }

    @Test
    fun inboundRequest_served_isAckedWithTheReplyAsSideEffect() = runTest {
        val local = stack()
        val remote = buildSyncRoutingStack(
            localDevice = testDeviceIdentity(remoteDevice),
            peersByAccount = mapOf(localAccount to listOf(localDevice)),
        )
        val served = listOf(textMsg())
        val inbound = SystemInboundHandler(
            local.ctx,
            MutableSharedFlow(),
            handler(local, RecordingSyncPayloadProvider(messages = served)),
        )

        val result = inbound.handle(remote.systemSender.buildForOutbox(localDevice, syncRequest(), 90.seconds))

        val reply =
            assertIs<InboundSideEffect.SyncRequested>(assertIs<InboundHandleResult.Success>(result).sideEffects.single())
        assertEquals(remoteDevice, reply.peerId)
        assertEquals(served, reply.messages)
    }
}
