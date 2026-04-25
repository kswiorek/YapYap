package org.yapyap.backend.db

import com.github.javakeyring.Keyring
import java.security.SecureRandom
import java.util.Base64

class JvmMasterKeyProvider internal constructor(
    private val serviceName: String,
    private val accountName: String,
    private val keySizeBytes: Int = 32,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val sessionFactory: KeyringSessionFactory,
) : MasterKeyProvider {
    constructor(
        serviceName: String,
        accountName: String,
        keySizeBytes: Int = 32,
        secureRandom: SecureRandom = SecureRandom(),
    ) : this(
        serviceName = serviceName,
        accountName = accountName,
        keySizeBytes = keySizeBytes,
        secureRandom = secureRandom,
        sessionFactory = JavaKeyringSessionFactory,
    )

    override fun getOrCreateMasterKey(): ByteArray {
        sessionFactory.open().use { session ->
            val existing = runCatching {
                session.getPassword(serviceName, accountName)
            }.getOrNull()

            if (!existing.isNullOrBlank()) {
                return decode(existing)
            }

            val generated = ByteArray(keySizeBytes)
            secureRandom.nextBytes(generated)
            session.setPassword(serviceName, accountName, encode(generated))
            return generated
        }
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
