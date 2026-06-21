package org.yapyap.backend.crypto

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.logging.NoopAppLogger

class DefaultKeyStore(
    private val serviceName: String,
    private val sessionFactory: KeyringSessionFactory,
    private val logger: AppLogger = NoopAppLogger,
) : KeyStore {

    override suspend fun putKey(ref: KeyReference, key: ByteArray) {
        withContext(Dispatchers.IO) {
            sessionFactory.open().use { session ->
                session.setPassword(
                    serviceName = serviceName,
                    accountName = accountName(ref),
                    secret = encode(key),
                )
            }
        }
        logger.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.KEY_STORED,
            message = "Stored key in keyring",
            fields = mapOf("keyId" to ref.keyId, "purpose" to ref.purpose.name, "type" to ref.type.name),
        )
    }

    override suspend fun getKey(ref: KeyReference): ByteArray? {
        val encoded = withContext(Dispatchers.IO) {
            sessionFactory.open().use { session ->
                runCatching {
                    session.getPassword(serviceName, accountName(ref))
                }.getOrNull()
            }
        }
        if (encoded.isNullOrBlank()) {
            logger.warn(
                component = LogComponent.CRYPTO,
                event = LogEvent.KEY_LOOKUP_MISS,
                message = "Key lookup returned empty value",
                fields = mapOf("keyId" to ref.keyId, "purpose" to ref.purpose.name, "type" to ref.type.name),
            )
            return null
        }
        return decode(encoded)
    }

    override suspend fun deleteKey(ref: KeyReference) {
        val deleted = withContext(Dispatchers.IO) {
            sessionFactory.open().use { session ->
                runCatching {
                    session.deletePassword(serviceName, accountName(ref))
                    true
                }.getOrDefault(false)
            }
        }
        if (deleted) {
            logger.info(
                component = LogComponent.CRYPTO,
                event = LogEvent.KEY_DELETED,
                message = "Deleted key from keyring",
                fields = mapOf("keyId" to ref.keyId, "purpose" to ref.purpose.name, "type" to ref.type.name),
            )
        }
    }

    private fun accountName(ref: KeyReference): String {
        return "${ref.purpose.name.lowercase()}:${ref.keyId}:${ref.type.name.lowercase()}"
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encode(bytes: ByteArray): String = Base64.encode(bytes)

    @OptIn(ExperimentalEncodingApi::class)
    private fun decode(value: String): ByteArray = Base64.decode(value)
}
