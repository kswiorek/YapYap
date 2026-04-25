package org.yapyap.backend.db

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class JvmMasterKeyProviderTest {

    @Test
    fun generatesKeyAndReusesStoredValue() {
        val fakeSession = InMemoryKeyringSession()
        val provider = JvmMasterKeyProvider(
            serviceName = "yapyap-test",
            accountName = "db-master-key",
            keySizeBytes = 32,
            secureRandom = FixedSecureRandom(),
            sessionFactory = KeyringSessionFactory { fakeSession },
        )

        val first = provider.getOrCreateMasterKey()
        val second = provider.getOrCreateMasterKey()

        assertEquals(32, first.size)
        assertContentEquals(first, second)
        assertEquals(1, fakeSession.writeCount)
        assertEquals(2, fakeSession.readCount)
    }
}

private class InMemoryKeyringSession : KeyringSession {
    private val secrets = mutableMapOf<Pair<String, String>, String>()
    var writeCount = 0
    var readCount = 0

    override fun setPassword(serviceName: String, accountName: String, secret: String) {
        writeCount += 1
        secrets[serviceName to accountName] = secret
    }

    override fun getPassword(serviceName: String, accountName: String): String {
        readCount += 1
        return secrets[serviceName to accountName] ?: error("Missing credential")
    }

    override fun close() = Unit
}

private class FixedSecureRandom : SecureRandom() {
    override fun nextBytes(bytes: ByteArray) {
        for (i in bytes.indices) {
            bytes[i] = (i + 7).toByte()
        }
    }
}
