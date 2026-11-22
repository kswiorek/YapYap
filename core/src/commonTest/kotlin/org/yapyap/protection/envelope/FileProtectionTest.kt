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
import org.yapyap.protocol.FileId
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import org.yapyap.protocol.TransferKey
import org.yapyap.protocol.envelopes.ByteRange
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.protocol.envelopes.FileEnvelopeKind
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.testfixtures.InMemoryOpkRepository
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource
import kotlin.uuid.Uuid

class FileProtectionTest {

    private val crypto = DefaultCryptoProvider()

    private data class FileProtectionPair(
        val sender: SignedAndEncryptedFileProtection,
        val receiver: SignedAndEncryptedFileProtection,
        val sourcePeer: PeerId,
        val targetPeer: PeerId,
    )

    private suspend fun sampleFileProtectionPair(
        receiverCache: OpenedFrameCache = OpenedFrameCache(),
    ): FileProtectionPair {
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
            receiver = SignedAndEncryptedFileProtection(
                receiverSignatureProvider,
                receiverSession,
                crypto,
                receiverCache
            ),
            sourcePeer = senderPeer.device.deviceId,
            targetPeer = receiverPeer.device.deviceId,
        )
    }

    private fun sampleSealedOffer(key: TransferKey = TransferKey(ByteArray(32) { it.toByte() })): FilePayload.SealedOffer =
        FilePayload.SealedOffer(
            fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
            blockSize = 1024 * 1024,
            blockHashes = listOf(ByteArray(32) { 1 }),
            transferKey = key,
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
        )

    private fun sampleManifest(): FileOfferSealedManifest {
        val offer = sampleSealedOffer()
        return FileOfferSealedManifest(offer.fileId, offer.blockSize, offer.blockHashes, offer.fileName, offer.mimeType)
    }

    private fun fileContext(transferId: TransferId, source: PeerId, target: PeerId) =
        FileProtectContext(
            sourceDeviceId = source,
            targetDeviceId = target,
            createdAt = Clock.System.now(),
            transferId = transferId,
        )

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
    fun protectOffer_sealsAndSignsInOneCall() = runTest {
        val pair = sampleFileProtectionPair()
        val transferId = TransferId(Uuid.random())
        val key = SignedAndEncryptedFileProtection.generateTransferKey(crypto)
        val envelope = pair.sender.protectOffer(
            publicHalf = FileOfferPublicHalf(220 * 1024, 1000L, 0),
            sealed = FileOfferSealedManifest(
                fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                blockSize = 1024 * 1024,
                blockHashes = listOf(ByteArray(32) { 1 }),
                fileName = "photo.jpg",
                mimeType = "image/jpeg",
            ),
            key = key,
            ctx = fileContext(transferId, pair.sourcePeer, pair.targetPeer),
        )
        val opened = pair.receiver.open(envelope)
        val offer = opened.payload as FilePayload.Offer
        assertEquals(220 * 1024, offer.chunkSizeBytes)
        assertEquals(sampleSealedOffer(key), pair.receiver.openOffer(offer.sealedOfferBytes, pair.sourcePeer))
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
            pair.receiver.decryptChunk(transferId, key, chunk),
        )
    }

    @Test
    fun chunk_aadBinding_replayUnderAnotherIndexOrTransferFails() = runTest {
        val pair = sampleFileProtectionPair()
        val transferId = TransferId(Uuid.random())
        val otherTransfer = TransferId(Uuid.random())
        val key = SignedAndEncryptedFileProtection.generateTransferKey(crypto)
        val ciphertext = pair.sender.encryptChunk(transferId, 7, key, "chunk-bytes".encodeToByteArray())
        // Ciphertext from index 7 presented as index 8: the index comes from the chunk only.
        val replayedIndex = FilePayload.EncryptedChunk(chunkIndex = 8, chunkCiphertext = ciphertext)
        assertFailsWith<ProtectionException> {
            pair.receiver.decryptChunk(transferId, key, replayedIndex)
        }
        val sameIndex = FilePayload.EncryptedChunk(chunkIndex = 7, chunkCiphertext = ciphertext)
        assertFailsWith<ProtectionException> {
            pair.receiver.decryptChunk(otherTransfer, key, sameIndex)
        }
    }

    @Test
    fun chunk_samePlaintextSameTransferTwice_givesDifferentCiphertext() = runTest {
        val pair = sampleFileProtectionPair()
        val key = SignedAndEncryptedFileProtection.generateTransferKey(crypto)
        val transferId = TransferId(Uuid.random())
        val plaintext = "same-bytes".encodeToByteArray()
        val first = pair.sender.encryptChunk(transferId, 0, key, plaintext)
        val second = pair.sender.encryptChunk(transferId, 0, key, plaintext)
        assertTrue(!first.contentEquals(second), "library-managed IV must randomize ciphertext")
    }

    @Test
    fun protectedWireBytes_carryNoFileId() = runTest {
        val pair = sampleFileProtectionPair()
        val ctx = fileContext(TransferId(Uuid.random()), pair.sourcePeer, pair.targetPeer)
        val fileId = sampleSealedOffer().fileId
        val request = FilePayload.Request(fileId, 0L, emptyList())
        // The check can see a fileId: the unprotected REQUEST body carries it in the clear.
        assertTrue(request.encode().decodeToString().contains(fileId.value))

        val envelopes = listOf(
            FileEnvelopeKind.OFFER to pair.sender.protectOffer(
                publicHalf = FileOfferPublicHalf(220 * 1024, 1000L, 0),
                sealed = sampleManifest(),
                key = TransferKey(ByteArray(32) { it.toByte() }),
                ctx = ctx,
            ),
            FileEnvelopeKind.CHUNK to pair.sender.protect(FilePayload.EncryptedChunk(0, byteArrayOf(1, 2, 3)), ctx),
            FileEnvelopeKind.ACK to pair.sender.protect(FilePayload.Ack(0, emptyList()), ctx),
            FileEnvelopeKind.COMPLETE to pair.sender.protect(FilePayload.Complete, ctx),
            FileEnvelopeKind.CANCEL to pair.sender.protect(FilePayload.Cancel(FileCancelReason.DECLINED), ctx),
            FileEnvelopeKind.REQUEST to pair.sender.protect(request, ctx),
        )
        for ((kind, envelope) in envelopes) {
            // The whole encoded envelope, header included, as it leaves this device.
            val wire = envelope.encode().decodeToString()
            assertFalse(wire.contains(fileId.value), "wire bytes of $kind must not contain the fileId")
        }
    }

    @Test
    fun protectOffer_badManifest_isPermanentProtectionFailure() = runTest {
        val pair = sampleFileProtectionPair()
        val ctx = fileContext(TransferId(Uuid.random()), pair.sourcePeer, pair.targetPeer)
        val failure = assertFailsWith<ProtectionException> {
            pair.sender.protectOffer(
                publicHalf = FileOfferPublicHalf(220 * 1024, 1000L, 0),
                sealed = sampleManifest().copy(fileName = "n".repeat(300)),
                key = TransferKey(ByteArray(32)),
                ctx = ctx,
            )
        }
        assertEquals(ProtectionDisposition.PERMANENT, failure.disposition)
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
    fun sealedHalf_redeliveredWithinTheWindow_opensAgain_andAfterItFails() = runTest {
        val time = TestTimeSource()
        val base = sampleFileProtectionPair(OpenedFrameCache(capacity = 8, ttl = 10.minutes, timeSource = time))
        val receiver = base.receiver
        val sealed = base.sender.sealOffer(sampleSealedOffer(), base.targetPeer)
        assertEquals(sampleSealedOffer(), receiver.openOffer(sealed, base.sourcePeer))
        // Our own Defer after opening: the sender redelivers the same bytes.
        time += 9.minutes
        assertEquals(sampleSealedOffer(), receiver.openOffer(sealed, base.sourcePeer))
        // Past the window the ratchet sees a replay.
        time += 2.minutes
        val failure = assertFailsWith<ProtectionException> { receiver.openOffer(sealed, base.sourcePeer) }
        assertEquals(
            ProtectionDisposition.PERMANENT,
            failure.disposition,
            "a sealed-half replay past the window must be permanent, never a silent retry",
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
}
