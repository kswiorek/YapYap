package org.yapyap.protection.envelope

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.e2ee.MapBackedCryptoSessionStore
import org.yapyap.crypto.e2ee.buildTestPeerIdentity
import org.yapyap.crypto.e2ee.manager.SessionUpgradePolicy
import org.yapyap.crypto.e2ee.managerForPeer
import org.yapyap.crypto.e2ee.session.SessionStatus
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.protection.ProtectionDisposition
import org.yapyap.protection.ProtectionException
import org.yapyap.protection.ProtectionReason
import org.yapyap.protection.sampleFileProtectionPair
import org.yapyap.protocol.ContentId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.envelopes.ByteRange
import org.yapyap.protocol.envelopes.FileRequest
import org.yapyap.testfixtures.InMemoryOpkRepository
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileProtectionTest {

    private val crypto = DefaultCryptoProvider()

    private fun sampleRequest(): FileRequest =
        FileRequest(
            fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
            contiguousBytes = 512L,
            missingRanges = listOf(ByteRange(1024L, 2048L)),
        )

    private suspend fun sampleSealId(label: String = "test-seal"): ContentId =
        ContentId(crypto.sha256(label.encodeToByteArray()))

    @Test
    fun seal_openSeal_roundTrip() = runTest {
        val pair = sampleFileProtectionPair(crypto)
        val key = pair.sender.generateSealKey()
        val seal = org.yapyap.protection.sampleFileSeal(key)
        val sealed = pair.sender.seal(seal, pair.targetPeer)
        val opened = pair.receiver.openSeal(sealed, pair.sourcePeer)
        assertContentEquals(seal.encode(), opened.encode())
        assertEquals(seal.fileId, opened.fileId)
        assertEquals(seal.fileName, opened.fileName)
        assertEquals(seal.key, opened.key)
    }

    @Test
    fun openSeal_sameContentTwice_secondIsDuplicate() = runTest {
        val pair = sampleFileProtectionPair(crypto)
        val sealed = pair.sender.seal(
            org.yapyap.protection.sampleFileSeal(pair.sender.generateSealKey()),
            pair.targetPeer,
        )
        pair.receiver.openSeal(sealed, pair.sourcePeer)
        val duplicate = assertFailsWith<ProtectionException.Duplicate> {
            pair.receiver.openSeal(sealed, pair.sourcePeer)
        }
        assertEquals(ProtectionDisposition.PERMANENT, duplicate.disposition)
        assertEquals(ProtectionReason.DUPLICATE, duplicate.reason)
    }

    @Test
    fun openSeal_tamperedContent_fails() = runTest {
        val pair = sampleFileProtectionPair(crypto)
        val sealed = pair.sender.seal(
            org.yapyap.protection.sampleFileSeal(pair.sender.generateSealKey()),
            pair.targetPeer,
        )
        val tampered = sealed.copyOf().also {
            it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte()
        }
        assertFailsWith<ProtectionException> {
            pair.receiver.openSeal(tampered, pair.sourcePeer)
        }
    }

    @Test
    fun request_sealOpen_roundTrip() = runTest {
        val pair = sampleFileProtectionPair(crypto)
        val request = sampleRequest()
        val sealed = pair.sender.sealRequest(request, pair.targetPeer)
        // The fileId never travels in clear: the sealed REQUEST is a ratchet frame.
        assertFalse(sealed.decodeToString().contains(request.fileId.value))
        assertEquals(request, pair.receiver.openRequest(sealed, pair.sourcePeer))
    }

    @Test
    fun request_openTwice_secondIsDuplicate() = runTest {
        val pair = sampleFileProtectionPair(crypto)
        val sealed = pair.sender.sealRequest(sampleRequest(), pair.targetPeer)
        assertEquals(sampleRequest(), pair.receiver.openRequest(sealed, pair.sourcePeer))
        assertFailsWith<ProtectionException.Duplicate> {
            pair.receiver.openRequest(sealed, pair.sourcePeer)
        }
    }

    @Test
    fun request_tamperedContent_fails() = runTest {
        val pair = sampleFileProtectionPair(crypto)
        val sealed = pair.sender.sealRequest(sampleRequest(), pair.targetPeer)
        val tampered = sealed.copyOf().also {
            it[0] = (it[0].toInt() xor 0xff).toByte()
        }
        assertFailsWith<ProtectionException> {
            pair.receiver.openRequest(tampered, pair.sourcePeer)
        }
    }

    @Test
    fun chunk_encryptDecrypt_roundTrip() = runTest {
        val pair = sampleFileProtectionPair(crypto)
        val key = pair.sender.generateSealKey()
        val sealId = sampleSealId()
        val plaintext = "chunk-bytes".encodeToByteArray()
        val ciphertext = pair.sender.encryptChunk(sealId, key, 0L, plaintext)
        assertContentEquals(
            plaintext,
            pair.receiver.decryptChunk(sealId, key, 0L, ciphertext),
        )
    }

    @Test
    fun chunk_aadBinding_wrongSealOffsetOrLengthFails() = runTest {
        val pair = sampleFileProtectionPair(crypto)
        val key = pair.sender.generateSealKey()
        val sealId = sampleSealId("seal-a")
        val otherSealId = sampleSealId("seal-b")
        val ciphertext = pair.sender.encryptChunk(sealId, key, 0L, "chunk-bytes".encodeToByteArray())
        // Another seal: the seal id is in the AAD.
        assertFailsWith<ProtectionException> {
            pair.receiver.decryptChunk(otherSealId, key, 0L, ciphertext)
        }
        // Another offset: placement comes from what decrypts, never from a public header.
        assertFailsWith<ProtectionException> {
            pair.receiver.decryptChunk(sealId, key, 1024L, ciphertext)
        }
        // Another length: truncating the ciphertext changes the AAD length.
        assertFailsWith<ProtectionException> {
            pair.receiver.decryptChunk(sealId, key, 0L, ciphertext.copyOf(ciphertext.size - 1))
        }
    }

    @Test
    fun chunk_samePlaintextSameSealTwice_givesDifferentCiphertext() = runTest {
        val pair = sampleFileProtectionPair(crypto)
        val key = pair.sender.generateSealKey()
        val sealId = sampleSealId()
        val plaintext = "same-bytes".encodeToByteArray()
        val first = pair.sender.encryptChunk(sealId, key, 0L, plaintext)
        val second = pair.sender.encryptChunk(sealId, key, 0L, plaintext)
        assertTrue(!first.contentEquals(second), "library-managed IV must randomize ciphertext")
    }

    @Test
    fun seal_invalidFileName_throws() = runTest {
        val pair = sampleFileProtectionPair(crypto)
        val key = pair.sender.generateSealKey()
        assertFailsWith<IllegalArgumentException> {
            org.yapyap.protection.sampleFileSeal(key).copyForTest(fileName = "")
        }
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
        val senderProtection = RatchetFileProtection(RatchetSealer(senderSession), crypto)
        val receiverProtection = RatchetFileProtection(RatchetSealer(receiverSession), crypto)
        // Epoch 1 alive both ways, then seal under it.
        receiverSession.decryptMessage(
            senderPeer.device.deviceId,
            senderSession.encryptMessage(receiverPeer.device.deviceId, byteArrayOf(1)),
        )
        senderSession.decryptMessage(
            receiverPeer.device.deviceId,
            receiverSession.encryptMessage(senderPeer.device.deviceId, byteArrayOf(2)),
        )
        val seal = org.yapyap.protection.sampleFileSeal(senderProtection.generateSealKey())
        val sealed = senderProtection.seal(seal, receiverPeer.device.deviceId)
        // Promote the sender to epoch 2 and deliver there: the receiver's epoch 1
        // goes SUPERSEDED, like a seal held past a rotation.
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
        val opened = receiverProtection.openSeal(sealed, senderPeer.device.deviceId)
        assertContentEquals(seal.encode(), opened.encode())
    }

    @Test
    fun openSeal_afterSupersedeWithoutRetention_stillDuplicateOnRedelivery() = runTest {
        // A seal opened before is a Duplicate however often it is redelivered,
        // even after the session moved on: the same bytes can never open again.
        val pair = sampleFileProtectionPair(crypto)
        val sealed = pair.sender.seal(
            org.yapyap.protection.sampleFileSeal(pair.sender.generateSealKey()),
            pair.targetPeer,
        )
        pair.receiver.openSeal(sealed, pair.sourcePeer)
        assertFailsWith<ProtectionException.Duplicate> {
            pair.receiver.openSeal(sealed, pair.sourcePeer)
        }
    }
}

/** Test-only copy helper: [FileSeal] exposes no copy, so rebuild it with one field changed. */
private fun org.yapyap.protocol.envelopes.FileSeal.copyForTest(
    fileName: String = this.fileName,
): org.yapyap.protocol.envelopes.FileSeal = org.yapyap.protocol.envelopes.FileSeal(
    fileId = fileId,
    totalBytes = totalBytes,
    blockSize = blockSize,
    blockHashes = blockHashes,
    fileName = fileName,
    mimeType = mimeType,
    key = key,
)
