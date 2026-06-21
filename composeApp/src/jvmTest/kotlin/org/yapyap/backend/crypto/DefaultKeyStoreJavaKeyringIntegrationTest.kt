package org.yapyap.backend.crypto

import com.github.javakeyring.Keyring
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlinx.coroutines.test.runTest
import org.yapyap.backend.logging.NoopAppLogger

/**
 * Exercises [DefaultKeyStore] with [JavaKeyringSessionFactory] against the host OS credential store
 * (Windows Credential Manager, macOS Keychain, Freedesktop Secret Service, …).
 *
 * Opt-in: `./gradlew :composeApp:jvmTest -PintegrationTests=true`
 */
class DefaultKeyStoreJavaKeyringIntegrationTest {

    private var serviceName: String? = null
    private var accountName: String? = null

    @AfterTest
    fun tearDown() {
        val service = serviceName ?: return
        val account = accountName ?: return
        runCatching {
            Keyring.create().use { keyring ->
                keyring.deletePassword(service, account)
            }
        }
    }

    @Test
    fun putKey_then_getKey_roundTrip_usesOsCredentialStore() = runTest {
        val ref = KeyReference(
            keyId = "integration-${UUID.randomUUID()}",
            purpose = IdentityKeyPurpose.SIGNING,
            type = KeyType.PRIVATE,
        )
        serviceName = "yapyap.it.keyring.${UUID.randomUUID()}"
        accountName = "${ref.purpose.name.lowercase()}:${ref.keyId}:${ref.type.name.lowercase()}"
        val store = DefaultKeyStore(
            serviceName = serviceName!!,
            sessionFactory = JavaKeyringSessionFactory,
            logger = NoopAppLogger,
        )
        val material = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)

        store.putKey(ref, material)

        assertContentEquals(material, store.getKey(ref))
    }
}
