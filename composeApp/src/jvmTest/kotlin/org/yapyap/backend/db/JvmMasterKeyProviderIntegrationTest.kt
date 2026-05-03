package org.yapyap.backend.db

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * In-memory keyring backing — avoids OS keyring during CI / headless runs.
 */
private class MapBackedDbKeyringFactory(
    val storage: MutableMap<Pair<String, String>, String>,
) : KeyringSessionFactory {

    constructor() : this(mutableMapOf())

    override fun open(): KeyringSession =
        object : KeyringSession {
            override fun setPassword(serviceName: String, accountName: String, secret: String) {
                storage[serviceName to accountName] = secret
            }

            override fun getPassword(serviceName: String, accountName: String): String =
                storage[serviceName to accountName]
                    ?: error("no password")

            override fun close() {}
        }
}

class JvmMasterKeyProviderIntegrationTest {

    @Test
    fun getOrCreate_secondCall_returnsSameMaterial() {
        val backing = mutableMapOf<Pair<String, String>, String>()
        val factory = MapBackedDbKeyringFactory(backing)
        val provider = JvmMasterKeyProvider(
            serviceName = "yapyap.jvmtest.mk",
            accountName = "master-key-slot",
            keySizeBytes = 32,
            secureRandom = SecureRandom(),
            sessionFactory = factory,
        )

        val first = provider.getOrCreateMasterKey()
        val second = provider.getOrCreateMasterKey()

        assertEquals(32, first.size)
        assertContentEquals(first, second)
        assertEquals(1, backing.size)
    }

    @Test
    fun getOrCreate_persistsDeterministicBytes_whenRandomIsFixed() {
        val factory = MapBackedDbKeyringFactory()
        val deterministicRandom =
            object : SecureRandom() {
                override fun nextBytes(bytes: ByteArray) {
                    for (i in bytes.indices) {
                        bytes[i] = (0xA5).toByte()
                    }
                }
            }

        val provider = JvmMasterKeyProvider(
            serviceName = "yapyap.jvmtest.mk.det",
            accountName = "slot",
            keySizeBytes = 16,
            secureRandom = deterministicRandom,
            sessionFactory = factory,
        )

        val key = provider.getOrCreateMasterKey()

        assertEquals(16, key.size)
        assertContentEquals(ByteArray(16) { (0xA5).toByte() }, key)
    }
}
