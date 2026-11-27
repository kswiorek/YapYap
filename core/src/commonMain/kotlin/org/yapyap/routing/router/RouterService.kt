package org.yapyap.routing.router

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.yapyap.routing.store.OutboxTag

/**
 * A router-internal service: the shape [DefaultRouter] starts and feeds outbox outcomes through.
 *
 * This is not the orchestrator seam — [OutboxOutcome] and [OutboxTag.Kind] are router vocabulary
 * and never cross it. Each service declares the tag kinds it owns, so the router's dispatch is
 * derived from the services themselves instead of a hardcoded `when`.
 */
internal interface RouterService {
    /** The outbox tag kinds whose outcomes this service consumes. */
    val outcomeTags: Set<OutboxTag.Kind>

    /** Mailbox inlet; never suspends. The service consumes the outcome in its own loop. */
    fun offerOutcome(outcome: OutboxOutcome)

    /** Starts the service's loops in [scope]. */
    fun runIn(scope: CoroutineScope): List<Job>
}
