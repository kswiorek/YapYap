package org.yapyap.protection.envelope

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.signature.DefaultSignatureProvider
import org.yapyap.protection.FakeIdentityResolverForProtection
import org.yapyap.protection.FixturePeerIds
import org.yapyap.protection.ProtectionException
import org.yapyap.protection.deviceRecordFor
import org.yapyap.protection.nonce24
import org.yapyap.protection.sampleEnvelopeContext
import org.yapyap.protection.samplePacketAckPayload
import org.yapyap.protection.samplePacketNackPayload
import org.yapyap.protection.samplePeerTriplet
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.envelopes.SystemEnvelope
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SystemProtectionTest {

    private val crypto = DefaultCryptoProvider()

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
        assertFailsWith<ProtectionException> {
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
        val protection = SignedSystemProtection(DefaultSignatureProvider(resolver, resolver, crypto), crypto)

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
        val protection = SignedSystemProtection(DefaultSignatureProvider(resolver, resolver, crypto), crypto)

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
        val protection = SignedSystemProtection(DefaultSignatureProvider(resolver, resolver, crypto), crypto)

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

        val ex = assertFailsWith<ProtectionException.AuthenticationFailed> {
            protection.open(tamperedEnvelope)
        }
        assertTrue(ex.message!!.contains("signature", ignoreCase = true))
    }

    @Test
    fun plaintext_open_throwsWhenEnvelopeNotPlaintext() = runTest {
        val protection = PlaintextSystemProtection(crypto)
        val payload = samplePacketAckPayload()
        val envelope = SystemEnvelope(
            systemEnvelopeId = Uuid.random(),
            source = FixturePeerIds.A,
            target = FixturePeerIds.B,
            createdAt = epochSeconds(1L),
            nonce = nonce24(),
            securityScheme = SignalSecurityScheme.SIGNED,
            signature = ByteArray(64),
            payload = payload.encode(),
        )
        assertFailsWith<ProtectionException> {
            protection.open(envelope)
        }
    }
}
