package org.yapyap.routing.inbound.handlers

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.e2ee.testTransportLimits
import org.yapyap.crypto.identity.*
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.persistence.db.DeviceType
import org.yapyap.protection.PassthroughFileProtection
import org.yapyap.protection.envelope.*
import org.yapyap.protection.service.DefaultEnvelopeProtectionService
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.BootstrapEnvelope
import org.yapyap.protocol.envelopes.BootstrapIntroPayload
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.packet.PacketType
import org.yapyap.routing.router.*
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class BootstrapInboundHandlerTest {

    private val crypto: CryptoProvider = DefaultCryptoProvider()
    private val newcomerDevice =
        PeerId("newcomerlocalddddddddddddddddddddddddddddddddddddddddddddddddddd")
    private val sponsorDevice =
        PeerId("sponsorremoteeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")
    private val otherDevice =
        PeerId("otherdeviceffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
    private val now = epochSeconds(10_000L)

    private fun localDeviceRecord(): DeviceIdentityRecord =
        DeviceIdentityRecord(
            deviceId = newcomerDevice,
            signing = IdentityPublicKeyRecord("ls", 0L, IdentityKeyPurpose.SIGNING, byteArrayOf(1)),
            encryption = IdentityPublicKeyRecord("le", 0L, IdentityKeyPurpose.ENCRYPTION, byteArrayOf(2)),
        )

    private fun routingContext(activeSecret: ByteArray?): RoutingContext {
        val service = DefaultEnvelopeProtectionService(
            webRtcSignalProtection = PlaintextWebRtcSignalProtection(crypto),
            fileProtection = PassthroughFileProtection(),
            messageProtection = PlaintextMessageProtection(crypto),
            systemProtection = PlaintextSystemProtection(crypto),
            bootstrapProtection = BootstrapIntroProtection(crypto, BootstrapKeySource { activeSecret }),
        )
        val local = localDeviceRecord()
        val ctx = RoutingContext(
            identityResolver = FakeIdentityResolverForRouter(localDevice = local),
            packetDeduplicator = InMemoryPacketDeduplicator(),
            envelopeProtectionService = service,
            torTransport = RecordingTorTransport(),
            webRtcTransport = RecordingWebRtcTransport(),
            clock = FakeClock(now),
            routerConfig = MutableStateFlow(RouterConfig()),
            transportLimits = MutableStateFlow(testTransportLimits()),
        )
        ctx.localDeviceIdentity = local
        return ctx
    }

    private suspend fun samplePayload(deviceId: PeerId): BootstrapIntroPayload {
        val signing = crypto.generateSigningKeyPair()
        val encryption = crypto.generateEncryptionKeyPair()
        val spk = crypto.generateEncryptionKeyPair()
        val encryptionKeyId = "device-encryption"
        return BootstrapIntroPayload(
            version = 1,
            account = AccountIdentityRecord(AccountId("sponsor-account"), "Sponsor", key = null),
            device = DeviceIdentityRecord(
                deviceId = deviceId,
                signing = IdentityPublicKeyRecord("device-signing", 0L, IdentityKeyPurpose.SIGNING, signing.publicKey),
                encryption = IdentityPublicKeyRecord(
                    encryptionKeyId,
                    0L,
                    IdentityKeyPurpose.ENCRYPTION,
                    encryption.publicKey
                ),
                signedPreKey = SignedPreKeyRecord(
                    deviceId = deviceId,
                    keyId = "spk-sponsor",
                    publicKey = spk.publicKey,
                    signature = crypto.signDetached(signing.privateKey, spk.publicKey),
                    privateKey = null,
                ),
                keySignature = crypto.signDetached(
                    signing.privateKey,
                    encryption.publicKey + encryptionKeyId.encodeToByteArray(),
                ),
            ),
            deviceType = DeviceType.DESKTOP,
            torEndpoint = TorEndpoint("sponsor.onion", 80),
            dagHeadMessageId = null,
            dagHeadLamport = 0L,
        )
    }

    private suspend fun protectedEnvelope(
        payload: BootstrapIntroPayload,
        source: PeerId,
        target: PeerId,
        secret: ByteArray,
    ): BootstrapEnvelope =
        BootstrapIntroProtection(crypto, BootstrapKeySource { secret })
            .protectIntro(payload, source, target, createdAt = now)

    private fun binaryEnvelope(bootstrapEnvelope: BootstrapEnvelope, source: PeerId, target: PeerId): BinaryEnvelope =
        BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.BOOTSTRAP,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = now + 1.minutes,
            source = source,
            target = target,
            payload = bootstrapEnvelope.encode(),
        )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun kotlinx.coroutines.test.TestScope.subscribe(
        intros: MutableSharedFlow<BootstrapIntroEvent>,
    ): MutableList<BootstrapIntroEvent> {
        val received = mutableListOf<BootstrapIntroEvent>()
        // Eager subscription: the emitter must see an active collector the moment handle() emits,
        // otherwise a replay=0 SharedFlow drops the value.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { intros.collect { received.add(it) } }
        return received
    }

    @Test
    fun handle_validIntro_emitsEvent_andSuccess() = runTest {
        val secret = ByteArray(32) { 5 }
        val ctx = routingContext(secret)
        val intros = MutableSharedFlow<BootstrapIntroEvent>(extraBufferCapacity = 64)
        val received = subscribe(intros)
        val handler = BootstrapInboundHandler(ctx, intros)

        val payload = samplePayload(sponsorDevice)
        val result = handler.handle(
            binaryEnvelope(
                protectedEnvelope(payload, sponsorDevice, newcomerDevice, secret),
                sponsorDevice,
                newcomerDevice
            ),
        )

        assertIs<InboundHandleResult.Success>(result)
        advanceUntilIdle()
        assertEquals(1, received.size)
        assertEquals(sponsorDevice, received[0].payload.device.deviceId)
    }

    @Test
    fun handle_wrongTarget_rejectedWrongTarget_noEmit() = runTest {
        val secret = ByteArray(32) { 5 }
        val ctx = routingContext(secret)
        val intros = MutableSharedFlow<BootstrapIntroEvent>(extraBufferCapacity = 64)
        val received = subscribe(intros)
        val handler = BootstrapInboundHandler(ctx, intros)

        val payload = samplePayload(sponsorDevice)
        val result = handler.handle(
            binaryEnvelope(protectedEnvelope(payload, sponsorDevice, otherDevice, secret), sponsorDevice, otherDevice),
        )

        assertEquals(PacketNackReason.WRONG_TARGET, (result as InboundHandleResult.Rejected).reason)
        advanceUntilIdle()
        assertEquals(0, received.size)
    }

    @Test
    fun handle_badBootstrapEnvelope_rejectedDecodeFailed() = runTest {
        val ctx = routingContext(ByteArray(32) { 5 })
        val intros = MutableSharedFlow<BootstrapIntroEvent>(extraBufferCapacity = 64)
        val handler = BootstrapInboundHandler(ctx, intros)

        val env = BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.BOOTSTRAP,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = now + 1.minutes,
            source = sponsorDevice,
            target = newcomerDevice,
            payload = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66),
        )
        val result = handler.handle(env)

        assertEquals(PacketNackReason.DECODE_FAILED, (result as InboundHandleResult.Rejected).reason)
    }

    @Test
    fun handle_noActiveSession_gate_rejectedProtectionFailed_noEmit() = runTest {
        // Handler side has NO active secret — the gate.
        val ctx = routingContext(activeSecret = null)
        val intros = MutableSharedFlow<BootstrapIntroEvent>(extraBufferCapacity = 64)
        val received = subscribe(intros)
        val handler = BootstrapInboundHandler(ctx, intros)

        // The intro was protected with a secret the handler does not hold.
        val payload = samplePayload(sponsorDevice)
        val result = handler.handle(
            binaryEnvelope(
                protectedEnvelope(payload, sponsorDevice, newcomerDevice, ByteArray(32) { 5 }),
                sponsorDevice,
                newcomerDevice
            ),
        )

        assertEquals(PacketNackReason.PROTECTION_FAILED, (result as InboundHandleResult.Rejected).reason)
        advanceUntilIdle()
        assertEquals(0, received.size)
    }

    @Test
    fun handle_wrongSecret_rejectedProtectionFailed() = runTest {
        val ctx = routingContext(ByteArray(32) { 1 })
        val intros = MutableSharedFlow<BootstrapIntroEvent>(extraBufferCapacity = 64)
        val handler = BootstrapInboundHandler(ctx, intros)

        val payload = samplePayload(sponsorDevice)
        val result = handler.handle(
            binaryEnvelope(
                protectedEnvelope(payload, sponsorDevice, newcomerDevice, ByteArray(32) { 2 }),
                sponsorDevice,
                newcomerDevice
            ),
        )

        assertEquals(PacketNackReason.PROTECTION_FAILED, (result as InboundHandleResult.Rejected).reason)
    }

    @Test
    fun handle_tamperedCiphertext_rejectedProtectionFailed() = runTest {
        val secret = ByteArray(32) { 5 }
        val ctx = routingContext(secret)
        val intros = MutableSharedFlow<BootstrapIntroEvent>(extraBufferCapacity = 64)
        val handler = BootstrapInboundHandler(ctx, intros)

        val payload = samplePayload(sponsorDevice)
        val bootstrapEnvelope = protectedEnvelope(payload, sponsorDevice, newcomerDevice, secret)
        val tampered = bootstrapEnvelope.copy(
            payload = bootstrapEnvelope.payload.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() },
        )
        val result = handler.handle(binaryEnvelope(tampered, sponsorDevice, newcomerDevice))

        assertEquals(PacketNackReason.PROTECTION_FAILED, (result as InboundHandleResult.Rejected).reason)
    }

    @Test
    fun handle_headerSourceDoesNotMatchAttestedDevice_rejected() = runTest {
        val secret = ByteArray(32) { 5 }
        val ctx = routingContext(secret)
        val intros = MutableSharedFlow<BootstrapIntroEvent>(extraBufferCapacity = 64)
        val handler = BootstrapInboundHandler(ctx, intros)

        // Envelope header claims otherDevice; the authenticated payload attests sponsorDevice.
        val payload = samplePayload(sponsorDevice)
        val result = handler.handle(
            binaryEnvelope(
                protectedEnvelope(payload, otherDevice, newcomerDevice, secret),
                otherDevice,
                newcomerDevice
            ),
        )

        assertEquals(PacketNackReason.PROTECTION_FAILED, (result as InboundHandleResult.Rejected).reason)
    }
}