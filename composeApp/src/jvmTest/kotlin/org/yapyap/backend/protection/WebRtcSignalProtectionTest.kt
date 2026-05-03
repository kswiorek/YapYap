package org.yapyap.backend.protection

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.yapyap.backend.crypto.DefaultSignatureProvider
import org.yapyap.backend.crypto.KmpCryptoProvider
import org.yapyap.backend.protocol.SignalSecurityScheme
import org.yapyap.backend.transport.webrtc.WebRtcSignalEnvelope
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal

class WebRtcSignalProtectionTest {

    private val crypto = KmpCryptoProvider()

    @Test
    fun plaintext_protectThenOpen_roundTrip() {
        val protection = PlaintextWebRtcSignalProtection()
        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            source = FixturePeerIds.A,
            target = FixturePeerIds.B,
        )
        val input = sampleWebRtcSignal(FixturePeerIds.A, FixturePeerIds.B)
        val envelope = protection.protect(input, ctx)
        val opened = protection.open(envelope)
        assertEquals(input.sessionId, opened.sessionId)
        assertEquals(input.kind, opened.kind)
        assertContentEquals(input.payload, opened.payload)
    }

    @Test
    fun plaintext_protect_throwsWhenSecuritySchemeNotPlaintext() {
        val protection = PlaintextWebRtcSignalProtection()
        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.SIGNED,
            source = FixturePeerIds.A,
            target = FixturePeerIds.B,
        )
        val input = sampleWebRtcSignal(FixturePeerIds.A, FixturePeerIds.B)
        assertFailsWith<IllegalArgumentException> {
            protection.protect(input, ctx)
        }
    }

    @Test
    fun plaintext_open_throwsWhenEnvelopeNotPlaintext() {
        val protection = PlaintextWebRtcSignalProtection()
        val input = sampleWebRtcSignal(FixturePeerIds.A, FixturePeerIds.B)
        val envelope = WebRtcSignalEnvelope(
            sessionId = input.sessionId,
            kind = input.kind,
            source = FixturePeerIds.A,
            target = FixturePeerIds.B,
            createdAtEpochSeconds = 1L,
            nonce = nonce24(),
            securityScheme = SignalSecurityScheme.SIGNED,
            signature = ByteArray(64),
            protectedPayload = input.payload,
        )
        assertFailsWith<IllegalArgumentException> {
            protection.open(envelope)
        }
    }

    @Test
    fun signed_protectThenOpen_roundTrip() {
        val (signingKeys, sourcePeer, targetPeer) = samplePeerTriplet(crypto)
        val encryptionKeys = crypto.generateEncryptionKeyPair()
        val record = deviceRecordFor(crypto, signingKeys, encryptionKeys)
        val resolver = FakeIdentityResolverForProtection(
            localSigningPrivateKey = signingKeys.privateKey,
            peerRecords = mapOf(sourcePeer to record),
        )
        val signatureProvider = DefaultSignatureProvider(resolver, crypto)
        val protection = SignedWebRtcSignalProtection(signatureProvider)

        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.SIGNED,
            source = sourcePeer,
            target = targetPeer,
        )
        val input = sampleWebRtcSignal(sourcePeer, targetPeer)
        val envelope = protection.protect(input, ctx)
        val opened = protection.open(envelope)
        assertEquals(input.sessionId, opened.sessionId)
        assertContentEquals(input.payload, opened.payload)
    }

    @Test
    fun signed_protect_throwsWhenContextSchemeNotSigned() {
        val (signingKeys, sourcePeer, targetPeer) = samplePeerTriplet(crypto)
        val encryptionKeys = crypto.generateEncryptionKeyPair()
        val record = deviceRecordFor(crypto, signingKeys, encryptionKeys)
        val resolver = FakeIdentityResolverForProtection(signingKeys.privateKey, mapOf(sourcePeer to record))
        val protection = SignedWebRtcSignalProtection(DefaultSignatureProvider(resolver, crypto))

        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            source = sourcePeer,
            target = targetPeer,
        )
        assertFailsWith<IllegalArgumentException> {
            protection.protect(sampleWebRtcSignal(sourcePeer, targetPeer), ctx)
        }
    }

    @Test
    fun signed_open_throwsWhenSignatureInvalid() {
        val (signingKeys, sourcePeer, targetPeer) = samplePeerTriplet(crypto)
        val encryptionKeys = crypto.generateEncryptionKeyPair()
        val record = deviceRecordFor(crypto, signingKeys, encryptionKeys)
        val resolver = FakeIdentityResolverForProtection(
            localSigningPrivateKey = signingKeys.privateKey,
            peerRecords = mapOf(sourcePeer to record),
        )
        val signatureProvider = DefaultSignatureProvider(resolver, crypto)
        val protection = SignedWebRtcSignalProtection(signatureProvider)

        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.SIGNED,
            source = sourcePeer,
            target = targetPeer,
        )
        val input = sampleWebRtcSignal(sourcePeer, targetPeer)
        val envelope = protection.protect(input, ctx)
        val corruptSignature = envelope.signature!!.copyOf().also {
            it[0] = (it[0].toInt() xor 0xff).toByte()
        }
        val tampered = envelope.copy(signature = corruptSignature)

        val ex = assertFailsWith<IllegalArgumentException> {
            protection.open(tampered)
        }
        assertTrue(ex.message!!.contains("signature", ignoreCase = true))
    }
}
