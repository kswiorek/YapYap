package org.yapyap.routing.router

import kotlinx.coroutines.runBlocking
import org.yapyap.crypto.identity.*
import org.yapyap.persistence.db.DeviceType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BootstrapEnvelope
import org.yapyap.protocol.envelopes.BootstrapIntroPayload
import org.yapyap.protocol.packet.PacketType
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class BootstrapSenderTest {

    private val localDevice =
        PeerId("localbootstrapaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val newcomerDevice =
        PeerId("newbootstrapbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

    private fun localDeviceRecord(): DeviceIdentityRecord =
        DeviceIdentityRecord(
            deviceId = localDevice,
            signing = IdentityPublicKeyRecord("ls", 0L, IdentityKeyPurpose.SIGNING, byteArrayOf(1)),
            encryption = IdentityPublicKeyRecord("le", 0L, IdentityKeyPurpose.ENCRYPTION, byteArrayOf(2)),
        )

    private fun newcomerIntroPayload(): BootstrapIntroPayload =
        BootstrapIntroPayload(
            version = 1,
            account = AccountIdentityRecord(
                accountId = AccountId("newcomer-account"),
                displayName = "Newcomer",
                key = null,
            ),
            device = DeviceIdentityRecord(
                deviceId = newcomerDevice,
                signing = IdentityPublicKeyRecord("ns", 0L, IdentityKeyPurpose.SIGNING, byteArrayOf(3)),
                encryption = IdentityPublicKeyRecord("ne", 0L, IdentityKeyPurpose.ENCRYPTION, byteArrayOf(4)),
            ),
            deviceType = DeviceType.DESKTOP,
            torEndpoint = TorEndpoint("newcomer.onion", 80),
            dagHeadMessageId = null,
            dagHeadLamport = 0L,
        )

    @Test
    fun sendBootstrapIntro_protectsAndEnqueuesBootstrapEnvelope() = runBlocking {
        val clock = FakeClock(epochSeconds(10_000L))
        val outbox = TrackingPacketOutbox()
        val router = defaultRouterUnderTest(
            identity = FakeIdentityResolverForRouter(localDevice = localDeviceRecord()),
            outbox = outbox,
            clock = clock,
            routerConfig = RouterConfig(bootstrapIntroLifetime = 2.hours),
        )
        router.start()

        val payload = newcomerIntroPayload()
        router.sendBootstrapIntro(payload)

        assertEquals(1, outbox.enqueued.size)
        val env = outbox.enqueued.single()
        assertEquals(PacketType.BOOTSTRAP, env.packetType)
        assertTrue(env.dispositionRequested, "intro must request an ACK so the sponsor's outbox clears")
        assertEquals(localDevice, env.source)
        assertEquals(newcomerDevice, env.target)
        assertEquals(clock.now() + 2.hours, env.expiresAt, "intro must carry the short bootstrap lifetime")

        // The passthrough protection stores the encoded payload inside the bootstrap envelope.
        val bootstrapEnvelope = BootstrapEnvelope.decode(env.payload)
        assertTrue(payload.encode().contentEquals(bootstrapEnvelope.payload))
        router.stop()
    }
}