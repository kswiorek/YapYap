package org.yapyap.orchestrator

import kotlinx.serialization.Serializable

@Serializable
data class OrchestratorConfig(
    val maintenanceIntervalSeconds: Long = 60 * 60,
)