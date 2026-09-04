package org.yapyap.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.AccountId
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.persistence.db.VerificationState
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.sync.DefaultSyncPayloadProvider
import org.yapyap.testfixtures.FakeMessageRepository
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DefaultSyncPayloadProviderTest {

    private val roomId = RoomId(Uuid.random())
    private val remoteAccount = AccountId("remote-account")
    private val remoteDevice = PeerId("remote-device")

    private val messageRepo = FakeMessageRepository()
    private val config = MutableStateFlow(RouterConfig())
    private val provider = DefaultSyncPayloadProvider(messageRepo, config)

    private fun textMsg(lamport: Long): MessagePayload.Text =
        MessagePayload.Text(
            messageId = Uuid.random(),
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDevice,
            authorSignature = byteArrayOf(1),
            prevId = null,
            lamportClock = lamport,
            createdAt = epochSeconds(0L),
            text = "m$lamport",
        )

    private suspend fun seed(vararg lamports: Long) {
        lamports.forEach { messageRepo.insert(textMsg(it), isOrphaned = false, verificationState = VerificationState.VERIFIED) }
    }

    private fun syncRequest(anchor: Long, orphan: Long): SystemPayload.SyncRequest =
        SystemPayload.SyncRequest(
            roomId = roomId,
            syncId = Uuid.random(),
            anchorLamport = anchor,
            orphanLamport = orphan,
        )

    @Test
    fun singleMessageAtAnchor_usesOpenLowerBound() = runTest {
        seed(5L, 6L, 7L)

        val result = provider.getMessages(syncRequest(anchor = 5L, orphan = 7L))

        assertEquals(listOf(6L, 7L), result.map { it.lamportClock })
    }

    @Test
    fun multipleMessagesAtAnchor_usesClosedLowerBound() = runTest {
        messageRepo.insert(textMsg(5L), isOrphaned = false, verificationState = VerificationState.VERIFIED)
        messageRepo.insert(textMsg(5L), isOrphaned = false, verificationState = VerificationState.VERIFIED)
        seed(6L, 7L)

        val result = provider.getMessages(syncRequest(anchor = 5L, orphan = 7L))

        assertEquals(listOf(5L, 5L, 6L, 7L), result.map { it.lamportClock })
    }

    @Test
    fun respectsMaxMessagesLimit() = runTest {
        seed(5L, 6L, 7L, 8L)

        val limitedProvider = DefaultSyncPayloadProvider(
            messageRepo,
            MutableStateFlow(RouterConfig(syncMaxMessages = 2)),
        )
        val result = limitedProvider.getMessages(syncRequest(anchor = 5L, orphan = 8L))

        assertEquals(2, result.size)
    }

    @Test
    fun emptyRange_returnsEmptyList() = runTest {
        val result = provider.getMessages(syncRequest(anchor = 5L, orphan = 7L))

        assertTrue(result.isEmpty())
    }
}
