package org.yapyap.routing.router

import org.yapyap.routing.file.port.FileCarrier

/**
 * The routing layer's lifecycle and its services, one per concern. Orchestrator components take
 * the service they use, never the whole router.
 */
internal interface Router {
    val messages: MessageCarrier
    val syncs: SyncCarrier
    val bootstrap: BootstrapCarrier
    val presence: Presence

    /** Account-level file operations and events (design §12); routing fans out per device. */
    val files: FileCarrier

    suspend fun start()
    suspend fun stop()
    fun isRunning(): Boolean
}
