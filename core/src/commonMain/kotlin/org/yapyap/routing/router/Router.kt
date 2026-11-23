package org.yapyap.routing.router

import org.yapyap.routing.file.port.FileCarrier

/**
 * The routing layer's lifecycle and its services, one per concern. Orchestrator components
 * take the service they use, never the whole router.
 *
 * The pull path is message content recovery, so it lives on [MessageCarrier] next to the push
 * path — the same way the file pull path lives on [FileCarrier] next to file push.
 */
internal interface Router {
    val messages: MessageCarrier
    val bootstrap: BootstrapCarrier
    val presence: Presence
    val files: FileCarrier

    suspend fun start()
    suspend fun stop()
    fun isRunning(): Boolean
}
