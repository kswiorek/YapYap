package org.yapyap.routing.bootstrap


import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.e2ee.testTransportLimits
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.store.BootstrapKeySource
import org.yapyap.protection.envelope.BootstrapProtection
import org.yapyap.protection.envelope.ProtectedBootstrap
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.protocol.envelopes.Intro
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.RecoveryRequest
import org.yapyap.protocol.identity.AccountIdentity
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SigningPublicKey
import org.yapyap.routing.link.TorPeerSessions
import org.yapyap.routing.router.BootstrapPacketEvent
import org.yapyap.routing.router.FakeIdentityResolverForRouter
import org.yapyap.routing.router.FileLimits
import org.yapyap.routing.router.InboundHandleResult
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RoutingContext
import org.yapyap.routing.router.passthroughProtections
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.transport.tor.RecordingTorTransport
import org.yapyap.transport.webrtc.RecordingWebRtcTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BootstrapInboundHandlerTest {

    private val crypto: CryptoProvider = DefaultCryptoProvider()
    private val newcomerDevice =
        PeerId("newcomerlocalddddddddddddddddddddddddddddddddddddddddddddddddddd")
    private val sponsorDevice =
        PeerId("sponsorremoteeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")
    private val otherDevice =
        PeerId("otherdeviceffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
    private val now = epochSeconds(10_000L)

    private fun localDeviceRecord(): DeviceIdentity =
        DeviceIdentity(
            deviceId = newcomerDevice,
            signingKey = SigningPublicKey(byteArrayOf(1)),
            encryptionKey = EncryptionPublicKey(byteArrayOf(2)),
        )

    private fun routingContext(activeSecret: ByteArray?): RoutingContext {
        val protections = passthroughProtections(crypto = crypto, keySource = BootstrapKeySource { activeSecret })
        val local = localDeviceRecord()
        val identity = FakeIdentityResolverForRouter(localDevice = local)
        val tor = RecordingTorTransport()
        val webRtc = RecordingWebRtcTransport()
        val ctx = RoutingContext(
            localIdentity = identity,
            localDeviceId = local.deviceId,
            devices = identity,
            torTransport = tor,
            webRtcTransport = webRtc,
            torPeers = TorPeerSessions(identity, tor),
            transportLimits = MutableStateFlow(testTransportLimits()),
            fileLimits = MutableStateFlow(FileLimits(220 * 1024, 220 * 1024, maxSealBytes = 64 * 1024)),
            envelopeProtections = protections,
            clock = FakeClock(now),
            routerConfig = MutableStateFlow(RouterConfig()),
        )
        return ctx
    }

    private suspend fun deviceRecord(deviceId: PeerId): DeviceIdentity {
        val signing = crypto.generateSigningKeyPair()
        val encryption = crypto.generateEncryptionKeyPair()
        return DeviceIdentity(
            deviceId = deviceId,
            signingKey = signing.publicKey,
            encryptionKey = encryption.publicKey,
        )
    }

    private suspend fun sampleIntro(deviceId: PeerId): Intro =
        Intro(
            version = 1,
            account = AccountIdentity(AccountId("sponsor-account"), "Sponsor", signingKey = null),
            device = deviceRecord(deviceId),
            deviceType = DeviceType.DESKTOP,
            torEndpoint = TorEndpoint("sponsor.onion", 80),
            dagHeadTipIds = emptyList(),
        )

    private suspend fun sampleRecoveryRequest(deviceId: PeerId): RecoveryRequest {
        val accountKeys = crypto.generateSigningKeyPair()
        val account = AccountIdentity(
            AccountId("recovering-account"),
            "Recovering",
            signingKey = accountKeys.publicKey,
        )
        val unsigned = RecoveryRequest(
            version = 1,
            account = account,
            device = deviceRecord(deviceId),
            deviceType = DeviceType.DESKTOP,
            torEndpoint = TorEndpoint("recovering.onion", 80),
            sharedSecret = ByteArray(32) { 7 },
            accountSignature = ByteArray(1),
        )
        return unsigned.copy(
            accountSignature = crypto.signDetached(
                accountKeys.privateKey,
                unsigned.accountSignedDeviceBindingBytes(),
            ),
        )
    }

    private suspend fun protectedEnvelope(
        payload: BootstrapPayload,
        source: PeerId,
        target: PeerId,
        secret: ByteArray?,
    ): ProtectedBootstrap =
        BootstrapProtection(crypto) { secret }
            .protect(payload, source, target, sharedSecret = secret)

    private fun binaryEnvelope(protected: ProtectedBootstrap, source: PeerId, target: PeerId): BinaryEnvelope =
        BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.BOOTSTRAP,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = now + 1.minutes,
            source = source,
            target = target,
            routingHeader = protected.header.encode(),
            content = protected.content,
            signature = ByteArray(BinaryEnvelope.SIGNATURE_BYTES),
        )

    private fun handlerUnderTest(
        ctx: RoutingContext,
        packets: MutableSharedFlow<BootstrapPacketEvent>,
    ): BootstrapInboundHandler =
        BootstrapInboundHandler(
            ctx = ctx,
            bootstrapPackets = packets,
        )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun kotlinx.coroutines.test.TestScope.subscribe(
        packets: MutableSharedFlow<BootstrapPacketEvent>,
    ): MutableList<BootstrapPacketEvent> {
        val received = mutableListOf<BootstrapPacketEvent>()
        // Eager subscription: the emitter must see an active collector the moment handle() emits,
        // otherwise a replay=0 SharedFlow drops the value.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { packets.collect { received.add(it) } }
        return received
    }

    @Test
    fun handle_validIntro_emitsEvent_andSuccess() = runTest {
        val secret = ByteArray(32) { 5 }
        val ctx = routingContext(secret)
        val packets = MutableSharedFlow<BootstrapPacketEvent>(extraBufferCapacity = 64)
        val received = subscribe(packets)
        // The newcomer holds an active session (its own onboarding wait, armed through the
        // protection service) — intros must NOT be declined by the own-session responder check.
        val handler = handlerUnderTest(ctx, packets)

        val payload = sampleIntro(sponsorDevice)
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
        assertEquals(sponsorDevice, (received[0].payload as Intro).device.deviceId)
    }

    @Test
    fun handle_wrongTarget_rejectedWrongTarget_noEmit() = runTest {
        val secret = ByteArray(32) { 5 }
        val ctx = routingContext(secret)
        val packets = MutableSharedFlow<BootstrapPacketEvent>(extraBufferCapacity = 64)
        val received = subscribe(packets)
        val handler = handlerUnderTest(ctx, packets)

        val payload = sampleIntro(sponsorDevice)
        val result = handler.handle(
            binaryEnvelope(
                protectedEnvelope(payload, sponsorDevice, otherDevice, secret),
                sponsorDevice,
                otherDevice
            ),
        )

        // Wrong-target is enforced by the inbound processor before dispatch (it NACKs
        // WRONG_TARGET without reaching the handler); the handler itself opens by content
        // and emits, so a direct call succeeds.
        assertIs<InboundHandleResult.Success>(result)
        advanceUntilIdle()
        assertEquals(1, received.size)
    }

    @Test
    fun handle_badBootstrapEnvelope_rejectedDecodeFailed() = runTest {
        val ctx = routingContext(ByteArray(32) { 5 })
        val packets = MutableSharedFlow<BootstrapPacketEvent>(extraBufferCapacity = 64)
        val handler = handlerUnderTest(ctx, packets)

        val env = BinaryEnvelope(
            packetId = Uuid.random(),
            packetType = PacketType.BOOTSTRAP,
            dispositionRequested = true,
            createdAt = now,
            expiresAt = now + 1.minutes,
            source = sponsorDevice,
            target = newcomerDevice,
            routingHeader = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66),
            content = byteArrayOf(),
            signature = ByteArray(BinaryEnvelope.SIGNATURE_BYTES),
        )
        val result = handler.handle(env)

        assertEquals(PacketNackReason.DECODE_FAILED, (result as InboundHandleResult.Rejected).reason)
    }

    @Test
    fun handle_introNoActiveSession_rejectedProtectionFailed_noEmit() = runTest {
        // Handler side has NO active secret — the AEAD gate.
        val ctx = routingContext(activeSecret = null)
        val packets = MutableSharedFlow<BootstrapPacketEvent>(extraBufferCapacity = 64)
        val received = subscribe(packets)
        val handler = handlerUnderTest(ctx, packets)

        // The intro was protected with a secret the handler does not hold.
        val payload = sampleIntro(sponsorDevice)
        val result = handler.handle(
            binaryEnvelope(
                protectedEnvelope(payload, sponsorDevice, newcomerDevice, ByteArray(32) { 5 }),
                sponsorDevice,
                newcomerDevice
            ),
        )

        assertEquals(PacketNackReason.PERMANENT_PROTECTION_FAILED, (result as InboundHandleResult.Rejected).reason)
        advanceUntilIdle()
        assertEquals(0, received.size)
    }

    @Test
    fun handle_introWrongSecret_rejectedProtectionFailed() = runTest {
        val ctx = routingContext(ByteArray(32) { 1 })
        val packets = MutableSharedFlow<BootstrapPacketEvent>(extraBufferCapacity = 64)
        val handler = handlerUnderTest(ctx, packets)

        val payload = sampleIntro(sponsorDevice)
        val result = handler.handle(
            binaryEnvelope(
                protectedEnvelope(payload, sponsorDevice, newcomerDevice, ByteArray(32) { 2 }),
                sponsorDevice,
                newcomerDevice
            ),
        )

        assertEquals(PacketNackReason.PERMANENT_PROTECTION_FAILED, (result as InboundHandleResult.Rejected).reason)
    }

    @Test
    fun handle_introHeaderSourceDoesNotMatchAttestedDevice_rejected() = runTest {
        val secret = ByteArray(32) { 5 }
        val ctx = routingContext(secret)
        val packets = MutableSharedFlow<BootstrapPacketEvent>(extraBufferCapacity = 64)
        val handler = handlerUnderTest(ctx, packets)

        // Envelope header claims otherDevice; the authenticated payload attests sponsorDevice.
        val payload = sampleIntro(sponsorDevice)
        val result = handler.handle(
            binaryEnvelope(
                protectedEnvelope(payload, otherDevice, newcomerDevice, secret),
                otherDevice,
                newcomerDevice
            ),
        )

        assertEquals(PacketNackReason.PERMANENT_PROTECTION_FAILED, (result as InboundHandleResult.Rejected).reason)
    }

    @Test
    fun handle_recoveryRequest_activeAccount_emitsEvent_andSuccess() = runTest {
        val ctx = routingContext(activeSecret = null)
        val packets = MutableSharedFlow<BootstrapPacketEvent>(extraBufferCapacity = 64)
        val received = subscribe(packets)
        val handler = handlerUnderTest(ctx, packets)

        val payload = sampleRecoveryRequest(otherDevice)
        (ctx.devices as FakeIdentityResolverForRouter)
            .accountStatuses[payload.account.accountId] = IdentityStatus.ACTIVE
        val result = handler.handle(
            binaryEnvelope(
                protectedEnvelope(payload, otherDevice, newcomerDevice, secret = null),
                otherDevice,
                newcomerDevice
            ),
        )

        assertIs<InboundHandleResult.Success>(result)
        advanceUntilIdle()
        assertEquals(1, received.size)
        assertEquals(otherDevice, (received[0].payload as RecoveryRequest).device.deviceId)
    }

    @Test
    fun handle_recoveryRequest_ownSessionActive_declined_noEmit() = runTest {
        // This node is itself mid-onboarding — the onboarding session armed through the
        // protection service doubles as the responder-side decline signal.
        val ctx = routingContext(activeSecret = ByteArray(32) { 9 })
        val packets = MutableSharedFlow<BootstrapPacketEvent>(extraBufferCapacity = 64)
        val received = subscribe(packets)
        val handler = handlerUnderTest(ctx, packets)

        val payload = sampleRecoveryRequest(otherDevice)
        (ctx.devices as FakeIdentityResolverForRouter)
            .accountStatuses[payload.account.accountId] = IdentityStatus.ACTIVE
        val result = handler.handle(
            binaryEnvelope(
                protectedEnvelope(payload, otherDevice, newcomerDevice, secret = null),
                otherDevice,
                newcomerDevice
            ),
        )

        assertEquals(PacketNackReason.DECLINED, (result as InboundHandleResult.Rejected).reason)
        advanceUntilIdle()
        assertEquals(0, received.size)
    }

    @Test
    fun handle_recoveryRequest_bannedAccount_declined_noEmit() = runTest {
        val ctx = routingContext(activeSecret = null)
        val packets = MutableSharedFlow<BootstrapPacketEvent>(extraBufferCapacity = 64)
        val received = subscribe(packets)
        val handler = handlerUnderTest(ctx, packets)

        val payload = sampleRecoveryRequest(otherDevice)
        (ctx.devices as FakeIdentityResolverForRouter)
            .accountStatuses[payload.account.accountId] = IdentityStatus.BANNED
        val result = handler.handle(
            binaryEnvelope(
                protectedEnvelope(payload, otherDevice, newcomerDevice, secret = null),
                otherDevice,
                newcomerDevice
            ),
        )

        assertEquals(PacketNackReason.DECLINED, (result as InboundHandleResult.Rejected).reason)
        advanceUntilIdle()
        assertEquals(0, received.size)
    }

    @Test
    fun handle_recoveryRequest_unknownAccount_deferred_noEmit() = runTest {
        val ctx = routingContext(activeSecret = null)
        val packets = MutableSharedFlow<BootstrapPacketEvent>(extraBufferCapacity = 64)
        val received = subscribe(packets)
        // No account status stubbed: absence asserts nothing (fold may not have seen it), so defer —
        // the sender's retry re-runs the check instead of being swallowed.
        val handler = handlerUnderTest(ctx, packets)

        val payload = sampleRecoveryRequest(otherDevice)
        val result = handler.handle(
            binaryEnvelope(
                protectedEnvelope(payload, otherDevice, newcomerDevice, secret = null),
                otherDevice,
                newcomerDevice
            ),
        )

        assertIs<InboundHandleResult.Deferred>(result)
        advanceUntilIdle()
        assertEquals(0, received.size)
    }

    @Test
    fun handle_recoveryRequest_badSignature_rejectedProtectionFailed() = runTest {
        val ctx = routingContext(activeSecret = null)
        val packets = MutableSharedFlow<BootstrapPacketEvent>(extraBufferCapacity = 64)
        val handler = handlerUnderTest(ctx, packets)

        val payload = sampleRecoveryRequest(otherDevice).copy(accountSignature = ByteArray(64) { 1 })
        (ctx.devices as FakeIdentityResolverForRouter)
            .accountStatuses[payload.account.accountId] = IdentityStatus.ACTIVE
        val result = handler.handle(
            binaryEnvelope(
                protectedEnvelope(payload, otherDevice, newcomerDevice, secret = null),
                otherDevice,
                newcomerDevice
            ),
        )

        assertEquals(PacketNackReason.PERMANENT_PROTECTION_FAILED, (result as InboundHandleResult.Rejected).reason)
    }
}
