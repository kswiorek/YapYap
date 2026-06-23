package org.yapyap.backend.protection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.yapyap.backend.crypto.DefaultSignatureProvider
import org.yapyap.backend.crypto.KmpCryptoProvider
import org.yapyap.backend.protocol.MessageEnvelope
import org.yapyap.backend.protocol.SignalSecurityScheme

class MessageProtectionTest {

    private val crypto = KmpCryptoProvider()

    @Test
    fun plaintext_protectThenOpen_roundTrip() = runTest {
        val protection = PlaintextMessageProtection(crypto)
        val payload = sampleTextPayload()
        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            source = FixturePeerIds.A,
            target = FixturePeerIds.B,
        )
        val envelope = protection.protect(payload, ctx)
        val opened = protection.open(envelope)
        assertEquals(payload.messageId, opened.messageId)
        assertEquals(payload, opened)
    }

    @Test
    fun plaintext_protect_throwsWhenSecuritySchemeNotPlaintext() = runTest {
        val protection = PlaintextMessageProtection(crypto)
        val payload = sampleTextPayload()
        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.SIGNED,
            source = FixturePeerIds.A,
            target = FixturePeerIds.B,
        )
        assertFailsWith<IllegalArgumentException> {
            protection.protect(payload, ctx)
        }
    }

    @Test
    fun plaintext_open_throwsWhenEnvelopeNotPlaintext() = runTest {
        val protection = PlaintextMessageProtection(crypto)
        val payload = sampleTextPayload()
        val envelope = MessageEnvelope(
            messageId = payload.messageId,
            source = FixturePeerIds.A,
            target = FixturePeerIds.B,
            createdAtEpochSeconds = 1L,
            nonce = nonce24(),
            securityScheme = SignalSecurityScheme.SIGNED,
            signature = ByteArray(64), // invalid but scheme check runs first
            payload = payload.encode(),
        )
        assertFailsWith<IllegalArgumentException> {
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
        val signatureProvider = DefaultSignatureProvider(resolver, crypto)
        val protection = SignedMessageProtection(signatureProvider, crypto)

        val payload = sampleTextPayload("signed-msg-1")
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
    fun signed_protect_throwsWhenContextSchemeNotSigned() = runTest {
        val (signingKeys, sourcePeer, targetPeer) = samplePeerTriplet(crypto)
        val encryptionKeys = crypto.generateEncryptionKeyPair()
        val record = deviceRecordFor(crypto, signingKeys, encryptionKeys)
        val resolver = FakeIdentityResolverForProtection(signingKeys.privateKey, mapOf(sourcePeer to record))
        val protection = SignedMessageProtection(DefaultSignatureProvider(resolver, crypto), crypto)

        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
            source = sourcePeer,
            target = targetPeer,
        )
        assertFailsWith<IllegalArgumentException> {
            protection.protect(sampleTextPayload(), ctx)
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
        val signatureProvider = DefaultSignatureProvider(resolver, crypto)
        val protection = SignedMessageProtection(signatureProvider, crypto)

        val payload = sampleTextPayload("tamper-msg")
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
    fun signedAndEncrypted_protectThenOpen_roundTrip() = runTest {
        val pair = sampleSignedAndEncryptedProtectionPair(crypto)
        val payload = sampleTextPayload("signed-encrypted-msg-1")
        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
            source = pair.sourcePeer,
            target = pair.targetPeer,
        )
        val envelope = pair.sender.protect(payload, ctx)
        val opened = pair.receiver.open(envelope)
        assertEquals(payload, opened)
    }

    @Test
    fun signedAndEncrypted_protect_throwsWhenContextSchemeNotEncryptedAndSigned() = runTest {
        val pair = sampleSignedAndEncryptedProtectionPair(crypto)
        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.SIGNED,
            source = pair.sourcePeer,
            target = pair.targetPeer,
        )
        assertFailsWith<IllegalArgumentException> {
            pair.sender.protect(sampleTextPayload(), ctx)
        }
    }

    @Test
    fun signedAndEncrypted_open_throwsWhenSignatureInvalid() = runTest {
        val pair = sampleSignedAndEncryptedProtectionPair(crypto)
        val payload = sampleTextPayload("signed-encrypted-tamper-msg")
        val ctx = sampleEnvelopeContext(
            scheme = SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
            source = pair.sourcePeer,
            target = pair.targetPeer,
        )
        val envelope = pair.sender.protect(payload, ctx)
        val corruptSignature = envelope.signature!!.copyOf().also {
            it[0] = (it[0].toInt() xor 0xff).toByte()
        }
        val tamperedEnvelope = envelope.copy(signature = corruptSignature)

        val ex = assertFailsWith<IllegalArgumentException> {
            pair.receiver.open(tamperedEnvelope)
        }
        assertTrue(ex.message!!.contains("signature", ignoreCase = true))
    }
}
