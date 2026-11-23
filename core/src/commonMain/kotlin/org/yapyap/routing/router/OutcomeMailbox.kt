package org.yapyap.routing.router

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import kotlin.coroutines.cancellation.CancellationException

/**
 * A service's inbox for the outbox outcomes it owns. The router's single outcome collector
 * hands each outcome to the mailbox of the service owning its tag kind
 * ([org.yapyap.routing.store.OutboxTag.Kind]); the service consumes its mailbox in its own loop,
 * so a slow consumer never blocks the collector and never delays another service's outcomes.
 */
internal class OutcomeMailbox {
    private val channel = Channel<OutboxOutcome>(Channel.UNLIMITED)

    /** Never blocks, never suspends: called on the router's single outcome collector. */
    fun offer(outcome: OutboxOutcome) {
        channel.trySend(outcome)
    }

    fun runIn(
        scope: CoroutineScope,
        component: LogComponent,
        onOutcome: suspend (OutboxOutcome) -> Unit,
    ): Job = scope.launch {
        for (outcome in channel) {
            try {
                onOutcome(outcome)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error(
                    component = component,
                    event = LogEvent.OUTBOX_PROCESS_FAILED,
                    message = "Failed to handle an outbox outcome",
                    throwable = e,
                    fields = mapOf("tag" to outcome.tag),
                )
            }
        }
    }
}
