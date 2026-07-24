package org.yapyap.orchestrator.message

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.logging.AppLogger
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.logging.NoopAppLogger
import org.yapyap.orchestrator.dag.DagEngine
import org.yapyap.orchestrator.dag.IngestResult
import org.yapyap.orchestrator.dag.MessageDraft
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.persistence.messaging.RoomMembershipRepository
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.router.Router
import org.yapyap.routing.router.SendFailureKind
import org.yapyap.routing.router.SendMessageResult
import org.yapyap.routing.router.SendMessageStatus
import org.yapyap.time.EpochSecondsProvider
import kotlin.concurrent.Volatile

class DefaultMessagingService(
    private val dagEngine: DagEngine,
    private val router: Router,
    private val pipeline: InboundMessagePipeline,
    private val roomMembershipRepository: RoomMembershipRepository,
    private val identityResolver: IdentityResolver,
    private val timeProvider: EpochSecondsProvider,
    private val logger: AppLogger = NoopAppLogger,
) : MessagingService {

    private val incomingMessageEventFlow = MutableSharedFlow<IncomingMessageEvent>(replay = 0, extraBufferCapacity = 64)
    override val incomingMessageEvents = incomingMessageEventFlow.asSharedFlow()

    private val openWindows = mutableMapOf<String, MutableSet<DefaultRoomMessageWindow>>()
    private var subscriptionJob: Job? = null

    override fun start(scope: CoroutineScope) {
        check(subscriptionJob == null) { "MessagingService already started" }
        subscriptionJob = scope.launch {
            pipeline.ingestResults.collect { result ->
                when (result) {
                    is IngestResult.Inserted -> {
                        if (result.payload is MessagePayload.GlobalEvent) return@collect
                        notifyWindowsNewItem(result.payload.roomId, result.payload, result.closedGapMissingPrevIds)
                        emitIncomingEventIfNeeded(result.payload)
                    }
                    is IngestResult.BecameOrphan -> {
                        if (result.payload is MessagePayload.GlobalEvent) return@collect
                        notifyWindowsNewItem(result.payload.roomId, result.payload, result.closedGapMissingPrevIds)
                        emitIncomingEventIfNeeded(result.payload)
                        //TODO sync request
                        logger.info(
                            component = LogComponent.MESSAGING,
                            event = LogEvent.OUTBOX_MESSAGE_QUEUED,
                            message = "Inbound message became orphan",
                            fields = mapOf(
                                "messageId" to result.payload.messageId,
                                "roomId" to result.payload.roomId,
                                "missingPrevId" to result.missingPrevId,
                            ),
                        )
                    }
                    is IngestResult.Duplicate -> {
                        logger.debug(
                            component = LogComponent.MESSAGING,
                            event = LogEvent.PACKET_DUPLICATED,
                            message = "Duplicate inbound message ignored",
                            fields = mapOf("messageId" to result.messageId),
                        )
                    }
                    is IngestResult.Rejected -> {
                        logger.warn(
                            component = LogComponent.MESSAGING,
                            event = LogEvent.ENVELOPE_HANDLE_FAILED,
                            message = "Inbound message rejected by DAG engine",
                            fields = mapOf("reason" to result.reason),
                        )
                    }
                }
            }
        }
    }

    override suspend fun sendTextMessage(
        roomId: String,
        text: String,
    ): SendMessageResult {
        val payload = dagEngine.append(roomId, MessageDraft.Text(text))

        val localAccountId = identityResolver.getLocalAccountId()
        val members = roomMembershipRepository.membersOfRoom(roomId)
            .filter { it != localAccountId }

        notifyWindowsNewItem(payload.roomId, payload, closedGapMissingPrevIds = emptyList())

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

    override fun openRoom(roomId: String, initialPageSize: Int): RoomMessageWindow {
        val window = DefaultRoomMessageWindow(roomId, initialPageSize)
        openWindows.getOrPut(roomId) { mutableSetOf() }.add(window)
        return window
    }

    private fun notifyWindowsNewItem(
        roomId: String,
        payload: MessagePayload,
        closedGapMissingPrevIds: List<String>,
    ) {
        val windows = openWindows[roomId] ?: return
        val displayItem = payload.toDisplayItem() ?: return
        for (window in windows) {
            window.onNewItem(displayItem, closedGapMissingPrevIds)
        }
    }

    private suspend fun emitIncomingEventIfNeeded(payload: MessagePayload) {
        when(payload) {
            is MessagePayload.Text -> {
                val localAccountId = identityResolver.getLocalAccountId()
                if (payload.senderAccountId == localAccountId.id) return

                val preview = if (payload.text.length > 80) {
                    payload.text.take(80) + "\u2026"
                } else {
                    payload.text
                }

                incomingMessageEventFlow.emit(
                    IncomingMessageEvent(
                        roomId = payload.roomId,
                        senderAccountId = AccountId(payload.senderAccountId),
                        messagePreview = preview,
                        timestamp = timeProvider.nowEpochSeconds(),
                    )
                )
            }
            else -> TODO("Handle other message types")
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

    private fun MessagePayload.toDisplayItem(): MessageDisplayItem? = when (this) {
        is MessagePayload.Text -> MessageDisplayItem.Text(
            accountId = AccountId(senderAccountId),
            timestamp = timeProvider.nowEpochSeconds(),
            displayOrderId = lamportClock,
            text = text,
        )
        else -> null
    }

    private inner class DefaultRoomMessageWindow(
        private val roomId: String,
        private val initialPageSize: Int,
    ) : RoomMessageWindow {

        private val _displayItems = MutableStateFlow<List<MessageDisplayItem>>(emptyList())
        override val displayItems: StateFlow<List<MessageDisplayItem>> = _displayItems.asStateFlow()

        private val _hasMoreOlder = MutableStateFlow(false)
        override val hasMoreOlder: StateFlow<Boolean> = _hasMoreOlder.asStateFlow()

        @Volatile
        private var closed = false

        @Volatile
        private var oldestLamport: Long? = null

        init {
            runBlocking { loadInitial() }
        }

        private suspend fun loadInitial() {
            val messages = dagEngine.getMessagesInRoom(roomId, initialPageSize)
            if (messages.isEmpty()) {
                _hasMoreOlder.value = false
                return
            }

            oldestLamport = messages.first().lamportClock
            _hasMoreOlder.value = messages.size >= initialPageSize
            _displayItems.value = buildDisplayList(messages)
        }

        override suspend fun loadOlder(pageSize: Int): Int {
            val cursor = oldestLamport ?: return 0

            val messages = dagEngine.getMessagesInRoom(roomId, pageSize, beforeLamport = cursor)
            if (messages.isEmpty()) {
                _hasMoreOlder.value = false
                return 0
            }

            oldestLamport = messages.first().lamportClock
            _hasMoreOlder.value = messages.size >= pageSize

            val olderItems = buildDisplayList(messages)
            _displayItems.value = olderItems + _displayItems.value

            return messages.size
        }

        override fun close() {
            if (closed) return
            closed = true
            openWindows[roomId]?.remove(this)
            if (openWindows[roomId]?.isEmpty() == true) {
                openWindows.remove(roomId)
            }
        }

        fun onNewItem(item: MessageDisplayItem, closedGapMissingPrevIds: List<String>) {
            if (closed) return
            val current = _displayItems.value.toMutableList()

            if (closedGapMissingPrevIds.isNotEmpty()) {
                val closedSet = closedGapMissingPrevIds.toSet()
                current.removeAll {
                    it is MessageDisplayItem.Gap && it.missingPrevId in closedSet
                }
            }

            val insertIdx = current.indexOfFirst { it.displayOrderId > item.displayOrderId }
            if (insertIdx == -1) {
                current.add(item)
            } else {
                current.add(insertIdx, item)
            }

            _displayItems.value = current
        }

        private suspend fun buildDisplayList(messages: List<MessagePayload>): List<MessageDisplayItem> {
            val gaps = dagEngine.openGaps(roomId)
            val gapsByOrphanId = gaps.associateBy { it.orphanedMessageId }

            val items = mutableListOf<MessageDisplayItem>()
            for (msg in messages) {
                if (msg !is MessagePayload.Text) continue

                items.add(
                    MessageDisplayItem.Text(
                        accountId = AccountId(msg.senderAccountId),
                        timestamp = timeProvider.nowEpochSeconds(),
                        displayOrderId = msg.lamportClock,
                        text = msg.text,
                    )
                )

                val orphanedGap = gapsByOrphanId[msg.messageId]
                if (orphanedGap != null) {
                    items.add(
                        MessageDisplayItem.Gap(
                            accountId = AccountId(msg.senderAccountId),
                            timestamp = timeProvider.nowEpochSeconds(),
                            displayOrderId = msg.lamportClock,
                            missingPrevId = orphanedGap.missingPrevId,
                        )
                    )
                }
            }
            return items
        }
    }
}
