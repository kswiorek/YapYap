package org.yapyap.persistence.key

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.keys.KeySlot
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.store.DefaultOpkRepository
import org.yapyap.crypto.store.OpkStatus
import org.yapyap.persistence.YapYapDatabase
import org.yapyap.persistence.db.*
import org.yapyap.protocol.identity.OneTimePreKeyId
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import kotlin.test.*
import kotlin.time.Clock

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

        val row = fixture.database.cryptoQueries.selectOneTimePreKeyById(opk.id).executeAsOneOrNull()
        assertNotNull(row)
        assertEquals(FixtureDevicePeerId, row.device_id)
        assertEquals(OpkStatus.ALLOCATED, row.status)
        assertEquals(epochSeconds(1_000L), row.created_at_epoch_seconds)
        assertNull(row.offered_at_epoch_seconds)
        assertEquals(opk.publicKey, row.public_key)
        assertContentEquals(opk.privateKey.bytes, fixture.keyStore.getKey(fixture.opkPrivateKeyRef(opk.id)))
    }

    @Test
    fun markOffered_thenConsume_onceOnly() = runTest {
        val fixture = openStore(nowEpochSeconds = 2_000L)

        val opk = fixture.store.allocate()
        fixture.store.markOffered(opk.id)

        val rowAfterOffer = fixture.database.cryptoQueries.selectOneTimePreKeyById(opk.id).executeAsOne()
        assertEquals(OpkStatus.OFFERED, rowAfterOffer.status)
        assertEquals(epochSeconds(2_000L), rowAfterOffer.offered_at_epoch_seconds)

        val consumed = fixture.store.consume(opk.id)
        assertNotNull(consumed)
        assertEquals(opk.publicKey, consumed.publicKey)
        assertEquals(opk.privateKey, consumed.privateKey)

        val rowAfterConsume = fixture.database.cryptoQueries.selectOneTimePreKeyById(opk.id).executeAsOne()
        assertEquals(OpkStatus.CONSUMED, rowAfterConsume.status)
        assertEquals(null, fixture.store.consume(opk.id))
    }

    @Test
    fun consume_requiresOfferedStatus() = runTest {
        val fixture = openStore(nowEpochSeconds = 3_000L)

        val opk = fixture.store.allocate()

        assertEquals(null, fixture.store.consume(opk.id))

        val row = fixture.database.cryptoQueries.selectOneTimePreKeyById(opk.id).executeAsOne()
        assertEquals(OpkStatus.ALLOCATED, row.status)
    }

    @Test
    fun consume_unknownOpkId_returnsNull() = runTest {
        val fixture = openStore(nowEpochSeconds = 4_000L)
        assertEquals(null, fixture.store.consume(OneTimePreKeyId("missing-opk")))
    }

    @Test
    fun pruneExpiredOffers_deletesRowAndPrivateKey() = runTest {
        val time = FakeClock(epochSeconds(10_000L))
        val fixture = openStore(clock = time)

        val opk = fixture.store.allocate()
        fixture.store.markOffered(opk.id)

        time.advanceTo(epochSeconds(10_120L))
        val pruned = fixture.store.pruneExpiredOffers(cutoff = epochSeconds(10_061L))

        assertEquals(listOf(opk.id), pruned)
        assertNull(fixture.database.cryptoQueries.selectOneTimePreKeyById(opk.id).executeAsOneOrNull())
        assertNull(fixture.keyStore.getKey(fixture.opkPrivateKeyRef(opk.id)))
    }

    @Test
    fun pruneExpiredOffers_keepsFreshOffers() = runTest {
        val time = FakeClock(epochSeconds(20_000L))
        val fixture = openStore(clock = time)

        val opk = fixture.store.allocate()
        fixture.store.markOffered(opk.id)

        val pruned = fixture.store.pruneExpiredOffers(cutoff = epochSeconds(20_000L))

        assertEquals(emptyList(), pruned)
        assertNotNull(fixture.database.cryptoQueries.selectOneTimePreKeyById(opk.id).executeAsOneOrNull())
        assertNotNull(fixture.keyStore.getKey(fixture.opkPrivateKeyRef(opk.id)))
    }

    @Test
    fun pruneExpiredOffers_ignoresAllocatedAndConsumed() = runTest {
        val time = FakeClock(epochSeconds(30_000L))
        val fixture = openStore(clock = time)

        val allocatedOnly = fixture.store.allocate()

        val consumedOpk = fixture.store.allocate()
        fixture.store.markOffered(consumedOpk.id)
        fixture.store.consume(consumedOpk.id)

        time.advanceTo(epochSeconds(30_200L))
        val pruned = fixture.store.pruneExpiredOffers(cutoff = epochSeconds(30_001L))

        assertEquals(emptyList(), pruned)
        assertEquals(OpkStatus.ALLOCATED, fixture.rowStatus(allocatedOnly.id))
        assertEquals(OpkStatus.CONSUMED, fixture.rowStatus(consumedOpk.id))
    }

    @Test
    fun loadOffered_returnsKeyWhileOffered_notAfterConsume() = runTest {
        val fixture = openStore(nowEpochSeconds = 5_000L)

        val opk = fixture.store.allocate()
        fixture.store.markOffered(opk.id)

        assertEquals(opk.publicKey, fixture.store.loadOffered(opk.id)!!.publicKey)
        fixture.store.consume(opk.id)
        assertEquals(null, fixture.store.loadOffered(opk.id))
    }

    private suspend fun openStore(
        nowEpochSeconds: Long = 0L,
        clock: Clock = FakeClock(epochSeconds(nowEpochSeconds)),
    ): StoreFixture {
        connection = openMemoryDatabase()
        val database = connection!!.database
        seedLocalAccountAndDevice(database, FixtureAccountId, FixtureDevicePeerId)
        val keyStore = InMemoryKeyStore()
        val store = DefaultOpkRepository(
            database = database,
            keyStore = keyStore,
            crypto = DefaultCryptoProvider(),
            localDeviceId = FixtureDevicePeerId,
            clock = clock,
        )
        return StoreFixture(database, keyStore, store)
    }

    private class StoreFixture(
        val database: YapYapDatabase,
        val keyStore: InMemoryKeyStore,
        val store: DefaultOpkRepository,
    ) {
        fun opkPrivateKeyRef(opkId: OneTimePreKeyId): KeySlot = KeySlot.OneTimePreKeyPrivate(opkId)

        fun rowStatus(opkId: OneTimePreKeyId): OpkStatus =
            database.cryptoQueries.selectOneTimePreKeyById(opkId).executeAsOne().status
    }
}
