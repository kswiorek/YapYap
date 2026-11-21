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
import org.yapyap.protection.samplePeerTriplet
import org.yapyap.protection.sampleWebRtcSignal
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.envelopes.WebRtcSignalEnvelope
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class WebRtcSignalProtectionTest {

    private val crypto = DefaultCryptoProvider()

    @Test
    fun plaintext_protectThenOpen_roundTrip() = runTest {
        val protection = PlaintextWebRtcSignalProtection(crypto)
        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            source = FixturePeerIds.A,
            target = FixturePeerIds.B,
        )
        val input = sampleWebRtcSignal(FixturePeerIds.A, FixturePeerIds.B)
        val envelope = protection.protect(input, ctx)
        val opened = protection.open(envelope)
        assertEquals(input.target, opened.target)
        assertEquals(input.kind, opened.kind)
        assertContentEquals(input.payload, opened.payload)
    }

    @Test
    fun plaintext_protect_throwsWhenSecuritySchemeNotPlaintext() = runTest {
        val protection = PlaintextWebRtcSignalProtection(crypto)
        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.SIGNED,
            source = FixturePeerIds.A,
            target = FixturePeerIds.B,
        )
        val input = sampleWebRtcSignal(FixturePeerIds.A, FixturePeerIds.B)
        assertFailsWith<ProtectionException> {
            protection.protect(input, ctx)
        }
    }

    @Test
    fun plaintext_open_throwsWhenEnvelopeNotPlaintext() = runTest {
        val protection = PlaintextWebRtcSignalProtection(crypto)
        val input = sampleWebRtcSignal(FixturePeerIds.A, FixturePeerIds.B)
        val envelope = WebRtcSignalEnvelope(
            signalEnvelopeId = Uuid.random(),
            kind = input.kind,
            source = FixturePeerIds.A,
            target = FixturePeerIds.B,
            createdAt = epochSeconds(1L),
            nonce = nonce24(),
            securityScheme = SignalSecurityScheme.SIGNED,
            signature = ByteArray(64),
            payload = input.payload,
        )
        assertFailsWith<ProtectionException> {
            protection.open(envelope)
        }
    }

    @Test
    fun signed_protectThenOpen_roundTrip() = runTest {
        val (signingKeys, sourcePeer, targetPeer) = samplePeerTriplet(crypto)
        val encryptionKeys = crypto.generateEncryptionKeyPair()
        val record = deviceRecordFor(crypto, signingKeys, encryptionKeys)
        val resolver = FakeIdentityResolverForProtection(
            localSigningPrivateKey = signingKeys.privateKey,
            peerRecords = mapOf(sourcePeer to record),
        )
        val signatureProvider = DefaultSignatureProvider(resolver, resolver, crypto)
        val protection = SignedWebRtcSignalProtection(signatureProvider, crypto)

        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.SIGNED,
            source = sourcePeer,
            target = targetPeer,
        )
        val input = sampleWebRtcSignal(sourcePeer, targetPeer)
        val envelope = protection.protect(input, ctx)
        val opened = protection.open(envelope)
        assertEquals(input.target, opened.target)
        assertContentEquals(input.payload, opened.payload)
    }

    @Test
    fun signed_protect_throwsWhenContextSchemeNotSigned() = runTest {
        val (signingKeys, sourcePeer, targetPeer) = samplePeerTriplet(crypto)
        val encryptionKeys = crypto.generateEncryptionKeyPair()
        val record = deviceRecordFor(crypto, signingKeys, encryptionKeys)
        val resolver = FakeIdentityResolverForProtection(signingKeys.privateKey, mapOf(sourcePeer to record))
        val protection = SignedWebRtcSignalProtection(DefaultSignatureProvider(resolver, resolver, crypto), crypto)

        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            source = sourcePeer,
            target = targetPeer,
        )
        assertFailsWith<ProtectionException> {
            protection.protect(sampleWebRtcSignal(sourcePeer, targetPeer), ctx)
        }
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
        val signatureProvider = DefaultSignatureProvider(resolver, resolver, crypto)
        val protection = SignedWebRtcSignalProtection(signatureProvider, crypto)

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

        val ex = assertFailsWith<ProtectionException.AuthenticationFailed> {
            protection.open(tampered)
        }
        assertTrue(ex.message!!.contains("signature", ignoreCase = true))
    }
}
