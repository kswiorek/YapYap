package org.yapyap.routing.policy

import org.yapyap.protocol.PeerId

internal interface SyncPeerPolicy {
    /**
     * The device to ask next among [candidates], or null when none can be asked now. [declined]
     * devices are never picked; [unanswered] ones (an earlier attempt expired or failed without a
     * decline) only when no other device can be asked.
     */
    fun pickNextDevice(
        candidates: List<PeerId>,
        declined: Set<PeerId>,
        unanswered: Set<PeerId> = emptySet(),
    ): PeerId?
}
