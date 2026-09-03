package org.yapyap.routing.retry

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

internal class RetryLoop(
    private val earliestPendingRetryAt: suspend () -> Instant?,
    private val clock: Clock,
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
                val sleepSeconds = computeSleep()
                select {
                    wake.onReceive { }
                    onTimeout(sleepSeconds) { }
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

    private suspend fun computeSleep(): Duration {
        val max = maxIdlePoll.value
        val next = earliestPendingRetryAt() ?: return max
        val wait = (next - clock.now()).coerceAtLeast(Duration.ZERO)
        return wait.coerceIn(Duration.ZERO..max)
    }
}