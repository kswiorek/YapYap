package org.yapyap.routing.retry

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.yapyap.time.EpochProvider
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

internal class RetryLoop(
    private val earliestPendingRetryAt: suspend () -> Long?,
    private val time: EpochProvider,
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

    private suspend fun computeSleepSeconds(): Long {
        val now = time.nowEpochSeconds()
        val next = earliestPendingRetryAt() ?: return maxIdlePollSeconds
        return (next - now).coerceAtLeast(0).coerceAtMost(maxIdlePollSeconds)
    }
}