package org.yapyap.routing.router

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.yapyap.routing.store.OutboxTag

/**
 * A router-internal service: the shape [DefaultRouter] starts uniformly. Services start before
 * inbound handling, so a packet that arrives during start waits behind their recovery instead of
 * being dropped.
 */
internal interface RouterService {
    /** Starts the service's loops in [scope]. */
    fun runIn(scope: CoroutineScope): List<Job>
}

/**
 * A [RouterService] that owns some outbox traffic and consumes its outcomes.
 *
 * This is not the orchestrator seam — [OutboxOutcome] and [OutboxTag.Kind] are router vocabulary
 * and never cross it. Each consumer declares the tag kinds it owns, so the router's dispatch is
 * derived from the services themselves instead of a hardcoded `when`.
 */
internal interface OutcomeConsumer : RouterService {
    /** The outbox tag kinds whose outcomes this service consumes. */
    val outcomeTags: Set<OutboxTag.Kind>

    /** Mailbox inlet; never suspends. The service consumes the outcome in its own loop. */
    fun offerOutcome(outcome: OutboxOutcome)
}
