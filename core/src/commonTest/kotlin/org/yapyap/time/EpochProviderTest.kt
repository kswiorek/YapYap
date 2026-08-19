package org.yapyap.time

import kotlin.test.Test
import kotlin.test.assertEquals

class EpochProviderTest {

    @Test
    fun fixedEpochSecondsProvider_returnsStableValue() {
        val clock = FixedEpochProvider(1_700_000_000L)
        assertEquals(1_700_000_000L, clock.nowEpochSeconds())
        assertEquals(1_700_000_000L, clock.nowEpochSeconds())
    }
}
