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

    /**
     * Daemon health: bootstrap progress (with Tor's own reason when it is stuck)
     * and network liveness once bootstrapped. [TorHealth.Stopped] outside start/stop.
     */
    val health: StateFlow<TorHealth>

    /**
     * Launches the daemon and adds our onion, then returns its endpoint. Does not
     * wait for bootstrap: progress is reported through [health], and sends made
     * before bootstrap completes fail like any other transient Tor error.
     */
    suspend fun start(): TorEndpoint

    suspend fun stop()

    suspend fun send(target: TorEndpoint, payload: ByteArray)
}

internal expect fun createResourceLoader(resourceDir: File): ResourceLoader.Tor