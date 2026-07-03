package org.yapyap.routing.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SendMessageResultTest {

    @Test
    fun aggregateSendResults_allQueued_isSuccess() {
        val result = aggregateSendResults(
            listOf(PeerSendOutcome.Queued, PeerSendOutcome.Queued),
        )
        assertEquals(SendMessageStatus.SUCCESS, result.status)
        assertEquals(2, result.peersTotal)
        assertEquals(2, result.peersQueued)
        assertNull(result.failureKind)
    }

    @Test
    fun aggregateSendResults_someQueued_isPartialWithNotReady() {
        val result = aggregateSendResults(
            listOf(PeerSendOutcome.Queued, PeerSendOutcome.NotReady),
        )
        assertEquals(SendMessageStatus.PARTIAL, result.status)
        assertEquals(2, result.peersTotal)
        assertEquals(1, result.peersQueued)
        assertEquals(SendFailureKind.NOT_READY, result.failureKind)
    }

    @Test
    fun aggregateSendResults_partialWithPermanent_isMixed() {
        val result = aggregateSendResults(
            listOf(PeerSendOutcome.Queued, PeerSendOutcome.PermanentFailure),
        )
        assertEquals(SendMessageStatus.PARTIAL, result.status)
        assertEquals(SendFailureKind.MIXED, result.failureKind)
    }

    @Test
    fun aggregateSendResults_allNotReady_isFailure() {
        val result = aggregateSendResults(
            listOf(PeerSendOutcome.NotReady, PeerSendOutcome.NotReady),
        )
        assertEquals(SendMessageStatus.FAILURE, result.status)
        assertEquals(0, result.peersQueued)
        assertEquals(SendFailureKind.NOT_READY, result.failureKind)
    }

    @Test
    fun aggregateSendResults_allPermanent_isFailure() {
        val result = aggregateSendResults(
            listOf(PeerSendOutcome.PermanentFailure),
        )
        assertEquals(SendMessageStatus.FAILURE, result.status)
        assertEquals(SendFailureKind.PERMANENT, result.failureKind)
    }

    @Test
    fun aggregateSendResults_mixedFailures_isFailureMixed() {
        val result = aggregateSendResults(
            listOf(PeerSendOutcome.NotReady, PeerSendOutcome.PermanentFailure),
        )
        assertEquals(SendMessageStatus.FAILURE, result.status)
        assertEquals(SendFailureKind.MIXED, result.failureKind)
    }
}
