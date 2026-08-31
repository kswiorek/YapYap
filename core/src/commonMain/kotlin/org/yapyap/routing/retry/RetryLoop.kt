package org.yapyap.routing.retry

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.yapyap.time.EpochProvider
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class RetryLoop(
    private val earliestPendingRetryAt: suspend () -> Long?,
    private val time: EpochProvider,
    private val processDue: suspend () -> Unit,
    private val maxIdlePoll: StateFlow<Duration>,
    private val onProcessFailed: (Throwable) -> Unit = {},
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun notifyChanged() { wake.trySend(Unit) }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun runIn(scope: CoroutineScope): Job = scope.launch {
        val configWatch = launch {
            maxIdlePoll.drop(1).collect { wake.trySend(Unit) }
        }
        try {
            runProcessDueSafely()
            while (isActive) {
                val sleepSeconds = computeSleepSeconds()
                select {
                    wake.onReceive { }
                    onTimeout(sleepSeconds.seconds) { }
                }
                runProcessDueSafely()
            }
        } finally {
            configWatch.cancel()
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
        val next = earliestPendingRetryAt() ?: return maxIdlePoll.value.inWholeSeconds
        return (next - now).coerceAtLeast(0).coerceAtMost(maxIdlePoll.value.inWholeSeconds)
    }
}