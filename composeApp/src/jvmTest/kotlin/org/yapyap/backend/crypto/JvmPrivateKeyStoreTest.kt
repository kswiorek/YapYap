package org.yapyap.backend.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JvmPrivateKeyStoreTest {

    @Test
    fun storesAndLoadsPrivateKeyFromKeyring() {
        val fakeSession = InMemoryKeyringSession()
        val store = JvmPrivateKeyStore(
            serviceName = "yapyap-test",
            sessionFactory = KeyringSessionFactory { fakeSession },
        )
        val ref = PrivateKeyRef(
            deviceId = "device-a",
            keyId = "sign-key-v1",
            purpose = IdentityKeyPurpose.SIGNING,
        )
        val privateKey = byteArrayOf(10, 20, 30, 40)

        store.putPrivateKey(ref, privateKey)
        val loaded = store.getPrivateKey(ref)

        assertContentEquals(privateKey, loaded)
        assertEquals(1, fakeSession.writeCount)
        assertEquals(1, fakeSession.readCount)
    }

    @Test
    fun returnsNullWhenPrivateKeyIsMissing() {
        val fakeSession = InMemoryKeyringSession()
        val store = JvmPrivateKeyStore(
            serviceName = "yapyap-test",
            sessionFactory = KeyringSessionFactory { fakeSession },
        )
        val missing = PrivateKeyRef(
            deviceId = "device-b",
            keyId = "enc-key-v1",
            purpose = IdentityKeyPurpose.ENCRYPTION,
        )

        val loaded = store.getPrivateKey(missing)

        assertNull(loaded)
        assertEquals(1, fakeSession.readCount)
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
