package org.yapyap.wiring

import org.yapyap.protocol.ByteWriter
import kotlin.test.Test
import kotlin.test.assertTrue

class MessageLimitsTest {
    @Test
    fun maxText_isEncodable_atTheDefaultTransportSizes() {
        val limit = MessageLimits.from(RuntimeConfig()).maxTextMessageBytes
        // The text travels as a u16-length string: the GUI limit must not admit a longer one.
        assertTrue(limit <= Short.MAX_VALUE, "limit $limit exceeds the u16 string prefix")
        ByteWriter(limit + 2).writeString("a".repeat(limit))
    }
}
