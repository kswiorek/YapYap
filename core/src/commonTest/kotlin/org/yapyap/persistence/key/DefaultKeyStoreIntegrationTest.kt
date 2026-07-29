package org.yapyap.persistence.key

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefaultKeyStoreIntegrationTest {

    @Test
    fun putKey_then_getKey_roundTrip() = runTest {
        val store = defaultStore(serviceName = "yapyap.test.pk")
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
    fun getKey_returnsNullWhenEntryMissing() = runTest {
        val store = defaultStore(serviceName = "yapyap.test.pk.miss")
        val ref = KeyReference(
            keyId = "missing",
            purpose = IdentityKeyPurpose.SIGNING,
            type = KeyType.PRIVATE,
        )

        assertNull(store.getKey(ref))
    }

    @Test
    fun deleteKey_removesEntry_andIsIdempotentWhenMissing() = runTest {
        val backing = mutableMapOf<Pair<String, String>, String>()
        val store = defaultStore(serviceName = "yapyap.test.pk.delete", backing = backing)
        val ref = KeyReference(
            keyId = "device-local-signing",
            purpose = IdentityKeyPurpose.SIGNING,
            type = KeyType.PRIVATE,
        )
        val otherRef = ref.copy(keyId = "other-key")
        val material = byteArrayOf(0x0A, 0x0B)

        store.putKey(ref, material)
        store.putKey(otherRef, byteArrayOf(0x0C))

        store.deleteKey(ref)

        assertNull(store.getKey(ref))
        assertContentEquals(byteArrayOf(0x0C), store.getKey(otherRef))
        assertEquals(1, backing.size)
        store.deleteKey(ref)
    }

    @Test
    fun masterKeyProvider_secondCall_returnsSameMaterial() = runTest {
        val backing = mutableMapOf<Pair<String, String>, String>()
        val store = defaultStore(serviceName = "yapyap.test.mk", backing = backing)
        val provider = DefaultMasterKeyProvider(
            keyStore = store,
            crypto = DefaultCryptoProvider(random = Random(1)),
        )

        val first = provider.getOrCreate()
        val second = DefaultMasterKeyProvider(
            keyStore = store,
            crypto = DefaultCryptoProvider(random = Random(2)),
        ).getOrCreate()

        assertEquals(DefaultMasterKeyProvider.DEFAULT_KEY_SIZE_BYTES, first.size)
        assertContentEquals(first, second)
        assertEquals(1, backing.size)
    }

    @Test
    fun masterKeyProvider_persistsDeterministicBytes_whenCryptoRandomIsFixed() = runTest {
        val store = defaultStore(serviceName = "yapyap.test.mk.det")
        val seed = 0xA5
        val keySize = 16
        val expected = DefaultCryptoProvider(random = Random(seed)).randomBytes(keySize)

        val key = DefaultMasterKeyProvider(
            keyStore = store,
            crypto = DefaultCryptoProvider(random = Random(seed)),
            keyId = "slot",
            keySizeBytes = keySize,
        ).getOrCreate()

        assertEquals(keySize, key.size)
        assertContentEquals(expected, key)
    }

    private fun defaultStore(
        serviceName: String,
        backing: MutableMap<Pair<String, String>, String> = mutableMapOf(),
    ): DefaultKeyStore =
        DefaultKeyStore(
            serviceName = serviceName,
            sessionFactory = MapBackedKeyringSessionFactory(backing),
        )
}
