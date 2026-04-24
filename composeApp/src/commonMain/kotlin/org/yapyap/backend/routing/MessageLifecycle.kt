package org.yapyap.backend.routing

/**
 * Coarse-grained delivery lifecycle for message-like envelopes handled by the router.
 */
enum class MessageLifecycleState {
    CREATED,
    QUEUED,
    SENT,
    ACKED,
    SYNCED,
    ARCHIVED,
    FAILED,
    EXPIRED,
    CANCELLED,
    CAUSAL_HOLD,
    RELAY_QUEUED,
}

/**
 * Fine-grained state of an outbound delivery attempt.
 */
enum class DeliveryAttemptState {
    SCHEDULED,
    IN_FLIGHT,
    TIMEOUT,
    RETRY_WAIT,
    GAVE_UP,
    WAITING_PEER_ONLINE,
    PAUSED_NETWORK_CHANGE,
}

/**
 * Router-facing grouping of envelope families used to apply retry and replay policy.
 */
enum class EnvelopeFamily {
    SIGNAL,
    MESSAGE,
    FILE,
}

/**
 * Replay handling modes applied by envelope family.
 */
enum class ReplayPolicy {
    DROP_DUPLICATE,
    ALLOW_IDEMPOTENT,
    ALLOW_WINDOWED,
}
