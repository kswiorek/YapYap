package org.yapyap.backend.crypto

import com.github.javakeyring.Keyring
import java.util.Base64
import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.logging.NoopAppLogger

class JvmPrivateKeyStore internal constructor(
    private val serviceName: String,
    private val sessionFactory: KeyringSessionFactory,
    private val logger: AppLogger = NoopAppLogger,
) : PrivateKeyStore {
    constructor(
        serviceName: String,
    ) : this(
        serviceName = serviceName,
        sessionFactory = JavaKeyringSessionFactory,
        logger = NoopAppLogger,
    )

    override fun putKey(ref: KeyReference, key: ByteArray) {
        sessionFactory.open().use { session ->
            session.setPassword(
                serviceName = serviceName,
                accountName = accountName(ref),
                secret = encode(key),
            )
        }
        logger.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.KEY_STORED,
            message = "Stored key in keyring",
            fields = mapOf("keyId" to ref.keyId, "purpose" to ref.purpose.name, "type" to ref.type.name),
        )
    }

    override fun getKey(ref: KeyReference): ByteArray? {
        sessionFactory.open().use { session ->
            val encoded = runCatching {
                session.getPassword(serviceName, accountName(ref))
            }.getOrNull()
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
    }

    private fun accountName(ref: KeyReference): String {
        return "${ref.purpose.name.lowercase()}:${ref.keyId}:${ref.type.name.lowercase()}"
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
}

internal fun interface KeyringSessionFactory {
    fun open(): KeyringSession
}

internal interface KeyringSession : AutoCloseable {
    fun setPassword(serviceName: String, accountName: String, secret: String)
    fun getPassword(serviceName: String, accountName: String): String
}

internal object JavaKeyringSessionFactory : KeyringSessionFactory {
    override fun open(): KeyringSession {
        val keyring = Keyring.create()
        return object : KeyringSession {
            override fun setPassword(serviceName: String, accountName: String, secret: String) {
                keyring.setPassword(serviceName, accountName, secret)
            }

            override fun getPassword(serviceName: String, accountName: String): String {
                return keyring.getPassword(serviceName, accountName)
            }

            override fun close() {
                keyring.close()
            }
        }
    }
}
