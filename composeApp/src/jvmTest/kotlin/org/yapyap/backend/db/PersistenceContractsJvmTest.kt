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
import org.yapyap.backend.protocol.PacketNackReason
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint
import kotlin.test.assertNull

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
    fun packetDeduplicator_markNacked_thenGetNackReason_roundTrip() {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)

        val dedup = DefaultPacketDeduplicator(db)
        val packetId = PacketId.fromHex("bb".repeat(PacketId.SIZE_BYTES))
        val source = FixtureDevicePeerId

        assertTrue(dedup.firstSeen(packetId, source, receivedAtEpochSeconds = 10L))
        assertEquals(null, dedup.getNackReason(packetId, source))

        dedup.markNacked(packetId, source, PacketNackReason.DECODE_FAILED)
        assertEquals(PacketNackReason.DECODE_FAILED, dedup.getNackReason(packetId, source))
        assertTrue(!dedup.firstSeen(packetId, source, receivedAtEpochSeconds = 11L))
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
            allocator.allocate(10_000L)
        }

        allocator.assignLocalDevice(FixtureDevicePeerId)

        val ids = List(12) { allocator.allocate(10_000L) }
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

    @Test
    fun packetOutbox_listDue_dropsCorruptRow_andKeepsValidRows() {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)

        val outbox = DefaultPacketOutbox(db)
        val validPacketId = PacketId.fromHex("cc".repeat(PacketId.SIZE_BYTES))
        val corruptPacketId = PacketId.fromHex("dd".repeat(PacketId.SIZE_BYTES))
        val now = 1_000L

        outbox.enqueue(
            envelope = sampleOutboxEnvelope(validPacketId, FixtureDevicePeerId, now = now),
            nextRetryAt = now,
        )
        outbox.enqueue(
            envelope = sampleOutboxEnvelope(corruptPacketId, FixtureDevicePeerId, now = now),
            nextRetryAt = now,
        )

        corruptOutboxBlob(connection!!.driver, corruptPacketId.toHex())

        val due = outbox.listDue(now)
        assertEquals(1, due.size)
        assertEquals(validPacketId, due.single().packetId)
        assertEquals(1, outbox.listAllForTarget(FixtureDevicePeerId).size)
    }

    @Test
    fun packetOutbox_enqueue_listDue_markDelivered_roundTrip() {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)

        val outbox = DefaultPacketOutbox(db)
        val packetId = PacketId.fromHex("ee".repeat(PacketId.SIZE_BYTES))
        val now = 1_000L

        outbox.enqueue(
            envelope = sampleOutboxEnvelope(packetId, FixtureDevicePeerId, now = now),
            nextRetryAt = now + 100,
        )
        assertTrue(outbox.listDue(now).isEmpty())
        assertEquals(now + 100, outbox.earliestPendingRetryAt())

        val due = outbox.listDue(now + 100)
        assertEquals(1, due.size)
        assertEquals(packetId, due.single().packetId)

        outbox.markDelivered(packetId)
        assertTrue(outbox.listDue(now + 100).isEmpty())
        assertNull(outbox.earliestPendingRetryAt())
    }

    @Test
    fun packetOutbox_recordAttempt_reschedulesRow() {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)

        val outbox = DefaultPacketOutbox(db)
        val packetId = PacketId.fromHex("ff".repeat(PacketId.SIZE_BYTES))
        val now = 2_000L

        outbox.enqueue(
            envelope = sampleOutboxEnvelope(packetId, FixtureDevicePeerId, now = now),
            nextRetryAt = now,
        )
        outbox.recordAttempt(packetId, nextRetryAt = now + 60, now = now)

        assertTrue(outbox.listDue(now).isEmpty())
        assertEquals(now + 60, outbox.earliestPendingRetryAt())
        assertEquals(1L, outbox.listDue(now + 60).single().attempts)
    }

    @Test
    fun packetOutbox_pruneExpired_removesExpiredRowsOnly() {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)

        val outbox = DefaultPacketOutbox(db)
        val now = 3_000L
        val expiredId = PacketId.fromHex("11".repeat(PacketId.SIZE_BYTES))
        val validId = PacketId.fromHex("22".repeat(PacketId.SIZE_BYTES))

        outbox.enqueue(
            envelope = sampleOutboxEnvelope(
                packetId = expiredId,
                target = FixtureDevicePeerId,
                now = now - 200,
                expiresAt = now - 1,
            ),
            nextRetryAt = now,
        )
        outbox.enqueue(
            envelope = sampleOutboxEnvelope(
                packetId = validId,
                target = FixtureDevicePeerId,
                now = now,
                expiresAt = now + 100,
            ),
            nextRetryAt = now,
        )

        assertEquals(1, outbox.pruneExpired(now))
        assertEquals(validId, outbox.listDue(now).single().packetId)
    }

    @Test
    fun packetOutbox_setDueForTarget_acceleratesOnlyFutureRetries() {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)

        val outbox = DefaultPacketOutbox(db)
        val now = 4_000L
        val dueId = PacketId.fromHex("33".repeat(PacketId.SIZE_BYTES))
        val futureId = PacketId.fromHex("44".repeat(PacketId.SIZE_BYTES))

        outbox.enqueue(
            envelope = sampleOutboxEnvelope(dueId, FixtureDevicePeerId, now = now),
            nextRetryAt = now,
        )
        outbox.enqueue(
            envelope = sampleOutboxEnvelope(futureId, FixtureDevicePeerId, now = now),
            nextRetryAt = now + 120,
        )

        outbox.setDueForTarget(FixtureDevicePeerId, now)

        val due = outbox.listDue(now).map { it.packetId }.toSet()
        assertEquals(setOf(dueId, futureId), due)
    }

    @Test
    fun packetOutbox_earliestPendingRetryAt_returnsMinimum() {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)

        val outbox = DefaultPacketOutbox(db)
        val now = 5_000L

        outbox.enqueue(
            envelope = sampleOutboxEnvelope(
                PacketId.fromHex("55".repeat(PacketId.SIZE_BYTES)),
                FixtureDevicePeerId,
                now = now,
            ),
            nextRetryAt = now + 200,
        )
        outbox.enqueue(
            envelope = sampleOutboxEnvelope(
                PacketId.fromHex("66".repeat(PacketId.SIZE_BYTES)),
                FixtureDevicePeerId,
                now = now,
            ),
            nextRetryAt = now + 100,
        )

        assertEquals(now + 100, outbox.earliestPendingRetryAt())
    }

    @Test
    fun packetOutbox_relayCacheBytes_countsRelayOnly() {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)

        val outbox = DefaultPacketOutbox(db)
        val now = 6_000L
        val relayPayload = ByteArray(400) { 0x01 }
        val localPayload = ByteArray(800) { 0x02 }

        outbox.enqueue(
            envelope = sampleOutboxEnvelope(
                packetId = PacketId.fromHex("77".repeat(PacketId.SIZE_BYTES)),
                target = FixtureDevicePeerId,
                now = now,
                payload = relayPayload,
            ),
            nextRetryAt = now + 60,
            relayMessage = true,
        )
        outbox.enqueue(
            envelope = sampleOutboxEnvelope(
                packetId = PacketId.fromHex("88".repeat(PacketId.SIZE_BYTES)),
                target = FixtureDevicePeerId,
                now = now,
                payload = localPayload,
            ),
            nextRetryAt = now + 60,
            relayMessage = false,
        )

        val relayEnvelopeSize = sampleOutboxEnvelope(
            packetId = PacketId.fromHex("77".repeat(PacketId.SIZE_BYTES)),
            target = FixtureDevicePeerId,
            now = now,
            payload = relayPayload,
        ).encode().size.toLong()

        assertEquals(relayEnvelopeSize, outbox.relayCacheBytes())
    }

    @Test
    fun packetOutbox_pruneRelayOverCapacity_evictsRelayRowsAndKeepsLocalRows() {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)

        val outbox = DefaultPacketOutbox(db)
        val now = 7_000L
        val relayPayload = ByteArray(500) { 0x01 }
        val localPayload = ByteArray(500) { 0x02 }

        val relaySoon = PacketId.fromHex("99".repeat(PacketId.SIZE_BYTES))
        val relayLater = PacketId.fromHex("ab".repeat(PacketId.SIZE_BYTES))
        val localId = PacketId.fromHex("cd".repeat(PacketId.SIZE_BYTES))

        outbox.enqueue(
            envelope = sampleOutboxEnvelope(
                packetId = relaySoon,
                target = FixtureDevicePeerId,
                now = now,
                expiresAt = now + 100,
                payload = relayPayload,
            ),
            nextRetryAt = now + 60,
            relayMessage = true,
        )
        outbox.enqueue(
            envelope = sampleOutboxEnvelope(
                packetId = relayLater,
                target = FixtureDevicePeerId,
                now = now,
                expiresAt = now + 200,
                payload = relayPayload,
            ),
            nextRetryAt = now + 60,
            relayMessage = true,
        )
        outbox.enqueue(
            envelope = sampleOutboxEnvelope(
                packetId = localId,
                target = FixtureDevicePeerId,
                now = now,
                payload = localPayload,
            ),
            nextRetryAt = now + 60,
            relayMessage = false,
        )

        val relayBlobSize = sampleOutboxEnvelope(
            packetId = relaySoon,
            target = FixtureDevicePeerId,
            now = now,
            expiresAt = now + 100,
            payload = relayPayload,
        ).encode().size.toLong()

        val evicted = outbox.pruneRelayOverCapacity(relayBlobSize-1)
        assertTrue(evicted >= 1)
        assertTrue(outbox.relayCacheBytes() <= relayBlobSize)
        assertEquals(1, outbox.listAllForTarget(FixtureDevicePeerId).size)
        assertEquals(localId, outbox.listAllForTarget(FixtureDevicePeerId).single().packetId)
    }
}
