package org.yapyap.backend.db

import org.yapyap.backend.crypto.CryptoProvider
import org.yapyap.backend.crypto.IdentityKeyPurpose
import org.yapyap.backend.crypto.KeyReference
import org.yapyap.backend.crypto.KeyStore
import org.yapyap.backend.crypto.KeyType
import org.yapyap.backend.crypto.LocalOneTimePreKey
import org.yapyap.backend.crypto.e2ee.OneTimePreKeyStore
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.time.EpochSecondsProvider
import org.yapyap.backend.time.SystemEpochSecondsProvider

class DefaultOneTimePreKeyStore(
    private val database: YapYapDatabase,
    private val keyStore: KeyStore,
    private val crypto: CryptoProvider,
    private val localDeviceId: PeerId,
    private val timeProvider: EpochSecondsProvider = SystemEpochSecondsProvider,
) : OneTimePreKeyStore {

    override suspend fun allocate(): LocalOneTimePreKey {
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
            device_id = localDeviceId.id,
            public_key = opk.publicKey,
            status = OpkStatus.ALLOCATED,
            created_at_epoch_seconds = now,
            offered_at_epoch_seconds = null,
        )

        val opkRef = opkPrivateKeyRef(opk.keyId)
        keyStore.putKey(opkRef, opk.privateKey)

        return opk
    }

    override suspend fun markOffered(opkId: String) {
        val now = timeProvider.nowEpochSeconds()
        database.identityQueries.markOneTimePreKeyOffered(
            status = OpkStatus.OFFERED,
            offered_at_epoch_seconds = now,
            opk_id = opkId,
            device_id = localDeviceId.id,
        )
    }

    override suspend fun consume(opkId: String): LocalOneTimePreKey? {
        val row = database.identityQueries.selectOneTimePreKeyById(opkId).executeAsOneOrNull() ?: return null
        if (row.status != OpkStatus.OFFERED) return null
        if (row.device_id != localDeviceId.id) return null
        database.identityQueries.markOneTimePreKeyConsumed(
            opk_id = opkId,
            device_id = localDeviceId.id,
        )

        val privateKey = keyStore.getKey(opkPrivateKeyRef(opkId)) ?: return null

        return LocalOneTimePreKey(
            keyId = row.opk_id,
            publicKey = row.public_key,
            privateKey = privateKey,
        )
    }

    override suspend fun loadOffered(opkId: String): LocalOneTimePreKey? {
        val row = database.identityQueries.selectOneTimePreKeyById(opkId).executeAsOneOrNull() ?: return null
        if (row.device_id != localDeviceId.id) return null
        if (row.status != OpkStatus.ALLOCATED && row.status != OpkStatus.OFFERED) return null
        val privateKey = keyStore.getKey(opkPrivateKeyRef(opkId)) ?: return null
        return LocalOneTimePreKey(
            keyId = row.opk_id,
            publicKey = row.public_key,
            privateKey = privateKey,
        )
    }

    override suspend fun pruneExpiredOffers(cutoffEpochSeconds: Long): List<String> {
        val expiredIds = database.identityQueries
            .selectExpiredOfferedOneTimePreKeys(
                device_id = localDeviceId.id,
                offered_at_epoch_seconds = cutoffEpochSeconds,
            )
            .executeAsList()
        for (opkId in expiredIds) {
            keyStore.deleteKey(opkPrivateKeyRef(opkId))
            database.identityQueries.deleteOneTimePreKeyById(
                opk_id = opkId,
                device_id = localDeviceId.id,
            )
        }
        return expiredIds
    }

    private fun opkPrivateKeyRef(opkId: String): KeyReference =
        KeyReference(keyId = opkId, purpose = IdentityKeyPurpose.ENCRYPTION, type = KeyType.PRIVATE)

    companion object {
        private const val OPK_ID_BYTES = 8
    }
}
