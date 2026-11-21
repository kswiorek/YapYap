package org.yapyap.orchestrator.file.receive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.orchestrator.OrchestratorConfig
import org.yapyap.orchestrator.file.FileDescriptorReader
import org.yapyap.orchestrator.file.FileReceiveControl
import org.yapyap.orchestrator.file.FileReceiveEvent
import org.yapyap.orchestrator.file.FileReceiveReader
import org.yapyap.orchestrator.file.FileStore
import org.yapyap.orchestrator.file.io.FileAccess
import org.yapyap.orchestrator.store.MessageRepository
import org.yapyap.protocol.FileId
import kotlin.time.Clock
import kotlin.time.Instant

/** Keeps the store's copies within `storeQuotaBytes` (design §10). */
internal interface FileQuota {
    /**
     * Evicts the least recently accessed copies, never one in [keep], until the copies fit.
     * Never called under a file lock or inside a completion transaction.
     */
    suspend fun enforceQuota(keep: Set<FileId> = emptySet())
}

/**
 * Retention of received and stored files (design §8, §10, invariant 25):
 * - **Unreferenced receives.** A receive no message references within
 *   [OrchestratorConfig.unreferencedFileRetention] is deleted with its `.part` or finished file,
 *   whatever its status.
 * - **Quota.** True LRU over copies only. A received file goes through the engine's `evicted` verb
 *   (EVICTED, never re-fetched automatically); a sent copy loses its descriptor, then its bytes.
 *   Runs after every READY (from [receiveEvents]) and when the send path asks.
 */
internal class FileRetention(
    private val receives: FileReceiveReader,
    private val descriptors: FileDescriptorReader,
    private val messages: MessageRepository,
    private val engine: FileReceiveControl,
    private val fileStore: FileStore,
    private val files: FileAccess,
    private val paths: ReceivePaths,
    private val receiveEvents: Flow<FileReceiveEvent>,
    private val config: StateFlow<OrchestratorConfig>,
    private val clock: Clock = Clock.System,
) : FileQuota {
    private val quotaMutex = Mutex()
    private val quotaRequests = Channel<Unit>(Channel.CONFLATED)
    private val jobs = mutableListOf<Job>()

    fun start(scope: CoroutineScope) {
        check(jobs.isEmpty()) { "FileRetention already started" }
        jobs += scope.launch {
            while (true) {
                delay(config.value.fileSweepInterval)
                runTask("Unreferenced receive sweep failed; will retry") { sweep(clock.now()) }
            }
        }
        jobs += scope.launch {
            receiveEvents.collect { event -> if (event is FileReceiveEvent.Ready) quotaRequests.trySend(Unit) }
        }
        jobs += scope.launch {
            for (request in quotaRequests) runTask("Quota enforcement failed; will retry") { enforceQuota() }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    /** One pass over every receive. A file that fails is logged and retried on the next pass. */
    suspend fun sweep(now: Instant) {
        for (row in receives.all()) {
            if (row.originMessageId != null) continue
            runTask("Unreferenced receive check failed; will retry", row.fileId) {
                val referencing = messages.findMessageIdsByFileId(row.fileId)
                if (referencing.isNotEmpty()) {
                    engine.updateOrigin(row.fileId, referencing.first(), now)
                } else if (row.createdAt + config.value.unreferencedFileRetention <= now) {
                    engine.forgetUnreferenced(row.fileId)
                    fileStore.forget(row.fileId)
                    AppLog.info(
                        component = LogComponent.FILE,
                        event = LogEvent.FILE_UNREFERENCED_DELETED,
                        message = "Deleted unreferenced receive past its retention",
                        fields = mapOf("fileId" to row.fileId),
                    )
                }
            }
        }
    }

    override suspend fun enforceQuota(keep: Set<FileId>) = quotaMutex.withLock {
        val quota = config.value.storeQuotaBytes
        var total = descriptors.copyBytesTotal()
        if (total <= quota) return@withLock
        for (victim in descriptors.copyDescriptorsLeastRecentlyUsed()) {
            if (total <= quota) break
            if (victim.fileId in keep) continue
            if (!engine.evicted(victim.fileId, clock.now())) {
                // A sent copy: descriptor first, so none is left HELD without its bytes. The bytes
                // are deleted at the store path, never at a path read from the row.
                fileStore.forget(victim.fileId)
                files.delete(paths.final(victim.fileId))
                AppLog.info(
                    component = LogComponent.FILE,
                    event = LogEvent.FILE_EVICTED,
                    message = "Stored copy evicted by quota",
                    fields = mapOf("fileId" to victim.fileId),
                )
            }
            total -= victim.totalBytes
        }
    }

    private suspend fun runTask(message: String, fileId: FileId? = null, task: suspend () -> Unit) {
        try {
            task()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(
                component = LogComponent.FILE,
                event = LogEvent.FILE_TASK_FAILED,
                message = message,
                throwable = e,
                fields = fileId?.let { mapOf("fileId" to it) } ?: emptyMap(),
            )
        }
    }
}
