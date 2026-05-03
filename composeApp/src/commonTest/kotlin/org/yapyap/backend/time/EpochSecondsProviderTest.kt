package org.yapyap.backend.time

import kotlin.test.Test
import kotlin.test.assertEquals

class EpochSecondsProviderTest {

    @Test
    fun fixedEpochSecondsProvider_returnsStableValue() {
        val clock = FixedEpochSecondsProvider(1_700_000_000L)
        assertEquals(1_700_000_000L, clock.nowEpochSeconds())
        assertEquals(1_700_000_000L, clock.nowEpochSeconds())
    }

    @Test
    fun epochSecondsProvider_funInterface_acceptsLambda() {
        var tick = 100L
        val provider = EpochSecondsProvider { tick++ }
        assertEquals(100L, provider.nowEpochSeconds())
        assertEquals(101L, provider.nowEpochSeconds())
    }
}
