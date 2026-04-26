package org.yapyap.backend.db

import java.nio.file.Files
import org.yapyap.backend.crypto.IdentityKeyPurpose
import org.yapyap.backend.crypto.IdentityKeyServiceConfig
import org.yapyap.backend.crypto.IdentityPublicKeyRecord
import org.yapyap.backend.crypto.LocalIdentityRecord
import org.yapyap.backend.protocol.DeviceAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class IdentityPublicKeyRepositoryTest {
    @Test
    fun upsertsAndResolvesIdentityKeysViaSqldelightQueries() {
        val tempDb = Files.createTempFile("yapyap-identity-", ".db")
        try {
            val connection = DatabaseFactory(
                driverFactory = JvmEncryptedDriverFactory(
                    databasePath = tempDb.toAbsolutePath().toString(),
                    masterKey = ByteArray(32) { i -> (i + 31).toByte() },
                ),
            ).createConnection()

            connection.driver.use {
                val repository = DefaultIdentityPublicKeyRepository(
                    database = connection.database,
                    config = IdentityKeyServiceConfig(
                        defaultDeviceType = DeviceType.DESKTOP,
                        defaultOnionAddress = "bootstrap.onion",
                        defaultOnionPort = 80L,
                        defaultLastSeenTimestamp = 0L,
                    ),
                )
                val identity = LocalIdentityRecord(
                    address = DeviceAddress(accountId = "alice", deviceId = "alice-device"),
                    signing = IdentityPublicKeyRecord(
                        keyId = "signing-v1-aaaaaaaaaaaaaaaa",
                        keyVersion = 1L,
                        purpose = IdentityKeyPurpose.SIGNING,
                        publicKey = byteArrayOf(1, 2, 3, 4),
                    ),
                    encryption = IdentityPublicKeyRecord(
                        keyId = "encryption-v1-bbbbbbbbbbbbbbbb",
                        keyVersion = 1L,
                        purpose = IdentityKeyPurpose.ENCRYPTION,
                        publicKey = byteArrayOf(5, 6, 7, 8),
                    ),
                )

                repository.upsertLocalIdentity(identity)

                val signing = repository.resolveDeviceKey("alice-device", IdentityKeyPurpose.SIGNING)
                val encryption = repository.resolveDeviceKey("alice-device", IdentityKeyPurpose.ENCRYPTION)
                assertNotNull(signing)
                assertNotNull(encryption)
                assertEquals(identity.signing.keyId, signing.keyId)
                assertEquals(identity.encryption.keyId, encryption.keyId)
                assertContentEquals(identity.signing.publicKey, signing.publicKey)
                assertContentEquals(identity.encryption.publicKey, encryption.publicKey)
            }
        } finally {
            Files.deleteIfExists(tempDb)
        }
    }
}
