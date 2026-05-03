package org.yapyap.backend.crypto

import org.yapyap.backend.db.MasterKeyProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

/** Always returns the same material (typical fake for tests). */
private class FixedMasterKeyProvider(private val material: ByteArray) : MasterKeyProvider {
    override fun getOrCreateMasterKey(): ByteArray = material.copyOf()
}

class MemoryKeyStoresContractTest {

    @Test
    fun privateKeyStore_putThenGet_roundTrip_andIsolation() {
        val store = InMemoryPrivateKeyStore()
        val ref = KeyReference(
            keyId = "yapyap:test:key",
            purpose = IdentityKeyPurpose.SIGNING,
            type = KeyType.PRIVATE,
        )
        val material = byteArrayOf(0x01, 0x02, 0x03)

        store.putKey(ref, material)

        assertContentEquals(material, store.getKey(ref))
        assertEquals(null, store.getKey(ref.copy(keyId = "other-key")))
        val retrieved = store.getKey(ref)!!
        assertNotSame(material, retrieved, "store should not expose stored buffer")
        assertContentEquals(material, retrieved)
    }

    @Test
    fun masterKeyProvider_returnsStableCopyEachCall() {
        val secret = ByteArray(32) { it.toByte() }
        val provider = FixedMasterKeyProvider(secret)

        val a = provider.getOrCreateMasterKey()
        val b = provider.getOrCreateMasterKey()

        assertContentEquals(secret, a)
        assertContentEquals(secret, b)
        assertContentEquals(a, b)
        assertNotSame(a, b)
        assertNotSame(secret, a)
    }
}
