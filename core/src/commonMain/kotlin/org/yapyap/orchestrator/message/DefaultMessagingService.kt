package org.yapyap.orchestrator.message

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.logging.AppLogger
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.logging.NoopAppLogger
import org.yapyap.orchestrator.dag.DagEngine
import org.yapyap.orchestrator.dag.Gap
import org.yapyap.orchestrator.dag.IngestResult
import org.yapyap.orchestrator.dag.MessageDraft
import org.yapyap.persistence.room.RoomMembershipRepository
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.router.Router
import org.yapyap.routing.router.SendFailureKind
import org.yapyap.routing.router.SendMessageResult
import org.yapyap.routing.router.SendMessageStatus
import kotlin.coroutines.cancellation.CancellationException

class DefaultMessagingService(
    private val dagEngine: DagEngine,
    private val router: Router,
    private val roomMembershipRepository: RoomMembershipRepository,
    private val identityResolver: IdentityResolver,
    private val logger: AppLogger = NoopAppLogger,
) : MessagingService {

    private val roomMessages = mutableMapOf<String, MutableStateFlow<List<MessageDisplayItem>>>()
    private val roomGaps = mutableMapOf<String, MutableStateFlow<List<Gap>>>()
    private val roomMessageLists = mutableMapOf<String, MutableList<MessagePayload>>()
    private val orphanedMessageIds = mutableSetOf<String>()
    private var inboundJob: Job? = null

    override suspend fun sendTextMessage(
        roomId: String,
        text: String,
    ): SendMessageResult {
        val payload = dagEngine.append(roomId, MessageDraft.Text(text))

        val localAccountId = identityResolver.getLocalAccountIdentityRecord().accountId
        val members = roomMembershipRepository.membersOfRoom(roomId)
            .filter { it != localAccountId }

        trackOutboundMessage(payload)

        if (members.isEmpty()) {
            logger.debug(
                component = LogComponent.MESSAGING,
                event = LogEvent.MESSAGE_NO_PEERS,
                message = "No room members to send to after self-exclusion",
                fields = mapOf(
                    "roomId" to roomId,
                    "messageId" to payload.messageId,
                ),
            )
            return SendMessageResult(
                status = SendMessageStatus.SUCCESS,
                peersTotal = 0,
                peersQueued = 0,
                failureKind = null,
            )
        }

        val results = coroutineScope {
            members.map { member ->
                async {
                    router.sendMessage(member, payload)
                }
            }.awaitAll()
        }

        logger.info(
            component = LogComponent.MESSAGING,
            event = LogEvent.OUTBOX_MESSAGE_QUEUED,
            message = "Outbound message sent to room members",
            fields = mapOf(
                "roomId" to roomId,
                "messageId" to payload.messageId,
                "memberCount" to members.size,
            ),
        )

        return aggregateRoomSendResults(results)
    }

    override fun startInboundCollection(scope: CoroutineScope) {
        check(inboundJob == null) { "Inbound collection already started" }
        inboundJob = scope.launch {
            router.incomingMessages.collect { payload ->
                runCatching {
                    val result = dagEngine.ingest(payload)
                    when (result) {
                        is IngestResult.Inserted -> {
                            trackInboundMessage(payload)
                            refreshRoomGaps(payload.roomId)
                        }
                        is IngestResult.BecameOrphan -> {
                            trackInboundMessage(payload)
                            refreshRoomGaps(payload.roomId)
                            //TODO sync request
                            logger.info(
                                component = LogComponent.MESSAGING,
                                event = LogEvent.OUTBOX_MESSAGE_QUEUED,
                                message = "Inbound message became orphan",
                                fields = mapOf(
                                    "messageId" to payload.messageId,
                                    "roomId" to payload.roomId,
                                    "missingPrevId" to result.missingPrevId,
                                ),
                            )
                        }
                        is IngestResult.Duplicate -> {
                            logger.debug(
                                component = LogComponent.MESSAGING,
                                event = LogEvent.PACKET_DUPLICATED,
                                message = "Duplicate inbound message ignored",
                                fields = mapOf(
                                    "messageId" to result.messageId,
                                    "roomId" to payload.roomId,
                                ),
                            )
                        }
                        is IngestResult.Rejected -> {
                            logger.warn(
                                component = LogComponent.MESSAGING,
                                event = LogEvent.ENVELOPE_HANDLE_FAILED,
                                message = "Inbound message rejected by DAG engine",
                                fields = mapOf(
                                    "messageId" to payload.messageId,
                                    "roomId" to payload.roomId,
                                    "reason" to result.reason,
                                ),
                            )
                        }
                    }
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    logger.error(
                        component = LogComponent.MESSAGING,
                        event = LogEvent.ENVELOPE_HANDLE_FAILED,
                        message = "Failed to ingest inbound message",
                        fields = mapOf(
                            "messageId" to payload.messageId,
                            "roomId" to payload.roomId,
                        ),
                        throwable = e,
                    )
                }
            }
        }
    }

    override fun messagesInRoom(roomId: String): Flow<List<MessageDisplayItem>> {
        return getOrCreateRoomMessagesFlow(roomId).asStateFlow()
    }

    override fun gapsInRoom(roomId: String): Flow<List<Gap>> {
        return getOrCreateRoomGapsFlow(roomId).asStateFlow()
    }

    private fun getOrCreateRoomMessagesFlow(roomId: String): MutableStateFlow<List<MessageDisplayItem>> =
        roomMessages.getOrPut(roomId) { MutableStateFlow(emptyList()) }

    private fun getOrCreateRoomGapsFlow(roomId: String): MutableStateFlow<List<Gap>> =
        roomGaps.getOrPut(roomId) { MutableStateFlow(emptyList()) }

    private fun trackOutboundMessage(payload: MessagePayload) {
        val list = roomMessageLists.getOrPut(payload.roomId) { mutableListOf() }
        list.add(payload)
        getOrCreateRoomMessagesFlow(payload.roomId).value = list.map { it.toDisplayItem() }
    }

    private fun trackInboundMessage(payload: MessagePayload) {
        val list = roomMessageLists.getOrPut(payload.roomId) { mutableListOf() }
        if (list.none { it.messageId == payload.messageId }) {
            list.add(payload)
            getOrCreateRoomMessagesFlow(payload.roomId).value = list.map { it.toDisplayItem() }
        }
    }

    private suspend fun refreshRoomGaps(roomId: String) {
        val gaps = dagEngine.openGaps(roomId)
        getOrCreateRoomGapsFlow(roomId).value = gaps

        val roomOrphanIds = gaps.map { it.orphanedMessageId }.toSet()
        val changed = orphanedMessageIds != roomOrphanIds
        orphanedMessageIds.clear()
        orphanedMessageIds.addAll(roomOrphanIds)

        if (changed) {
            val list = roomMessageLists[roomId]
            if (list != null) {
                getOrCreateRoomMessagesFlow(roomId).value = list.map { it.toDisplayItem() }
            }
        }
    }

    private fun aggregateRoomSendResults(results: List<SendMessageResult>): SendMessageResult {
        val totalPeers = results.sumOf { it.peersTotal }
        val totalQueued = results.sumOf { it.peersQueued }
        val allStatuses = results.map { it.status }

        val aggregatedStatus = when {
            allStatuses.all { it == SendMessageStatus.SUCCESS } -> SendMessageStatus.SUCCESS
            allStatuses.all { it == SendMessageStatus.FAILURE } -> SendMessageStatus.FAILURE
            else -> SendMessageStatus.PARTIAL
        }

        val aggregatedFailureKind = when (aggregatedStatus) {
            SendMessageStatus.SUCCESS -> null
            SendMessageStatus.FAILURE -> {
                results.firstOrNull { it.failureKind != null }?.failureKind
                    ?: SendFailureKind.MIXED
            }
            SendMessageStatus.PARTIAL -> SendFailureKind.MIXED
        }

        return SendMessageResult(
            status = aggregatedStatus,
            peersTotal = totalPeers,
            peersQueued = totalQueued,
            failureKind = aggregatedFailureKind,
        )
    }

    private fun MessagePayload.toDisplayItem(): MessageDisplayItem = when (this) {
        is MessagePayload.Text -> MessageDisplayItem(
            accountId = AccountId(senderAccountId),
            text = text,
            lamportClock = lamportClock,
            isOrphaned = messageId in orphanedMessageIds,
        )
        is MessagePayload.GlobalEvent -> MessageDisplayItem(
            accountId = AccountId(senderAccountId),
            text = "[Global Event]",
            lamportClock = lamportClock,
            isOrphaned = messageId in orphanedMessageIds,
        )
        //TODO process global events
    }
}
