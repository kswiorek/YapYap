package org.yapyap.routing.message.port

import org.yapyap.protocol.AccountId
import org.yapyap.protocol.MessageId
import org.yapyap.protocol.RoomId

/**
 * The frontiers a ping advertises, supplied from above the router: which rooms we share with
 * an account and their tips are room knowledge, not routing.
 */
internal interface FrontierSnapshotProvider {
    /** Chainable frontier tip IDs per room shared with [account]. Empty on fresh installs. */
    suspend fun latestRoomFrontiers(account: AccountId): List<Pair<RoomId, List<MessageId>>>
}
