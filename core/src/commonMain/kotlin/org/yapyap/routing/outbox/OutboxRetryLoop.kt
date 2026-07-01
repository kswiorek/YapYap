package org.yapyap.routing.outbox

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.yapyap.persistence.packet.PacketOutbox
import org.yapyap.time.EpochSecondsProvider
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

class OutboxRetryLoop(
    private val outbox: PacketOutbox,
    private val time: EpochSecondsProvider,
    private val processDue: suspend () -> Unit,
    private val maxIdlePollSeconds: Long = 60,
    private val onProcessFailed: (Throwable) -> Unit = {},
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun notifyChanged() { wake.trySend(Unit) }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun runIn(scope: CoroutineScope): Job = scope.launch {
        runProcessDueSafely()
        while (isActive) {
            val sleepSeconds = computeSleepSeconds()
            select {
                wake.onReceive { }
                onTimeout((sleepSeconds * 1000).milliseconds) { }
            }
            runProcessDueSafely()
        }
    }

    private suspend fun runProcessDueSafely() {
        runCatching { processDue() }
            .onFailure { error ->
                if (error is CancellationException) throw error
                onProcessFailed(error)
            }
    }

    private fun computeSleepSeconds(): Long {
        val now = time.nowEpochSeconds()
        val next = outbox.earliestPendingRetryAt() ?: return maxIdlePollSeconds
        return (next - now).coerceAtLeast(0).coerceAtMost(maxIdlePollSeconds)
    }
}