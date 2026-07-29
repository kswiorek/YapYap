package org.yapyap.orchestrator.sync

import kotlinx.coroutines.CoroutineScope

interface SyncCoordinator {
    fun start(scope: CoroutineScope)
    suspend fun stop()
}