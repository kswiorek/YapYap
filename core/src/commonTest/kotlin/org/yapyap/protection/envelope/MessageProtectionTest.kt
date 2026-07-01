package org.yapyap.protection.envelope

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.identity.*
import org.yapyap.crypto.primitives.KmpCryptoProvider
import org.yapyap.crypto.signature.DefaultSignatureProvider
import org.yapyap.persistence.db.DeviceType
import org.yapyap.persistence.key.InMemoryIdentityKeyRepository
import org.yapyap.persistence.key.InMemoryKeyStore
import org.yapyap.persistence.key.KeyReference
import org.yapyap.persistence.key.KeyType
import org.yapyap.protection.*
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.MessageEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

        val ex = assertFailsWith<ProtectionException.SignatureVerificationFailed> {
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

        val ex = assertFailsWith<ProtectionException.SignatureVerificationFailed> {
            pair.receiver.open(tamperedEnvelope)
        }
        assertTrue(ex.message!!.contains("signature", ignoreCase = true))
    }

    @Test
    fun signed_open_failsWhenPeerEncryptionKeyAttestationInvalid() = runTest {
        val crypto = KmpCryptoProvider()
        val repo = InMemoryIdentityKeyRepository()
        val store = InMemoryKeyStore()
        val config = IdentityKeyServiceConfig()
        val resolver = DefaultIdentityResolver(crypto, repo, store, config)

        val senderSigning = crypto.generateSigningKeyPair()
        val senderEncryption = crypto.generateEncryptionKeyPair()
        val senderPeer = crypto.peerIdFromPublicKey(senderSigning.publicKey)
        val encryptionKeyId = "${config.defaultDeviceLocalKeyPrefix}encryption"
        val signingKeyId = "${config.defaultDeviceLocalKeyPrefix}signing"

        store.putKey(
            KeyReference(signingKeyId, IdentityKeyPurpose.SIGNING, KeyType.PRIVATE),
            senderSigning.privateKey,
        )
        store.putKey(
            KeyReference(signingKeyId, IdentityKeyPurpose.SIGNING, KeyType.PUBLIC),
            senderSigning.publicKey,
        )

        val validKeySignature = crypto.signDetached(
            senderSigning.privateKey,
            senderEncryption.publicKey + encryptionKeyId.encodeToByteArray(),
        )
        val invalidKeySignature = validKeySignature.copyOf().also {
            it[0] = (it[0].toInt() xor 0xff).toByte()
        }

        val accountId = AccountId("protection-attest-account")
        repo.insertLocalAccount(
            displayName = "Protection",
            identity = AccountIdentityRecord(
                accountId = accountId,
                key = IdentityPublicKeyRecord("acc-k", 0, IdentityKeyPurpose.SIGNING, byteArrayOf(0x01)),
            ),
        )
        repo.insertPeerDevice(
            accountId = accountId,
            deviceType = DeviceType.DESKTOP,
            identity = DeviceIdentityRecord(
                deviceId = senderPeer,
                signing = IdentityPublicKeyRecord(
                    keyId = signingKeyId,
                    keyVersion = 0,
                    purpose = IdentityKeyPurpose.SIGNING,
                    publicKey = senderSigning.publicKey,
                ),
                encryption = IdentityPublicKeyRecord(
                    keyId = encryptionKeyId,
                    keyVersion = 0,
                    purpose = IdentityKeyPurpose.ENCRYPTION,
                    publicKey = senderEncryption.publicKey,
                ),
                keySignature = invalidKeySignature,
            ),
            torEndpoint = TorEndpoint(onionAddress = "sender-attest.onion", port = 443),
        )

        val senderProtection = SignedMessageProtection(
            DefaultSignatureProvider(
                FakeIdentityResolverForProtection(
                    localSigningPrivateKey = senderSigning.privateKey,
                    peerRecords = emptyMap(),
                ),
                crypto,
            ),
            crypto,
        )
        val receiverProtection = SignedMessageProtection(DefaultSignatureProvider(resolver, crypto), crypto)

        val payload = sampleTextPayload("attest-invalid-msg")
        val envelope = senderProtection.protect(
            payload,
            sampleEnvelopeContext(
                scheme = SignalSecurityScheme.SIGNED,
                source = senderPeer,
                target = FixturePeerIds.B,
            ),
        )

        assertFailsWith<CryptoException.IncompleteRecord> {
            receiverProtection.open(envelope)
        }
    }
}
