package org.yapyap.backend.db

import org.yapyap.backend.crypto.CryptoProvider
import org.yapyap.backend.crypto.IdentityKeyPurpose
import org.yapyap.backend.crypto.KeyReference
import org.yapyap.backend.crypto.KeyStore
import org.yapyap.backend.crypto.KeyType
import org.yapyap.backend.crypto.LocalOneTimePreKey
import org.yapyap.backend.crypto.e2ee.OneTimePreKeyStore
import org.yapyap.backend.protocol.PeerId

class DefaultOneTimePreKeyStore(
    private val database: YapYapDatabase,
    private val keyStore: KeyStore,
    private val crypto: CryptoProvider,
    private val localDeviceId: PeerId,
) : OneTimePreKeyStore {

    override suspend fun allocate(): LocalOneTimePreKey {
        val keyPair = crypto.generateEncryptionKeyPair()
        val opkId = "opk-${crypto.sha256(keyPair.publicKey).take(OPK_ID_BYTES).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }}"
        val opk = LocalOneTimePreKey(
            keyId = opkId,
            publicKey = keyPair.publicKey,
            privateKey = keyPair.privateKey,
        )
        database.identityQueries.insertOneTimePreKey(
            opk_id = opk.keyId,
            device_id = localDeviceId.id,
            public_key = opk.publicKey,
            is_consumed = false,
        )

        val opkRef = KeyReference(keyId = opk.keyId, purpose = IdentityKeyPurpose.ENCRYPTION, type = KeyType.PRIVATE)

        keyStore.putKey(opkRef, opk.privateKey)

        return opk
    }

    override suspend fun consume(opkId: String): LocalOneTimePreKey? {
        val row = database.identityQueries.selectOneTimePreKeyById(opkId).executeAsOneOrNull() ?: return null
        if (row.is_consumed) return null
        if (row.device_id != localDeviceId.id) return null
        database.identityQueries.markOneTimePreKeyConsumed(opk_id = opkId)

        val opkRef = KeyReference(keyId = opkId, purpose = IdentityKeyPurpose.ENCRYPTION, type = KeyType.PRIVATE)

        val privateKey = keyStore.getKey(opkRef)?: return null
        
        return LocalOneTimePreKey(
            keyId = row.opk_id,
            publicKey = row.public_key,
            privateKey = privateKey,
        )
    }

    companion object {
        private const val OPK_ID_BYTES = 8
    }
}
