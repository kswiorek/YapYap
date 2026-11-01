package org.yapyap.persistence.key

import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.identity.AccountIdentityRecord
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityPublicKeyRecord
import org.yapyap.persistence.db.DatabaseConnection
import org.yapyap.persistence.db.openMemoryDatabase
import org.yapyap.persistence.messaging.DefaultRoomRepository
import org.yapyap.protocol.*
import kotlin.test.*

/**
 * A device added to an existing account starts on a key-less stand-in local account; the
 * fold confirming the device under the real account must make that account the local one
 * (real SQLite, so the FKs between accounts, devices and room members are enforced).
 */
class LocalAccountRebindJvmTest {

    private var connection: DatabaseConnection? = null

    @AfterTest
    fun closeDb() {
        connection?.driver?.close()
        connection = null
    }

    private val standIn = AccountId("placeholder")
    private val realAccount = AccountId("real-account")
    private val localDevice = PeerId("d".repeat(64))

    private val device = DeviceIdentityRecord(
        deviceId = localDevice,
        signing = IdentityPublicKeyRecord(IdentityKeyPurpose.SIGNING, byteArrayOf(1, 2)),
        encryption = IdentityPublicKeyRecord(IdentityKeyPurpose.ENCRYPTION, byteArrayOf(3, 4)),
    )

    /** What AddDeviceToExistingAccount setup writes, plus the real account as the fold commits it. */
    private suspend fun seed(): Pair<DefaultIdentityKeyRepository, DefaultRoomRepository> {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val identity = DefaultIdentityKeyRepository(db, DeviceType.DESKTOP)
        val rooms = DefaultRoomRepository(db)
        identity.insertLocalAccount(AccountIdentityRecord(standIn, "placeholder"))
        identity.insertLocalDevice(standIn, device)
        rooms.ensureRoomExists(RoomId.GLOBAL, RoomType.GLOBAL_CONTROL, "global")
        rooms.upsertMember(RoomId.GLOBAL, standIn, RoomMemberRole.MEMBER)
        identity.upsertChainAccount(realAccount, byteArrayOf(9), AccountRole.OWNER, IdentityStatus.ACTIVE, "alice")
        return identity to rooms
    }

    private suspend fun DefaultIdentityKeyRepository.confirmLocalDevice() = upsertChainDevice(
        deviceId = localDevice,
        accountId = realAccount,
        deviceType = DeviceType.DESKTOP,
        torEndpoint = TorEndpoint("self.onion", 80),
        signingPublicKey = device.signing.publicKey,
        encryptionPublicKey = device.encryption.publicKey,
        status = IdentityStatus.ACTIVE,
    )

    @Test
    fun confirmingTheLocalDevice_makesItsAccountLocal_andDropsTheStandIn() = runTest {
        val (identity, rooms) = seed()

        identity.confirmLocalDevice()

        val local = assertNotNull(identity.getLocalAccountRecord())
        assertEquals(realAccount, local.accountId)
        assertEquals("alice", local.displayName)
        assertTrue(identity.isLocalAccountOwner(), "the chain role comes with it")
        assertNull(identity.getAccountRecord(standIn), "nothing references the stand-in any more")
        assertNull(rooms.memberRowOf(RoomId.GLOBAL, standIn))
        assertNotNull(rooms.memberRowOf(RoomId.GLOBAL, realAccount), "membership carried over")
        assertEquals(1, identity.getAllAccountRows().count { it.isLocal })
    }

    @Test
    fun confirmingAPeerDevice_leavesTheLocalAccountAlone() = runTest {
        val (identity, _) = seed()
        val peer = PeerId("e".repeat(64))

        identity.upsertChainDevice(
            deviceId = peer,
            accountId = realAccount,
            deviceType = DeviceType.DESKTOP,
            torEndpoint = TorEndpoint("peer.onion", 80),
            signingPublicKey = byteArrayOf(5),
            encryptionPublicKey = byteArrayOf(6),
            status = IdentityStatus.ACTIVE,
        )

        assertEquals(standIn, identity.getLocalAccountRecord()?.accountId)
    }

    @Test
    fun repair_rebindsAStoreWrittenWithoutTheRebind() = runTest {
        val (identity, _) = seed()
        identity.confirmLocalDevice()
        // Roll the store back to what older builds left behind: stand-in still local.
        val db = connection!!.database
        db.identityQueries.putAccount(
            standIn,
            null,
            true,
            AccountRole.MEMBER,
            IdentityStatus.ACTIVE,
            "placeholder",
            true
        )
        db.identityQueries.setLocalAccount(standIn)
        assertEquals(standIn, identity.getLocalAccountRecord()?.accountId)

        assertEquals(standIn, identity.rebindLocalAccountToLocalDevice())
        assertEquals(realAccount, identity.getLocalAccountRecord()?.accountId)
        assertNull(identity.rebindLocalAccountToLocalDevice(), "idempotent")
    }

    @Test
    fun repair_leavesAProvisionalDeviceAlone() = runTest {
        val (identity, _) = seed()

        assertNull(identity.rebindLocalAccountToLocalDevice())
        assertEquals(standIn, identity.getLocalAccountRecord()?.accountId)
    }
}
