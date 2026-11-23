package org.yapyap.routing.router

import kotlin.concurrent.Volatile

/**
 * The router's started state, shared by every service. Public operations check it uniformly,
 * so no service is reachable before its loops run and none is silently unusable after start
 * while another is usable (the file carrier used to be).
 */
internal class RouterLifecycle {
    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    fun requireStarted(action: String) {
        check(running) { "Router must be started before $action" }
    }

    fun markStarted() {
        running = true
    }

    fun markStopped() {
        running = false
    }
}
