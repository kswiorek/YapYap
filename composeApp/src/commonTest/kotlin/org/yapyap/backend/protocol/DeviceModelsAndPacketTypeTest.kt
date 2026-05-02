package org.yapyap.backend.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeviceModelsAndPacketTypeTest {

    @Test
    fun peerId_rejectsBlank() {
        assertFailsWith<IllegalArgumentException> { PeerId(" ") }
        assertFailsWith<IllegalArgumentException> { PeerId("") }
    }

    @Test
    fun torEndpoint_requiresOnionSuffixAndPortRange() {
        assertFailsWith<IllegalArgumentException> { TorEndpoint("not-onion", 80) }
        assertFailsWith<IllegalArgumentException> { TorEndpoint("x.onion", 0) }
        assertFailsWith<IllegalArgumentException> { TorEndpoint("x.onion", 65536) }
        TorEndpoint("abc123def456789012345678901234567890123456789012.onion", 443)
    }

    @Test
    fun packetType_fromWireValue_coversAllDistinct() {
        val values = PacketType.entries.map { it.wireValue }.toSet()
        assertEquals(PacketType.entries.size, values.size)
        PacketType.entries.forEach { pt ->
            assertEquals(pt, PacketType.fromWireValue(pt.wireValue))
        }
    }

    @Test
    fun packetType_fromWireValue_rejectsUnknown() {
        assertFailsWith<IllegalStateException> {
            PacketType.fromWireValue(99)
        }
    }

    @Test
    fun signalSecurityScheme_fromWireValue_coversAll() {
        SignalSecurityScheme.entries.forEach { s ->
            assertEquals(s, SignalSecurityScheme.fromWireValue(s.wireValue))
            assertEquals(24, s.nonceSize)
        }
    }

    @Test
    fun signalSecurityScheme_fromWireValue_rejectsUnknown() {
        assertFailsWith<IllegalStateException> {
            SignalSecurityScheme.fromWireValue(9)
        }
    }
}
