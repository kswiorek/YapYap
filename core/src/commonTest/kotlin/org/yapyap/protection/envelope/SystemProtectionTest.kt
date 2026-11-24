package org.yapyap.protection.envelope

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.e2ee.buildTestPeerIdentity
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.signature.DefaultSignatureProvider
import org.yapyap.protection.FakeIdentityResolverForProtection
import org.yapyap.protection.samplePacketAckPayload
import org.yapyap.protection.samplePacketNackPayload
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.envelopes.UnsignedEnvelope
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * SYSTEM has no content protection: packets are header-only and hop-signed. These tests cover
 * the [SystemPayload] codec round-trips and the hop sign/verify round-trip over them via
 * [SignedHopProtection] — tampered bytes fail verification, unknown sources verify false.
 */
class SystemProtectionTest {

    private val crypto = DefaultCryptoProvider()

    private data class HopPair(
        val sender: SignedHopProtection,
        val receiver: SignedHopProtection,
        val sourcePeer: PeerId,
        val targetPeer: PeerId,
    )

    private suspend fun sampleHopPair(): HopPair {
        val senderPeer = buildTestPeerIdentity(crypto, "sys-sender")
        val receiverPeer = buildTestPeerIdentity(crypto, "sys-receiver")
        val senderResolver = FakeIdentityResolverForProtection(
            localSigningPrivateKey = senderPeer.signingPrivateKey,
            peerRecords = emptyMap(),
        )
        val receiverResolver = FakeIdentityResolverForProtection(
            localSigningPrivateKey = receiverPeer.signingPrivateKey,
            peerRecords = mapOf(senderPeer.device.deviceId to senderPeer.device),
        )
        return HopPair(
            sender = SignedHopProtection(DefaultSignatureProvider(senderResolver, senderResolver, crypto), crypto),
            receiver = SignedHopProtection(
                DefaultSignatureProvider(receiverResolver, receiverResolver, crypto),
                crypto,
            ),
            sourcePeer = senderPeer.device.deviceId,
            targetPeer = receiverPeer.device.deviceId,
        )
    }

    private fun unsignedSystem(
        payload: SystemPayload,
        source: PeerId,
        target: PeerId,
        packetId: Uuid = Uuid.random(),
    ): UnsignedEnvelope {
        val now = epochSeconds(1_700_000_000L)
        return UnsignedEnvelope(
            packetId = packetId,
            packetType = PacketType.SYSTEM,
            dispositionRequested = false,
            createdAt = now,
            expiresAt = now,
            source = source,
            target = target,
            routingHeader = payload.encode(),
            content = byteArrayOf(),
        )
    }

    @Test
    fun packetAck_signVerify_roundTrip() = runTest {
        val pair = sampleHopPair()
        val payload = samplePacketAckPayload()
        val envelope = pair.sender.sign(unsignedSystem(payload, pair.sourcePeer, pair.targetPeer))
        assertTrue(pair.receiver.verify(envelope))
        assertEquals(payload, SystemPayload.decode(envelope.routingHeader))
        assertTrue(envelope.content.isEmpty(), "SYSTEM packets have no content")
    }

    @Test
    fun packetNack_signVerify_roundTrip() = runTest {
        val pair = sampleHopPair()
        val payload = samplePacketNackPayload(reasonText = null)
        val envelope = pair.sender.sign(unsignedSystem(payload, pair.sourcePeer, pair.targetPeer))
        assertTrue(pair.receiver.verify(envelope))
        assertEquals(payload, SystemPayload.decode(envelope.routingHeader))
    }

    @Test
    fun typingIndicator_codec_roundTrip() = runTest {
        val payload = SystemPayload.TypingIndicator(roomId = RoomId(Uuid.random()), intervalMillis = 5_000)
        assertEquals(payload, SystemPayload.decode(payload.encode()))
    }

    @Test
    fun ping_codec_roundTrip() = runTest {
        val payload = SystemPayload.Ping(
            pingId = Uuid.random(),
            isReply = false,
            selfReportedAvailability = 0.5,
            roomFrontiers = listOf(RoomId(Uuid.random()) to listOf(Uuid.random())),
        )
        val decoded = SystemPayload.decode(payload.encode()) as SystemPayload.Ping
        assertEquals(payload.pingId, decoded.pingId)
        assertEquals(payload.isReply, decoded.isReply)
        assertEquals(payload.roomFrontiers, decoded.roomFrontiers)
    }

    @Test
    fun logOff_codec_roundTrip() = runTest {
        assertEquals(SystemPayload.LogOff, SystemPayload.decode(SystemPayload.LogOff.encode()))
    }

    @Test
    fun verify_tamperedRoutingHeader_fails() = runTest {
        val pair = sampleHopPair()
        val envelope = pair.sender.sign(
            unsignedSystem(samplePacketAckPayload(), pair.sourcePeer, pair.targetPeer),
        )
        val tamperedHeader = envelope.routingHeader.copyOf().also {
            it[0] = (it[0].toInt() xor 0xff).toByte()
        }
        val tampered = envelope.unsigned().copyForTest(routingHeader = tamperedHeader)
            .signed(envelope.signature)
        assertFalse(pair.receiver.verify(tampered))
    }

    @Test
    fun verify_tamperedContent_fails() = runTest {
        val pair = sampleHopPair()
        val base = unsignedSystem(samplePacketAckPayload(), pair.sourcePeer, pair.targetPeer)
            .copyForTest(content = byteArrayOf(1, 2, 3))
        val envelope = pair.sender.sign(base)
        val tampered = envelope.unsigned().copyForTest(content = byteArrayOf(1, 2, 4))
            .signed(envelope.signature)
        assertFalse(pair.receiver.verify(tampered))
    }

    @Test
    fun verify_unknownSource_isFalse() = runTest {
        val pair = sampleHopPair()
        val envelope = pair.sender.sign(
            unsignedSystem(samplePacketAckPayload(), pair.sourcePeer, pair.targetPeer),
        )
        // A resolver with no key for the source cannot verify anything it signed.
        val strangerPeer = buildTestPeerIdentity(crypto, "sys-stranger")
        val strangerResolver = FakeIdentityResolverForProtection(
            localSigningPrivateKey = strangerPeer.signingPrivateKey,
            peerRecords = emptyMap(),
        )
        val strangerHop = SignedHopProtection(
            DefaultSignatureProvider(strangerResolver, strangerResolver, crypto),
            crypto,
        )
        assertFalse(strangerHop.verify(envelope))
    }
}

private fun UnsignedEnvelope.copyForTest(
    routingHeader: ByteArray = this.routingHeader,
    content: ByteArray = this.content,
): UnsignedEnvelope = UnsignedEnvelope(
    packetId = packetId,
    packetType = packetType,
    dispositionRequested = dispositionRequested,
    createdAt = createdAt,
    expiresAt = expiresAt,
    source = source,
    target = target,
    routingHeader = routingHeader,
    content = content,
)
