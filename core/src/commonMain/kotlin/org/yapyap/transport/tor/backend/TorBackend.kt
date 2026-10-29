package org.yapyap.transport.tor.backend

import io.matthewnelson.kmp.file.File
import io.matthewnelson.kmp.tor.common.api.ResourceLoader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.yapyap.protocol.TorEndpoint
import org.yapyap.transport.tor.TorIncomingFrame

internal interface TorBackend {
    val incomingFrames: Flow<TorIncomingFrame>

    /**
     * Whether our own onion is dialable yet. Starts PUBLISHING on every start
     * (Tor re-uploads the descriptor per boot); latches PUBLISHED on the first
     * matching `HS_DESC UPLOADED`, DEGRADED past the publish budget.
     */
    val publishState: StateFlow<TorPublishState>

    suspend fun start(): TorEndpoint

    suspend fun stop()

    suspend fun send(target: TorEndpoint, payload: ByteArray)
}

internal expect fun createResourceLoader(resourceDir: File): ResourceLoader.Tor