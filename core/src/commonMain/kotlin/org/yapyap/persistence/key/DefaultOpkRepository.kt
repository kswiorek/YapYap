package org.yapyap.persistence.key

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.LocalOneTimePreKey
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.OpkStatus
import org.yapyap.persistence.db.databaseDispatcher
import org.yapyap.protocol.PeerId
import org.yapyap.time.EpochProvider
import org.yapyap.time.SystemEpochProvider

class DefaultOpkRepository(
    private val database: YapYapDatabase,
    private val keyStore: KeyStore,
    private val crypto: CryptoProvider,
    private val localDeviceId: PeerId,
    private val timeProvider: EpochProvider = SystemEpochProvider,
    private val dbDispatcher: CoroutineDispatcher = databaseDispatcher,
) : OpkRepository {

    override suspend fun allocate(): LocalOneTimePreKey = withContext(dbDispatcher) {
        val keyPair = crypto.generateEncryptionKeyPair()
        val opkId = "opk-${crypto.sha256(keyPair.publicKey).take(OPK_ID_BYTES).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }}"
        val opk = LocalOneTimePreKey(
            keyId = opkId,
            publicKey = keyPair.publicKey,
            privateKey = keyPair.privateKey,
        )
        val now = timeProvider.nowEpochSeconds()
        database.identityQueries.insertOneTimePreKey(
            opk_id = opk.keyId,
            device_id = localDeviceId,
            public_key = opk.publicKey,
            status = OpkStatus.ALLOCATED,
            created_at_epoch_seconds = now,
            offered_at_epoch_seconds = null,
        )

        val opkRef = opkPrivateKeyRef(opk.keyId)
        keyStore.putKey(opkRef, opk.privateKey)

        return@withContext opk
    }

    override suspend fun markOffered(opkId: String) {
        withContext(dbDispatcher) {
            val now = timeProvider.nowEpochSeconds()
            database.identityQueries.markOneTimePreKeyOffered(
                status = OpkStatus.OFFERED,
                offered_at_epoch_seconds = now,
                opk_id = opkId,
                device_id = localDeviceId,
            )
        }
    }

    override suspend fun consume(opkId: String): LocalOneTimePreKey? = withContext(dbDispatcher) {
        val row = database.identityQueries.selectOneTimePreKeyById(opkId).executeAsOneOrNull() ?: return@withContext null
        if (row.status != OpkStatus.OFFERED) return@withContext null
        if (row.device_id != localDeviceId) return@withContext null
        database.identityQueries.markOneTimePreKeyConsumed(
            opk_id = opkId,
            device_id = localDeviceId,
        )

        val privateKey = keyStore.getKey(opkPrivateKeyRef(opkId)) ?: return@withContext null

        return@withContext LocalOneTimePreKey(
            keyId = row.opk_id,
            publicKey = row.public_key,
            privateKey = privateKey,
        )
    }

    override suspend fun loadOffered(opkId: String): LocalOneTimePreKey? = withContext(dbDispatcher) {
        val row = database.identityQueries.selectOneTimePreKeyById(opkId).executeAsOneOrNull() ?: return@withContext null
        if (row.device_id != localDeviceId) return@withContext null
        if (row.status != OpkStatus.ALLOCATED && row.status != OpkStatus.OFFERED) return@withContext null
        val privateKey = keyStore.getKey(opkPrivateKeyRef(opkId)) ?: return@withContext null
        return@withContext LocalOneTimePreKey(
            keyId = row.opk_id,
            publicKey = row.public_key,
            privateKey = privateKey,
        )
    }

    override suspend fun pruneExpiredOffers(cutoffEpochSeconds: Long): List<String> =
        withContext(dbDispatcher) {
            val expiredIds = database.identityQueries
                .selectExpiredOfferedOneTimePreKeys(
                    device_id = localDeviceId,
                    offered_at_epoch_seconds = cutoffEpochSeconds,
                )
                .executeAsList()
            for (opkId in expiredIds) {
                keyStore.deleteKey(opkPrivateKeyRef(opkId))
                database.identityQueries.deleteOneTimePreKeyById(
                    opk_id = opkId,
                    device_id = localDeviceId,
                )
            }
            expiredIds
        }

    private fun opkPrivateKeyRef(opkId: String): KeyReference =
        KeyReference(keyId = opkId, purpose = IdentityKeyPurpose.ENCRYPTION, type = KeyType.PRIVATE)

    companion object {
        private const val OPK_ID_BYTES = 8
    }
}
