package org.yapyap.orchestrator.sync

import kotlinx.coroutines.CoroutineScope
import org.yapyap.orchestrator.dag.RoomId
import kotlin.uuid.Uuid

interface SyncCoordinator {
    fun start(scope: CoroutineScope)
    suspend fun stop()

    /**
     * A ping carried [tips] as the peer's chainable frontier for [roomId].
     * Every tip unknown locally gets its own pending sync (one row per missing ID);
     * the responder serves the tip plus its ancestry down to our known frontier.
     */
    suspend fun requestFrontierSync(roomId: RoomId, tips: List<Uuid>)
}