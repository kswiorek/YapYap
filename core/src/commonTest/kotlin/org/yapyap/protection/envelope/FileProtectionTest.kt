package org.yapyap.protection.envelope

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.e2ee.MapBackedCryptoSessionStore
import org.yapyap.crypto.e2ee.buildTestPeerIdentity
import org.yapyap.crypto.e2ee.manager.SessionUpgradePolicy
import org.yapyap.crypto.e2ee.managerForPeer
import org.yapyap.crypto.e2ee.session.SessionStatus
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.signature.DefaultSignatureProvider
import org.yapyap.protection.FakeIdentityResolverForProtection
import org.yapyap.protection.ProtectionDisposition
import org.yapyap.protection.ProtectionException
import org.yapyap.protection.sampleEnvelopeContext
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.envelopes.ByteRange
import org.yapyap.protocol.envelopes.FileEnvelopeKind
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.testfixtures.InMemoryOpkRepository
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class FileProtectionTest {

    private val crypto = DefaultCryptoProvider()

    private data class FileProtectionPair(
        val sender: SignedAndEncryptedFileProtection,
        val receiver: SignedAndEncryptedFileProtection,
        val sourcePeer: PeerId,
        val targetPeer: PeerId,
    )

    private suspend fun sampleFileProtectionPair(): FileProtectionPair {
        val senderPeer = buildTestPeerIdentity(crypto, "saf-sender")
        val receiverPeer = buildTestPeerIdentity(crypto, "saf-receiver")
        val senderSession = managerForPeer(
            crypto = crypto,
            local = senderPeer,
            peer = receiverPeer,
            sessionStore = MapBackedCryptoSessionStore(),
            oneTimePreKeyStore = InMemoryOpkRepository(crypto),
        )
        val receiverSession = managerForPeer(
            crypto = crypto,
            local = receiverPeer,
            peer = senderPeer,
            sessionStore = MapBackedCryptoSessionStore(),
            oneTimePreKeyStore = InMemoryOpkRepository(crypto),
        )
        val senderSignatureProvider = FakeIdentityResolverForProtection(
            localSigningPrivateKey = senderPeer.signingPrivateKey,
            peerRecords = emptyMap(),
        ).let { DefaultSignatureProvider(it, it, crypto) }
        val receiverSignatureProvider = FakeIdentityResolverForProtection(
            localSigningPrivateKey = receiverPeer.signingPrivateKey,
            peerRecords = mapOf(senderPeer.device.deviceId to senderPeer.device),
        ).let { DefaultSignatureProvider(it, it, crypto) }
        return FileProtectionPair(
            sender = SignedAndEncryptedFileProtection(senderSignatureProvider, senderSession, crypto),
            receiver = SignedAndEncryptedFileProtection(receiverSignatureProvider, receiverSession, crypto),
            sourcePeer = senderPeer.device.deviceId,
            targetPeer = receiverPeer.device.deviceId,
        )
    }

    private fun sampleSealedOffer(key: ByteArray = ByteArray(32) { it.toByte() }): FilePayload.SealedOffer =
        FilePayload.SealedOffer(
            fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
            blockSize = 1024 * 1024,
            blockHashes = listOf(ByteArray(32) { 1 }),
            transferKey = key,
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
        )

    private fun fileContext(transferId: TransferId, source: PeerId, target: PeerId) =
        sampleEnvelopeContext(
            scheme = SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
            source = source,
            target = target,
        ).copy(transferId = transferId)

    @Test
    fun sealedOffer_sealOpen_roundTrip() = runTest {
        val pair = sampleFileProtectionPair()
        val offer = sampleSealedOffer()
        val sealed = pair.sender.sealOffer(offer, pair.targetPeer)
        assertEquals(offer, pair.receiver.openOffer(sealed, pair.sourcePeer))
    }

    @Test
    fun offer_protectOpen_roundTrip() = runTest {
        val pair = sampleFileProtectionPair()
        val transferId = TransferId(Uuid.random())
        val sealed = pair.sender.sealOffer(sampleSealedOffer(), pair.targetPeer)
        val offer = FilePayload.Offer(
            chunkSizeBytes = 220 * 1024,
            totalBytes = 1000L,
            startIndex = 0,
            sealedOfferBytes = sealed,
        )
        val envelope = pair.sender.protect(offer, fileContext(transferId, pair.sourcePeer, pair.targetPeer))
        val opened = pair.receiver.open(envelope)
        assertEquals(transferId, opened.transferId)
        assertEquals(offer, opened.payload)
        // The sealed half still needs an explicit open.
        assertEquals(
            sampleSealedOffer(),
            pair.receiver.openOffer((opened.payload as FilePayload.Offer).sealedOfferBytes, pair.sourcePeer),
        )
    }

    @Test
    fun request_protectOpen_roundTrip() = runTest {
        val pair = sampleFileProtectionPair()
        val transferId = TransferId(Uuid.random())
        val request = FilePayload.Request(
            fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
            contiguousBytes = 512L,
            missingRanges = listOf(ByteRange(1024L, 2048L)),
        )
        val envelope = pair.sender.protect(request, fileContext(transferId, pair.sourcePeer, pair.targetPeer))
        // The fileId never travels in clear: only the kind byte is visible.
        assertEquals(FileEnvelopeKind.REQUEST.wireValue, envelope.payload[0])
        assertEquals(request, pair.receiver.open(envelope).payload)
    }

    @Test
    fun chunk_encryptDecrypt_roundTrip() = runTest {
        val pair = sampleFileProtectionPair()
        val transferId = TransferId(Uuid.random())
        val key = SignedAndEncryptedFileProtection.generateTransferKey(crypto)
        val plaintext = "chunk-bytes".encodeToByteArray()
        val ciphertext = pair.sender.encryptChunk(transferId, 7, key, plaintext)
        val chunk = FilePayload.EncryptedChunk(chunkIndex = 7, chunkCiphertext = ciphertext)
        assertContentEquals(
            plaintext,
            pair.receiver.decryptChunk(transferId, 7, key, chunk),
        )
    }

    @Test
    fun chunk_aadBinding_replayUnderAnotherIndexOrTransferFails() = runTest {
        val pair = sampleFileProtectionPair()
        val transferId = TransferId(Uuid.random())
        val otherTransfer = TransferId(Uuid.random())
        val key = SignedAndEncryptedFileProtection.generateTransferKey(crypto)
        val ciphertext = pair.sender.encryptChunk(transferId, 7, key, "chunk-bytes".encodeToByteArray())
        val chunk = FilePayload.EncryptedChunk(chunkIndex = 7, chunkCiphertext = ciphertext)
        assertFailsWith<ProtectionException> {
            pair.receiver.decryptChunk(transferId, 8, key, chunk)
        }
        assertFailsWith<ProtectionException> {
            pair.receiver.decryptChunk(otherTransfer, 7, key, chunk)
        }
    }

    @Test
    fun chunk_samePlaintextUnderTwoTransfers_givesDifferentCiphertext() = runTest {
        val pair = sampleFileProtectionPair()
        val key = SignedAndEncryptedFileProtection.generateTransferKey(crypto)
        val plaintext = "same-bytes".encodeToByteArray()
        val first = pair.sender.encryptChunk(TransferId(Uuid.random()), 0, key, plaintext)
        val second = pair.sender.encryptChunk(TransferId(Uuid.random()), 0, key, plaintext)
        assertTrue(!first.contentEquals(second), "library-managed IV must randomize ciphertext")
    }

    @Test
    fun sealedOffer_decryptsAfterSessionSupersedeWithinRetention() = runTest {
        val senderPeer = buildTestPeerIdentity(crypto, "saf-late-sender")
        val receiverPeer = buildTestPeerIdentity(crypto, "saf-late-receiver")
        val receiverStore = MapBackedCryptoSessionStore()
        val senderSession = managerForPeer(
            crypto = crypto,
            local = senderPeer,
            peer = receiverPeer,
            sessionStore = MapBackedCryptoSessionStore(),
            oneTimePreKeyStore = InMemoryOpkRepository(crypto),
        )
        val receiverSession = managerForPeer(
            crypto = crypto,
            local = receiverPeer,
            peer = senderPeer,
            sessionStore = receiverStore,
            oneTimePreKeyStore = InMemoryOpkRepository(crypto),
            upgradePolicy = SessionUpgradePolicy.OFFER_OPK_ON_FIRST_EPOCH1_REPLY,
        )
        val senderProtection = SignedAndEncryptedFileProtection(
            FakeIdentityResolverForProtection(senderPeer.signingPrivateKey, emptyMap())
                .let { DefaultSignatureProvider(it, it, crypto) },
            senderSession,
            crypto,
        )
        val receiverProtection = SignedAndEncryptedFileProtection(
            FakeIdentityResolverForProtection(
                receiverPeer.signingPrivateKey,
                mapOf(senderPeer.device.deviceId to senderPeer.device),
            ).let { DefaultSignatureProvider(it, it, crypto) },
            receiverSession,
            crypto,
        )
        // Epoch 1 alive both ways, then seal under it.
        receiverSession.decryptMessage(
            senderPeer.device.deviceId,
            senderSession.encryptMessage(receiverPeer.device.deviceId, byteArrayOf(1)),
        )
        senderSession.decryptMessage(
            receiverPeer.device.deviceId,
            receiverSession.encryptMessage(senderPeer.device.deviceId, byteArrayOf(2)),
        )
        val sealed = senderProtection.sealOffer(sampleSealedOffer(), receiverPeer.device.deviceId)
        // Promote the sender to epoch 2 and deliver there: the receiver's epoch 1
        // goes SUPERSEDED, like a sealed half held past a rotation.
        senderSession.decryptMessage(
            receiverPeer.device.deviceId,
            receiverSession.encryptMessage(senderPeer.device.deviceId, byteArrayOf(50)),
        )
        receiverSession.decryptMessage(
            senderPeer.device.deviceId,
            senderSession.encryptMessage(receiverPeer.device.deviceId, byteArrayOf(3)),
        )
        val superseded = receiverStore.loadSessions(senderPeer.device.deviceId, sessionEpoch = 1).single()
        assertEquals(SessionStatus.SUPERSEDED, superseded.meta.status)
        // Still decryptable within retention.
        assertEquals(
            sampleSealedOffer(),
            receiverProtection.openOffer(sealed, senderPeer.device.deviceId),
        )
    }

    @Test
    fun sealedHalf_replayFails() = runTest {
        val pair = sampleFileProtectionPair()
        val sealed = pair.sender.sealOffer(sampleSealedOffer(), pair.targetPeer)
        assertEquals(sampleSealedOffer(), pair.receiver.openOffer(sealed, pair.sourcePeer))
        val failure = assertFailsWith<ProtectionException> {
            pair.receiver.openOffer(sealed, pair.sourcePeer)
        }
        assertEquals(
            ProtectionDisposition.PERMANENT,
            failure.disposition,
            "a sealed-half replay must be permanent, never a silent retry",
        )
    }

    @Test
    fun open_tamperedSignatureFails() = runTest {
        val pair = sampleFileProtectionPair()
        val transferId = TransferId(Uuid.random())
        val sealed = pair.sender.sealOffer(sampleSealedOffer(), pair.targetPeer)
        val envelope = pair.sender.protect(
            FilePayload.Offer(220 * 1024, 1000L, 0, sealed),
            fileContext(transferId, pair.sourcePeer, pair.targetPeer),
        )
        val tampered = envelope.copy(signature = envelope.signature!!.copyOf().also {
            it[0] = (it[0].toInt() xor 0xff).toByte()
        })
        val failure = assertFailsWith<ProtectionException.AuthenticationFailed> {
            pair.receiver.open(tampered)
        }
        assertTrue(failure.message!!.contains("signature", ignoreCase = true))
    }

    @Test
    fun protect_requiresTransferId() = runTest {
        val pair = sampleFileProtectionPair()
        assertFailsWith<IllegalArgumentException> {
            pair.sender.protect(
                FilePayload.Complete,
                sampleEnvelopeContext(
                    scheme = SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
                    source = pair.sourcePeer,
                    target = pair.targetPeer,
                ),
            )
        }
    }
}
