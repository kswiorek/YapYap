package org.yapyap.backend.routing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.yapyap.backend.crypto.CryptoProvider
import org.yapyap.backend.crypto.DeviceIdentityRecord
import org.yapyap.backend.crypto.IdentityResolver
import org.yapyap.backend.crypto.SignatureProvider
import org.yapyap.backend.db.DefaultPacketDeduplicator
import org.yapyap.backend.db.PacketIdAllocator
import org.yapyap.backend.protocol.BinaryEnvelope
import org.yapyap.backend.protocol.PacketType
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.transport.tor.TorInboundEnvelope
import org.yapyap.backend.transport.tor.TorTransport
import org.yapyap.backend.transport.webrtc.WebRtcTransport

class DefaultRouter(
    val torTransport: TorTransport,
    val webRtcTransport: WebRtcTransport,
    val identityResolver: IdentityResolver,
    val cryptoProvider: CryptoProvider,
    val packetIdAllocator: PacketIdAllocator,
    val packetDeduplicator: DefaultPacketDeduplicator
): Router {
    var started = false
    var torEndpoint: TorEndpoint? = null
    var localDeviceIdentity: DeviceIdentityRecord? = null

    private var scope: CoroutineScope? = null
    private var torIncomingJob: Job? = null

    override suspend fun start() {
        check(!started) { "Router is already started" }
        localDeviceIdentity = identityResolver.getLocalDeviceIdentityRecord()
        packetIdAllocator.assignLocalDevice(localDeviceIdentity!!.deviceId)

        try {
            torEndpoint = torTransport.start()
            webRtcTransport.start(localDeviceIdentity!!.deviceId)
        }
        catch (e: Exception) {
            webRtcTransport.stop()
            torTransport.stop()
            throw e
        }

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        torIncomingJob = s.launch(start = CoroutineStart.UNDISPATCHED) {
            torTransport.incoming.collect { inbound ->
                runCatching { handleInboundEnvelope(inbound) }
                    .onFailure { /* log + metrics; don't kill stream */ }
            }
        }
        started = true

    }
    override suspend fun stop() {
        if (!started) return

        webRtcTransport.stop()
        torTransport.stop()
        torIncomingJob?.cancel()
        torIncomingJob = null

        started = false
    }

    override fun isRunning(): Boolean {
        return started
    }

    private suspend fun handleInboundEnvelope(inbound: TorInboundEnvelope) {
        val env = inbound.envelope

        if (!packetDeduplicator.firstSeen(
                packetId = env.packetId,
                sourceDeviceId = env.source,
                receivedAtEpochSeconds = env.createdAtEpochSeconds,
            )
        ) {
            return
        }

        when (env.packetType) {
            PacketType.SIGNAL -> handleSignalEnvelope(env)
            PacketType.FILE -> handleFileEnvelope(env)
            else -> { /* ignore or log */ }
        }
    }

    private suspend fun handleSignalEnvelope(env: BinaryEnvelope) {
        // TODO
    }
    private suspend fun handleFileEnvelope(env: BinaryEnvelope) {
        // TODO
    }
}