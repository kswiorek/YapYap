package org.yapyap.orchestrator

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@Serializable
data class OrchestratorConfig(
    val maintenanceInterval: Duration = 1.hours,
    /** Send cadence for typing indicators while the user is composing; receivers idle-timeout at ~2x this. */
    val typingIndicatorInterval: Duration = 3.seconds,
    /** Delay before a freshly created pending sync is first attempted (lets in-flight messages land). */
    val syncGracePeriod: Duration = 60.seconds,
) {
    init {
        require(maintenanceInterval > Duration.ZERO) { "maintenanceIntervalSeconds must be > 0" }
        require(typingIndicatorInterval > Duration.ZERO) { "typingIndicatorInterval must be > 0" }
        require(syncGracePeriod >= Duration.ZERO) { "syncGracePeriodSeconds must be >= 0" }
    }
}
