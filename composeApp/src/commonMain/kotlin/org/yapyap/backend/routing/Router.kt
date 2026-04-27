package org.yapyap.backend.routing

interface Router {

    suspend fun start()
    suspend fun stop()
    fun isRunning(): Boolean


}