package org.yapyap.routing.router

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.yapyap.crypto.e2ee.buildTestPeerIdentity
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.AccountIdentityRecord
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.persistence.db.DeviceType
import org.yapyap.persistence.key.BootstrapKeySource
import org.yapyap.protection.envelope.BootstrapProtection
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.Intro
import org.yapyap.protocol.packet.PacketType
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.tor.TorIncomingEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Full-path inbound check: a bootstrap intro from a sponsor the newcomer has NEVER seen (no device
 * row, no Tor endpoint — the pre-bootstrap chicken-and-egg) must be tolerated by
 * [InboundEnvelopeProcessor.handleTorInbound], authenticated by the preshared-key AEAD, and surfaced
 * on [Router.bootstrapPackets].
 */
class BootstrapInboundIntegrationTest {

    @Test
    fun introFromUnknownSponsor_isTolerated_emitsEvent() = runBlocking {
        val crypto = DefaultCryptoProvider()
        val secret = ByteArray(32) { 9 }
        val clock = FakeClock(epochSeconds(10_000L))

        val newcomer = buildTestPeerIdentity(crypto, "inbound-newcomer")
        val placeholder = buildTestPeerIdentity(crypto, "inbound-placeholder")
        val sponsor = buildTestPeerIdentity(crypto, "inbound-sponsor")

        val tor = RecordingTorTransport(TorEndpoint("newcomer.onion", 80))
        val stack = buildE2eeRouterStack(
            local = newcomer,
            remote = placeholder,
            peersByAccount = emptyMap(),
            torByPeer = mutableMapOf(newcomer.device.deviceId to tor.advertisedEndpoint),
            clock = clock,
            crypto = crypto,
            bootstrapKeySource = BootstrapKeySource { secret.copyOf() },
        )
        val router = e2eeRouterUnderTest(stack, tor = tor, clock = clock)
        router.start()
        try {
            val payload = Intro(
                version = 1,
                account = AccountIdentityRecord(AccountId("sponsor-account"), "Sponsor", key = null),
                device = sponsor.device,
                deviceType = DeviceType.DESKTOP,
                torEndpoint = TorEndpoint("sponsor.onion", 80),
                dagHeadLamport = 0L,
            )
            val bootstrapEnvelope = BootstrapProtection(crypto, BootstrapKeySource { secret.copyOf() })
                .protect(payload, sponsor.device.deviceId, newcomer.device.deviceId, clock.now(), secret.copyOf())
            val binary = BinaryEnvelope(
                packetId = Uuid.random(),
                packetType = PacketType.BOOTSTRAP,
                dispositionRequested = true,
                createdAt = clock.now(),
                expiresAt = clock.now() + 1.minutes,
                source = sponsor.device.deviceId,
                target = newcomer.device.deviceId,
                payload = bootstrapEnvelope.encode(),
            )

            // Subscribe before emitting: bootstrapPackets has no replay, so an early
            // emit would otherwise be lost and the wait would time out under load.
            val firstEvent = async { router.bootstrapPackets.first() }
            // Let the collector suspend before the inbound races it.
            yield()

            tor.tryEmitIncoming(
                TorIncomingEnvelope(source = TorEndpoint("sponsor.onion", 80), envelope = binary),
            )

            val event = withTimeout(15.seconds) { firstEvent.await() }
            assertEquals(sponsor.device.deviceId, event.payload.device.deviceId)
        } finally {
            router.stop()
        }
    }
}
