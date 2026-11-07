package org.yapyap.persistence.key

import org.yapyap.orchestrator.identity.*

import org.yapyap.crypto.store.*

import org.yapyap.crypto.keys.*

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class KeyringNamespaceTest {

    private fun ref(): KeySlot = KeySlot.DeviceSigningPrivate

    private fun store(
        backing: MutableMap<Pair<String, String>, String>,
        namespace: String?,
    ): DefaultKeyStore = DefaultKeyStore(
        serviceName = "org.yapyap.test.ns",
        sessionFactory = MapBackedKeyringSessionFactory(backing, namespace),
    )

    @Test
    fun serviceName_nullNamespace_keepsLegacyService() {
        assertEquals("org.yapyap", keyringServiceName("org.yapyap", null))
    }

    @Test
    fun serviceName_namespace_isAppended() {
        assertEquals("org.yapyap.dev-a", keyringServiceName("org.yapyap", "dev-a"))
    }

    @Test
    fun serviceName_blankNamespace_failsFast() = runTest {
        val failure: IllegalArgumentException = assertFailsWith {
            store(mutableMapOf(), "  ")
        }
        assertTrue(failure.message.orEmpty().isNotBlank())
    }

    @Test
    fun serviceName_badCharset_failsFast() = runTest {
        val failure: IllegalArgumentException = assertFailsWith {
            store(mutableMapOf(), "dev/a?")
        }
        assertTrue(failure.message.orEmpty().isNotBlank())
    }

    @Test
    fun namespaces_isolateSameRef() = runTest {
        val backing = mutableMapOf<Pair<String, String>, String>()
        val storeA = store(backing, "a")
        val storeB = store(backing, "b")
        val legacy = store(backing, null)
        val material = byteArrayOf(0x01, 0x02)

        storeA.putKey(ref(), material)

        assertContentEquals(material, storeA.getKey(ref()))
        assertNull(storeB.getKey(ref()))
        assertNull(legacy.getKey(ref()))
        assertEquals(
            // Pins the keyring account name existing installs look the device key up by.
            setOf("org.yapyap.test.ns.a" to "signing:yapyap:local_device:signing:private"),
            backing.keys,
        )
    }

    @Test
    fun legacy_writes_stayVisibleToLegacyOnly() = runTest {
        val backing = mutableMapOf<Pair<String, String>, String>()
        val storeA = store(backing, "a")
        val legacy = store(backing, null)
        val material = byteArrayOf(0x03)

        legacy.putKey(ref(), material)

        assertContentEquals(material, legacy.getKey(ref()))
        assertNull(storeA.getKey(ref()))
    }

    @Test
    fun deleteAll_staysWithinOwnNamespace() = runTest {
        val backing = mutableMapOf<Pair<String, String>, String>()
        val storeA = store(backing, "a")
        val storeB = store(backing, "b")

        storeA.putKey(KeySlot.DeviceSigningPrivate, byteArrayOf(0x01))
        storeB.putKey(KeySlot.DeviceSigningPrivate, byteArrayOf(0x02))

        storeA.deleteAll()

        assertNull(storeA.getKey(KeySlot.DeviceSigningPrivate))
        assertContentEquals(byteArrayOf(0x02), storeB.getKey(KeySlot.DeviceSigningPrivate))
    }
}
