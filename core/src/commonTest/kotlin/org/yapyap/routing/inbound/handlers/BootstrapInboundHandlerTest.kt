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
import org.yapyap.persistence.db.AccountStatus
import org.yapyap.persistence.db.DeviceType
import org.yapyap.persistence.key.BootstrapKeySource
import org.yapyap.persistence.key.BootstrapSessionStore
import org.yapyap.persistence.key.InMemoryIdentityKeyRepository
import org.yapyap.persistence.key.InMemoryKeyStore
import org.yapyap.protection.PassthroughFileProtection
import org.yapyap.protection.envelope.BootstrapProtection
import org.yapyap.protection.envelope.PlaintextMessageProtection
import org.yapyap.protection.envelope.PlaintextSystemProtection
import org.yapyap.protection.envelope.PlaintextWebRtcSignalProtection
import org.yapyap.protection.service.DefaultEnvelopeProtectionService
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.*
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
            bootstrapProtection = BootstrapProtection(crypto, BootstrapKeySource { activeSecret }),
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

    private suspend fun deviceRecord(deviceId: PeerId): DeviceIdentityRecord {
        val signing = crypto.generateSigningKeyPair()
        val encryption = crypto.generateEncryptionKeyPair()
        return DeviceIdentityRecord(
            deviceId = deviceId,
            signing = IdentityPublicKeyRecord("device-signing", 0L, IdentityKeyPurpose.SIGNING, signing.publicKey),
            encryption = IdentityPublicKeyRecord(
                "device-encryption",
                0L,
                IdentityKeyPurpose.ENCRYPTION,
                encryption.publicKey
            ),
        )
    }

    private suspend fun sampleIntro(deviceId: PeerId): Intro =
        Intro(
            version = 1,
            account = AccountIdentityRecord(AccountId("sponsor-account"), "Sponsor", key = null),
            device = deviceRecord(deviceId),
            deviceType = DeviceType.DESKTOP,
            torEndpoint = TorEndpoint("sponsor.onion", 80),
            dagHeadLamport = 0L,
        )

    private suspend fun sampleRecoveryRequest(deviceId: PeerId): RecoveryRequest {
        val accountKeys = crypto.generateSigningKeyPair()
        val account = AccountIdentityRecord(
            AccountId("recovering-account"),
            "Recovering",
            key = IdentityPublicKeyRecord("account-signing", 0L, IdentityKeyPurpose.SIGNING, accountKeys.publicKey),
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
    ): BootstrapEnvelope =
        BootstrapProtection(crypto, BootstrapKeySource { secret })
            .protect(payload, source, target, createdAt = now, sharedSecret = secret)

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

    private fun handlerUnderTest(
        ctx: RoutingContext,
        packets: MutableSharedFlow<BootstrapPacketEvent>,
        sessionStore: BootstrapSessionStore,
        identityRepo: InMemoryIdentityKeyRepository,
    ): BootstrapInboundHandler =
        BootstrapInboundHandler(
            ctx = ctx,
            bootstrapPackets = packets,
            sessionStore = sessionStore,
            identityKeyRepository = identityRepo,
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
        // The newcomer holds an active session (its own onboarding wait) — intros must NOT be
        // declined by the own-session responder check.
        val sessionStore = BootstrapSessionStore(InMemoryKeyStore())
        sessionStore.setActiveSecret(secret, now + 5.minutes)
        val handler = handlerUnderTest(ctx, packets, sessionStore, InMemoryIdentityKeyRepository())

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
        val handler =
            handlerUnderTest(ctx, packets, BootstrapSessionStore(InMemoryKeyStore()), InMemoryIdentityKeyRepository())

        val payload = sampleIntro(sponsorDevice)
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
        val packets = MutableSharedFlow<BootstrapPacketEvent>(extraBufferCapacity = 64)
        val handler =
            handlerUnderTest(ctx, packets, BootstrapSessionStore(InMemoryKeyStore()), InMemoryIdentityKeyRepository())

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
    fun handle_introNoActiveSession_rejectedProtectionFailed_noEmit() = runTest {
        // Handler side has NO active secret — the AEAD gate.
        val ctx = routingContext(activeSecret = null)
        val packets = MutableSharedFlow<BootstrapPacketEvent>(extraBufferCapacity = 64)
        val received = subscribe(packets)
        val handler =
            handlerUnderTest(ctx, packets, BootstrapSessionStore(InMemoryKeyStore()), InMemoryIdentityKeyRepository())

        // The intro was protected with a secret the handler does not hold.
        val payload = sampleIntro(sponsorDevice)
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
    fun handle_introWrongSecret_rejectedProtectionFailed() = runTest {
        val ctx = routingContext(ByteArray(32) { 1 })
        val packets = MutableSharedFlow<BootstrapPacketEvent>(extraBufferCapacity = 64)
        val handler =
            handlerUnderTest(ctx, packets, BootstrapSessionStore(InMemoryKeyStore()), InMemoryIdentityKeyRepository())

        val payload = sampleIntro(sponsorDevice)
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
    fun handle_introHeaderSourceDoesNotMatchAttestedDevice_rejected() = runTest {
        val secret = ByteArray(32) { 5 }
        val ctx = routingContext(secret)
        val packets = MutableSharedFlow<BootstrapPacketEvent>(extraBufferCapacity = 64)
        val handler =
            handlerUnderTest(ctx, packets, BootstrapSessionStore(InMemoryKeyStore()), InMemoryIdentityKeyRepository())

        // Envelope header claims otherDevice; the authenticated payload attests sponsorDevice.
        val payload = sampleIntro(sponsorDevice)
        val result = handler.handle(
            binaryEnvelope(
                protectedEnvelope(payload, otherDevice, newcomerDevice, secret),
                otherDevice,
                newcomerDevice
            ),
        )

        assertEquals(PacketNackReason.PROTECTION_FAILED, (result as InboundHandleResult.Rejected).reason)
    }

    @Test
    fun handle_recoveryRequest_activeAccount_emitsEvent_andSuccess() = runTest {
        val ctx = routingContext(activeSecret = null)
        val packets = MutableSharedFlow<BootstrapPacketEvent>(extraBufferCapacity = 64)
        val received = subscribe(packets)
        val identityRepo = InMemoryIdentityKeyRepository()
        val handler = handlerUnderTest(ctx, packets, BootstrapSessionStore(InMemoryKeyStore()), identityRepo)

        val payload = sampleRecoveryRequest(otherDevice)
        identityRepo.insertPeerAccount(
            payload.account,
            admin = false,
            status = AccountStatus.ACTIVE,
            displayName = "Recovering"
        )
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
        val ctx = routingContext(activeSecret = null)
        val packets = MutableSharedFlow<BootstrapPacketEvent>(extraBufferCapacity = 64)
        val received = subscribe(packets)
        // This node is itself mid-onboarding — a poor sync seed, so it declines.
        val sessionStore = BootstrapSessionStore(InMemoryKeyStore())
        sessionStore.setActiveSecret(ByteArray(32) { 9 }, now + 5.minutes)
        val identityRepo = InMemoryIdentityKeyRepository()
        val handler = handlerUnderTest(ctx, packets, sessionStore, identityRepo)

        val payload = sampleRecoveryRequest(otherDevice)
        identityRepo.insertPeerAccount(
            payload.account,
            admin = false,
            status = AccountStatus.ACTIVE,
            displayName = "Recovering"
        )
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
        val identityRepo = InMemoryIdentityKeyRepository()
        val handler = handlerUnderTest(ctx, packets, BootstrapSessionStore(InMemoryKeyStore()), identityRepo)

        val payload = sampleRecoveryRequest(otherDevice)
        identityRepo.insertPeerAccount(
            payload.account,
            admin = false,
            status = AccountStatus.BANNED,
            displayName = "Recovering"
        )
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
        // No account row seeded: absence asserts nothing (fold may not have seen it), so defer —
        // the sender's retry re-runs the check instead of being swallowed.
        val handler =
            handlerUnderTest(ctx, packets, BootstrapSessionStore(InMemoryKeyStore()), InMemoryIdentityKeyRepository())

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
        val identityRepo = InMemoryIdentityKeyRepository()
        val handler = handlerUnderTest(ctx, packets, BootstrapSessionStore(InMemoryKeyStore()), identityRepo)

        val payload = sampleRecoveryRequest(otherDevice).copy(accountSignature = ByteArray(64) { 1 })
        identityRepo.insertPeerAccount(
            payload.account,
            admin = false,
            status = AccountStatus.ACTIVE,
            displayName = "Recovering"
        )
        val result = handler.handle(
            binaryEnvelope(
                protectedEnvelope(payload, otherDevice, newcomerDevice, secret = null),
                otherDevice,
                newcomerDevice
            ),
        )

        assertEquals(PacketNackReason.PROTECTION_FAILED, (result as InboundHandleResult.Rejected).reason)
    }
}
