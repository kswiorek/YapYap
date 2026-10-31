package org.yapyap.transport.tor.backend

import io.matthewnelson.kmp.file.File
import io.matthewnelson.kmp.tor.common.api.ResourceLoader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.yapyap.protocol.TorEndpoint
import org.yapyap.transport.tor.TorIncomingFrame
import kotlin.time.Duration

internal interface TorBackend {
    /** One-shot frames (one frame per connection, see [TOR_TRANSPORT_FRAME_MAGIC]). */
    val incomingFrames: Flow<TorIncomingFrame>

    /**
     * Accepted session streams (see [TOR_STREAM_MAGIC]); the collector owns each one and
     * must close it. Single consumer: streams nobody takes are closed by the backend.
     */
    val incomingStreams: Flow<TorInboundStream>

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
     * The recovery step the backend's supervisor is working through when Tor is
     * stuck while the network is fine; null when healthy (or not supervised).
     */
    val recovery: StateFlow<TorRecoveryStep?>

    /**
     * Launches the daemon and adds our onion, then returns its endpoint. Does not
     * wait for bootstrap: progress is reported through [health], and sends made
     * before bootstrap completes fail like any other transient Tor error.
     */
    suspend fun start(): TorEndpoint

    suspend fun stop()

    /**
     * [budget] caps how long this send may keep retrying, never beyond
     * `TorBackendConfig.socksRetryTimeout` (null: that configured budget).
     */
    suspend fun send(target: TorEndpoint, payload: ByteArray, budget: Duration? = null)

    /**
     * Dials [target] and writes the stream hello, returning the open connection. Retries
     * within [budget] exactly like [send] (null: `TorBackendConfig.socksRetryTimeout`).
     * Unlike [send]'s connections, the returned stream has no socket read timeout: idle
     * detection is the caller's (keepalives).
     */
    suspend fun openStream(target: TorEndpoint, budget: Duration? = null): TorDuplex

    /**
     * Drops Tor's cached onion lookups (descriptors and remembered "not found"
     * misses) so the next dial to any onion fetches afresh. Global and therefore
     * throttled; true only when a refresh actually happened now.
     */
    suspend fun refreshOnionLookups(): Boolean
}

internal expect fun createResourceLoader(resourceDir: File): ResourceLoader.Tor