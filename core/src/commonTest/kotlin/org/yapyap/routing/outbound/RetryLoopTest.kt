package org.yapyap.routing.outbound

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.packet.PacketType
import org.yapyap.routing.retry.RetryLoop
import org.yapyap.routing.router.TrackingPacketOutbox
import org.yapyap.time.FixedEpochProvider
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

class RetryLoopTest {

    private val targetPeer =
        PeerId("outboxlooptargetcccccccccccccccccccccccccccccccccccccccccccccccccc")

    @Test
    fun runIn_processDueFailure_doesNotStopLoop() = runBlocking {
        var calls = 0
        val outbox = TrackingPacketOutbox()
        val loop = RetryLoop(
            earliestPendingRetryAt = { outbox.earliestPendingRetryAt() },
            time = FixedEpochProvider(1_000L),
            processDue = {
                calls++
                if (calls == 1) {
                    error("boom")
                }
            },
            maxIdlePollSeconds = MutableStateFlow(1),
        )

        val scope = CoroutineScope(SupervisorJob())
        val job = loop.runIn(scope)
        delay(2_500.milliseconds)
        job.cancel()
        scope.cancel()

        assertTrue(calls >= 2, "expected retry loop to survive processDue failure, calls=$calls")
    }

    @Test
    fun notifyChanged_wakesBeforeIdleTimeout() = runBlocking {
        var calls = 0
        val outbox = TrackingPacketOutbox()
        seedFutureOutboxEntry(outbox, nextRetryAt = 99_999L)

        val loop = RetryLoop(
            earliestPendingRetryAt = { outbox.earliestPendingRetryAt() },
            time = FixedEpochProvider(1_000L),
            processDue = { calls++ },
            maxIdlePollSeconds = MutableStateFlow(60),
        )

        val scope = CoroutineScope(SupervisorJob())
        val job = loop.runIn(scope)
        delay(300.milliseconds)
        loop.notifyChanged()
        delay(300.milliseconds)
        job.cancel()
        scope.cancel()

        assertTrue(calls >= 2, "expected notifyChanged to wake loop early, calls=$calls")
    }

    @Test
    fun sleepsUntilEarliestRetryAt() = runBlocking {
        var calls = 0
        val outbox = TrackingPacketOutbox()
        seedFutureOutboxEntry(outbox, nextRetryAt = 1_003L)

        val loop = RetryLoop(
            earliestPendingRetryAt = { outbox.earliestPendingRetryAt() },
            time = FixedEpochProvider(1_000L),
            processDue = { calls++ },
            maxIdlePollSeconds = MutableStateFlow(60),
        )

        val scope = CoroutineScope(SupervisorJob())
        val job = loop.runIn(scope)
        delay(3_500.milliseconds)
        job.cancel()
        scope.cancel()

        assertTrue(calls >= 2, "expected loop to wake near earliest retry time, calls=$calls")
    }

    private suspend fun seedFutureOutboxEntry(outbox: TrackingPacketOutbox, nextRetryAt: Long) {
        outbox.enqueue(
            envelope = BinaryEnvelope(
                packetId = Uuid.random(),
                packetType = PacketType.MESSAGE,
                createdAtEpochSeconds = 1_000L,
                expiresAtEpochSeconds = 9_999L,
                source = targetPeer,
                target = targetPeer,
                payload = byteArrayOf(0x01),
            ),
            nextRetryAt = nextRetryAt,
        )
    }
}
