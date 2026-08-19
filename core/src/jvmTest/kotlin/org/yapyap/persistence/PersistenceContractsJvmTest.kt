package org.yapyap.persistence

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.*
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.persistence.db.*
import org.yapyap.persistence.key.DefaultIdentityKeyRepository
import org.yapyap.persistence.key.InMemoryKeyStore
import org.yapyap.persistence.packet.DefaultPacketDeduplicator
import org.yapyap.persistence.packet.DefaultPacketOutbox
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.time.FixedEpochProvider
import kotlin.test.*
import kotlin.uuid.Uuid

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
    fun packetDeduplicator_firstSeen_thenDuplicate_thenPruneRestoresFirstSeen() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)

        val dedup = DefaultPacketDeduplicator(db)
        val packetId = Uuid.random()
        val source = FixtureDevicePeerId

        assertTrue(dedup.firstSeen(packetId, source, receivedAtEpochSeconds = 10L))
        assertTrue(!dedup.firstSeen(packetId, source, receivedAtEpochSeconds = 11L))

        dedup.prune(receivedBeforeEpochSeconds = 15L)
        assertTrue(dedup.firstSeen(packetId, source, receivedAtEpochSeconds = 20L))
    }

    @Test
    fun packetDeduplicator_markNacked_thenGetNackReason_roundTrip() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)

        val dedup = DefaultPacketDeduplicator(db)
        val packetId = Uuid.random()
        val source = FixtureDevicePeerId

        assertTrue(dedup.firstSeen(packetId, source, receivedAtEpochSeconds = 10L))
        assertEquals(null, dedup.getNackReason(packetId, source))

        dedup.markNacked(packetId, source, PacketNackReason.DECODE_FAILED)
        assertEquals(PacketNackReason.DECODE_FAILED, dedup.getNackReason(packetId, source))
        assertTrue(!dedup.firstSeen(packetId, source, receivedAtEpochSeconds = 11L))
    }

    @Test
    fun identityPublicKeyRepository_insertLocal_then_get_resolve_tor_and_peers() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val repo = DefaultIdentityKeyRepository(db)

        val accountId = AccountId("repo-account-1")
        val deviceA = PeerId("repodeviceaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val deviceB = PeerId("repodevicebbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

        val accountRecord = AccountIdentityRecord(
            accountId = accountId,
            displayName = "Repo Account",
            key = IdentityPublicKeyRecord(
                keyId = "acc-k",
                keyVersion = 1L,
                purpose = IdentityKeyPurpose.SIGNING,
                publicKey = byteArrayOf(0x11),
            ),
        )
        repo.insertLocalAccount(identity = accountRecord)

        val devA = DeviceIdentityRecord(
            deviceId = deviceA,
            signing = IdentityPublicKeyRecord("s-a", 0L, IdentityKeyPurpose.SIGNING, byteArrayOf(0x21)),
            encryption = IdentityPublicKeyRecord("e-a", 0L, IdentityKeyPurpose.ENCRYPTION, byteArrayOf(0x31)),
        )
        repo.insertLocalDevice(accountId = accountId, identity = devA,)

        assertEquals(accountRecord.accountId.id, repo.getAccountRecord(accountId)!!.accountId.id)
        assertEquals(deviceA.id, repo.getDeviceRecord(deviceA)!!.deviceId.id)

        assertContentEquals(byteArrayOf(0x21), repo.resolveDeviceKey(deviceA, IdentityKeyPurpose.SIGNING)!!.publicKey)
        assertContentEquals(byteArrayOf(0x31), repo.resolveDeviceKey(deviceA, IdentityKeyPurpose.ENCRYPTION)!!.publicKey)

        val torBefore = requireNotNull(repo.resolveTorEndpointForDevice(deviceA))
        assertTrue(torBefore.onionAddress.endsWith(".onion"))

        repo.upsertPeerTorEndpoint(deviceA, FixtureTorEndpoint)
        val torAfter = requireNotNull(repo.resolveTorEndpointForDevice(deviceA))
        assertEquals(FixtureTorEndpoint.onionAddress, torAfter.onionAddress)
        assertEquals(FixtureTorEndpoint.port, torAfter.port)

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
    fun identityPublicKeyRepository_insertPeerAccount_and_lookup() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val repo = DefaultIdentityKeyRepository(db)

        val accountId = AccountId("peer-acc-99")
        val record = AccountIdentityRecord(
            accountId = accountId,
            displayName = "Peer Account",
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

        val loaded = repo.getAccountRecord(accountId)
        assertEquals(accountId.id, loaded!!.accountId.id)
        assertContentEquals(byteArrayOf(0x55), loaded.key!!.publicKey)
    }

    @Test
    fun identityPublicKeyRepository_getDevicePublicKey_roundTripsKeySignature() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val repo = DefaultIdentityKeyRepository(db)
        val store = InMemoryKeyStore()
        val crypto = DefaultCryptoProvider()
        val config = IdentityKeyServiceConfig(
            defaultOnionAddress = "keysig-test.onion",
            defaultOnionPort = 443L,
        )
        val timeProvider = FixedEpochProvider(0L)
        val resolver = DefaultIdentityResolver(crypto, repo, store, config)
        val provisioning = DefaultIdentityProvisioning(crypto, repo, store, config, resolver, timeProvider)

        provisioning.createNewAccountIdentity(displayName = "KeySig User")
        val device = provisioning.createNewDeviceIdentity()

        val loaded = repo.getDeviceRecord(device.deviceId)
        assertNotNull(loaded?.keySignature)
        assertContentEquals(device.keySignature, loaded.keySignature)

        val resolved = resolver.resolvePeerIdentityRecord(device.deviceId)
        assertNotNull(resolved)
        assertContentEquals(device.keySignature, resolved.keySignature)
    }

    @Test
    fun identityPublicKeyRepository_signedPreKey_roundTripsViaTable() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val repo = DefaultIdentityKeyRepository(db)
        val store = InMemoryKeyStore()
        val crypto = DefaultCryptoProvider()
        val config = IdentityKeyServiceConfig(
            defaultOnionAddress = "spk-test.onion",
            defaultOnionPort = 443L,
        )
        val timeProvider = FixedEpochProvider(0L)
        val resolver = DefaultIdentityResolver(crypto, repo, store, config)
        val provisioning = DefaultIdentityProvisioning(crypto, repo, store, config, resolver, timeProvider)

        provisioning.createNewAccountIdentity(displayName = "SPK User")
        val device = provisioning.createNewDeviceIdentity()
        val spkId = device.signedPreKey!!.keyId

        val stored = repo.getSignedPreKey(spkId)!!
        assertNotNull(stored)
        assertEquals(device.deviceId, stored.deviceId)
        assertContentEquals(device.signedPreKey.publicKey, stored.publicKey)

        val active = repo.getActiveSignedPreKeyForDevice(device.deviceId)
        assertNotNull(active)
        assertEquals(spkId, active.keyId)

        val localSpk = resolver.resolveLocalSignedPreKey(spkId)
        assertEquals(spkId, localSpk.keyId)
        assertContentEquals(device.signedPreKey.publicKey, localSpk.publicKey)
    }

    @Test
    fun packetOutbox_listDue_dropsCorruptRow_andKeepsValidRows() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)

        val outbox = DefaultPacketOutbox(db)
        val validPacketId = Uuid.random()
        val corruptPacketId = Uuid.random()
        val now = 1_000L

        outbox.enqueue(
            envelope = sampleOutboxEnvelope(validPacketId, FixtureDevicePeerId, now = now),
            nextRetryAt = now,
        )
        outbox.enqueue(
            envelope = sampleOutboxEnvelope(corruptPacketId, FixtureDevicePeerId, now = now),
            nextRetryAt = now,
        )

        corruptOutboxBlob(connection!!.driver, corruptPacketId)

        val due = outbox.listDue(now)
        assertEquals(1, due.size)
        assertEquals(validPacketId, due.single().packetId)
        assertEquals(1, outbox.listAllForTarget(FixtureDevicePeerId).size)
    }

    @Test
    fun packetOutbox_enqueue_listDue_markDelivered_roundTrip() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)

        val outbox = DefaultPacketOutbox(db)
        val packetId = Uuid.random()
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
    fun packetOutbox_recordAttempt_reschedulesRow() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)

        val outbox = DefaultPacketOutbox(db)
        val packetId = Uuid.random()
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
    fun packetOutbox_pruneExpired_removesExpiredRowsOnly() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)

        val outbox = DefaultPacketOutbox(db)
        val now = 3_000L
        val expiredId = Uuid.random()
        val validId = Uuid.random()

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
    fun packetOutbox_setDueForTarget_acceleratesOnlyFutureRetries() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)

        val outbox = DefaultPacketOutbox(db)
        val now = 4_000L
        val dueId = Uuid.random()
        val futureId = Uuid.random()

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
    fun packetOutbox_earliestPendingRetryAt_returnsMinimum() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)

        val outbox = DefaultPacketOutbox(db)
        val now = 5_000L

        outbox.enqueue(
            envelope = sampleOutboxEnvelope(
                Uuid.random(),
                FixtureDevicePeerId,
                now = now,
            ),
            nextRetryAt = now + 200,
        )
        outbox.enqueue(
            envelope = sampleOutboxEnvelope(
                Uuid.random(),
                FixtureDevicePeerId,
                now = now,
            ),
            nextRetryAt = now + 100,
        )

        assertEquals(now + 100, outbox.earliestPendingRetryAt())
    }

    @Test
    fun packetOutbox_relayCacheBytes_countsRelayOnly() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)

        val outbox = DefaultPacketOutbox(db)
        val now = 6_000L
        val relayPayload = ByteArray(400) { 0x01 }
        val localPayload = ByteArray(800) { 0x02 }

        outbox.enqueue(
            envelope = sampleOutboxEnvelope(
                packetId = Uuid.random(),
                target = FixtureDevicePeerId,
                now = now,
                payload = relayPayload,
            ),
            nextRetryAt = now + 60,
            relayMessage = true,
        )
        outbox.enqueue(
            envelope = sampleOutboxEnvelope(
                packetId = Uuid.random(),
                target = FixtureDevicePeerId,
                now = now,
                payload = localPayload,
            ),
            nextRetryAt = now + 60,
            relayMessage = false,
        )

        val relayEnvelopeSize = sampleOutboxEnvelope(
            packetId = Uuid.random(),
            target = FixtureDevicePeerId,
            now = now,
            payload = relayPayload,
        ).encode().size.toLong()

        assertEquals(relayEnvelopeSize, outbox.relayCacheBytes())
    }

    @Test
    fun packetOutbox_pruneRelayOverCapacity_evictsRelayRowsAndKeepsLocalRows() = runTest {
        connection = openMemoryDatabase()
        val db = connection!!.database
        seedLocalAccountAndDevice(db, FixtureAccountId, FixtureDevicePeerId)

        val outbox = DefaultPacketOutbox(db)
        val now = 7_000L
        val relayPayload = ByteArray(500) { 0x01 }
        val localPayload = ByteArray(500) { 0x02 }

        val relaySoon = Uuid.random()
        val relayLater = Uuid.random()
        val localId = Uuid.random()

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
