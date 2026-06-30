package org.yapyap.crypto

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.persistence.key.KeyReference
import org.yapyap.persistence.key.KeyType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class MemoryKeyStoresContractTest {

    @Test
    fun privateKeyStore_putThenGet_roundTrip_andIsolation() = runTest {
        val store = InMemoryKeyStore()
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
    fun deleteKey_removesEntry_andIsIdempotentWhenMissing() = runTest {
        val store = InMemoryKeyStore()
        val ref = KeyReference(
            keyId = "yapyap:test:key",
            purpose = IdentityKeyPurpose.SIGNING,
            type = KeyType.PRIVATE,
        )
        val otherRef = ref.copy(keyId = "other-key")

        store.putKey(ref, byteArrayOf(0x01))
        store.putKey(otherRef, byteArrayOf(0x02))

        store.deleteKey(ref)

        assertNull(store.getKey(ref))
        assertContentEquals(byteArrayOf(0x02), store.getKey(otherRef))
        store.deleteKey(ref)
    }
}
