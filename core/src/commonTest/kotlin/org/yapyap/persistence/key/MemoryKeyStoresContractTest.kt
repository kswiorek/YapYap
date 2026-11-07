package org.yapyap.persistence.key

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.keys.KeySlot
import org.yapyap.protocol.identity.SignedPreKeyId
import kotlin.test.*

class MemoryKeyStoresContractTest {

    @Test
    fun privateKeyStore_putThenGet_roundTrip_andIsolation() = runTest {
        val store = InMemoryKeyStore()
        val ref = KeySlot.SignedPreKeyPrivate(SignedPreKeyId("yapyap:test:key"))
        val material = byteArrayOf(0x01, 0x02, 0x03)

        store.putKey(ref, material)

        assertContentEquals(material, store.getKey(ref))
        assertEquals(null, store.getKey(KeySlot.SignedPreKeyPrivate(SignedPreKeyId("other-key"))))
        val retrieved = store.getKey(ref)!!
        assertNotSame(material, retrieved, "store should not expose stored buffer")
        assertContentEquals(material, retrieved)
    }

    @Test
    fun deleteKey_removesEntry_andIsIdempotentWhenMissing() = runTest {
        val store = InMemoryKeyStore()
        val ref = KeySlot.SignedPreKeyPrivate(SignedPreKeyId("yapyap:test:key"))
        val otherRef = KeySlot.SignedPreKeyPrivate(SignedPreKeyId("other-key"))

        store.putKey(ref, byteArrayOf(0x01))
        store.putKey(otherRef, byteArrayOf(0x02))

        store.deleteKey(ref)

        assertNull(store.getKey(ref))
        assertContentEquals(byteArrayOf(0x02), store.getKey(otherRef))
        store.deleteKey(ref)
    }
}
