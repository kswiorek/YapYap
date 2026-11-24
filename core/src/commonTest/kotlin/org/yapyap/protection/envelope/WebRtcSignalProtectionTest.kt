package org.yapyap.protection.envelope

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.protection.ProtectionDisposition
import org.yapyap.protection.ProtectionException
import org.yapyap.protection.ProtectionReason
import org.yapyap.protection.sampleSignalProtectionPair
import org.yapyap.protection.sampleWebRtcSignal
import org.yapyap.protocol.envelopes.WebRtcSignalKind
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebRtcSignalProtectionTest {

    private val crypto = DefaultCryptoProvider()

    @Test
    fun seal_open_roundTrip() = runTest {
        val pair = sampleSignalProtectionPair(crypto)
        val input = sampleWebRtcSignal(pair.sourcePeer, pair.targetPeer)
        val content = pair.sender.seal(input)
        val opened = pair.receiver.open(content, input.kind, pair.sourcePeer, pair.targetPeer)
        assertEquals(input.kind, opened.kind)
        assertEquals(input.source, opened.source)
        assertEquals(input.target, opened.target)
        assertContentEquals(input.payload, opened.payload)
    }

    @Test
    fun open_tamperedContent_fails() = runTest {
        val pair = sampleSignalProtectionPair(crypto)
        val input = sampleWebRtcSignal(pair.sourcePeer, pair.targetPeer)
        val content = pair.sender.seal(input)
        val tampered = content.copyOf().also {
            it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte()
        }
        assertFailsWith<ProtectionException> {
            pair.receiver.open(tampered, input.kind, pair.sourcePeer, pair.targetPeer)
        }
    }

    @Test
    fun open_sameContentTwice_secondIsDuplicate() = runTest {
        val pair = sampleSignalProtectionPair(crypto)
        val input = sampleWebRtcSignal(pair.sourcePeer, pair.targetPeer)
        val content = pair.sender.seal(input)
        val opened = pair.receiver.open(content, input.kind, pair.sourcePeer, pair.targetPeer)
        assertContentEquals(input.payload, opened.payload)
        val duplicate = assertFailsWith<ProtectionException.Duplicate> {
            pair.receiver.open(content, input.kind, pair.sourcePeer, pair.targetPeer)
        }
        assertEquals(ProtectionDisposition.PERMANENT, duplicate.disposition)
        assertEquals(ProtectionReason.DUPLICATE, duplicate.reason)
    }

    @Test
    fun open_kindComesFromHeader_notContent() = runTest {
        val pair = sampleSignalProtectionPair(crypto)
        val input = sampleWebRtcSignal(pair.sourcePeer, pair.targetPeer)
        val content = pair.sender.seal(input)
        // The kind rides the routing header: opening the same frame under another kind
        // yields that kind with the same payload.
        val opened = pair.receiver.open(content, WebRtcSignalKind.ICE, pair.sourcePeer, pair.targetPeer)
        assertEquals(WebRtcSignalKind.ICE, opened.kind)
        assertContentEquals(input.payload, opened.payload)
    }
}
