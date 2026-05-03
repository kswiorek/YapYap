package org.yapyap.backend.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import org.yapyap.backend.logging.NoopAppLogger

/**
 * Uses [JvmPrivateKeyStore]'s internal constructor with an in-memory keyring — exercises encode/decode +
 * account naming without relying on OS credential stores (Windows Credential Manager, macOS Keychain, …).
 */
private class MapBackedCryptoKeyringFactory(
    private val storage: MutableMap<Pair<String, String>, String> = mutableMapOf(),
) : KeyringSessionFactory {

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

class JvmPrivateKeyStoreIntegrationTest {

    @Test
    fun putKey_then_getKey_roundTrip() {
        val factory = MapBackedCryptoKeyringFactory()
        val store = JvmPrivateKeyStore(
            serviceName = "yapyap.jvmtest.pk",
            sessionFactory = factory,
            logger = NoopAppLogger,
        )
        val ref = KeyReference(
            keyId = "device-local-signing",
            purpose = IdentityKeyPurpose.SIGNING,
            type = KeyType.PRIVATE,
        )
        val material = byteArrayOf(0x01, 0x02, 0x03, 0x04)

        store.putKey(ref, material)

        assertContentEquals(material, store.getKey(ref))
    }

    @Test
    fun getKey_returnsNullWhenEntryMissing() {
        val factory = MapBackedCryptoKeyringFactory()
        val store = JvmPrivateKeyStore(
            serviceName = "yapyap.jvmtest.pk.miss",
            sessionFactory = factory,
            logger = NoopAppLogger,
        )
        val ref = KeyReference(
            keyId = "missing",
            purpose = IdentityKeyPurpose.SIGNING,
            type = KeyType.PRIVATE,
        )

        assertNull(store.getKey(ref))
    }
}
