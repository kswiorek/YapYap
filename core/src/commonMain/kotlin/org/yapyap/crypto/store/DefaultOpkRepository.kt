package org.yapyap.crypto.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.crypto.identity.LocalOneTimePreKey
import org.yapyap.crypto.keys.EncryptionPrivateKey
import org.yapyap.crypto.keys.KeySlot
import org.yapyap.crypto.keys.OPK_ID_PREFIX
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.databaseDispatcher
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.identity.OneTimePreKeyId
import kotlin.time.Clock
import kotlin.time.Instant

internal class DefaultOpkRepository(
    private val database: YapYapDatabase,
    private val keyStore: KeyStore,
    private val crypto: CryptoProvider,
    private val localDeviceId: PeerId,
    private val clock: Clock = Clock.System,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : OpkRepository {

    override suspend fun allocate(): LocalOneTimePreKey = withContext(dbDispatcher) {
        val keyPair = crypto.generateEncryptionKeyPair()
        val opkId =
            OneTimePreKeyId(OPK_ID_PREFIX + crypto.toHex(crypto.sha256(keyPair.publicKey.bytes).copyOf(OPK_ID_BYTES)))
        val opk = LocalOneTimePreKey(
            id = opkId,
            publicKey = keyPair.publicKey,
            privateKey = keyPair.privateKey,
        )
        val now = clock.now()
        database.cryptoQueries.insertOneTimePreKey(
            opk_id = opk.id,
            device_id = localDeviceId,
            public_key = opk.publicKey,
            status = OpkStatus.ALLOCATED,
            created_at_epoch_seconds = now,
            offered_at_epoch_seconds = null,
        )

        keyStore.putKey(KeySlot.OneTimePreKeyPrivate(opk.id), opk.privateKey.bytes)

        return@withContext opk
    }

    override suspend fun markOffered(id: OneTimePreKeyId) {
        withContext(dbDispatcher) {
            val now = clock.now()
            database.cryptoQueries.markOneTimePreKeyOffered(
                status = OpkStatus.OFFERED,
                offered_at_epoch_seconds = now,
                opk_id = id,
                device_id = localDeviceId,
            )
        }
    }

    override suspend fun consume(id: OneTimePreKeyId): LocalOneTimePreKey? = withContext(dbDispatcher) {
        val row =
            database.cryptoQueries.selectOneTimePreKeyById(id).executeAsOneOrNull() ?: return@withContext null
        if (row.status != OpkStatus.OFFERED) return@withContext null
        if (row.device_id != localDeviceId) return@withContext null
        database.cryptoQueries.markOneTimePreKeyConsumed(
            opk_id = id,
            device_id = localDeviceId,
        )

        val privateKey = keyStore.getKey(KeySlot.OneTimePreKeyPrivate(id)) ?: return@withContext null

        return@withContext LocalOneTimePreKey(
            id = row.opk_id,
            publicKey = row.public_key,
            privateKey = EncryptionPrivateKey(privateKey),
        )
    }

    override suspend fun loadOffered(id: OneTimePreKeyId): LocalOneTimePreKey? = withContext(dbDispatcher) {
        val row =
            database.cryptoQueries.selectOneTimePreKeyById(id).executeAsOneOrNull() ?: return@withContext null
        if (row.device_id != localDeviceId) return@withContext null
        if (row.status != OpkStatus.ALLOCATED && row.status != OpkStatus.OFFERED) return@withContext null
        val privateKey = keyStore.getKey(KeySlot.OneTimePreKeyPrivate(id)) ?: return@withContext null
        return@withContext LocalOneTimePreKey(
            id = row.opk_id,
            publicKey = row.public_key,
            privateKey = EncryptionPrivateKey(privateKey),
        )
    }

    override suspend fun pruneExpiredOffers(cutoff: Instant): List<OneTimePreKeyId> =
        withContext(dbDispatcher) {
            val expiredIds = database.cryptoQueries
                .selectExpiredOfferedOneTimePreKeys(
                    device_id = localDeviceId,
                    offered_at_epoch_seconds = cutoff,
                )
                .executeAsList()
            for (opkId in expiredIds) {
                keyStore.deleteKey(KeySlot.OneTimePreKeyPrivate(opkId))
                database.cryptoQueries.deleteOneTimePreKeyById(
                    opk_id = opkId,
                    device_id = localDeviceId,
                )
            }
            expiredIds
        }

    override suspend fun opkIds(): List<OneTimePreKeyId> =
        withContext(dbDispatcher) {
            database.cryptoQueries
                .selectOneTimePreKeyIdsForDevice(localDeviceId)
                .executeAsList()
        }

    companion object {
        private const val OPK_ID_BYTES = 8
    }
}
