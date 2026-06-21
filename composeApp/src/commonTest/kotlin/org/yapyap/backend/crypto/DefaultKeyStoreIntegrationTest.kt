package org.yapyap.backend.crypto

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.yapyap.backend.logging.NoopAppLogger

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
    fun getOrCreateMasterKey_secondCall_returnsSameMaterial() = runTest {
        val backing = mutableMapOf<Pair<String, String>, String>()
        val store = defaultStore(serviceName = "yapyap.test.mk", backing = backing)
        val ref = masterKeyRef()

        val first = getOrCreateMasterKey(store, ref, keySizeBytes = 32, random = Random(1))
        val second = getOrCreateMasterKey(store, ref, keySizeBytes = 32, random = Random(2))

        assertEquals(32, first.size)
        assertContentEquals(first, second)
        assertEquals(1, backing.size)
    }

    @Test
    fun getOrCreateMasterKey_persistsDeterministicBytes_whenRandomIsFixed() = runTest {
        val store = defaultStore(serviceName = "yapyap.test.mk.det")
        val ref = masterKeyRef(keyId = "slot")
        val expectedRandom = Random(0xA5)
        val expected = ByteArray(16) { expectedRandom.nextInt().toByte() }

        val key = getOrCreateMasterKey(store, ref, keySizeBytes = 16, random = Random(0xA5))

        assertEquals(16, key.size)
        assertContentEquals(expected, key)
    }

    private fun defaultStore(
        serviceName: String,
        backing: MutableMap<Pair<String, String>, String> = mutableMapOf(),
    ): DefaultKeyStore =
        DefaultKeyStore(
            serviceName = serviceName,
            sessionFactory = MapBackedKeyringSessionFactory(backing),
            logger = NoopAppLogger,
        )

    private fun masterKeyRef(keyId: String = "master-key-slot"): KeyReference =
        KeyReference(
            keyId = keyId,
            purpose = IdentityKeyPurpose.ENCRYPTION,
            type = KeyType.PRIVATE,
        )

    private suspend fun getOrCreateMasterKey(
        store: KeyStore,
        ref: KeyReference,
        keySizeBytes: Int,
        random: Random,
    ): ByteArray {
        store.getKey(ref)?.let { return it }
        val generated = ByteArray(keySizeBytes) { random.nextInt().toByte() }
        store.putKey(ref, generated)
        return generated
    }
}
