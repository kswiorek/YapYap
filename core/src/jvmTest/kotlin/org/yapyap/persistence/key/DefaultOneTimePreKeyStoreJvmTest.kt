package org.yapyap.persistence.key

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.primitives.KmpCryptoProvider
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.*
import org.yapyap.time.EpochSecondsProvider
import kotlin.test.*

class DefaultOneTimePreKeyStoreJvmTest {

    private var connection: DatabaseConnection? = null

    @AfterTest
    fun closeDb() {
        connection?.driver?.close()
        connection = null
    }

    @Test
    fun allocate_persistsAllocatedRow_andStoresPrivateKeyInKeyStore() = runTest {
        val fixture = openStore(nowEpochSeconds = 1_000L)

        val opk = fixture.store.allocate()

        val row = fixture.database.identityQueries.selectOneTimePreKeyById(opk.keyId).executeAsOneOrNull()
        assertNotNull(row)
        assertEquals(FixtureDevicePeerId.id, row.device_id)
        assertEquals(OpkStatus.ALLOCATED, row.status)
        assertEquals(1_000L, row.created_at_epoch_seconds)
        assertNull(row.offered_at_epoch_seconds)
        assertContentEquals(opk.publicKey, row.public_key)
        assertContentEquals(opk.privateKey, fixture.keyStore.getKey(fixture.opkPrivateKeyRef(opk.keyId)))
    }

    @Test
    fun markOffered_thenConsume_onceOnly() = runTest {
        val fixture = openStore(nowEpochSeconds = 2_000L)

        val opk = fixture.store.allocate()
        fixture.store.markOffered(opk.keyId)

        val rowAfterOffer = fixture.database.identityQueries.selectOneTimePreKeyById(opk.keyId).executeAsOne()
        assertEquals(OpkStatus.OFFERED, rowAfterOffer.status)
        assertEquals(2_000L, rowAfterOffer.offered_at_epoch_seconds)

        val consumed = fixture.store.consume(opk.keyId)
        assertNotNull(consumed)
        assertContentEquals(opk.publicKey, consumed.publicKey)
        assertContentEquals(opk.privateKey, consumed.privateKey)

        val rowAfterConsume = fixture.database.identityQueries.selectOneTimePreKeyById(opk.keyId).executeAsOne()
        assertEquals(OpkStatus.CONSUMED, rowAfterConsume.status)
        assertEquals(null, fixture.store.consume(opk.keyId))
    }

    @Test
    fun consume_requiresOfferedStatus() = runTest {
        val fixture = openStore(nowEpochSeconds = 3_000L)

        val opk = fixture.store.allocate()

        assertEquals(null, fixture.store.consume(opk.keyId))

        val row = fixture.database.identityQueries.selectOneTimePreKeyById(opk.keyId).executeAsOne()
        assertEquals(OpkStatus.ALLOCATED, row.status)
    }

    @Test
    fun consume_unknownOpkId_returnsNull() = runTest {
        val fixture = openStore(nowEpochSeconds = 4_000L)
        assertEquals(null, fixture.store.consume("missing-opk"))
    }

    @Test
    fun pruneExpiredOffers_deletesRowAndPrivateKey() = runTest {
        val time = MutableEpochSecondsProvider(10_000L)
        val fixture = openStore(timeProvider = time)

        val opk = fixture.store.allocate()
        fixture.store.markOffered(opk.keyId)

        time.advanceTo(10_120L)
        val pruned = fixture.store.pruneExpiredOffers(cutoffEpochSeconds = 10_061L)

        assertEquals(listOf(opk.keyId), pruned)
        assertNull(fixture.database.identityQueries.selectOneTimePreKeyById(opk.keyId).executeAsOneOrNull())
        assertNull(fixture.keyStore.getKey(fixture.opkPrivateKeyRef(opk.keyId)))
    }

    @Test
    fun pruneExpiredOffers_keepsFreshOffers() = runTest {
        val time = MutableEpochSecondsProvider(20_000L)
        val fixture = openStore(timeProvider = time)

        val opk = fixture.store.allocate()
        fixture.store.markOffered(opk.keyId)

        val pruned = fixture.store.pruneExpiredOffers(cutoffEpochSeconds = 20_000L)

        assertEquals(emptyList(), pruned)
        assertNotNull(fixture.database.identityQueries.selectOneTimePreKeyById(opk.keyId).executeAsOneOrNull())
        assertNotNull(fixture.keyStore.getKey(fixture.opkPrivateKeyRef(opk.keyId)))
    }

    @Test
    fun pruneExpiredOffers_ignoresAllocatedAndConsumed() = runTest {
        val time = MutableEpochSecondsProvider(30_000L)
        val fixture = openStore(timeProvider = time)

        val allocatedOnly = fixture.store.allocate()

        val consumedOpk = fixture.store.allocate()
        fixture.store.markOffered(consumedOpk.keyId)
        fixture.store.consume(consumedOpk.keyId)

        time.advanceTo(30_200L)
        val pruned = fixture.store.pruneExpiredOffers(cutoffEpochSeconds = 30_001L)

        assertEquals(emptyList(), pruned)
        assertEquals(OpkStatus.ALLOCATED, fixture.rowStatus(allocatedOnly.keyId))
        assertEquals(OpkStatus.CONSUMED, fixture.rowStatus(consumedOpk.keyId))
    }

    @Test
    fun loadOffered_returnsKeyWhileOffered_notAfterConsume() = runTest {
        val fixture = openStore(nowEpochSeconds = 5_000L)

        val opk = fixture.store.allocate()
        fixture.store.markOffered(opk.keyId)

        assertContentEquals(opk.publicKey, fixture.store.loadOffered(opk.keyId)!!.publicKey)
        fixture.store.consume(opk.keyId)
        assertEquals(null, fixture.store.loadOffered(opk.keyId))
    }

    private fun openStore(
        nowEpochSeconds: Long = 0L,
        timeProvider: EpochSecondsProvider = MutableEpochSecondsProvider(nowEpochSeconds),
    ): StoreFixture {
        connection = openMemoryDatabase()
        val database = connection!!.database
        seedLocalAccountAndDevice(database, FixtureAccountId, FixtureDevicePeerId)
        val keyStore = InMemoryKeyStore()
        val store = DefaultOpkRepository(
            database = database,
            keyStore = keyStore,
            crypto = KmpCryptoProvider(),
            localDeviceId = FixtureDevicePeerId,
            timeProvider = timeProvider,
        )
        return StoreFixture(database, keyStore, store)
    }

    private class StoreFixture(
        val database: YapYapDatabase,
        val keyStore: InMemoryKeyStore,
        val store: DefaultOpkRepository,
    ) {
        fun opkPrivateKeyRef(opkId: String): KeyReference =
            KeyReference(keyId = opkId, purpose = IdentityKeyPurpose.ENCRYPTION, type = KeyType.PRIVATE)

        fun rowStatus(opkId: String): OpkStatus =
            database.identityQueries.selectOneTimePreKeyById(opkId).executeAsOne().status
    }

    private class MutableEpochSecondsProvider(private var epochSeconds: Long) : EpochSecondsProvider {
        fun advanceTo(epochSeconds: Long) {
            this.epochSeconds = epochSeconds
        }

        override fun nowEpochSeconds(): Long = epochSeconds
    }
}
