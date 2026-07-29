package org.yapyap.transport.tor.backend

import io.matthewnelson.kmp.file.File
import io.matthewnelson.kmp.tor.common.api.ResourceLoader
import kotlinx.coroutines.flow.Flow
import org.yapyap.protocol.TorEndpoint
import org.yapyap.transport.tor.TorIncomingFrame

interface TorBackend {
    val incomingFrames: Flow<TorIncomingFrame>

    suspend fun start(localPort: Int? = null): TorEndpoint

    suspend fun stop()

    suspend fun send(target: TorEndpoint, payload: ByteArray)
}

internal expect fun createResourceLoader(resourceDir: File): ResourceLoader.Tor