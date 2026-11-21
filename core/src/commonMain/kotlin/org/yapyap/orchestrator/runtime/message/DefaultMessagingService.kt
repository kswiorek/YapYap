package org.yapyap.orchestrator.runtime.message

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.yapyap.crypto.identity.LocalIdentity
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.dag.DagEngine
import org.yapyap.orchestrator.dag.DagException
import org.yapyap.orchestrator.dag.IngestResult
import org.yapyap.orchestrator.dag.MessageDraft
import org.yapyap.orchestrator.dag.VerificationState
import org.yapyap.orchestrator.dag.VerificationStateChange
import org.yapyap.orchestrator.file.FileSendService
import org.yapyap.orchestrator.file.PrepareResult
import org.yapyap.orchestrator.pipeline.InboundMessagePipeline
import org.yapyap.orchestrator.store.CausalHoldRepository
import org.yapyap.orchestrator.store.MessageCursor
import org.yapyap.orchestrator.store.MessageDeliveryRepository
import org.yapyap.orchestrator.store.MessageRepository
import org.yapyap.orchestrator.store.RoomRepository
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.RoomMemberStatus
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.router.AccountPushReport
import org.yapyap.routing.router.MessageCarrier
import org.yapyap.routing.router.SyncCarrier
import org.yapyap.routing.router.TypingIndicatorEvent
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** How long a preparing send's refusal stays on its item before the item goes. */
private val PREPARING_ERROR_SHOWN = 5.seconds

