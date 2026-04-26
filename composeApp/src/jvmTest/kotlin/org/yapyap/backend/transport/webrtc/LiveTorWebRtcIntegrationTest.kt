package org.yapyap.backend.transport.webrtc

import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.yapyap.backend.protection.PlaintextWebRtcSignalProtection
import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.DeviceAddress
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.testutil.testDevice
import org.yapyap.backend.transport.tor.DefaultTorTransport
import org.yapyap.backend.transport.tor.KmpTorNoExecBackend
import org.yapyap.backend.transport.tor.TorBackendConfig
import org.yapyap.backend.transport.webrtc.types.WebRtcSessionPhase
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LiveTorWebRtcIntegrationTest {

    @Test
    fun twoPeersConnectAndExchangeDataOverTorSignaling() = runBlocking {
        val runId = UUID.randomUUID().toString().replace("-", "")
        val deviceA = "alice-$runId"
        val deviceB = "bob-$runId"

        val peerA = testDevice(
            account = "alice",
            device = deviceA,
            onion = "alice1234567890abcdef1234567890abcdef1234567890abcdef.onion",
        )
        val peerB = testDevice(
            account = "bob",
            device = deviceB,
            onion = "bob1234567890abcdef1234567890abcdef1234567890abcdef12.onion",
        )

        val torBackendA = KmpTorNoExecBackend(
            deviceId = deviceA,
            config = TorBackendConfig(startupTimeoutMillis = 180_000),
        )
        val torBackendB = KmpTorNoExecBackend(
            deviceId = deviceB,
            config = TorBackendConfig(startupTimeoutMillis = 180_000),
        )
        val torTransportA = DefaultTorTransport(backend = torBackendA)
        val torTransportB = DefaultTorTransport(backend = torBackendB)

        val transportA = TorRoutedWebRtcTransport(
            delegate = DefaultWebRtcTransport(
                backend = JvmWebRtcBackend(),
                packetIdGenerator = { PacketId.random() },
            ),
            torTransport = torTransportA,
            protection = PlaintextWebRtcSignalProtection(),
            signalRoutingResolver = object : SignalRoutingResolver {
                override fun resolveAccountIdForDevice(deviceId: String): String =
                    when (deviceId) {
                        peerA.address.deviceId -> peerA.address.accountId
                        peerB.address.deviceId -> peerB.address.accountId
                        else -> error("Unknown deviceId: $deviceId")
                    }

                override fun resolveTorEndpointForDevice(deviceId: String): TorEndpoint =
                    when (deviceId) {
                        peerA.address.deviceId -> requireNotNull(torBackendA.publishedLocalEndpoint) { "Peer A endpoint unavailable" }
                        peerB.address.deviceId -> requireNotNull(torBackendB.publishedLocalEndpoint) { "Peer B endpoint unavailable" }
                        else -> error("Unknown deviceId: $deviceId")
                    }
            },
        )
        val transportB = TorRoutedWebRtcTransport(
            delegate = DefaultWebRtcTransport(
                backend = JvmWebRtcBackend(),
                packetIdGenerator = { PacketId.random() },
            ),
            torTransport = torTransportB,
            protection = PlaintextWebRtcSignalProtection(),
            signalRoutingResolver = object : SignalRoutingResolver {
                override fun resolveAccountIdForDevice(deviceId: String): String =
                    when (deviceId) {
                        peerA.address.deviceId -> peerA.address.accountId
                        peerB.address.deviceId -> peerB.address.accountId
                        else -> error("Unknown deviceId: $deviceId")
                    }

                override fun resolveTorEndpointForDevice(deviceId: String): TorEndpoint =
                    when (deviceId) {
                        peerA.address.deviceId -> requireNotNull(torBackendA.publishedLocalEndpoint) { "Peer A endpoint unavailable" }
                        peerB.address.deviceId -> requireNotNull(torBackendB.publishedLocalEndpoint) { "Peer B endpoint unavailable" }
                        else -> error("Unknown deviceId: $deviceId")
                    }
            },
        )

        var autoAcceptJob: Job? = null
        try {
            transportA.start(peerA.address)
            transportB.start(peerB.address)

            autoAcceptJob = launch(start = CoroutineStart.UNDISPATCHED) {
                transportB.incomingSessionRequests.collect { request ->
                    transportB.acceptSession(request.sessionId)
                }
            }

            val sessionId = withTimeout(240.seconds) {
                transportA.initiateSession(target = peerB.address)
            }

            withTimeout(240.seconds) {
                transportA.sessionStates.first {
                    it.sessionId == sessionId &&
                        it.peer == peerB.address &&
                        it.phase == WebRtcSessionPhase.CONNECTED
                }
            }
            withTimeout(240.seconds) {
                transportB.sessionStates.first {
                    it.sessionId == sessionId &&
                        it.peer == peerA.address &&
                        it.phase == WebRtcSessionPhase.CONNECTED
                }
            }

            val payload = "live-tor-webrtc-e2e".encodeToByteArray()
            sendWhenReady(
                transport = transportA,
                sessionId = sessionId,
                target = peerB.address,
                payload = payload,
            )

            val inbound = withTimeout(120.seconds) {
                transportB.incomingData.first { frame ->
                    frame.sessionId == sessionId && frame.source == peerA.address
                }
            }

            assertEquals(sessionId, inbound.sessionId)
            assertEquals(peerA.address, inbound.source)
            assertContentEquals(payload, inbound.payload)
        } finally {
            autoAcceptJob?.cancel()
            runCatching { transportA.stop() }
            runCatching { transportB.stop() }
        }
    }

    private suspend fun sendWhenReady(
        transport: WebRtcTransport,
        sessionId: String,
        target: DeviceAddress,
        payload: ByteArray,
    ) {
        withTimeout(30.seconds) {
            while (true) {
                val sent = runCatching {
                    transport.sendData(sessionId = sessionId, target = target, payload = payload)
                }.isSuccess
                if (sent) return@withTimeout
                delay(200.milliseconds)
            }
        }
    }
}
