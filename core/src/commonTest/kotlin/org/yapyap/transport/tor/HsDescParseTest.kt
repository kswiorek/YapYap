package org.yapyap.transport.tor

import org.yapyap.transport.tor.backend.hsDescUploadedOnion
import org.yapyap.transport.tor.backend.onionMatches
import kotlin.test.*

class HsDescParseTest {

    private val onion = "ottpmp6o46zxx6e4cy42kjckz3nb6k3ohnuy2zrcprthnm3uaacdejyd.onion"

    @Test
    fun uploaded_withPrefix_parsesOnion() {
        assertEquals(
            onion,
            hsDescUploadedOnion("650 HS_DESC UPLOADED $onion UNKNOWN abcdef0123456789"),
        )
    }

    @Test
    fun uploaded_bareLine_parsesOnion() {
        assertEquals(
            onion,
            hsDescUploadedOnion("UPLOADED $onion"),
        )
    }

    @Test
    fun otherActions_returnNull() {
        assertNull(hsDescUploadedOnion("650 HS_DESC CREATED $onion UNKNOWN abcdef0123456789"))
        assertNull(hsDescUploadedOnion("650 HS_DESC FAILED $onion UNKNOWN abcdef0123456789 REASON=NOT_FOUND"))
        assertNull(hsDescUploadedOnion("650 HS_DESC REQUESTED $onion"))
        assertNull(hsDescUploadedOnion("650 HS_DESC RECEIVED $onion UNKNOWN abcdef0123456789"))
    }

    @Test
    fun malformed_returnsNull() {
        assertNull(hsDescUploadedOnion(""))
        assertNull(hsDescUploadedOnion("HS_DESC"))
        assertNull(hsDescUploadedOnion("650 HS_DESC UPLOADED"))
        assertNull(hsDescUploadedOnion("650 NOTICE something else"))
    }

    @Test
    fun onionMatches_ignoresCaseAndSuffix() {
        val bare = onion.removeSuffix(".onion")
        assertTrue(onionMatches(onion, onion))
        assertTrue(onionMatches(onion, bare))
        assertTrue(onionMatches(bare, onion))
        assertTrue(onionMatches(onion.uppercase(), bare))
        assertFalse(onionMatches(onion, "other.onion"))
        assertFalse(onionMatches("", ""))
    }
}
