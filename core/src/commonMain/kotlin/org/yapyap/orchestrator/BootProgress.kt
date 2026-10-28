package org.yapyap.orchestrator

/** Coarse boot phase, for progress UX while [Orchestrator.state] is `Starting`. */
public enum class BootPhase {
    STARTING,
    TRANSPORTS,
    DATABASE,
    ROUTER,
    FOLDS,
    READY,
}

/**
 * Where boot currently is. Only meaningful while [Orchestrator.state] is
 * `Starting` — anything else, and the GUI should ignore it.
 *
 * [roomsDone]/[roomsTotal] are only populated during [BootPhase.FOLDS] (the
 * room-sweep); zero elsewhere (indeterminate).
 */
public data class BootProgress(
    val phase: BootPhase,
    val roomsDone: Int,
    val roomsTotal: Int,
)