internal class DefaultMessagingService(
    private val dagEngine: DagEngine,
    private val messageCarrier: MessageCarrier,
    syncCarrier: SyncCarrier,
    private val pipeline: InboundMessagePipeline,
    private val roomRepository: RoomRepository,
    private val messageRepository: MessageRepository,
    deliveryRepository: MessageDeliveryRepository,
    private val causalHoldRepository: CausalHoldRepository,
    private val localIdentity: LocalIdentity,
    private val maxTextBytes: StateFlow<Int>,
    private val orchestratorConfig: StateFlow<OrchestratorConfig>,
    private val fileSendService: FileSendService,
) : MessagingService {

    private val incomingMessageEventFlow = MutableSharedFlow<IncomingMessageEvent>(replay = 0, extraBufferCapacity = 64)
    override val incomingMessageEvents = incomingMessageEventFlow.asSharedFlow()

    private val deliveryTracker =
        DeliveryTracker(messageCarrier, syncCarrier, messageRepository, deliveryRepository, localIdentity)
    private var deliveryJobs: List<Job> = emptyList()
    override val deliveryUpdates: Flow<MessageDelivery> = deliveryTracker.updates

    override suspend fun deliveredTo(messageId: Uuid): Set<AccountId> = deliveryTracker.deliveredTo(messageId)

    override val maxTextMessageBytes: Int get() = maxTextBytes.value

    private val _preparingSends = MutableStateFlow<Map<Uuid, PreparingSend>>(emptyMap())
    override val preparingSends: StateFlow<Map<Uuid, PreparingSend>> = _preparingSends.asStateFlow()
    private val preparingJobs = mutableMapOf<Uuid, Job>()
    private val preparingMutex = Mutex()

    /**
     * Map of open windows per room. Guarded by [windowsMapMutex].
     */
    private val windowsMapMutex = Mutex()
    private val openWindows = mutableMapOf<RoomId, DefaultRoomMessageWindow>()

    // --- Typing: outbound announcements -------------------------------------------------

    /** Per-room announcement loops started by [setTyping]. Guarded by [typingSendMutex]. */
    private val typingSendMutex = Mutex()
    private val typingSendJobs = mutableMapOf<RoomId, Job>()

    // --- Typing: inbound state -----------------------------------------------------------

    private data class TypingKey(val roomId: RoomId, val accountId: AccountId)

    private val _typingState = MutableStateFlow<Map<RoomId, Set<AccountId>>>(emptyMap())
    override val typingState: StateFlow<Map<RoomId, Set<AccountId>>> = _typingState.asStateFlow()

    /**
     * Idle-timeout jobs keyed by (roomId, account). Guarded by [typingTimeoutMutex]. Each
     * received indicator replaces the previous job, so the account drops out of
     * [typingState] only after ~2x the announced cadence without a refresh.
     */
    private val typingTimeoutMutex = Mutex()
    private val typingTimeoutJobs = mutableMapOf<TypingKey, Job>()

    @Volatile
    private var serviceScope: CoroutineScope? = null
    private var subscriptionJob: Job? = null
    private var tmpCleanup: Job? = null
    private var verificationStateSubscriptionJob: Job? = null
    private var typingSubscriptionJob: Job? = null

    fun start(scope: CoroutineScope) {
        check(subscriptionJob == null) { "MessagingService already started" }
        serviceScope = scope
        deliveryJobs = deliveryTracker.start(scope)
        // Preparing sends wait for this, so it never deletes a tmp a new send is writing.
        tmpCleanup = scope.launch {
            try {
                fileSendService.cleanTmp()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_TASK_FAILED,
                    message = "Failed to clean partial copies on start",
                    throwable = e,
                )
            }
        }
        subscriptionJob = scope.launch {
            pipeline.ingestResults.collect { result ->
                // A REJECTED message is never shown: skip both the window insert and any notification.
                if (result.verificationState == VerificationState.REJECTED) return@collect
                // Single displayability gate: payloads with no display item
                // (control-plane events) stop here.
                val item = result.payload.toDisplayItem() ?: return@collect
                // Removal-boundary display policy (docs/room events.md §3), enforced
                // in SQL: hidden rows never reach the window or the notification event.
                if (!messageRepository.isRenderable(result.payload.roomId, result.payload.messageId)) return@collect
                notifyWindowsNewItem(result, item)
                emitIncomingEventIfNeeded(result.payload, item)
            }
        }
        verificationStateSubscriptionJob = scope.launch {
            dagEngine.verificationStateChanges.collect { change ->
                if (change.toState == VerificationState.REJECTED) {
                    notifyWindowsRejected(change)
                }
            }
        }
        typingSubscriptionJob = scope.launch {
            messageCarrier.typingIndicators.collect { event ->
                runCatching { onTypingIndicatorReceived(event) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        AppLog.error(
                            component = LogComponent.MESSAGING,
                            event = LogEvent.TYPING_INDICATOR_RECEIVED,
                            message = "Failed to process typing indicator",
                            fields = mapOf("roomId" to event.roomId, "error" to e.toString()),
                        )
                    }
            }
        }
    }

    suspend fun stop() {
        deliveryJobs.forEach { it.cancel() }
        deliveryJobs = emptyList()
        subscriptionJob?.cancel()
        subscriptionJob?.join()  // wait for pipeline collector to finish
        verificationStateSubscriptionJob?.cancel()
        verificationStateSubscriptionJob?.join()
        typingSubscriptionJob?.cancel()
        typingSubscriptionJob?.join()
        preparingMutex.withLock {
            preparingJobs.values.forEach { it.cancel() }
            preparingJobs.clear()
            _preparingSends.value = emptyMap()
        }
        typingSendMutex.withLock {
            typingSendJobs.values.forEach { it.cancel() }
            typingSendJobs.clear()
        }
        typingTimeoutMutex.withLock {
            typingTimeoutJobs.values.forEach { it.cancel() }
            typingTimeoutJobs.clear()
        }
        _typingState.value = emptyMap()
        serviceScope = null
        val windows = windowsMapMutex.withLock {
            openWindows.values.toList()  // just snapshot, no clear
        }
        for (window in windows) {
            window.close()
        }
    }

    override suspend fun setTyping(roomId: RoomId, isTyping: Boolean) {
        typingSendMutex.withLock {
            val existing = typingSendJobs[roomId]
            if (isTyping) {
                if (existing?.isActive == true) return  // already announcing
                val scope = serviceScope ?: error("MessagingService not started")
                typingSendJobs[roomId] = scope.launch { announceTyping(roomId) }
            } else {
                existing?.cancel()
                typingSendJobs.remove(roomId)
            }
        }
    }

    /**
     * Announces typing to the room's current members immediately, then re-announces every
     * configured interval until cancelled. Members are re-resolved each tick so membership
     * changes during a long composition are respected; the interval is re-read so hot config
     * reloads take effect.
     */
    private suspend fun announceTyping(roomId: RoomId) {
        while (true) {
            val interval = orchestratorConfig.value.typingIndicatorInterval
            val members = roomRepository.membersOfRoom(roomId)
            if (members.isNotEmpty()) {
                try {
                    messageCarrier.sendTypingIndicator(members, roomId, interval)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.warn(
                        component = LogComponent.MESSAGING,
                        event = LogEvent.TYPING_INDICATOR_DISPATCH_FAILED,
                        message = "Typing indicator announcement failed",
                        fields = mapOf("roomId" to roomId, "error" to e.toString()),
                    )
                }
            }
            delay(interval)
        }
    }

    private suspend fun onTypingIndicatorReceived(event: TypingIndicatorEvent) {
        val scope = serviceScope ?: return
        val localAccountId = localIdentity.getLocalAccountId()
        if (event.senderAccountId == localAccountId) return
        // Typing from anyone without an ACTIVE row (removed, never-member,
        // pre-fold) is ignored — the same boundary as the render policy, for an
        // ephemeral signal. Outbound needs no check: announceTyping fans out via
        // membersOfRoom, which is ACTIVE-only.
        if (roomRepository.memberRowOf(event.roomId, event.senderAccountId)?.status != RoomMemberStatus.ACTIVE) return

        val key = TypingKey(event.roomId, event.senderAccountId)
        // Idle-timeout at ~2x the announced cadence (tolerates one lost indicator).
        val timeoutMillis = event.interval * 2
        val timeoutJob = scope.launch {
            delay(timeoutMillis)
            onTypingTimedOut(key)
        }

        val previous = typingTimeoutMutex.withLock {
            _typingState.value += (event.roomId to ((_typingState.value[event.roomId]
                ?: emptySet()) + event.senderAccountId))
            typingTimeoutJobs.put(key, timeoutJob)
        }
        previous?.cancel()
    }

    private suspend fun onTypingTimedOut(key: TypingKey) {
        // Identity check: a newer indicator may have re-registered a fresh timeout job after
        // this one fired; only clear the account if our job is still the registered one.
        val self = currentCoroutineContext().job
        val cleared = typingTimeoutMutex.withLock {
            if (typingTimeoutJobs[key] !== self) return@withLock false
            typingTimeoutJobs.remove(key)
            true
        }
        if (!cleared) return
        _typingState.update { current ->
            val roomSet = current[key.roomId] ?: return@update current
            val newSet = roomSet - key.accountId
            if (newSet.isEmpty()) current - key.roomId else current + (key.roomId to newSet)
        }
    }

    override suspend fun sendMessage(
        roomId: RoomId,
        message: Message,
    ): SendMessageResult {
        val localAccountId = localIdentity.getLocalAccountId()
        if (roomRepository.memberRowOf(roomId, localAccountId)?.status == RoomMemberStatus.REMOVED) {
            AppLog.warn(
                component = LogComponent.MESSAGING,
                event = LogEvent.APPEND_REFUSED,
                message = "Text message not sent — local account removed from room",
                fields = mapOf("roomId" to roomId),
            )
            return SendMessageResult.Refused(SendRefusal.NotMember)
        }

        val textBytes = message.content.encodeToByteArray()
        if (textBytes.size > maxTextMessageBytes) {
            AppLog.warn(
                component = LogComponent.MESSAGING,
                event = LogEvent.SIZE_EXCEEDED,
                message = "Text message exceeds maximum size",
                fields = mapOf(
                    "roomId" to roomId,
                    "size" to textBytes.size,
                    "maxTextMessageBytes" to maxTextMessageBytes,
                ),
            )
            return SendMessageResult.Refused(SendRefusal.TooLarge)
        }

        // Check the attachment count before any import (design §10).
        if (message.pickedFiles.size > MessagePayload.Attachment.MAX_ATTACHMENTS) {
            return SendMessageResult.Refused(SendRefusal.TooLarge)
        }

        if (message.pickedFiles.isEmpty()) {
            return sendText(roomId, message.content, emptyList(), localAccountId)
        }

        // Async preparing (design §10): return at once with a local send id; hashing runs
        // afterwards and the message lands when every fileId is known.
        val sendId = Uuid.random()
        val scope = serviceScope ?: error("MessagingService not started")
        _preparingSends.update { it + (sendId to PreparingSend(sendId, roomId, 0L, 0L)) }
        val job = scope.launch {
            try {
                tmpCleanup?.join()
                val refusal = prepareAndSend(sendId, roomId, message, localAccountId)
                if (refusal != null) {
                    // Show the refusal on the preparing item for a moment, then drop it.
                    _preparingSends.update { cur ->
                        cur[sendId]?.let { cur + (sendId to it.copy(error = refusal)) } ?: cur
                    }
                    delay(PREPARING_ERROR_SHOWN)
                }
            } finally {
                withContext(NonCancellable) {
                    preparingMutex.withLock {
                        preparingJobs.remove(sendId)
                        _preparingSends.update { it - sendId }
                    }
                }
            }
        }
        preparingMutex.withLock { preparingJobs[sendId] = job }
        return SendMessageResult.Preparing(sendId)
    }

    /**
     * Hash and import the picked files, then append. Null when the message landed, else the
     * refusal to show. Copies are released on every path that does not land the message;
     * cancellation (the user cancelled) releases them too and propagates.
     */
    private suspend fun prepareAndSend(
        sendId: Uuid,
        roomId: RoomId,
        message: Message,
        localAccountId: AccountId,
    ): SendRefusal? {
        val prepared = try {
            fileSendService.prepare(message.pickedFiles) { hashed, total ->
                _preparingSends.update { cur ->
                    cur[sendId]?.let { cur + (sendId to it.copy(hashedBytes = hashed, totalBytes = total)) } ?: cur
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = "Preparing files for a send failed",
                throwable = e,
                fields = mapOf("roomId" to roomId),
            )
            return SendRefusal.SendFailed
        }
        val attachments = when (prepared) {
            is PrepareResult.Refused -> return prepared.refusal
            is PrepareResult.Ready -> prepared.attachments
        }
        val sent = try {
            sendText(roomId, message.content, attachments, localAccountId)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { fileSendService.release(attachments) }
            throw e
        } catch (e: Exception) {
            fileSendService.release(attachments)
            AppLog.error(
                component = LogComponent.MESSAGING,
                event = LogEvent.APPEND_REFUSED,
                message = "Send with files failed at the append",
                throwable = e,
                fields = mapOf("roomId" to roomId),
            )
            return SendRefusal.SendFailed
        }
        return when (sent) {
            is SendMessageResult.Sent -> {
                fileSendService.confirm(attachments)
                null
            }

            // sendText already released the copies.
            is SendMessageResult.Refused -> sent.reason
            is SendMessageResult.Preparing -> error("sendText never returns Preparing")
        }
    }

    override suspend fun cancelPreparingSend(sendId: Uuid) {
        val job = preparingMutex.withLock { preparingJobs.remove(sendId) }
        job?.cancel()
        preparingMutex.withLock { _preparingSends.update { it - sendId } }
    }

    private suspend fun sendText(
        roomId: RoomId,
        content: String,
        wireAttachments: List<MessagePayload.Attachment>,
        localAccountId: AccountId,
    ): SendMessageResult {
        val payload = try {
            dagEngine.append(roomId, MessageDraft.Text(content, wireAttachments))
        } catch (_: DagException.FrontierUnavailable) {
            AppLog.warn(
                component = LogComponent.MESSAGING,
                event = LogEvent.APPEND_REFUSED,
                message = "Text message not sent — room holds messages but the chainable frontier is empty",
                fields = mapOf("roomId" to roomId),
            )
            fileSendService.release(wireAttachments)
            return SendMessageResult.Refused(SendRefusal.HistoryIncomplete)
        }

        val members = roomRepository.membersOfRoom(roomId)

        val item = checkNotNull(payload.toDisplayItem()) { "Text draft appended as non-displayable payload" }
        notifyWindowsNewItem(IngestResult.Inserted(payload), item)

        fileSendService.startPushes(wireAttachments, members)

        if (members.isEmpty()) {
            AppLog.debug(
                component = LogComponent.MESSAGING,
                event = LogEvent.MESSAGE_NO_PEERS,
                message = "No room members to send to",
                fields = mapOf(
                    "roomId" to roomId,
                    "messageId" to payload.messageId,
                ),
            )
            return SendMessageResult.Sent(FanoutReport(membersTotal = 0, membersQueued = 0, membersPullOnly = 0))
        }

        // Fan out to ALL members — the local account's own other devices get the
        // push (multi-device latency); the pull path converges them regardless.
        // The report counts other members only (display-side self-exclusion, like
        // typing indicators), paired by member so the self report drops out.
        val reports = coroutineScope {
            members.map { member ->
                async {
                    member to messageCarrier.sendMessage(member, payload)
                }
            }.awaitAll()
        }

        AppLog.info(
            component = LogComponent.MESSAGING,
            event = LogEvent.OUTBOX_MESSAGE_QUEUED,
            message = "Outbound message sent to room members",
            fields = mapOf(
                "roomId" to roomId,
                "messageId" to payload.messageId,
                "memberCount" to members.size,
            ),
        )

        return SendMessageResult.Sent(
            aggregateFanout(reports.filter { it.first != localAccountId }.map { it.second }),
        )
    }

    override suspend fun getMessage(messageId: Uuid): MessageDisplayItem? {
        // Single-message fetch for a GUI-held id (the GUI only holds ids it
        // rendered, so no render-policy filtering here by design).
        val payload = messageRepository.findById(messageId)?.payload ?: return null
        return payload.toDisplayItem()
    }

    override suspend fun openRoom(roomId: RoomId, initialPageSize: Int): RoomMessageWindow {
        windowsMapMutex.withLock {
            check(openWindows[roomId] == null) {
                "Room $roomId is already open; close it first"
            }
            val window =
                DefaultRoomMessageWindow(roomId, initialPageSize, serviceScope ?: error("MessagingService not started"))
            openWindows[roomId] = window
            return window
        }
    }

    private suspend fun notifyWindowsNewItem(result: IngestResult, item: MessageDisplayItem) {
        val payload = result.payload
        val roomId = payload.roomId
        val window = windowsMapMutex.withLock { openWindows[roomId] } ?: return
        val orphanGap: MessageDisplayItem.Gap? = (result as? IngestResult.BecameOrphan)?.let {
            MessageDisplayItem.Gap(
                messageId = payload.messageId,
                accountId = payload.senderAccountId,
                timestamp = payload.createdAt,
                missingPrevIds = it.missingPrevIds,
            )
        }
        window.onNewItem(item, result.closedGapMissingPrevIds, orphanGap)
    }

    /**
     * A stored message became REJECTED — drop it from any open window so a previously displayed
     * (e.g. PENDING) message is removed from the GUI.
     */
    private suspend fun notifyWindowsRejected(change: VerificationStateChange) {
        val window = windowsMapMutex.withLock { openWindows[change.roomId] } ?: return
        window.onItemRejected(change.messageId)
    }

    /**
     * Renderability was already decided by the caller via the renderable check
     * in the ingest collector; only the notification policy applies here: no self-notify.
     * The emitted item is policy-vetted and unformatted.
     */
    private suspend fun emitIncomingEventIfNeeded(payload: MessagePayload, item: MessageDisplayItem) {
        val localAccountId = localIdentity.getLocalAccountId()
        if (payload.senderAccountId == localAccountId) return
        incomingMessageEventFlow.emit(
            IncomingMessageEvent(
                roomId = payload.roomId,
                senderAccountId = payload.senderAccountId,
                item = item,
            )
        )
    }

    override suspend fun roomPreview(roomId: RoomId, scanLimit: Int): RoomPreview? {
        // Renderable-first page (docs/room events.md §3): the query skips
        // REJECTED-verdict and hidden-author rows alike, so the first displayable
        // row is the latest visible message. A re-fold converges the result on
        // re-pull — no re-evaluation is needed here.
        for (row in messageRepository.renderableMessagesInRoom(roomId, scanLimit)) {
            val msg = row.payload
            val item = msg.toDisplayItem() ?: continue
            return RoomPreview(
                messageId = msg.messageId,
                senderAccountId = msg.senderAccountId,
                timestamp = msg.createdAt,
                item = item,
            )
        }
        return null
    }

    /**
     * Collapses per-member push reports into the send-time reachability snapshot.
     * A member counts as queued with at least one device in the outbox now;
     * everything else is pull-only (deferred, no devices, failed pushes).
     */
    private fun aggregateFanout(reports: List<AccountPushReport>): FanoutReport {
        val queued = reports.count { it.devicesQueued > 0 }
        return FanoutReport(
            membersTotal = reports.size,
            membersQueued = queued,
            membersPullOnly = reports.size - queued,
        )
    }

    /**
     * Single displayability decision point: maps a payload to its GUI display
     * item, or null when the payload is not chat-transcript content
     * (control-plane events). Exhaustive on purpose — a new payload type must
     * be handled here explicitly.
     */
    private fun MessagePayload.toDisplayItem(): MessageDisplayItem? = when (this) {
        is MessagePayload.Text -> MessageDisplayItem.Text(
            messageId = messageId,
            accountId = senderAccountId,
            timestamp = createdAt,
            text = text,
        )

        is MessagePayload.GlobalEvent,
        is MessagePayload.RoomEvent -> null
    }

    private inner class DefaultRoomMessageWindow(
        private val roomId: RoomId,
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
            // The page is renderable-first (docs/room events.md §3): hidden rows
            // never leave the DB, so the cursor always sits on a rendered row.
            val page = messageRepository.renderableMessagesInRoom(roomId, initialPageSize).map { it.payload }
            if (page.isEmpty()) {
                _hasMoreOlder.value = false
                return
            }
            val gapsByOrphanId = causalHoldRepository.findByRoom(roomId)
                .groupBy(keySelector = { it.orphanedMessageId }, valueTransform = { it.missingPrevId })
            windowMutex.withLock {
                val oldest = page.last()
                oldestCursor = MessageCursor(
                    createdAt = oldest.createdAt,
                    messageId = oldest.messageId,
                )
                _hasMoreOlder.value = page.size >= initialPageSize
                _displayItems.value = buildDisplayList(page.asReversed(), gapsByOrphanId)
            }
        }

        override suspend fun loadOlder(pageSize: Int): Int {
            if (closed) return 0
            val cursor = windowMutex.withLock { oldestCursor } ?: return 0

            val page = messageRepository.renderableMessagesInRoom(roomId, pageSize, cursor = cursor)
                .map { it.payload }
            if (page.isEmpty()) {
                _hasMoreOlder.value = false
                return 0
            }

            val gapsByOrphanId = causalHoldRepository.findByRoom(roomId)
                .groupBy(keySelector = { it.orphanedMessageId }, valueTransform = { it.missingPrevId })
            return windowMutex.withLock {
                val oldest = page.last()
                oldestCursor = MessageCursor(
                    createdAt = oldest.createdAt,
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
                    for (i in current.indices) {
                        val item = current[i]
                        if (item is MessageDisplayItem.Gap) {
                            val remaining = item.missingPrevIds.filter { it !in closedSet }
                            if (remaining.size != item.missingPrevIds.size) {
                                current[i] = item.copy(missingPrevIds = remaining)
                            }
                        }
                    }
                    current.removeAll {
                        it is MessageDisplayItem.Gap && it.missingPrevIds.isEmpty()
                    }
                }

                // Display list is oldest -> newest; insert before the first item newer than [item].
                // Composite order (timestamp, messageId) matches the DB display-order index.
                // messageId is the primary key, so the pair is unique among display items.
                // An orphan's gap goes right before it: what's missing is its predecessor.
                val insertIdx = current.indexOfFirst { it.isDisplayAfter(item) }
                val toInsert = listOfNotNull(orphanGap, item)
                if (insertIdx == -1) {
                    current.addAll(toInsert)
                } else {
                    current.addAll(insertIdx, toInsert)
                }

                _displayItems.value = current
            }
        }

        /** Remove a displayed item whose message became REJECTED. The orphan gap shares the message id, so it is removed as well. */
        suspend fun onItemRejected(messageId: Uuid) {
            if (closed) return
            windowMutex.withLock {
                _displayItems.value = _displayItems.value.filterNot { it.messageId == messageId }
            }
        }

        /**
         * True if `this` is strictly newer than [other] per the composite display order
         * `(createdAt, messageId)`.
         */
        private fun MessageDisplayItem.isDisplayAfter(other: MessageDisplayItem): Boolean {
            if (timestamp != other.timestamp) return timestamp > other.timestamp
            return messageId > other.messageId
        }

        private fun buildDisplayList(
            messages: List<MessagePayload>,
            gapsByOrphanId: Map<Uuid, List<Uuid>>,
        ): List<MessageDisplayItem> {
            val items = mutableListOf<MessageDisplayItem>()
            for (msg in messages) {
                val item = msg.toDisplayItem() ?: continue
                // An orphan's gap goes right before it: what's missing is its predecessor.
                val orphanedGap = gapsByOrphanId[msg.messageId]
                if (orphanedGap != null) {
                    items.add(
                        MessageDisplayItem.Gap(
                            messageId = msg.messageId,
                            accountId = msg.senderAccountId,
                            timestamp = msg.createdAt,
                            missingPrevIds = orphanedGap,
                        )
                    )
                }
                items.add(item)
            }
            return items
        }
    }
}
