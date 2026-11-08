package org.yapyap.orchestrator.identity


import kotlinx.coroutines.test.runTest
import org.yapyap.orchestrator.store.DefaultRoomRepository
import org.yapyap.persistence.DatabaseConnection
import org.yapyap.persistence.openMemoryDatabase
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.AccountRole
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.RoomMemberRole
import org.yapyap.protocol.RoomType
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.identity.AccountIdentity
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SigningPublicKey
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private val device = DeviceIdentity(
        deviceId = localDevice,
        signingKey = SigningPublicKey(byteArrayOf(1, 2)),
        encryptionKey = EncryptionPublicKey(byteArrayOf(3, 4)),
    )

    /** What AddDeviceToExistingAccount setup writes, plus the real account as the fold commits it. */
    private suspend fun seed(): Pair<DefaultIdentityStore, DefaultRoomRepository> {
        connection = openMemoryDatabase()
        val db = connection!!.database
        val identity = DefaultIdentityStore(db, DeviceType.DESKTOP)
        val rooms = DefaultRoomRepository(db)
        identity.insertLocalAccount(AccountIdentity(standIn, "placeholder"))
        identity.insertLocalDevice(standIn, device)
        rooms.ensureRoomExists(RoomId.GLOBAL, RoomType.GLOBAL_CONTROL, "global")
        rooms.upsertMember(RoomId.GLOBAL, standIn, RoomMemberRole.MEMBER)
        identity.upsertChainAccount(
            realAccount,
            SigningPublicKey(byteArrayOf(9)),
            AccountRole.OWNER,
            IdentityStatus.ACTIVE,
            "alice"
        )
        return identity to rooms
    }

    private suspend fun DefaultIdentityStore.confirmLocalDevice() = upsertChainDevice(
        deviceId = localDevice,
        accountId = realAccount,
        deviceType = DeviceType.DESKTOP,
        torEndpoint = TorEndpoint("self.onion", 80),
        signingPublicKey = device.signingKey,
        encryptionPublicKey = device.encryptionKey,
        status = IdentityStatus.ACTIVE,
    )

    @Test
    fun confirmingTheLocalDevice_makesItsAccountLocal_andDropsTheStandIn() = runTest {
        val (identity, rooms) = seed()

        identity.confirmLocalDevice()

        val local = assertNotNull(identity.getLocalAccountIdentity())
        assertEquals(realAccount, local.accountId)
        assertEquals("alice", local.displayName)
        assertTrue(identity.isLocalAccountOwner(), "the chain role comes with it")
        assertNull(identity.getAccountIdentity(standIn), "nothing references the stand-in any more")
        assertNull(rooms.memberRowOf(RoomId.GLOBAL, standIn))
        assertNotNull(rooms.memberRowOf(RoomId.GLOBAL, realAccount), "membership carried over")
        assertEquals(1, identity.getAllAccounts().count { it.isLocal })
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
            signingPublicKey = SigningPublicKey(byteArrayOf(5)),
            encryptionPublicKey = EncryptionPublicKey(byteArrayOf(6)),
            status = IdentityStatus.ACTIVE,
        )

        assertEquals(standIn, identity.getLocalAccountIdentity()?.accountId)
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
        assertEquals(standIn, identity.getLocalAccountIdentity()?.accountId)

        assertEquals(standIn, identity.rebindLocalAccountToLocalDevice())
        assertEquals(realAccount, identity.getLocalAccountIdentity()?.accountId)
        assertNull(identity.rebindLocalAccountToLocalDevice(), "idempotent")
    }

    @Test
    fun repair_leavesAProvisionalDeviceAlone() = runTest {
        val (identity, _) = seed()

        assertNull(identity.rebindLocalAccountToLocalDevice())
        assertEquals(standIn, identity.getLocalAccountIdentity()?.accountId)
    }
}
