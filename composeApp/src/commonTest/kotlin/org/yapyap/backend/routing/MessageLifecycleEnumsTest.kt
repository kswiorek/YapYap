package org.yapyap.backend.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compile-time anchor for routing enums until they are wired through [DefaultRouter].
 */
class MessageLifecycleEnumsTest {

    @Test
    fun deliveryAttemptState_hasExpectedVariants() {
        assertEquals(7, DeliveryAttemptState.entries.size)
        assertTrue(DeliveryAttemptState.entries.contains(DeliveryAttemptState.SCHEDULED))
        assertTrue(DeliveryAttemptState.entries.contains(DeliveryAttemptState.IN_FLIGHT))
    }

    @Test
    fun envelopeFamily_and_replayPolicy_exist() {
        assertEquals(3, EnvelopeFamily.entries.size)
        assertEquals(3, ReplayPolicy.entries.size)
    }
}
