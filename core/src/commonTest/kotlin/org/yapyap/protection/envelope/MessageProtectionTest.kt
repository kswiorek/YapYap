package org.yapyap.protection.envelope

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.protection.ProtectionDisposition
import org.yapyap.protection.ProtectionException
import org.yapyap.protection.ProtectionReason
import org.yapyap.protection.sampleMessageProtectionPair
import org.yapyap.protection.sampleTextPayload
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MessageProtectionTest {

    private val crypto = DefaultCryptoProvider()

    @Test
    fun seal_open_roundTrip() = runTest {
        val pair = sampleMessageProtectionPair(crypto)
        val payload = sampleTextPayload()
        val content = pair.sender.seal(payload, pair.targetPeer)
        val opened = pair.receiver.open(content, pair.sourcePeer)
        assertEquals(payload, opened)
    }

    @Test
    fun open_tamperedContent_failsAuthentication() = runTest {
        val pair = sampleMessageProtectionPair(crypto)
        val content = pair.sender.seal(sampleTextPayload(), pair.targetPeer)
        val tampered = content.copyOf().also {
            it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte()
        }
        assertFailsWith<ProtectionException> {
            pair.receiver.open(tampered, pair.sourcePeer)
        }
    }

    @Test
    fun open_sameContentTwice_secondIsDuplicate() = runTest {
        val pair = sampleMessageProtectionPair(crypto)
        val payload = sampleTextPayload()
        val content = pair.sender.seal(payload, pair.targetPeer)
        assertEquals(payload, pair.receiver.open(content, pair.sourcePeer))
        val duplicate = assertFailsWith<ProtectionException.Duplicate> {
            pair.receiver.open(content, pair.sourcePeer)
        }
        assertEquals(ProtectionDisposition.PERMANENT, duplicate.disposition)
        assertEquals(ProtectionReason.DUPLICATE, duplicate.reason)
    }

    @Test
    fun open_garbageContent_fails() = runTest {
        val pair = sampleMessageProtectionPair(crypto)
        assertFailsWith<ProtectionException> {
            pair.receiver.open(byteArrayOf(1, 2, 3, 4), pair.sourcePeer)
        }
    }

    @Test
    fun sealedBytes_carryNoPlaintext() = runTest {
        val pair = sampleMessageProtectionPair(crypto)
        val payload = sampleTextPayload()
        val content = pair.sender.seal(payload, pair.targetPeer)
        assertContentEquals(payload.encode(), pair.receiver.open(content, pair.sourcePeer).encode())
        // The ratchet frame must not contain the cleartext message.
        val textBytes = payload.text.encodeToByteArray()
        assert(!content.toList().windowed(textBytes.size).any { it == textBytes.toList() }) {
            "sealed content must not contain the plaintext"
        }
    }
}
