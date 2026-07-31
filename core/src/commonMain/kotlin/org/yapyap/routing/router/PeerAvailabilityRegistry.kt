package org.yapyap.routing.router

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.yapyap.protocol.PeerId

internal class PeerAvailabilityRegistry {

    private val onlineDevicesFlow = MutableStateFlow<Set<PeerId>>(emptySet())
    private val onlineEventsFlow = MutableStateFlow<PeerId?>(null)

    val onlineDevices: Flow<Set<PeerId>> = onlineDevicesFlow.asStateFlow()
    val onlineEvents: Flow<PeerId> = onlineEventsFlow.asStateFlow() as Flow<PeerId> //TODO


    fun markReachable(deviceId: PeerId, now: Long) {TODO()}
    fun isOnline(deviceId: PeerId): Boolean {TODO()}
}