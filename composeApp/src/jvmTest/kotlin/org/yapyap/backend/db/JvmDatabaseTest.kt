package org.yapyap.backend.db

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmDatabaseTest {

    @Test
    fun createsEncryptedDatabaseAndReopens() {
        val tempDb = Files.createTempFile("yapyap-encrypted-", ".db")
        val masterKey = ByteArray(32) { i -> (i + 1).toByte() }
        val driverFactory = JvmEncryptedDriverFactory(
            databasePath = tempDb.toAbsolutePath().toString(),
            masterKey = masterKey,
        )
        val databaseFactory = DatabaseFactory(driverFactory)

        try {
            val firstConnection = databaseFactory.createConnection()
            val firstDatabase = firstConnection.database

            firstDatabase.contactsQueries.insertContact(
                onion_address = "alice1234567890abcdef1234567890abcdef1234567890abcdef.onion",
                status = "ONLINE",
            )
            assertEquals(1, firstDatabase.contactsQueries.getAllContacts().executeAsList().size)
            firstConnection.driver.close()

            val secondConnection = databaseFactory.createConnection()
            val secondDatabase = secondConnection.database
            assertEquals(1, secondDatabase.contactsQueries.getAllContacts().executeAsList().size)
            secondConnection.driver.close()
        } finally {
            Files.deleteIfExists(tempDb)
        }
    }
}
