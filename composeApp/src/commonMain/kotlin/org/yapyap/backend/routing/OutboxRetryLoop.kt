package org.yapyap.backend.routing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.yapyap.backend.db.PacketOutbox
import org.yapyap.backend.time.EpochSecondsProvider
import kotlinx.coroutines.selects.onTimeout

class OutboxRetryLoop(
    private val outbox: PacketOutbox,
    private val time: EpochSecondsProvider,
    private val processDue: suspend () -> Unit,
    private val maxIdlePollSeconds: Long = 60,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun notifyChanged() { wake.trySend(Unit) }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun runIn(scope: CoroutineScope): Job = scope.launch {
        processDue() // boot / start recovery
        while (isActive) {
            val sleepSeconds = computeSleepSeconds()
            select {
                wake.onReceive { }
                onTimeout(sleepSeconds * 1000) { }
            }
            processDue()
        }
    }

    private fun computeSleepSeconds(): Long {
        val now = time.nowEpochSeconds()
        val next = outbox.earliestPendingRetryAt() ?: return maxIdlePollSeconds
        return (next - now).coerceAtLeast(0).coerceAtMost(maxIdlePollSeconds)
    }
}