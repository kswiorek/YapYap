package org.yapyap.backend.protection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.yapyap.backend.crypto.DefaultSignatureProvider
import org.yapyap.backend.crypto.KmpCryptoProvider
import org.yapyap.backend.protocol.SignalSecurityScheme
import org.yapyap.backend.protocol.SystemEnvelope

class SystemProtectionTest {

    private val crypto = KmpCryptoProvider()

    @Test
    fun plaintext_protectThenOpen_packetAck_roundTrip() = runTest {
        val protection = PlaintextSystemProtection(crypto)
        val payload = samplePacketAckPayload()
        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            source = FixturePeerIds.A,
            target = FixturePeerIds.B,
        )
        val envelope = protection.protect(payload, ctx)
        val opened = protection.open(envelope)
        assertEquals(payload, opened)
    }

    @Test
    fun plaintext_protectThenOpen_packetNack_roundTrip() = runTest {
        val protection = PlaintextSystemProtection(crypto)
        val payload = samplePacketNackPayload(reasonText = null)
        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            source = FixturePeerIds.A,
            target = FixturePeerIds.B,
        )
        val envelope = protection.protect(payload, ctx)
        val opened = protection.open(envelope)
        assertEquals(payload, opened)
    }

    @Test
    fun plaintext_protect_throwsWhenSecuritySchemeNotPlaintext() = runTest {
        val protection = PlaintextSystemProtection(crypto)
        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.SIGNED,
            source = FixturePeerIds.A,
            target = FixturePeerIds.B,
        )
        assertFailsWith<IllegalArgumentException> {
            protection.protect(samplePacketAckPayload(), ctx)
        }
    }

    @Test
    fun signed_protectThenOpen_packetAck_roundTrip() = runTest {
        val (signingKeys, sourcePeer, targetPeer) = samplePeerTriplet(crypto)
        val encryptionKeys = crypto.generateEncryptionKeyPair()
        val record = deviceRecordFor(crypto, signingKeys, encryptionKeys)
        val resolver = FakeIdentityResolverForProtection(
            localSigningPrivateKey = signingKeys.privateKey,
            peerRecords = mapOf(sourcePeer to record),
        )
        val protection = SignedSystemProtection(DefaultSignatureProvider(resolver, crypto), crypto)

        val payload = samplePacketAckPayload()
        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.SIGNED,
            source = sourcePeer,
            target = targetPeer,
        )
        val envelope = protection.protect(payload, ctx)
        val opened = protection.open(envelope)
        assertEquals(payload, opened)
    }

    @Test
    fun signed_protectThenOpen_packetNack_roundTrip() = runTest {
        val (signingKeys, sourcePeer, targetPeer) = samplePeerTriplet(crypto)
        val encryptionKeys = crypto.generateEncryptionKeyPair()
        val record = deviceRecordFor(crypto, signingKeys, encryptionKeys)
        val resolver = FakeIdentityResolverForProtection(
            localSigningPrivateKey = signingKeys.privateKey,
            peerRecords = mapOf(sourcePeer to record),
        )
        val protection = SignedSystemProtection(DefaultSignatureProvider(resolver, crypto), crypto)

        val payload = samplePacketNackPayload()
        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.SIGNED,
            source = sourcePeer,
            target = targetPeer,
        )
        val envelope = protection.protect(payload, ctx)
        val opened = protection.open(envelope)
        assertEquals(payload, opened)
    }

    @Test
    fun signed_open_throwsWhenSignatureInvalid() = runTest {
        val (signingKeys, sourcePeer, targetPeer) = samplePeerTriplet(crypto)
        val encryptionKeys = crypto.generateEncryptionKeyPair()
        val record = deviceRecordFor(crypto, signingKeys, encryptionKeys)
        val resolver = FakeIdentityResolverForProtection(
            localSigningPrivateKey = signingKeys.privateKey,
            peerRecords = mapOf(sourcePeer to record),
        )
        val protection = SignedSystemProtection(DefaultSignatureProvider(resolver, crypto), crypto)

        val payload = samplePacketAckPayload()
        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.SIGNED,
            source = sourcePeer,
            target = targetPeer,
        )
        val envelope = protection.protect(payload, ctx)
        val corruptSignature = envelope.signature!!.copyOf().also {
            it[0] = (it[0].toInt() xor 0xff).toByte()
        }
        val tamperedEnvelope = envelope.copy(signature = corruptSignature)

        val ex = assertFailsWith<IllegalArgumentException> {
            protection.open(tamperedEnvelope)
        }
        assertTrue(ex.message!!.contains("signature", ignoreCase = true))
    }

    @Test
    fun signed_protect_setsCorrelationIdFromPayload() = runTest {
        val (signingKeys, sourcePeer, targetPeer) = samplePeerTriplet(crypto)
        val encryptionKeys = crypto.generateEncryptionKeyPair()
        val record = deviceRecordFor(crypto, signingKeys, encryptionKeys)
        val resolver = FakeIdentityResolverForProtection(
            localSigningPrivateKey = signingKeys.privateKey,
            peerRecords = mapOf(sourcePeer to record),
        )
        val protection = SignedSystemProtection(DefaultSignatureProvider(resolver, crypto), crypto)

        val payload = samplePacketAckPayload()
        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.SIGNED,
            source = sourcePeer,
            target = targetPeer,
        )
        val envelope = protection.protect(payload, ctx)
        assertEquals("ack:${payload.packetId.toHex()}", envelope.correlationId)
    }

    @Test
    fun plaintext_open_throwsWhenEnvelopeNotPlaintext() = runTest {
        val protection = PlaintextSystemProtection(crypto)
        val payload = samplePacketAckPayload()
        val envelope = SystemEnvelope(
            correlationId = "ack:${payload.packetId.toHex()}",
            source = FixturePeerIds.A,
            target = FixturePeerIds.B,
            createdAtEpochSeconds = 1L,
            nonce = nonce24(),
            securityScheme = SignalSecurityScheme.SIGNED,
            signature = ByteArray(64),
            payload = payload,
        )
        assertFailsWith<IllegalArgumentException> {
            protection.open(envelope)
        }
    }
}
