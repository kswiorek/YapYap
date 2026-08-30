package org.yapyap.orchestrator.sync

import kotlinx.coroutines.CoroutineScope
import org.yapyap.orchestrator.dag.RoomId

interface SyncCoordinator {
    fun start(scope: CoroutineScope)
    suspend fun stop()

    suspend fun requestRangeSync(roomId: RoomId, pingLamport: Long)
}