package org.yapyap.routing.policy

import org.yapyap.protocol.PeerId

interface SyncPeerPolicy {
    fun pickNextDevice(candidates: List<PeerId>, attempted: Set<PeerId>): PeerId?
}