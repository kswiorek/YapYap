package org.yapyap.crypto.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.yapyap.crypto.keys.KeySlot
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import kotlin.io.encoding.Base64

internal class DefaultKeyStore(
    private val sessionFactory: KeyringSessionFactory,
    private val serviceName: String = "org.yapyap",
) : KeyStore {

    override suspend fun putKey(slot: KeySlot, key: ByteArray) {
        withContext(Dispatchers.IO) {
            sessionFactory.open().use { session ->
                session.setPassword(
                    serviceName = serviceName,
                    accountName = slot.keyringName,
                    secret = encode(key),
                )
            }
        }
        AppLog.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.KEY_STORED,
            message = "Stored key in keyring",
            fields = mapOf("slot" to slot.keyringName),
        )
    }

    override suspend fun getKey(slot: KeySlot): ByteArray? {
        val encoded = withContext(Dispatchers.IO) {
            sessionFactory.open().use { session ->
                runCatching {
                    session.getPassword(serviceName, slot.keyringName)
                }.getOrNull()
            }
        }
        if (encoded.isNullOrBlank()) {
            AppLog.warn(
                component = LogComponent.CRYPTO,
                event = LogEvent.KEY_LOOKUP_MISS,
                message = "Key lookup returned empty value",
                fields = mapOf("slot" to slot.keyringName),
            )
            return null
        }
        return decode(encoded)
    }

    override suspend fun deleteKey(slot: KeySlot) {
        val deleted = withContext(Dispatchers.IO) {
            sessionFactory.open().use { session ->
                runCatching {
                    session.deletePassword(serviceName, slot.keyringName)
                    true
                }.getOrDefault(false)
            }
        }
        if (deleted) {
            AppLog.info(
                component = LogComponent.CRYPTO,
                event = LogEvent.KEY_DELETED,
                message = "Deleted key from keyring",
                fields = mapOf("slot" to slot.keyringName),
            )
        }
    }


    override suspend fun deleteAll() {
        // Well-known slots only — OS keyrings cannot be enumerated. Prekey slots are
        // enumerated from the DB by the wipe caller (see LocalStoreReset).
        for (slot in KeySlot.wellKnown) {
            deleteKey(slot)
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.encode(bytes)

    private fun decode(value: String): ByteArray = Base64.decode(value)
}
