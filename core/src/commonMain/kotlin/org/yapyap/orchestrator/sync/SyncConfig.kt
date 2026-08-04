package org.yapyap.orchestrator.sync

data class SyncConfig(
    val gracePeriodSeconds: Long = 60,
    val syncMaxMessages: Int = 20,
    val deviceOfflineRetryDelaySeconds: Long = 60,
)