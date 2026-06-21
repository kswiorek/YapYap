package org.yapyap.backend.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

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
}
