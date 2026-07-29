package org.yapyap.orchestrator.message

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.logging.AppLogger
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.logging.NoopAppLogger
import org.yapyap.orchestrator.dag.DagEngine
import org.yapyap.orchestrator.dag.Gap
import org.yapyap.orchestrator.dag.IngestResult
import org.yapyap.orchestrator.dag.MessageDraft
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.persistence.messaging.MessageCursor
import org.yapyap.persistence.messaging.RoomMembershipRepository
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.router.Router
import org.yapyap.routing.router.SendFailureKind
import org.yapyap.routing.router.SendMessageResult
import org.yapyap.routing.router.SendMessageStatus
import org.yapyap.time.EpochSecondsProvider
import kotlin.concurrent.Volatile
import kotlin.uuid.Uuid

internal class DefaultMessagingService(
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

    /**
     * Map of open windows per room. Guarded by [windowsMapMutex].
     */
    private val windowsMapMutex = Mutex()
    private val openWindows = mutableMapOf<String, DefaultRoomMessageWindow>()

    @Volatile
    private var serviceScope: CoroutineScope? = null
    private var subscriptionJob: Job? = null

    fun start(scope: CoroutineScope) {
        check(subscriptionJob == null) { "MessagingService already started" }
        serviceScope = scope
        subscriptionJob = scope.launch {
            pipeline.ingestResults.collect { result ->
                if (result.payload is MessagePayload.GlobalEvent) return@collect
                notifyWindowsNewItem(result)
                emitIncomingEventIfNeeded(result.payload)
            }
        }
    }

    suspend fun stop() {
        subscriptionJob?.cancel()
        subscriptionJob?.join()  // wait for pipeline collector to finish
        serviceScope = null
        val windows = windowsMapMutex.withLock {
            openWindows.values.toList()  // just snapshot, no clear
        }
        for (window in windows) {
            window.close()
        }
    }

    override suspend fun sendTextMessage(
        roomId: String,
        text: String,
    ): SendMessageResult {
        val payload = dagEngine.append(roomId, MessageDraft.Text(text))

        val members = roomMembershipRepository.membersOfRoom(roomId)

        notifyWindowsNewItem(IngestResult.Inserted(payload))

        if (members.isEmpty()) {
            logger.debug(
                component = LogComponent.MESSAGING,
                event = LogEvent.MESSAGE_NO_PEERS,
                message = "No room members to send to",
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

    override suspend fun openRoom(roomId: String, initialPageSize: Int): RoomMessageWindow {
        windowsMapMutex.withLock {
            check(openWindows[roomId] == null) {
                "Room $roomId is already open; close it first"
            }
            val window = DefaultRoomMessageWindow(roomId, initialPageSize, serviceScope ?: error("MessagingService not started"))
            openWindows[roomId] = window
            return window
        }
    }

    private suspend fun notifyWindowsNewItem(result: IngestResult) {
        val payload = result.payload
        val roomId = payload.roomId
        val window = windowsMapMutex.withLock { openWindows[roomId] } ?: return
        val displayItem = payload.toDisplayItem() ?: return
        val orphanGap: MessageDisplayItem.Gap? = (result as? IngestResult.BecameOrphan)?.let {
            MessageDisplayItem.Gap(
                accountId = payload.senderAccountId,
                timestamp = payload.createdAtEpochSeconds,
                displayOrderId = payload.lamportClock,
                missingPrevId = it.missingPrevId,
            )
        }
        window.onNewItem(displayItem, result.closedGapMissingPrevIds, orphanGap)
    }

    private suspend fun emitIncomingEventIfNeeded(payload: MessagePayload) {
        when(payload) {
            is MessagePayload.Text -> {
                val localAccountId = identityResolver.getLocalAccountId()
                if (payload.senderAccountId == localAccountId) return

                val preview = if (payload.text.length > 79) {
                    payload.text.take(79) + "\u2026"
                } else {
                    payload.text
                }

                incomingMessageEventFlow.emit(
                    IncomingMessageEvent(
                        roomId = payload.roomId,
                        senderAccountId = payload.senderAccountId,
                        messagePreview = preview,
                        timestamp = timeProvider.nowEpochSeconds(),
                    )
                )
            }
            else -> {}//TODO Handle other message types
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
            accountId = senderAccountId,
            timestamp = createdAtEpochSeconds,
            displayOrderId = lamportClock,
            text = text,
        )
        else -> null
    }

    private inner class DefaultRoomMessageWindow(
        private val roomId: String,
        private val initialPageSize: Int,
        scope: CoroutineScope,
    ) : RoomMessageWindow {

        private val _displayItems = MutableStateFlow<List<MessageDisplayItem>>(emptyList())
        override val displayItems: StateFlow<List<MessageDisplayItem>> = _displayItems.asStateFlow()

        private val _hasMoreOlder = MutableStateFlow(false)
        override val hasMoreOlder: StateFlow<Boolean> = _hasMoreOlder.asStateFlow()

        @Volatile
        private var closed = false

        /**
         * Cursor of the oldest currently-loaded row; the next page is loaded strictly below it.
         * Guarded by [windowMutex].
         */
        private var oldestCursor: MessageCursor? = null

        /**
         * Serializes read-modify-write of [_displayItems] / [oldestCursor] between
         * [loadOlder] (caller coroutine) and [onNewItem] (pipeline collector coroutine).
         */
        private val windowMutex = Mutex()

        init {
            // Load asynchronously; displayItems starts empty and populates once loaded.
            scope.launch { loadInitial() }
        }

        private suspend fun loadInitial() {
            // DagEngine returns newest -> oldest; display is oldest -> newest.
            val page = dagEngine.getMessagesInRoom(roomId, initialPageSize)
            if (page.isEmpty()) {
                _hasMoreOlder.value = false
                return
            }
            val gapsByOrphanId = dagEngine.openGaps(roomId).associateBy { it.orphanedMessageId }
            windowMutex.withLock {
                val oldest = page.last()
                oldestCursor = MessageCursor(
                    createdAtEpochSeconds = oldest.createdAtEpochSeconds,
                    lamportClock = oldest.lamportClock,
                    messageId = oldest.messageId,
                )
                _hasMoreOlder.value = page.size >= initialPageSize
                _displayItems.value = buildDisplayList(page.asReversed(), gapsByOrphanId)
            }
        }

        override suspend fun loadOlder(pageSize: Int): Int {
            if (closed) return 0
            val cursor = windowMutex.withLock { oldestCursor } ?: return 0

            val page = dagEngine.getMessagesInRoom(roomId, pageSize, before = cursor)
            if (page.isEmpty()) {
                _hasMoreOlder.value = false
                return 0
            }

            val gapsByOrphanId = dagEngine.openGaps(roomId).associateBy { it.orphanedMessageId }
            return windowMutex.withLock {
                val oldest = page.last()
                oldestCursor = MessageCursor(
                    createdAtEpochSeconds = oldest.createdAtEpochSeconds,
                    lamportClock = oldest.lamportClock,
                    messageId = oldest.messageId,
                )
                _hasMoreOlder.value = page.size >= pageSize

                val olderItems = buildDisplayList(page.asReversed(), gapsByOrphanId)
                _displayItems.value = olderItems + _displayItems.value

                page.size
            }
        }

        override suspend fun close() {
            if (closed) return
            closed = true
            windowsMapMutex.withLock {
                if (openWindows[roomId] === this) {
                    openWindows.remove(roomId)
                }
            }
        }

        suspend fun onNewItem(
            item: MessageDisplayItem,
            closedGapMissingPrevIds: List<Uuid>,
            orphanGap: MessageDisplayItem.Gap?,
        ) {
            if (closed) return
            windowMutex.withLock {
                val current = _displayItems.value.toMutableList()

                if (closedGapMissingPrevIds.isNotEmpty()) {
                    val closedSet = closedGapMissingPrevIds.toSet()
                    current.removeAll {
                        it is MessageDisplayItem.Gap && it.missingPrevId in closedSet
                    }
                }

                // Display list is oldest -> newest; insert before the first item newer than [item].
                // Composite tie-break: (timestamp, displayOrderId, accountId.id) — matches the DB
                // display-order index, which uses messageId as the final tiebreak. Same-sender
                // messages never share a lamport clock, so (timestamp, lamport, accountId) is unique
                // among display items and messageId is not needed here.
                val insertIdx = current.indexOfFirst { it.isDisplayAfter(item) }
                if (insertIdx == -1) {
                    current.add(item)
                    if (orphanGap != null) current.add(orphanGap)
                } else {
                    current.add(insertIdx, item)
                    if (orphanGap != null) current.add(insertIdx + 1, orphanGap)
                }

                _displayItems.value = current
            }
        }

        /**
         * True if `this` is strictly newer than [other] per the composite display order
         * `(createdAtEpochSeconds, lamportClock, accountId)`.
         */
        private fun MessageDisplayItem.isDisplayAfter(other: MessageDisplayItem): Boolean {
            if (timestamp != other.timestamp) return timestamp > other.timestamp
            if (displayOrderId != other.displayOrderId) return displayOrderId > other.displayOrderId
            return accountId.id > other.accountId.id
        }

        private fun buildDisplayList(
            messages: List<MessagePayload>,
            gapsByOrphanId: Map<Uuid, Gap>,
        ): List<MessageDisplayItem> {
            val items = mutableListOf<MessageDisplayItem>()
            for (msg in messages) {
                if (msg !is MessagePayload.Text) continue

                items.add(
                    MessageDisplayItem.Text(
                        accountId = msg.senderAccountId,
                        timestamp = msg.createdAtEpochSeconds,
                        displayOrderId = msg.lamportClock,
                        text = msg.text,
                    )
                )

                val orphanedGap = gapsByOrphanId[msg.messageId]
                if (orphanedGap != null) {
                    items.add(
                        MessageDisplayItem.Gap(
                            accountId = msg.senderAccountId,
                            timestamp = msg.createdAtEpochSeconds,
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
