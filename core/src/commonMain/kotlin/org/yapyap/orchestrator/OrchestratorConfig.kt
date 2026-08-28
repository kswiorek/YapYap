package org.yapyap.orchestrator

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
data class OrchestratorConfig(
    val maintenanceIntervalSeconds: Long = 60 * 60,
    /** Send cadence for typing indicators while the user is composing; receivers idle-timeout at ~2x this. */
    val typingIndicatorInterval: Duration = 3.seconds,
    /** Delay before a freshly created pending sync is first attempted (lets in-flight messages land). */
    val syncGracePeriodSeconds: Long = 60,
) {
    init {
        require(maintenanceIntervalSeconds > 0) { "maintenanceIntervalSeconds must be > 0" }
        require(typingIndicatorInterval > Duration.ZERO) { "typingIndicatorInterval must be > 0" }
        require(syncGracePeriodSeconds >= 0) { "syncGracePeriodSeconds must be >= 0" }
    }
}
