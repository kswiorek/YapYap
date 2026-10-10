package org.yapyap.sync

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.routing.sync.SyncHandler
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class SyncHandlerTest {

    private val localDevice = PeerId("handler-local-device")
    private val remoteDevice = PeerId("handler-remote-device")
    private val remoteAccount = AccountId("handler-remote-account")
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

    @Test
    fun onSyncRequested_withMessages_sendsEachToSourcePeer() = runTest {
        val stack = buildSyncRoutingStack(
            localDevice = testDeviceIdentity(localDevice),
            peersByAccount = mapOf(remoteAccount to listOf(remoteDevice)),
            torByPeer = mutableMapOf(remoteDevice to TorEndpoint("handler-remote.onion", 80)),
        )
        val payloadProvider = RecordingSyncPayloadProvider(messages = listOf(textMsg(), textMsg()))
        val handler =
            SyncHandler(stack.outboundMessenger, payloadProvider, FakePendingSyncRepository(), stack.systemSender)

        // Responses route through the outbox; the dispatch loop must run
        // (production always runs it via router.start()).
        val loopJob = stack.outboxProcessor.runIn(this)
        try {
            handler.onSyncRequested(syncRequest(), sourceDevice = remoteDevice)

            withTimeout(10.seconds) { stack.tor.awaitMessageSendCount(2) }
            assertEquals(1, payloadProvider.requests.size)
            assertEquals(2, stack.outbox.enqueued.size)
            assertEquals(2, stack.tor.sends.size)
            assertTrue(stack.outbox.enqueued.all { it.target == remoteDevice })
        } finally {
            loopJob.cancel()
        }
    }

    @Test
    fun onSyncRequested_withNoMessages_sendsOnlySyncNack() = runTest {
        val stack = buildSyncRoutingStack(
            localDevice = testDeviceIdentity(localDevice),
            peersByAccount = mapOf(remoteAccount to listOf(remoteDevice)),
            torByPeer = mutableMapOf(remoteDevice to TorEndpoint("handler-remote.onion", 80)),
        )
        val payloadProvider = RecordingSyncPayloadProvider(messages = emptyList())
        val handler =
            SyncHandler(stack.outboundMessenger, payloadProvider, FakePendingSyncRepository(), stack.systemSender)

        handler.onSyncRequested(syncRequest(), sourceDevice = remoteDevice)

        assertEquals(0, stack.outbox.enqueued.size)
        assertEquals(1, stack.tor.sends.size)
    }

    @Test
    fun onMarkPeerAttempted_recordsPeerOnPendingSync() = runTest {
        val stack = buildSyncRoutingStack(localDevice = testDeviceIdentity(localDevice))
        val pendingRepo = FakePendingSyncRepository()
        val syncId = Uuid.random()
        pendingRepo.insertSync(
            syncId = syncId, roomId = roomId,
            targetMessageId = Uuid.random(),
            candidateAccounts = listOf(remoteAccount), nextAttemptAt = epochSeconds(1_000L),
        )
        val handler =
            SyncHandler(stack.outboundMessenger, RecordingSyncPayloadProvider(), pendingRepo, stack.systemSender)

        handler.onMarkPeerAttempted(syncId, peerId = remoteDevice)

        assertEquals(setOf(remoteDevice), pendingRepo.getAttemptedDevices(syncId))
    }
}
