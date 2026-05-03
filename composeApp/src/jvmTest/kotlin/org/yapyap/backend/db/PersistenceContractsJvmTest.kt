package org.yapyap.backend.db

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.yapyap.backend.crypto.AccountId
import org.yapyap.backend.crypto.AccountIdentityRecord
import org.yapyap.backend.crypto.DeviceIdentityRecord
import org.yapyap.backend.crypto.IdentityKeyPurpose
import org.yapyap.backend.crypto.IdentityPublicKeyRecord
import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint

class PersistenceContractsJvmTest {

    private var connection: DatabaseConnection? = null

    @AfterTest
    fun closeDb() {
        connection?.driver?.close()
        connection = null
    }

    @Test
    fun databaseFactory_createConnection_initializesSchema_andForeignKeysEnabled() {
        connection = openMemoryDatabase()
        val v = readPragmaUserVersion(connection!!.driver)
        assertTrue(v > 0L, "expected PRAGMA user_version after schema create, got $v")
        assertTrue(readPragmaForeignKeys(connection!!.driver), "foreign_keys should be ON")
    }

    @Test
    fun packetDeduplicator_firstSeen_thenDuplicate_thenPruneRestoresFirstSeen() {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)

        val dedup = DefaultPacketDeduplicator(db)
        val packetId = PacketId.fromHex("aa".repeat(PacketId.SIZE_BYTES))
        val source = FixtureDevicePeerId

        assertTrue(dedup.firstSeen(packetId, source, receivedAtEpochSeconds = 10L))
        assertTrue(!dedup.firstSeen(packetId, source, receivedAtEpochSeconds = 11L))

        dedup.prune(receivedBeforeEpochSeconds = 15L)
        assertTrue(dedup.firstSeen(packetId, source, receivedAtEpochSeconds = 20L))
    }

    @Test
    fun packetIdAllocator_assignLocalDevice_then_allocate_areUnique() {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)

        val allocator = DefaultPacketIdAllocator(
            database = db,
            random = Random(12345),
            maxAttempts = 64,
        )
        assertFailsWith<IllegalArgumentException>(
            message = "allocate() before assignLocalDevice should fail",
        ) {
            allocator.allocate()
        }

        allocator.assignLocalDevice(FixtureDevicePeerId)

        val ids = List(12) { allocator.allocate() }
        val distinct = ids.map { it.toHex() }.toSet()
        assertEquals(ids.size, distinct.size, "allocated PacketIds must be unique (random IDs, not ordered)")
    }

    @Test
    fun identityPublicKeyRepository_insertLocal_then_get_resolve_tor_and_peers() {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val repo = DefaultIdentityPublicKeyRepository(db)

        val accountId = AccountId("repo-account-1")
        val deviceA = PeerId("repodeviceaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val deviceB = PeerId("repodevicebbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

        val accountRecord = AccountIdentityRecord(
            accountId = accountId,
            key = IdentityPublicKeyRecord(
                keyId = "acc-k",
                keyVersion = 1L,
                purpose = IdentityKeyPurpose.SIGNING,
                publicKey = byteArrayOf(0x11),
            ),
        )
        repo.insertLocalAccount(displayName = "Repo User", identity = accountRecord)

        val devA = DeviceIdentityRecord(
            deviceId = deviceA,
            signing = IdentityPublicKeyRecord("s-a", 0L, IdentityKeyPurpose.SIGNING, byteArrayOf(0x21)),
            encryption = IdentityPublicKeyRecord("e-a", 0L, IdentityKeyPurpose.ENCRYPTION, byteArrayOf(0x31)),
        )
        repo.insertLocalDevice(accountId = accountId, identity = devA)

        assertEquals(accountRecord.accountId.id, repo.getAccountPublicKey(accountId)!!.accountId.id)
        assertEquals(deviceA.id, repo.getDevicePublicKey(deviceA)!!.deviceId.id)

        assertContentEquals(byteArrayOf(0x21), repo.resolveDeviceKey(deviceA, IdentityKeyPurpose.SIGNING)!!.publicKey)
        assertContentEquals(byteArrayOf(0x31), repo.resolveDeviceKey(deviceA, IdentityKeyPurpose.ENCRYPTION)!!.publicKey)

        val torBefore = repo.resolveTorEndpointForDevice(deviceA)
        assertTrue(torBefore.onionAddress.endsWith(".onion"))

        repo.upsertPeerTorEndpoint(deviceA, FixtureTorEndpoint)
        assertEquals(FixtureTorEndpoint.onionAddress, repo.resolveTorEndpointForDevice(deviceA).onionAddress)
        assertEquals(FixtureTorEndpoint.port, repo.resolveTorEndpointForDevice(deviceA).port)

        val devB = DeviceIdentityRecord(
            deviceId = deviceB,
            signing = IdentityPublicKeyRecord("s-b", 0L, IdentityKeyPurpose.SIGNING, byteArrayOf(0x41)),
            encryption = IdentityPublicKeyRecord("e-b", 0L, IdentityKeyPurpose.ENCRYPTION, byteArrayOf(0x51)),
        )
        repo.insertPeerDevice(
            accountId = accountId,
            deviceType = DeviceType.DESKTOP,
            identity = devB,
            torEndpoint = TorEndpoint(onionAddress = "peerb.onion", port = 80),
        )

        val peers = repo.getAllPeerDevicesForAccount(accountId).toSet()
        assertEquals(setOf(deviceA, deviceB), peers)
    }

    @Test
    fun identityPublicKeyRepository_insertPeerAccount_and_lookup() {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val repo = DefaultIdentityPublicKeyRepository(db)

        val accountId = AccountId("peer-acc-99")
        val record = AccountIdentityRecord(
            accountId = accountId,
            key = IdentityPublicKeyRecord(
                keyId = "pk-99",
                keyVersion = 2L,
                purpose = IdentityKeyPurpose.SIGNING,
                publicKey = byteArrayOf(0x55),
            ),
        )
        repo.insertPeerAccount(
            identity = record,
            admin = true,
            status = AccountStatus.ACTIVE,
            displayName = "Peer Account",
        )

        val loaded = repo.getAccountPublicKey(accountId)
        assertEquals(accountId.id, loaded!!.accountId.id)
        assertContentEquals(byteArrayOf(0x55), loaded.key.publicKey)
    }
}
