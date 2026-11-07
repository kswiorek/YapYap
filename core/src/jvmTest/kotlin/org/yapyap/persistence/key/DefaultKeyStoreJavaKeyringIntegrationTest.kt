package org.yapyap.persistence.key

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.JavaKeyringSessionFactory
import org.yapyap.crypto.keys.KeySlot
import org.yapyap.crypto.store.DefaultKeyStore
import org.yapyap.protocol.identity.SignedPreKeyId
import java.util.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * Exercises [org.yapyap.crypto.store.DefaultKeyStore] with [JavaKeyringSessionFactory] against the host OS credential store
 * (Windows Credential Manager, macOS Keychain, Freedesktop Secret Service, …).
 *
 * Opt-in: `./gradlew :composeApp:jvmTest -PintegrationTests=true`
 */
class DefaultKeyStoreJavaKeyringIntegrationTest {

    private var store: DefaultKeyStore? = null
    private var ref: KeySlot? = null

    @AfterTest
    fun tearDown() = runTest {
        val activeStore = store ?: return@runTest
        val activeRef = ref ?: return@runTest
        activeStore.deleteKey(activeRef)
    }

    @Test
    fun putKey_then_getKey_roundTrip_usesOsCredentialStore() = runTest {
        val keyRef = KeySlot.SignedPreKeyPrivate(SignedPreKeyId("integration-${UUID.randomUUID()}"))
        val activeStore = DefaultKeyStore(
            serviceName = "yapyap.it.keyring.${UUID.randomUUID()}",
            sessionFactory = JavaKeyringSessionFactory(),
        )
        store = activeStore
        ref = keyRef
        val material = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)

        activeStore.putKey(keyRef, material)

        assertContentEquals(material, activeStore.getKey(keyRef))

        activeStore.deleteKey(keyRef)

        assertNull(activeStore.getKey(keyRef))
        ref = null
    }
}
