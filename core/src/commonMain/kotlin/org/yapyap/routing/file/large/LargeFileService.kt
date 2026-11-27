package org.yapyap.routing.file.large

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.yapyap.protocol.FileId
import org.yapyap.protocol.LargeFileAbortReason
import org.yapyap.protocol.PeerId
import org.yapyap.routing.file.LargeFileResult
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileIngestPort
import org.yapyap.routing.file.port.FilePlaintextSource
import org.yapyap.routing.file.port.FileRequestSource
import org.yapyap.routing.router.DeviceDirectory
import org.yapyap.routing.router.FileLimits
import org.yapyap.routing.router.RouterConfig
import org.yapyap.transport.webrtc.transport.WebRtcTransport
import kotlin.time.Clock

/**
 * The large path's composition face (design §6.6): the [LargeFileMux], [LargeFileRequester],
 * [LargeFileHolder], [LargeFileAdmissionGate] and [LargeFileChannelIo] wired together. No
 * envelopes, keys or rows: all state lives in memory and dies with the channel. `FileService`
 * and the large strategy only see this class.
 */
internal class LargeFileService(
    devices: DeviceDirectory,
    transport: WebRtcTransport,
    plaintext: FilePlaintextSource,
    ingest: FileIngestPort,
    requests: FileRequestSource,
    routerConfig: StateFlow<RouterConfig>,
    fileLimits: StateFlow<FileLimits>,
    events: MutableSharedFlow<FileEvent>,
    clock: Clock = Clock.System,
) {
    private val io = LargeFileChannelIo(transport)
    private val mux = LargeFileMux(transport)
    private val requester = LargeFileRequester(transport, ingest, routerConfig, mux, io)
    private val gate = LargeFileAdmissionGate(devices, requests, plaintext)
    private val holder = LargeFileHolder(
        transport = transport,
        plaintext = plaintext,
        events = events,
        routerConfig = routerConfig,
        fileLimits = fileLimits,
        gate = gate,
        mux = mux,
        io = io,
        clock = clock,
    )

    init {
        mux.attach(holder)
    }

    fun runIn(scope: CoroutineScope): Job {
        requester.start(scope)
        holder.start(scope)
        return mux.runIn(scope)
    }

    suspend fun activeCount(): Int = mux.count()

    suspend fun fetch(peer: PeerId, fileId: FileId, fromOffset: Long): LargeFileResult =
        requester.fetch(peer, fileId, fromOffset)

    suspend fun abortFetch(fileId: FileId, reason: LargeFileAbortReason) = requester.abort(fileId, reason)
}
