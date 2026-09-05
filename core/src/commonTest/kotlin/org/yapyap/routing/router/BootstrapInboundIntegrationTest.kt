package org.yapyap.routing.router

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.yapyap.crypto.e2ee.buildTestPeerIdentity
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.AccountIdentityRecord
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.persistence.db.DeviceType
import org.yapyap.protection.envelope.BootstrapIntroProtection
import org.yapyap.protection.envelope.BootstrapKeySource
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.BootstrapIntroPayload
import org.yapyap.protocol.packet.PacketType
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.tor.TorIncomingEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Full-path inbound check: a bootstrap intro from a sponsor the newcomer has NEVER seen (no device
 * row, no Tor endpoint — the pre-bootstrap chicken-and-egg) must be tolerated by
 * [InboundEnvelopeProcessor.handleTorInbound], authenticated by the preshared-key AEAD, and surfaced
 * on [Router.bootstrapIntros].
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

        val payload = BootstrapIntroPayload(
            version = 1,
            account = AccountIdentityRecord(AccountId("sponsor-account"), "Sponsor", key = null),
            device = sponsor.device,
            deviceType = DeviceType.DESKTOP,
            torEndpoint = TorEndpoint("sponsor.onion", 80),
            dagHeadMessageId = null,
            dagHeadLamport = 0L,
        )
        val bootstrapEnvelope = BootstrapIntroProtection(crypto, BootstrapKeySource { secret.copyOf() })
            .protectIntro(payload, sponsor.device.deviceId, newcomer.device.deviceId, clock.now())
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

        val received = mutableListOf<BootstrapIntroEvent>()
        val collectJob = launch { router.bootstrapIntros.collect { received.add(it) } }

        tor.tryEmitIncoming(
            TorIncomingEnvelope(source = TorEndpoint("sponsor.onion", 80), envelope = binary),
        )

        withTimeout(5_000) {
            while (received.isEmpty()) yield()
        }
        assertEquals(1, received.size)
        assertEquals(sponsor.device.deviceId, received[0].payload.device.deviceId)

        collectJob.cancel()
        router.stop()
    }
}