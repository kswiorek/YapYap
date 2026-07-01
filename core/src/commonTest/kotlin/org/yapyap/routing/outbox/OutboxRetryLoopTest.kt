package org.yapyap.routing.outbox

import kotlinx.coroutines.*
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.packet.PacketId
import org.yapyap.protocol.packet.PacketType
import org.yapyap.routing.router.TrackingPacketOutbox
import org.yapyap.time.FixedEpochSecondsProvider
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class OutboxRetryLoopTest {

    private val targetPeer =
        PeerId("outboxlooptargetcccccccccccccccccccccccccccccccccccccccccccccccccc")

    @Test
    fun runIn_processDueFailure_doesNotStopLoop() = runBlocking {
        var calls = 0
        val outbox = TrackingPacketOutbox()
        val loop = OutboxRetryLoop(
            outbox = outbox,
            time = FixedEpochSecondsProvider(1_000L),
            processDue = {
                calls++
                if (calls == 1) {
                    error("boom")
                }
            },
            maxIdlePollSeconds = 1,
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

        val loop = OutboxRetryLoop(
            outbox = outbox,
            time = FixedEpochSecondsProvider(1_000L),
            processDue = { calls++ },
            maxIdlePollSeconds = 60,
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

        val loop = OutboxRetryLoop(
            outbox = outbox,
            time = FixedEpochSecondsProvider(1_000L),
            processDue = { calls++ },
            maxIdlePollSeconds = 60,
        )

        val scope = CoroutineScope(SupervisorJob())
        val job = loop.runIn(scope)
        delay(3_500.milliseconds)
        job.cancel()
        scope.cancel()

        assertTrue(calls >= 2, "expected loop to wake near earliest retry time, calls=$calls")
    }

    private fun seedFutureOutboxEntry(outbox: TrackingPacketOutbox, nextRetryAt: Long) {
        outbox.enqueue(
            envelope = BinaryEnvelope(
                packetId = PacketId.random(),
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
