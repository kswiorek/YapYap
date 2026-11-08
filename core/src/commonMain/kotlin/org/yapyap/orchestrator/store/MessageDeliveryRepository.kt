package org.yapyap.orchestrator.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.databaseDispatcher
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.RoomId
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Which accounts are known to hold which messages (orchestrator-owned). */
internal interface MessageDeliveryRepository {
    /** Records that [accountId] holds [messageId]; true when this is new. */
    suspend fun markDelivered(messageId: Uuid, accountId: AccountId, at: Instant): Boolean

    suspend fun deliveredTo(messageId: Uuid): Set<AccountId>

    /** Up to [limit] messages by [authorAccountId] in [roomId] not yet known to be held by [accountId], newest first. */
    suspend fun undelivered(roomId: RoomId, authorAccountId: AccountId, accountId: AccountId, limit: Int): List<Uuid>
}

internal class DefaultMessageDeliveryRepository(
    database: YapYapDatabase,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : MessageDeliveryRepository {

    private val queries = database.messageDeliveryQueries

    override suspend fun markDelivered(messageId: Uuid, accountId: AccountId, at: Instant): Boolean =
        withContext(dbDispatcher) {
            queries.insertDelivery(messageId, accountId, at).value > 0L
        }

    override suspend fun deliveredTo(messageId: Uuid): Set<AccountId> =
        withContext(dbDispatcher) {
            queries.selectDeliveredAccounts(messageId).executeAsList().toSet()
        }

    override suspend fun undelivered(
        roomId: RoomId,
        authorAccountId: AccountId,
        accountId: AccountId,
        limit: Int,
    ): List<Uuid> =
        withContext(dbDispatcher) {
            queries.selectUndelivered(roomId, authorAccountId, accountId, limit.toLong()).executeAsList()
        }
}
