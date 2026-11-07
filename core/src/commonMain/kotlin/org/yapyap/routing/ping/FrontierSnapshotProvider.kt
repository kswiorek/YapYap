package org.yapyap.routing.ping

import org.yapyap.protocol.AccountId
import org.yapyap.protocol.RoomId
import kotlin.uuid.Uuid

/**
 * The frontiers a ping advertises, supplied from above the router: which rooms we share with
 * an account and their tips are room knowledge, not routing.
 */
internal interface FrontierSnapshotProvider {
    /** Chainable frontier tip IDs per room shared with [account]. Empty on fresh installs. */
    suspend fun latestRoomFrontiers(account: AccountId): List<Pair<RoomId, List<Uuid>>>
}
