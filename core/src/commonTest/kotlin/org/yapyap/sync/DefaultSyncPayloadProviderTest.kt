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
import kotlin.time.Instant
import kotlin.uuid.Uuid

class DefaultSyncPayloadProviderTest {

    private val roomId = RoomId(Uuid.random())
    private val remoteAccount = AccountId("remote-account")
    private val remoteDevice = PeerId("remote-device")

    private val messageRepo = FakeMessageRepository()
    private val config = MutableStateFlow(RouterConfig())
    private val provider = DefaultSyncPayloadProvider(messageRepo, config)

    private var tick = 0L

    private fun textMsg(
        prevIds: List<Uuid>,
        createdAt: Instant = epochSeconds(tick++),
    ): MessagePayload.Text =
        MessagePayload.Text(
            messageId = Uuid.random(),
            roomId = roomId,
            senderAccountId = remoteAccount,
            authorDeviceId = remoteDevice,
            authorSignature = byteArrayOf(1),
            prevIds = prevIds,
            createdAt = createdAt,
            text = "m",
        )

    private suspend fun seed(msg: MessagePayload.Text) {
        messageRepo.insert(msg, isOrphaned = false, ancestryComplete = true, verificationState = VerificationState.VERIFIED)
    }

    private fun syncRequest(missingIds: List<Uuid>, knownIds: List<Uuid>): SystemPayload.SyncRequest =
        SystemPayload.SyncRequest(
            roomId = roomId,
            syncId = Uuid.random(),
            missingIds = missingIds,
            knownIds = knownIds,
        )

    @Test
    fun servesTargetPlusAncestryDownToKnownIds_parentsBeforeChildren() = runTest {
        val m0 = textMsg(prevIds = emptyList())
        val m1 = textMsg(prevIds = listOf(m0.messageId))
        val m2 = textMsg(prevIds = listOf(m1.messageId))
        seed(m0); seed(m1); seed(m2)

        val result = provider.getMessages(syncRequest(missingIds = listOf(m2.messageId), knownIds = listOf(m0.messageId)))

        assertEquals(listOf(m1.messageId, m2.messageId), result.map { it.messageId })
    }

    @Test
    fun stopsAtKnownFrontier_servesOnlyAboveIt() = runTest {
        val m0 = textMsg(prevIds = emptyList())
        val m1 = textMsg(prevIds = listOf(m0.messageId))
        val m2 = textMsg(prevIds = listOf(m1.messageId))
        seed(m0); seed(m1); seed(m2)

        val result = provider.getMessages(syncRequest(missingIds = listOf(m2.messageId), knownIds = listOf(m1.messageId)))

        assertEquals(listOf(m2.messageId), result.map { it.messageId })
    }

    @Test
    fun emptyKnownIds_servesDownToGenesis() = runTest {
        val m0 = textMsg(prevIds = emptyList())
        val m1 = textMsg(prevIds = listOf(m0.messageId))
        val m2 = textMsg(prevIds = listOf(m1.messageId))
        seed(m0); seed(m1); seed(m2)

        val result = provider.getMessages(syncRequest(missingIds = listOf(m2.messageId), knownIds = emptyList()))

        assertEquals(listOf(m0.messageId, m1.messageId, m2.messageId), result.map { it.messageId })
    }

    @Test
    fun branchParents_bothLandBeforeChild() = runTest {
        val m0 = textMsg(prevIds = emptyList())
        val m1 = textMsg(prevIds = listOf(m0.messageId))
        val m2 = textMsg(prevIds = listOf(m0.messageId))
        val m3 = textMsg(prevIds = listOf(m1.messageId, m2.messageId))
        seed(m0); seed(m1); seed(m2); seed(m3)

        val result = provider.getMessages(syncRequest(missingIds = listOf(m3.messageId), knownIds = listOf(m0.messageId)))

        val ids = result.map { it.messageId }
        assertEquals(3, ids.size)
        assertEquals(m3.messageId, ids.last())
        assertTrue(ids.subList(0, 2).toSet() == setOf(m1.messageId, m2.messageId))
    }

    @Test
    fun unknownTarget_returnsEmptyList() = runTest {
        val result = provider.getMessages(syncRequest(missingIds = listOf(Uuid.random()), knownIds = emptyList()))

        assertTrue(result.isEmpty())
    }

    @Test
    fun otherRoomMessages_areSkipped() = runTest {
        val otherRoom = RoomId(Uuid.random())
        val foreign = textMsg(prevIds = emptyList()).copy(roomId = otherRoom)
        seed(foreign)

        val result = provider.getMessages(syncRequest(missingIds = listOf(foreign.messageId), knownIds = emptyList()))

        assertTrue(result.isEmpty())
    }

    @Test
    fun ownGaps_yieldNothingWithoutBlockingTheRest() = runTest {
        // The responder is missing m1 itself: m2 is still served (its absent parent
        // simply yields nothing), and the requester re-chases m1 elsewhere.
        val m0 = textMsg(prevIds = emptyList())
        val m2 = textMsg(prevIds = listOf(Uuid.random()))
        seed(m0); seed(m2)

        val result = provider.getMessages(syncRequest(missingIds = listOf(m2.messageId), knownIds = listOf(m0.messageId)))

        assertEquals(listOf(m2.messageId), result.map { it.messageId })
    }

    @Test
    fun respectsMaxMessagesLimit() = runTest {
        val m0 = textMsg(prevIds = emptyList())
        val m1 = textMsg(prevIds = listOf(m0.messageId))
        val m2 = textMsg(prevIds = listOf(m1.messageId))
        val m3 = textMsg(prevIds = listOf(m2.messageId))
        seed(m0); seed(m1); seed(m2); seed(m3)

        val limitedProvider = DefaultSyncPayloadProvider(
            messageRepo,
            MutableStateFlow(RouterConfig(syncMaxMessages = 2)),
        )
        val result = limitedProvider.getMessages(syncRequest(missingIds = listOf(m3.messageId), knownIds = emptyList()))

        assertEquals(2, result.size)
    }
}
