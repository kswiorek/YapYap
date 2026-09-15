package org.yapyap.orchestrator.globalevent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.yapyap.crypto.e2ee.session.X3dhRemotePeerKeys
import org.yapyap.crypto.identity.*
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.signature.SignatureProvider
import org.yapyap.orchestrator.dag.DefaultDagEngine
import org.yapyap.orchestrator.dag.IngestResult
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.persistence.db.DeviceType
import org.yapyap.persistence.db.IdentityStatus
import org.yapyap.persistence.db.VerificationState
import org.yapyap.persistence.key.InMemoryIdentityKeyRepository
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.*
import org.yapyap.routing.router.*
import org.yapyap.sync.FakeInboundMessagePipeline
import org.yapyap.testfixtures.*
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

private class TestAccount(
    val accountId: AccountId,
    val signingPublic: ByteArray,
    val signingPrivate: ByteArray,
    val displayName: String,
)

private class TestDevice(
    val deviceId: PeerId,
    val signingPublic: ByteArray,
    val signingPrivate: ByteArray,
    val encryptionPublic: ByteArray,
    val account: TestAccount,
    val torEndpoint: TorEndpoint,
    val deviceType: DeviceType = DeviceType.DESKTOP,
)

private suspend fun newAccount(crypto: CryptoProvider, displayName: String): TestAccount {
    val keys = crypto.generateSigningKeyPair()
    return TestAccount(
        accountId = crypto.accountIdFromPublicKey(keys.publicKey),
        signingPublic = keys.publicKey,
        signingPrivate = keys.privateKey,
        displayName = displayName,
    )
}

private suspend fun newDevice(crypto: CryptoProvider, account: TestAccount, onion: String): TestDevice {
    val signing = crypto.generateSigningKeyPair()
    val encryption = crypto.generateEncryptionKeyPair()
    return TestDevice(
        deviceId = crypto.peerIdFromPublicKey(signing.publicKey),
        signingPublic = signing.publicKey,
        signingPrivate = signing.privateKey,
        encryptionPublic = encryption.publicKey,
        account = account,
        torEndpoint = TorEndpoint("$onion.onion"),
    )
}

private fun toAccountRecord(account: TestAccount): AccountIdentityRecord =
    AccountIdentityRecord(
        accountId = account.accountId,
        displayName = account.displayName,
        key = IdentityPublicKeyRecord("test-acct", 0, IdentityKeyPurpose.SIGNING, account.signingPublic),
    )

private fun toDeviceRecord(device: TestDevice): DeviceIdentityRecord =
    DeviceIdentityRecord(
        deviceId = device.deviceId,
        signing = IdentityPublicKeyRecord("test-sign", 0, IdentityKeyPurpose.SIGNING, device.signingPublic),
        encryption = IdentityPublicKeyRecord("test-enc", 0, IdentityKeyPurpose.ENCRYPTION, device.encryptionPublic),
    )

private suspend fun bindingSig(crypto: CryptoProvider, account: TestAccount, device: TestDevice): ByteArray =
    crypto.signDetached(
        account.signingPrivate,
        accountSignedDeviceBindingBytes(
            accountId = account.accountId,
            deviceId = device.deviceId,
            signingPublicKey = device.signingPublic,
            encryptionPublicKey = device.encryptionPublic,
            torEndpoint = device.torEndpoint,
            deviceType = device.deviceType,
        ),
    )

private suspend fun globalNode(
    crypto: CryptoProvider,
    author: TestDevice,
    prevIds: List<Uuid>,
    createdAt: Instant,
    event: GlobalEventPayload,
    signWith: ByteArray = author.signingPrivate,
): MessagePayload.GlobalEvent {
    val unsigned = MessagePayload.GlobalEvent(
        messageId = Uuid.random(),
        senderAccountId = author.account.accountId,
        authorDeviceId = author.deviceId,
        prevIds = prevIds,
        createdAt = createdAt,
        eventBytes = event.encode(),
    )
    return unsigned.withSignature(crypto.signDetached(signWith, unsigned.encodeForAuthorSigning()))
}

private fun addAccountEvent(account: TestAccount): GlobalEventPayload.AddAccount =
    GlobalEventPayload.AddAccount(account.accountId, account.signingPublic, account.displayName)

private fun addDeviceBranch1(account: TestAccount, device: TestDevice): GlobalEventPayload.AddDevice =
    GlobalEventPayload.AddDevice(
        accountId = account.accountId,
        deviceId = device.deviceId,
        signingPublicKey = device.signingPublic,
        encryptionPublicKey = device.encryptionPublic,
        torEndpoint = device.torEndpoint,
        deviceType = device.deviceType,
        keySignature = null,
    )

private suspend fun addDeviceKeyed(
    crypto: CryptoProvider,
    account: TestAccount,
    device: TestDevice,
): GlobalEventPayload.AddDevice =
    GlobalEventPayload.AddDevice(
        accountId = account.accountId,
        deviceId = device.deviceId,
        signingPublicKey = device.signingPublic,
        encryptionPublicKey = device.encryptionPublic,
        torEndpoint = device.torEndpoint,
        deviceType = device.deviceType,
        keySignature = bindingSig(crypto, account, device),
    )

private fun inviteFor(device: TestDevice, account: TestAccount? = null, keySig: ByteArray? = null): Invite =
    Invite(
        account = account?.let { toAccountRecord(it) },
        device = toDeviceRecord(device),
        deviceType = device.deviceType,
        torEndpoint = device.torEndpoint,
        sharedSecret = byteArrayOf(0x01),
        accountKeySignature = keySig,
    )

private class KeyedSignatureProvider(
    private val crypto: CryptoProvider,
) : SignatureProvider {
    lateinit var privateKey: ByteArray
    override suspend fun sign(message: ByteArray): ByteArray = crypto.signDetached(privateKey, message)
    override suspend fun verify(deviceId: PeerId, message: ByteArray, signature: ByteArray): Boolean =
        error("not used")

    override suspend fun verifyMessageAuthorship(
        accountId: AccountId,
        authorDeviceId: PeerId,
        signedBytes: ByteArray,
        signature: ByteArray,
    ): Boolean = error("not used")
}

private class TestIdentities : IdentityResolver {
    lateinit var account: TestAccount
    lateinit var device: TestDevice
    var admin: Boolean = false

    override suspend fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord = toDeviceRecord(device)
    override suspend fun getLocalAccountIdentityRecord(): AccountIdentityRecord = toAccountRecord(account)
    override suspend fun isLocalAccountAdmin(): Boolean = admin
    override suspend fun getLocalDevicePrivateKey(purpose: IdentityKeyPurpose): ByteArray = error("not used")
    override suspend fun getLocalAccountPrivateKey(purpose: IdentityKeyPurpose): ByteArray = error("not used")
    override suspend fun getLocalDeviceId(): PeerId = device.deviceId
    override suspend fun getLocalAccountId(): AccountId = account.accountId
    override suspend fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord = error("not used")
    override suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint = error("not used")
    override suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> = error("not used")
    override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? = error("not used")
    override suspend fun updatePeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) = error("not used")
    override suspend fun resolvePeerX3dhRemoteKeys(deviceId: PeerId, signedPreKeyId: String?): X3dhRemotePeerKeys =
        error("not used")

    override suspend fun getCurrentLocalSignedPreKey(): org.yapyap.crypto.identity.SignedPreKeyRecord =
        error("not used")

    override suspend fun resolveLocalSignedPreKey(signedPreKeyId: String): org.yapyap.crypto.identity.SignedPreKeyRecord =
        error("not used")

    override suspend fun getAllPeers(): List<PeerId> = error("not used")
}

private class RecordingProjectorRouter : Router {
    val sent = mutableListOf<Pair<AccountId, MessagePayload>>()
    override val incomingMessages: Flow<MessagePayload> = emptyFlow()
    override val typingIndicators: Flow<TypingIndicatorEvent> = emptyFlow()
    override val pingPayloads: Flow<List<Pair<RoomId, List<Uuid>>>> = emptyFlow()
    override val bootstrapPackets: Flow<BootstrapPacketEvent> = emptyFlow()
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override fun isRunning(): Boolean = true
    override suspend fun announceOnline() = Unit
    override suspend fun sendMessage(
        target: AccountId,
        payload: MessagePayload,
        forceTransport: RouterTransport?,
    ): SendMessageResult {
        sent.add(target to payload)
        return SendMessageResult(SendMessageStatus.SUCCESS, 0, 0, null)
    }

    override suspend fun sendTypingIndicator(
        targets: Collection<AccountId>,
        roomId: RoomId,
        interval: Duration,
    ) = Unit

    override suspend fun sendBootstrap(
        payload: BootstrapPayload,
        target: PeerId,
        targetEndpoint: TorEndpoint?,
        sharedSecret: ByteArray?,
    ) = Unit
}

private class Harness(
    val crypto: CryptoProvider,
    val account: TestAccount,
    val oldPhone: TestDevice,
) {
    val messageRepo = FakeMessageRepository()
    val roomRepo = FakeRoomRepository()
    val identityRepo = InMemoryIdentityKeyRepository()
    val pipeline = FakeInboundMessagePipeline()
    val router = RecordingProjectorRouter()
    val clock = FakeClock(epochSeconds(9_000L))
    val seen = mutableListOf<IdentityStateChange>()

    val identities = TestIdentities()
    private val signer = KeyedSignatureProvider(crypto)
    private val engine = DefaultDagEngine(
        messageRepository = messageRepo,
        causalHoldRepository = FakeCausalHoldRepository(messageRepo),
        roomRepository = roomRepo,
        identityResolver = identities,
        signatureProvider = signer,
        clock = clock,
    )
    val projector = DefaultGlobalEventProjector(
        dagEngine = engine,
        pipeline = pipeline,
        messageRepository = messageRepo,
        identityKeyRepository = identityRepo,
        identityResolver = identities,
        roomRepository = roomRepo,
        router = router,
        cryptoProvider = crypto,
    )

    init {
        become(oldPhone, admin = false)
    }

    fun become(device: TestDevice, admin: Boolean) {
        identities.account = device.account
        identities.device = device
        identities.admin = admin
        signer.privateKey = device.signingPrivate
    }

    /**
     * Starts the projector jobs (boot fold, ingest collector) and the `seen` collector on
     * `backgroundScope` — infinite collectors must live there, otherwise `runTest` fails
     * with `UncompletedCoroutinesError` at teardown.
     *
     * Drive discipline: the tests advance with `runCurrent()`, never `advanceUntilIdle()`.
     * `advanceUntilIdle` stops once only background work remains (it never drives
     * `backgroundScope` tasks), which would leave the boot fold, the ingest-collector folds
     * and the `seen` collector unscheduled: stored nodes would stay PENDING and early
     * emissions would be lost. `runCurrent` drains everything queued (foreground and
     * background, including cascades) without advancing virtual time — and no path under
     * test uses real delays, so nothing else is needed.
     */
    fun startIn(scope: CoroutineScope) {
        projector.start(scope)
        scope.launch { projector.stateChanges.collect { seen.add(it) } }
    }

    fun tickTo(seconds: Long) {
        clock.advanceTo(epochSeconds(seconds))
    }

    suspend fun genesis() {
        tickTo(10_000L)
        projector.publishGenesisAccount(
            account = toAccountRecord(account),
            device = toDeviceRecord(oldPhone),
            deviceType = oldPhone.deviceType,
            torEndpoint = oldPhone.torEndpoint,
            accountKeySignature = bindingSig(crypto, account, oldPhone),
        )
    }

    suspend fun store(node: MessagePayload, orphaned: Boolean = false) {
        messageRepo.insert(
            payload = node,
            isOrphaned = orphaned,
            ancestryComplete = !orphaned,
            verificationState = VerificationState.PENDING,
        )
        for (parent in node.prevIds) messageRepo.insertParent(node.messageId, parent)
        pipeline.emit(IngestResult.Inserted(node))
    }

    suspend fun frontier(): List<Uuid> =
        messageRepo.findRoomFrontier(RoomId.GLOBAL).map { it.payload.messageId }

    suspend fun rootId(): Uuid =
        messageRepo.findAllInRoom(RoomId.GLOBAL)
            .first { it.payload.prevIds.isEmpty() }.payload.messageId

    suspend fun verdictOf(messageId: Uuid): VerificationState? =
        messageRepo.findById(messageId)?.verificationState
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DefaultGlobalEventProjectorTest {

    private suspend fun newHarness(): Harness {
        val crypto = DefaultCryptoProvider()
        val account = newAccount(crypto, "genesis-user")
        val oldPhone = newDevice(crypto, account, "old-phone")
        return Harness(crypto, account, oldPhone)
    }

    @Test
    fun genesis_publish_bootstraps_network() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        runCurrent()

        h.genesis()
        runCurrent()

        assertEquals(IdentityStatus.ACTIVE, h.identityRepo.getAccountStatus(h.account.accountId))
        assertTrue(h.identityRepo.isAccountAdmin(h.account.accountId))
        assertEquals(IdentityStatus.ACTIVE, h.identityRepo.getDeviceStatus(h.oldPhone.deviceId))
        assertEquals(listOf(h.account.accountId), h.roomRepo.membersOfRoom(RoomId.GLOBAL))
        assertContains(h.seen, IdentityStateChange.AccountAdded(h.account.accountId))
        assertContains(h.seen, IdentityStateChange.AdminGranted(h.account.accountId))
        assertContains(h.seen, IdentityStateChange.DeviceAdded(h.account.accountId, h.oldPhone.deviceId))
        for (row in h.messageRepo.findAllInRoom(RoomId.GLOBAL)) {
            assertEquals(VerificationState.VERIFIED, row.verificationState)
        }
    }

    @Test
    fun sponsored_new_account_with_grant() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()
        h.become(h.oldPhone, admin = true)

        val newcomerAccount = newAccount(h.crypto, "newcomer")
        val newcomer = newDevice(h.crypto, newcomerAccount, "newcomer-device")
        h.tickTo(11_000L)
        h.projector.publishSponsoredNewAccount(
            inviteFor(newcomer, newcomerAccount, bindingSig(h.crypto, newcomerAccount, newcomer)),
            grantAdmin = true,
        )
        runCurrent()

        assertEquals(IdentityStatus.ACTIVE, h.identityRepo.getAccountStatus(newcomerAccount.accountId))
        assertTrue(h.identityRepo.isAccountAdmin(newcomerAccount.accountId))
        assertEquals(IdentityStatus.ACTIVE, h.identityRepo.getDeviceStatus(newcomer.deviceId))
        assertContains(h.seen, IdentityStateChange.AccountAdded(newcomerAccount.accountId))
        assertContains(h.seen, IdentityStateChange.AdminGranted(newcomerAccount.accountId))
        assertContains(
            h.seen,
            IdentityStateChange.DeviceAdded(newcomerAccount.accountId, newcomer.deviceId),
        )
    }

    @Test
    fun rotation_ban_keeps_synced_siblings() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()

        val pc = newDevice(h.crypto, h.account, "pc")
        h.tickTo(11_000L)
        h.projector.publishOwnAccountDevice(inviteFor(pc))
        val newPhone = newDevice(h.crypto, h.account, "new-phone")
        h.tickTo(12_000L)
        h.projector.publishOwnAccountDevice(inviteFor(newPhone))
        runCurrent()

        assertEquals(
            listOf(pc.deviceId, newPhone.deviceId).sortedBy { it.id },
            h.projector.activeDevicesAddedBy(h.oldPhone.deviceId),
        )

        h.become(newPhone, admin = true)
        h.tickTo(13_000L)
        h.projector.publishRemoveDevice(h.oldPhone.deviceId)
        runCurrent()

        assertEquals(IdentityStatus.BANNED, h.identityRepo.getDeviceStatus(h.oldPhone.deviceId))
        assertEquals(IdentityStatus.ACTIVE, h.identityRepo.getDeviceStatus(pc.deviceId))
        assertEquals(IdentityStatus.ACTIVE, h.identityRepo.getDeviceStatus(newPhone.deviceId))
        assertEquals(IdentityStatus.ACTIVE, h.identityRepo.getAccountStatus(h.account.accountId))
        assertContains(h.seen, IdentityStateChange.DeviceRemoved(h.oldPhone.deviceId))
        assertEquals(listOf(h.account.accountId), h.roomRepo.membersOfRoom(RoomId.GLOBAL))
        assertEquals(
            listOf(pc.deviceId, newPhone.deviceId).sortedBy { it.id },
            h.projector.activeDevicesAddedBy(h.oldPhone.deviceId),
        )
        for (row in h.messageRepo.findAllInRoom(RoomId.GLOBAL)) {
            assertEquals(VerificationState.VERIFIED, row.verificationState, "node ${row.payload.messageId}")
        }
    }

    @Test
    fun post_ban_backdated_add_is_cut() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()
        val root = h.rootId()

        h.become(h.oldPhone, admin = true)
        h.tickTo(13_000L)
        h.projector.publishRemoveDevice(h.oldPhone.deviceId)
        runCurrent()

        val evil = newDevice(h.crypto, h.account, "evil")
        val forged = globalNode(
            crypto = h.crypto,
            author = h.oldPhone,
            prevIds = listOf(root),
            createdAt = epochSeconds(10_500L),
            event = addDeviceBranch1(h.account, evil),
        )
        h.store(forged)
        runCurrent()

        // Cut adds are ignored (authentic, stored VERIFIED) and project as tombstones.
        assertEquals(VerificationState.VERIFIED, h.verdictOf(forged.messageId))
        assertNull(h.identityRepo.getDeviceRecord(evil.deviceId))
        assertTrue(h.seen.none { it is IdentityStateChange.DeviceAdded && it.deviceId == evil.deviceId })
    }

    @Test
    fun backdated_add_cascade_through_intermediate() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()
        val root = h.rootId()

        h.become(h.oldPhone, admin = true)
        h.tickTo(13_000L)
        h.projector.publishRemoveDevice(h.oldPhone.deviceId)
        runCurrent()

        val evil = newDevice(h.crypto, h.account, "evil")
        val evilAdd = globalNode(
            crypto = h.crypto,
            author = h.oldPhone,
            prevIds = listOf(root),
            createdAt = epochSeconds(10_500L),
            event = addDeviceBranch1(h.account, evil),
        )
        h.store(evilAdd)
        val puppet = newDevice(h.crypto, h.account, "puppet")
        val puppetAdd = globalNode(
            crypto = h.crypto,
            author = evil,
            prevIds = listOf(root),
            createdAt = epochSeconds(10_600L),
            event = addDeviceBranch1(h.account, puppet),
        )
        h.store(puppetAdd)
        runCurrent()

        // The cut puppet never enters shadow state, so its own add has an unresolvable
        // author — transitivity via PENDING, not via rejection.
        assertEquals(VerificationState.VERIFIED, h.verdictOf(evilAdd.messageId))
        assertEquals(VerificationState.PENDING, h.verdictOf(puppetAdd.messageId))
        assertNull(h.identityRepo.getDeviceRecord(evil.deviceId))
        assertNull(h.identityRepo.getDeviceRecord(puppet.deviceId))
    }

    @Test
    fun concurrent_with_ban_add_is_cut() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()

        h.become(h.oldPhone, admin = true)
        h.tickTo(13_000L)
        h.projector.publishRemoveDevice(h.oldPhone.deviceId)
        runCurrent()

        // Created after the ban but chained off the pre-ban frontier: unsynced at ban time.
        val late = newDevice(h.crypto, h.account, "late")
        val lateAdd = globalNode(
            crypto = h.crypto,
            author = h.oldPhone,
            prevIds = h.frontier(),
            createdAt = epochSeconds(13_500L),
            event = addDeviceBranch1(h.account, late),
        )
        h.store(lateAdd)
        runCurrent()

        assertEquals(VerificationState.VERIFIED, h.verdictOf(lateAdd.messageId))
        assertNull(h.identityRepo.getDeviceRecord(late.deviceId))
    }

    @Test
    fun self_removal_cuts_backdated_add() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()
        val root = h.rootId()

        val pc = newDevice(h.crypto, h.account, "pc")
        h.tickTo(11_000L)
        h.projector.publishOwnAccountDevice(inviteFor(pc))
        runCurrent()

        // Discarded-phone flow: the old phone signs itself out; a later thief backdates an add.
        h.tickTo(13_000L)
        h.projector.publishRemoveDevice(h.oldPhone.deviceId)
        runCurrent()

        val evil = newDevice(h.crypto, h.account, "evil")
        val forged = globalNode(
            crypto = h.crypto,
            author = h.oldPhone,
            prevIds = listOf(root),
            createdAt = epochSeconds(10_500L),
            event = addDeviceBranch1(h.account, evil),
        )
        h.store(forged)
        runCurrent()

        assertEquals(IdentityStatus.BANNED, h.identityRepo.getDeviceStatus(h.oldPhone.deviceId))
        assertEquals(VerificationState.VERIFIED, h.verdictOf(forged.messageId))
        assertNull(h.identityRepo.getDeviceRecord(evil.deviceId))
        assertEquals(IdentityStatus.ACTIVE, h.identityRepo.getDeviceStatus(pc.deviceId))
    }

    @Test
    fun duplicate_remove_first_in_canonical_order_defines_cut() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()
        val root = h.rootId()

        val pc = newDevice(h.crypto, h.account, "pc")
        h.tickTo(11_000L)
        h.projector.publishOwnAccountDevice(inviteFor(pc))
        val tablet = newDevice(h.crypto, h.account, "tablet")
        h.store(
            globalNode(
                crypto = h.crypto,
                author = pc,
                prevIds = h.frontier(),
                createdAt = epochSeconds(11_500L),
                event = addDeviceBranch1(h.account, tablet),
            ),
        )
        runCurrent()
        assertEquals(IdentityStatus.ACTIVE, h.identityRepo.getDeviceStatus(tablet.deviceId))

        // Minimal-ancestry ban sorts first: it defines the cut even though the tablet's add
        // sits inside the later full-frontier ban's ancestry.
        val ban1 = globalNode(
            crypto = h.crypto,
            author = h.oldPhone,
            prevIds = listOf(root),
            createdAt = epochSeconds(11_800L),
            event = GlobalEventPayload.RemoveDevice(pc.deviceId),
        )
        h.store(ban1)
        h.tickTo(12_000L)
        h.projector.publishRemoveDevice(pc.deviceId)
        runCurrent()

        assertEquals(IdentityStatus.BANNED, h.identityRepo.getDeviceStatus(pc.deviceId))
        assertEquals(VerificationState.VERIFIED, h.verdictOf(ban1.messageId))
        val tabletAddId = h.messageRepo.findAllInRoom(RoomId.GLOBAL)
            .first { row ->
                val event = (row.payload as? MessagePayload.GlobalEvent)?.decodeEvent()
                event is GlobalEventPayload.AddDevice && event.deviceId == tablet.deviceId
            }.payload.messageId
        assertEquals(VerificationState.VERIFIED, h.verdictOf(tabletAddId))
        // The tablet committed ACTIVE before the ban landed, then reversed into the tombstone.
        assertEquals(IdentityStatus.BANNED, h.identityRepo.getDeviceStatus(tablet.deviceId))
        assertContains(
            h.seen,
            IdentityStateChange.DeviceAdded(h.account.accountId, tablet.deviceId),
        )
        assertContains(h.seen, IdentityStateChange.DeviceRemoved(tablet.deviceId))
    }

    @Test
    fun parked_orphan_at_ban_time_is_cut_and_flips_on_closure() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()

        // The add references a parent the banner never synced: parked outside the frontier.
        val missingParent = Uuid.random()
        val parked = newDevice(h.crypto, h.account, "parked")
        val parkedAdd = globalNode(
            crypto = h.crypto,
            author = h.oldPhone,
            prevIds = listOf(missingParent),
            createdAt = epochSeconds(12_500L),
            event = addDeviceBranch1(h.account, parked),
        )
        h.store(parkedAdd, orphaned = true)

        h.become(h.oldPhone, admin = true)
        h.tickTo(13_000L)
        h.projector.publishRemoveDevice(h.oldPhone.deviceId)
        runCurrent()

        // Provisional while the gap is open: no tombstone, no retraction.
        assertEquals(VerificationState.PENDING, h.verdictOf(parkedAdd.messageId))
        assertNull(h.identityRepo.getDeviceStatus(parked.deviceId))

        // The gap closes onto a forged parent (wrong payload type in the control room):
        // the parent is REJECTED and poisons its structural descendants — the parked add
        // stays PENDING (never folds), with no tombstone and no DeviceAdded.
        val lateParent = MessagePayload.Text(
            messageId = missingParent,
            roomId = RoomId.GLOBAL,
            senderAccountId = h.account.accountId,
            authorDeviceId = h.oldPhone.deviceId,
            prevIds = listOf(h.rootId()),
            createdAt = epochSeconds(12_400L),
            text = "late parent",
        )
        h.store(lateParent)
        h.messageRepo.updateOrphanedFlag(parkedAdd.messageId, isOrphaned = false)
        h.messageRepo.updateAncestryComplete(parkedAdd.messageId, complete = true)
        runCurrent()

        assertEquals(VerificationState.REJECTED, h.verdictOf(lateParent.messageId))
        assertEquals(VerificationState.PENDING, h.verdictOf(parkedAdd.messageId))
        assertNull(h.identityRepo.getDeviceStatus(parked.deviceId))
        assertTrue(h.seen.none { it is IdentityStateChange.DeviceAdded && it.deviceId == parked.deviceId })
    }

    @Test
    fun cascade_ban_kills_survivor_second_remove_is_noop() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()

        // Puppet synced pre-ban: survives the ban (accepted residual), stays visible.
        val puppet = newDevice(h.crypto, h.account, "puppet")
        h.store(
            globalNode(
                crypto = h.crypto,
                author = h.oldPhone,
                prevIds = h.frontier(),
                createdAt = epochSeconds(11_000L),
                event = addDeviceBranch1(h.account, puppet),
            ),
        )
        val newPhone = newDevice(h.crypto, h.account, "new-phone")
        h.tickTo(12_000L)
        h.projector.publishOwnAccountDevice(inviteFor(newPhone))
        h.become(newPhone, admin = true)
        h.tickTo(13_000L)
        h.projector.publishRemoveDevice(h.oldPhone.deviceId)
        runCurrent()
        assertEquals(IdentityStatus.ACTIVE, h.identityRepo.getDeviceStatus(puppet.deviceId))
        assertContains(h.projector.activeDevicesAddedBy(h.oldPhone.deviceId), puppet.deviceId)

        // Cascade: killing the survivor requires its own RemoveDevice.
        h.tickTo(14_000L)
        h.projector.publishRemoveDevice(puppet.deviceId)
        runCurrent()
        assertEquals(IdentityStatus.BANNED, h.identityRepo.getDeviceStatus(puppet.deviceId))
        assertContains(h.seen, IdentityStateChange.DeviceRemoved(puppet.deviceId))

        // A second RemoveDevice for the already-banned phone changes nothing.
        val seenCount = h.seen.size
        h.tickTo(15_000L)
        h.projector.publishRemoveDevice(h.oldPhone.deviceId)
        runCurrent()
        assertEquals(IdentityStatus.BANNED, h.identityRepo.getDeviceStatus(h.oldPhone.deviceId))
        assertEquals(seenCount, h.seen.size)
    }

    @Test
    fun forged_root_is_denied_admin() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()

        // Second root for a fresh account: unreachable from the winning root, so it and
        // its private branch stay PENDING — never folded, never admin. Honest post-forgery
        // events chaining off the full frontier (both roots) still fold normally.
        val attackerAccount = newAccount(h.crypto, "attacker-net")
        val forgedRoot = globalNode(
            crypto = h.crypto,
            author = h.oldPhone,
            prevIds = emptyList(),
            createdAt = epochSeconds(10_500L),
            event = addAccountEvent(attackerAccount),
        )
        h.store(forgedRoot)
        runCurrent()

        assertEquals(VerificationState.PENDING, h.verdictOf(forgedRoot.messageId))
        assertNull(h.identityRepo.getAccountStatus(attackerAccount.accountId))
        assertFalse(h.identityRepo.isAccountAdmin(attackerAccount.accountId))
        assertTrue(h.identityRepo.isAccountAdmin(h.account.accountId))
    }

    @Test
    fun forged_same_account_root_is_preempted() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()

        // Same-account root with a forged earliest timestamp sorts first, but it is
        // unreachable from the winning root — generic PENDING, and the true root preempts
        // the duplicate regardless.
        val forgedRoot = globalNode(
            crypto = h.crypto,
            author = h.oldPhone,
            prevIds = emptyList(),
            createdAt = epochSeconds(1L),
            event = addAccountEvent(h.account),
        )
        h.store(forgedRoot)
        runCurrent()

        assertEquals(VerificationState.PENDING, h.verdictOf(forgedRoot.messageId))
        assertTrue(h.identityRepo.isAccountAdmin(h.account.accountId))
        assertEquals("genesis-user", h.identityRepo.getAccountRecord(h.account.accountId)?.displayName)
    }

    @Test
    fun device_id_derivation_mismatch_is_rejected() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()

        // Attacker keys for a victim device_id: the derivation assertion kills the hijack.
        val attackerKeys = h.crypto.generateSigningKeyPair()
        val victimId = PeerId("victim-device-id")
        val hijack = globalNode(
            crypto = h.crypto,
            author = h.oldPhone,
            prevIds = h.frontier(),
            createdAt = epochSeconds(11_000L),
            event = GlobalEventPayload.AddDevice(
                accountId = h.account.accountId,
                deviceId = victimId,
                signingPublicKey = attackerKeys.publicKey,
                encryptionPublicKey = h.crypto.generateEncryptionKeyPair().publicKey,
                torEndpoint = TorEndpoint("hijack.onion"),
                deviceType = DeviceType.DESKTOP,
                keySignature = null,
            ),
        )
        h.store(hijack)
        runCurrent()

        assertEquals(VerificationState.REJECTED, h.verdictOf(hijack.messageId))
        assertNull(h.identityRepo.getDeviceRecord(victimId))
    }

    @Test
    fun non_admin_grant_is_ignored() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()

        val plainAccount = newAccount(h.crypto, "plain")
        val plainDevice = newDevice(h.crypto, plainAccount, "plain-device")
        h.become(h.oldPhone, admin = true)
        h.tickTo(11_000L)
        h.projector.publishSponsoredNewAccount(
            inviteFor(plainDevice, plainAccount, bindingSig(h.crypto, plainAccount, plainDevice)),
            grantAdmin = false,
        )
        runCurrent()

        val grant = globalNode(
            crypto = h.crypto,
            author = plainDevice,
            prevIds = h.frontier(),
            createdAt = epochSeconds(12_000L),
            event = GlobalEventPayload.GrantAdmin(h.account.accountId),
        )
        h.store(grant)
        runCurrent()

        assertEquals(VerificationState.VERIFIED, h.verdictOf(grant.messageId))
        assertFalse(h.identityRepo.isAccountAdmin(plainAccount.accountId))
    }

    @Test
    fun branch1_add_to_other_account_is_ignored() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()

        val otherAccount = newAccount(h.crypto, "other")
        val otherDevice = newDevice(h.crypto, otherAccount, "other-device")
        h.become(h.oldPhone, admin = true)
        h.tickTo(11_000L)
        h.projector.publishSponsoredNewAccount(
            inviteFor(otherDevice, otherAccount, bindingSig(h.crypto, otherAccount, otherDevice)),
            grantAdmin = false,
        )
        runCurrent()

        val intruder = newDevice(h.crypto, h.account, "intruder")
        val crossAdd = globalNode(
            crypto = h.crypto,
            author = h.oldPhone,
            prevIds = h.frontier(),
            createdAt = epochSeconds(12_000L),
            event = addDeviceBranch1(otherAccount, intruder),
        )
        h.store(crossAdd)
        runCurrent()

        assertEquals(VerificationState.VERIFIED, h.verdictOf(crossAdd.messageId))
        assertNull(h.identityRepo.getDeviceRecord(intruder.deviceId))
    }

    @Test
    fun tombstoned_author_post_removal_event_is_ignored() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()

        h.become(h.oldPhone, admin = true)
        h.tickTo(13_000L)
        h.projector.publishRemoveDevice(h.oldPhone.deviceId)
        runCurrent()

        val lateGrant = globalNode(
            crypto = h.crypto,
            author = h.oldPhone,
            prevIds = h.frontier(),
            createdAt = epochSeconds(13_500L),
            event = GlobalEventPayload.GrantAdmin(h.account.accountId),
        )
        h.store(lateGrant)
        runCurrent()

        assertEquals(VerificationState.VERIFIED, h.verdictOf(lateGrant.messageId))
    }

    @Test
    fun duplicate_device_and_account_ignored() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()

        val pc = newDevice(h.crypto, h.account, "pc")
        h.tickTo(11_000L)
        h.projector.publishOwnAccountDevice(inviteFor(pc))
        runCurrent()

        val dupDevice = globalNode(
            crypto = h.crypto,
            author = h.oldPhone,
            prevIds = h.frontier(),
            createdAt = epochSeconds(12_000L),
            event = addDeviceBranch1(h.account, pc),
        )
        h.store(dupDevice)
        val dupAccount = globalNode(
            crypto = h.crypto,
            author = h.oldPhone,
            prevIds = h.frontier(),
            createdAt = epochSeconds(12_100L),
            event = addAccountEvent(h.account),
        )
        h.store(dupAccount)
        runCurrent()

        assertEquals(VerificationState.VERIFIED, h.verdictOf(dupDevice.messageId))
        assertEquals(VerificationState.VERIFIED, h.verdictOf(dupAccount.messageId))
        assertEquals(IdentityStatus.ACTIVE, h.identityRepo.getDeviceStatus(pc.deviceId))
    }

    @Test
    fun account_key_add_for_banned_account_is_ignored() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()

        val doomedAccount = newAccount(h.crypto, "doomed")
        val doomedDevice = newDevice(h.crypto, doomedAccount, "doomed-device")
        h.become(h.oldPhone, admin = true)
        h.tickTo(11_000L)
        h.projector.publishSponsoredNewAccount(
            inviteFor(doomedDevice, doomedAccount, bindingSig(h.crypto, doomedAccount, doomedDevice)),
            grantAdmin = false,
        )
        h.tickTo(13_000L)
        h.projector.publishRemoveAccount(doomedAccount.accountId)
        runCurrent()
        assertEquals(IdentityStatus.BANNED, h.identityRepo.getAccountStatus(doomedAccount.accountId))
        assertEquals(IdentityStatus.BANNED, h.identityRepo.getDeviceStatus(doomedDevice.deviceId))

        // Leaked recovery key relayed backdated: rule 2 kills it at every position.
        val relayAdd = globalNode(
            crypto = h.crypto,
            author = h.oldPhone,
            prevIds = listOf(h.rootId()),
            createdAt = epochSeconds(12_000L),
            event = addDeviceKeyed(h.crypto, doomedAccount, newDevice(h.crypto, doomedAccount, "ghost")),
        )
        h.store(relayAdd)
        runCurrent()

        assertEquals(VerificationState.VERIFIED, h.verdictOf(relayAdd.messageId))
    }

    @Test
    fun remove_account_tombstones_devices_and_membership() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()

        val leavingAccount = newAccount(h.crypto, "leaving")
        val leavingDevice = newDevice(h.crypto, leavingAccount, "leaving-device")
        h.become(h.oldPhone, admin = true)
        h.tickTo(11_000L)
        h.projector.publishSponsoredNewAccount(
            inviteFor(leavingDevice, leavingAccount, bindingSig(h.crypto, leavingAccount, leavingDevice)),
            grantAdmin = false,
        )
        runCurrent()
        assertEquals(
            listOf(h.account.accountId, leavingAccount.accountId).sortedBy { it.id },
            h.roomRepo.membersOfRoom(RoomId.GLOBAL).sortedBy { it.id })

        h.tickTo(13_000L)
        h.projector.publishRemoveAccount(leavingAccount.accountId)
        runCurrent()

        assertEquals(IdentityStatus.BANNED, h.identityRepo.getAccountStatus(leavingAccount.accountId))
        assertEquals(IdentityStatus.BANNED, h.identityRepo.getDeviceStatus(leavingDevice.deviceId))
        assertEquals(listOf(h.account.accountId), h.roomRepo.membersOfRoom(RoomId.GLOBAL))
        assertContains(h.seen, IdentityStateChange.AccountRemoved(leavingAccount.accountId))
        assertContains(h.seen, IdentityStateChange.DeviceRemoved(leavingDevice.deviceId))
    }

    @Test
    fun concurrent_grant_revoke_siblings_resolve_deterministically() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()

        val subjectAccount = newAccount(h.crypto, "subject")
        val subjectDevice = newDevice(h.crypto, subjectAccount, "subject-device")
        h.become(h.oldPhone, admin = true)
        h.tickTo(11_000L)
        h.projector.publishSponsoredNewAccount(
            inviteFor(subjectDevice, subjectAccount, bindingSig(h.crypto, subjectAccount, subjectDevice)),
            grantAdmin = false,
        )
        runCurrent()

        val frontier = h.frontier()
        h.store(
            globalNode(
                crypto = h.crypto,
                author = h.oldPhone,
                prevIds = frontier,
                createdAt = epochSeconds(12_000L),
                event = GlobalEventPayload.GrantAdmin(subjectAccount.accountId),
            ),
        )
        // Fold the grant on its own: the assertions below require the grant to commit
        // (emitting AdminGranted) before the revoke lands — a single fold over both
        // siblings would net to zero with no intermediate emission.
        runCurrent()
        val revoke = globalNode(
            crypto = h.crypto,
            author = h.oldPhone,
            prevIds = frontier,
            createdAt = epochSeconds(12_100L),
            event = GlobalEventPayload.RemoveAdmin(subjectAccount.accountId),
        )
        h.store(revoke)
        runCurrent()

        assertFalse(h.identityRepo.isAccountAdmin(subjectAccount.accountId))
        assertContains(h.seen, IdentityStateChange.AdminGranted(subjectAccount.accountId))
        assertContains(h.seen, IdentityStateChange.AdminRevoked(subjectAccount.accountId))
    }

    @Test
    fun recovery_branch3_add_validates() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()

        // Recovery relay: any member transports the account-key authorization (§8.2).
        val recovered = newDevice(h.crypto, h.account, "recovered")
        h.store(
            globalNode(
                crypto = h.crypto,
                author = h.oldPhone,
                prevIds = h.frontier(),
                createdAt = epochSeconds(11_000L),
                event = addDeviceKeyed(h.crypto, h.account, recovered),
            ),
        )
        runCurrent()

        assertEquals(IdentityStatus.ACTIVE, h.identityRepo.getDeviceStatus(recovered.deviceId))
        assertContains(
            h.seen,
            IdentityStateChange.DeviceAdded(h.account.accountId, recovered.deviceId),
        )
    }

    @Test
    fun malformed_and_bad_signature_nodes_are_rejected() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()

        h.store(
            MessagePayload.Text(
                messageId = Uuid.random(),
                roomId = RoomId.GLOBAL,
                senderAccountId = h.account.accountId,
                authorDeviceId = h.oldPhone.deviceId,
                prevIds = h.frontier(),
                createdAt = epochSeconds(11_000L),
                text = "not a control event",
            ),
        )
        val pc = newDevice(h.crypto, h.account, "pc")
        val wrongKey = h.crypto.generateSigningKeyPair()
        val badSig = globalNode(
            crypto = h.crypto,
            author = h.oldPhone,
            prevIds = h.frontier(),
            createdAt = epochSeconds(11_100L),
            event = addDeviceBranch1(h.account, pc),
            signWith = wrongKey.privateKey,
        )
        h.store(badSig)
        runCurrent()

        for (row in h.messageRepo.findAllInRoom(RoomId.GLOBAL)) {
            if (row.payload is MessagePayload.Text) {
                assertEquals(VerificationState.REJECTED, row.verificationState)
            }
        }
        assertEquals(VerificationState.REJECTED, h.verdictOf(badSig.messageId))
        assertNull(h.identityRepo.getDeviceRecord(pc.deviceId))
    }

    @Test
    fun unknown_author_stays_pending_and_self_add_is_pending() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()

        val strangerXKeys = h.crypto.generateSigningKeyPair()
        val strangerX = TestDevice(
            deviceId = h.crypto.peerIdFromPublicKey(strangerXKeys.publicKey),
            signingPublic = strangerXKeys.publicKey,
            signingPrivate = strangerXKeys.privateKey,
            encryptionPublic = h.crypto.generateEncryptionKeyPair().publicKey,
            account = h.account,
            torEndpoint = TorEndpoint("stranger-x.onion"),
        )
        val strangerZKeys = h.crypto.generateSigningKeyPair()
        val strangerZ = TestDevice(
            deviceId = h.crypto.peerIdFromPublicKey(strangerZKeys.publicKey),
            signingPublic = strangerZKeys.publicKey,
            signingPrivate = strangerZKeys.privateKey,
            encryptionPublic = h.crypto.generateEncryptionKeyPair().publicKey,
            account = h.account,
            torEndpoint = TorEndpoint("stranger-z.onion"),
        )
        val otherFresh = newDevice(h.crypto, h.account, "other-fresh")
        // Never-introduced author with no self-introduction link → PENDING, never REJECTED.
        val orphan = globalNode(
            crypto = h.crypto,
            author = strangerX,
            prevIds = h.frontier(),
            createdAt = epochSeconds(11_000L),
            event = addDeviceBranch1(h.account, otherFresh),
        )
        h.store(orphan)
        // A fresh device authoring its own add past genesis: the only self-introduction
        // key is the genesis one, so the author is unresolvable → PENDING (unverifiable,
        // not provably forged). Unilateral onboarding stays closed either way.
        val selfAdd = globalNode(
            crypto = h.crypto,
            author = strangerZ,
            prevIds = h.frontier(),
            createdAt = epochSeconds(11_100L),
            event = addDeviceBranch1(h.account, strangerZ),
        )
        h.store(selfAdd)
        runCurrent()

        assertEquals(VerificationState.PENDING, h.verdictOf(orphan.messageId))
        assertNull(h.identityRepo.getDeviceRecord(otherFresh.deviceId))
        assertEquals(VerificationState.PENDING, h.verdictOf(selfAdd.messageId))
        assertNull(h.identityRepo.getDeviceRecord(strangerZ.deviceId))
    }

    @Test
    fun boot_on_empty_room_is_silent_noop() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        runCurrent()

        assertTrue(h.seen.isEmpty())
        assertTrue(h.roomRepo.membersOfRoom(RoomId.GLOBAL).isEmpty())
    }


    private suspend fun Harness.secondAdminAccount(): Pair<TestAccount, TestDevice> {
        val account = newAccount(crypto, "admin-b")
        val device = newDevice(crypto, account, "device-b")
        become(oldPhone, admin = true)
        tickTo(11_000L)
        projector.publishSponsoredNewAccount(
            inviteFor(device, account, bindingSig(crypto, account, device)),
            grantAdmin = true,
        )
        return account to device
    }

    @Test
    fun counter_ban_backdated_forgery_bans_both() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()
        val (_, deviceB) = h.secondAdminAccount()
        runCurrent()

        // True order: B bans the old phone at the frontier.
        h.become(deviceB, admin = true)
        h.tickTo(13_000L)
        h.projector.publishRemoveDevice(h.oldPhone.deviceId)
        runCurrent()

        // Forged counter-ban: positioned after device B exists (so the forgery is
        // authorization-plausible and genuinely threatens the honest ban) but forked off
        // the root (concurrent with it — outside its ancestry, as every post-hoc forgery
        // is). Sorts before the honest ban, voids nothing.
        val forged = globalNode(
            crypto = h.crypto,
            author = h.oldPhone,
            prevIds = listOf(h.rootId()),
            createdAt = epochSeconds(12_000L),
            event = GlobalEventPayload.RemoveDevice(deviceB.deviceId),
        )
        h.store(forged)
        runCurrent()

        // Mutual destruction: the forgery is valid at its position (A still admin), and so
        // is the real ban (B's adminship is independent of the forgery) — both banned.
        assertEquals(IdentityStatus.BANNED, h.identityRepo.getDeviceStatus(h.oldPhone.deviceId))
        assertEquals(IdentityStatus.BANNED, h.identityRepo.getDeviceStatus(deviceB.deviceId))
        assertEquals(VerificationState.VERIFIED, h.verdictOf(forged.messageId))
        assertContains(h.seen, IdentityStateChange.DeviceRemoved(h.oldPhone.deviceId))
        assertContains(h.seen, IdentityStateChange.DeviceRemoved(deviceB.deviceId))
    }

    @Test
    fun sequential_duel_first_mover_wins() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()
        val (_, deviceB) = h.secondAdminAccount()
        runCurrent()

        // First mover: the old phone bans device B at the frontier.
        h.become(h.oldPhone, admin = true)
        h.tickTo(13_000L)
        h.projector.publishRemoveDevice(deviceB.deviceId)
        runCurrent()

        // Retaliation chained off the frontier (sequential — the first ban is in its
        // ancestry): the author is tombstoned with no pair exemption → ignored.
        val retaliation = globalNode(
            crypto = h.crypto,
            author = deviceB,
            prevIds = h.frontier(),
            createdAt = epochSeconds(13_500L),
            event = GlobalEventPayload.RemoveDevice(h.oldPhone.deviceId),
        )
        h.store(retaliation)
        runCurrent()

        assertEquals(IdentityStatus.ACTIVE, h.identityRepo.getDeviceStatus(h.oldPhone.deviceId))
        assertEquals(IdentityStatus.BANNED, h.identityRepo.getDeviceStatus(deviceB.deviceId))
        assertEquals(VerificationState.VERIFIED, h.verdictOf(retaliation.messageId))
    }

    @Test
    fun non_admin_forged_ban_is_void() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()

        val plainAccount = newAccount(h.crypto, "plain")
        val plainDevice = newDevice(h.crypto, plainAccount, "plain-device")
        h.become(h.oldPhone, admin = true)
        h.tickTo(11_000L)
        h.projector.publishSponsoredNewAccount(
            inviteFor(plainDevice, plainAccount, bindingSig(h.crypto, plainAccount, plainDevice)),
            grantAdmin = false,
        )
        runCurrent()

        // No opposing ban exists, and the author is neither admin nor a sibling:
        // fails the rule on its own merits — forms no pair, drags nobody down.
        val forged = globalNode(
            crypto = h.crypto,
            author = plainDevice,
            prevIds = h.frontier(),
            createdAt = epochSeconds(12_000L),
            event = GlobalEventPayload.RemoveDevice(h.oldPhone.deviceId),
        )
        h.store(forged)
        runCurrent()

        assertEquals(VerificationState.VERIFIED, h.verdictOf(forged.messageId))
        assertEquals(IdentityStatus.ACTIVE, h.identityRepo.getDeviceStatus(h.oldPhone.deviceId))
    }

    @Test
    fun counter_demotion_backdated_forgery_demotes_both() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()
        val (accountB, deviceB) = h.secondAdminAccount()
        runCurrent()
        // Third admin C: the duel below runs between the two grant-derived admins
        // (genesis itself is irrevocable).
        val (accountC, deviceC) = h.secondAdminAccount()
        runCurrent()

        // True order: B demotes C at the frontier.
        h.become(deviceB, admin = true)
        h.tickTo(13_000L)
        h.projector.publishRemoveAdmin(accountC.accountId)
        runCurrent()
        assertFalse(h.identityRepo.isAccountAdmin(accountC.accountId))

        // Forged counter-demotion: positioned after account B exists (so the forgery is
        // authorization-plausible and genuinely threatens the honest demotion) but forked
        // off the root (concurrent with it — outside its ancestry, as every post-hoc
        // forgery is). Sorts before the honest demotion, voids nothing.
        val forged = globalNode(
            crypto = h.crypto,
            author = deviceC,
            prevIds = listOf(h.rootId()),
            createdAt = epochSeconds(12_000L),
            event = GlobalEventPayload.RemoveAdmin(accountB.accountId),
        )
        h.store(forged)
        runCurrent()

        // Mutual destruction at the admin level: both demoted, both verdicts VERIFIED.
        assertFalse(h.identityRepo.isAccountAdmin(accountC.accountId))
        assertFalse(h.identityRepo.isAccountAdmin(accountB.accountId))
        assertEquals(VerificationState.VERIFIED, h.verdictOf(forged.messageId))
        assertContains(h.seen, IdentityStateChange.AdminRevoked(accountB.accountId))
    }

    @Test
    fun ban_survives_backdated_self_serving_demotion() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()
        val (accountB, deviceB) = h.secondAdminAccount()
        runCurrent()
        // Third admin C: the banner (genesis itself is irrevocable, so the duel
        // runs between grant-derived admins).
        val (accountC, deviceC) = h.secondAdminAccount()
        runCurrent()

        // True order: C bans the rogue device at the frontier.
        h.become(deviceC, admin = true)
        h.tickTo(13_000L)
        h.projector.publishRemoveDevice(deviceB.deviceId)
        runCurrent()

        // The rogue counter-demotes C, backdated to sort before the ban. A ban
        // target can't demote its way out: self-serving demotions don't disqualify
        // the banner, and the carried ban then voids the demotion — a single ban
        // suffices, no demote → ban → re-grant round-trip.
        val forged = globalNode(
            crypto = h.crypto,
            author = deviceB,
            prevIds = listOf(h.rootId()),
            createdAt = epochSeconds(12_000L),
            event = GlobalEventPayload.RemoveAdmin(accountC.accountId),
        )
        h.store(forged)
        runCurrent()

        assertEquals(IdentityStatus.BANNED, h.identityRepo.getDeviceStatus(deviceB.deviceId))
        assertTrue(h.identityRepo.isAccountAdmin(accountC.accountId))
        assertEquals(VerificationState.VERIFIED, h.verdictOf(forged.messageId))
        assertContains(h.seen, IdentityStateChange.DeviceRemoved(deviceB.deviceId))
    }

    @Test
    fun demote_then_regrant_restores_admin() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()
        val (accountB, deviceB) = h.secondAdminAccount()
        runCurrent()

        val plainAccount = newAccount(h.crypto, "plain")
        val plainDevice = newDevice(h.crypto, plainAccount, "plain-device")
        h.become(h.oldPhone, admin = true)
        h.tickTo(12_000L)
        h.projector.publishSponsoredNewAccount(
            inviteFor(plainDevice, plainAccount, bindingSig(h.crypto, plainAccount, plainDevice)),
            grantAdmin = false,
        )
        // Third admin C: demotes B (genesis itself is irrevocable, so the suite
        // exercises demotion on a grant-derived admin).
        val accountC = newAccount(h.crypto, "admin-c")
        val deviceC = newDevice(h.crypto, accountC, "device-c")
        h.tickTo(12_500L)
        h.projector.publishSponsoredNewAccount(
            inviteFor(deviceC, accountC, bindingSig(h.crypto, accountC, deviceC)),
            grantAdmin = true,
        )
        runCurrent()

        h.become(deviceC, admin = true)
        h.tickTo(13_000L)
        h.projector.publishRemoveAdmin(accountB.accountId)
        runCurrent()
        assertFalse(h.identityRepo.isAccountAdmin(accountB.accountId))
        assertContains(h.seen, IdentityStateChange.AdminRevoked(accountB.accountId))

        // A surviving admin re-grants: a new interval opens, positional validity returns.
        h.tickTo(14_000L)
        h.projector.publishGrantAdmin(accountB.accountId)
        runCurrent()
        assertTrue(h.identityRepo.isAccountAdmin(accountB.accountId))

        // Post-re-grant admin acts by B are valid again.
        h.become(deviceB, admin = true)
        h.tickTo(15_000L)
        h.projector.publishGrantAdmin(plainAccount.accountId)
        runCurrent()
        assertTrue(h.identityRepo.isAccountAdmin(plainAccount.accountId))
        assertContains(h.seen, IdentityStateChange.AdminGranted(plainAccount.accountId))
        assertTrue(h.identityRepo.isAccountAdmin(accountC.accountId))
    }

    @Test
    fun genesis_demotion_and_removal_are_ignored() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()
        val (_, deviceB) = h.secondAdminAccount()
        runCurrent()

        h.become(deviceB, admin = true)
        h.tickTo(13_000L)
        h.projector.publishRemoveAdmin(h.account.accountId)
        runCurrent()
        assertTrue(h.identityRepo.isAccountAdmin(h.account.accountId))

        h.tickTo(14_000L)
        h.projector.publishRemoveAccount(h.account.accountId)
        runCurrent()
        assertEquals(IdentityStatus.ACTIVE, h.identityRepo.getAccountStatus(h.account.accountId))
        assertTrue(h.identityRepo.isAccountAdmin(h.account.accountId))
    }

    @Test
    fun backdated_grant_by_demoted_admin_is_void() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()
        val (accountB, deviceB) = h.secondAdminAccount()
        runCurrent()

        val plainAccount = newAccount(h.crypto, "plain")
        val plainDevice = newDevice(h.crypto, plainAccount, "plain-device")
        h.become(h.oldPhone, admin = true)
        h.tickTo(12_000L)
        h.projector.publishSponsoredNewAccount(
            inviteFor(plainDevice, plainAccount, bindingSig(h.crypto, plainAccount, plainDevice)),
            grantAdmin = false,
        )
        // Third admin C so the demotion below targets a grant-derived admin
        // (genesis itself is irrevocable).
        val accountC = newAccount(h.crypto, "admin-c2")
        val deviceC = newDevice(h.crypto, accountC, "device-c2")
        h.tickTo(12_500L)
        h.projector.publishSponsoredNewAccount(
            inviteFor(deviceC, accountC, bindingSig(h.crypto, accountC, deviceC)),
            grantAdmin = true,
        )
        runCurrent()

        // C demotes B at the frontier…
        h.become(deviceC, admin = true)
        h.tickTo(13_000L)
        h.projector.publishRemoveAdmin(accountB.accountId)
        runCurrent()
        assertFalse(h.identityRepo.isAccountAdmin(accountB.accountId))

        // …then B forges a grant positioned before the demotion (but after B's own
        // add, so the forgery is authorization-plausible): outside the demotion's
        // seal (the demoter never vouched for it) → ignored, stored VERIFIED.
        val forged = globalNode(
            crypto = h.crypto,
            author = deviceB,
            prevIds = listOf(h.rootId()),
            createdAt = epochSeconds(12_600L),
            event = GlobalEventPayload.GrantAdmin(plainAccount.accountId),
        )
        h.store(forged)
        runCurrent()

        assertEquals(VerificationState.VERIFIED, h.verdictOf(forged.messageId))
        assertFalse(h.identityRepo.isAccountAdmin(plainAccount.accountId))
    }

    @Test
    fun granter_ban_cycle_resolves_against_attacker() = runTest {
        val h = newHarness()
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()

        // Sibling device G on the genesis account.
        val siblingG = newDevice(h.crypto, h.account, "sibling-g")
        h.tickTo(11_000L)
        h.projector.publishOwnAccountDevice(inviteFor(siblingG))
        runCurrent()

        // Account X sponsored, then granted admin by G.
        val accountX = newAccount(h.crypto, "x")
        val deviceX = newDevice(h.crypto, accountX, "device-x")
        h.tickTo(11_500L)
        h.projector.publishSponsoredNewAccount(
            inviteFor(deviceX, accountX, bindingSig(h.crypto, accountX, deviceX)),
            grantAdmin = false,
        )
        h.become(siblingG, admin = true)
        h.tickTo(12_000L)
        h.projector.publishGrantAdmin(accountX.accountId)
        runCurrent()
        assertTrue(h.identityRepo.isAccountAdmin(accountX.accountId))

        // True order: X bans the old phone at the frontier.
        h.become(deviceX, admin = true)
        h.tickTo(13_000L)
        h.projector.publishRemoveDevice(h.oldPhone.deviceId)
        val banXId = h.messageRepo.findAllInRoom(RoomId.GLOBAL)
            .first { row ->
                val event = (row.payload as? MessagePayload.GlobalEvent)?.decodeEvent()
                event is GlobalEventPayload.RemoveDevice && event.targetDeviceId == h.oldPhone.deviceId
            }.payload.messageId
        runCurrent()

        // Retaliation: the old phone bans its sibling G — positionally valid (G's add
        // synced long before), but forked off a stale frontier that excludes G's grant,
        // so the grant is outside the ban's ancestry. Two self-consistent fixpoints;
        // the oscillation tie-break keeps the maximal-revocation one.
        val gAddId = h.messageRepo.findAllInRoom(RoomId.GLOBAL)
            .first { row ->
                val event = (row.payload as? MessagePayload.GlobalEvent)?.decodeEvent()
                event is GlobalEventPayload.AddDevice && event.deviceId == siblingG.deviceId
            }.payload.messageId
        val forged = globalNode(
            crypto = h.crypto,
            author = h.oldPhone,
            prevIds = listOf(gAddId),
            createdAt = epochSeconds(12_500L),
            event = GlobalEventPayload.RemoveDevice(siblingG.deviceId),
        )
        h.store(forged)
        runCurrent()

        // The attacker is banned (its own revocation stands); the banner's adminship is
        // intact. Collateral, per the contested-principal-loses doctrine: the validly-
        // positioned retaliation takes the innocent sibling G down with the attacker —
        // griefing-only, auditable, recoverable by re-adding G with a fresh device id.
        assertEquals(IdentityStatus.BANNED, h.identityRepo.getDeviceStatus(h.oldPhone.deviceId))
        assertEquals(IdentityStatus.BANNED, h.identityRepo.getDeviceStatus(siblingG.deviceId))
        assertTrue(h.identityRepo.isAccountAdmin(accountX.accountId))
        assertEquals(VerificationState.VERIFIED, h.verdictOf(forged.messageId))
        assertEquals(VerificationState.VERIFIED, h.verdictOf(banXId))
    }

    // ------------------------------------------------------------------
    // Fuzz property tests (§9): seeded adversarial graphs over the same Harness.
    // The retaliation invariant under test: post-revocation forgeries by the revoked
    // principal change nothing — the revoked stay revoked, nobody else moves.
    // ------------------------------------------------------------------

    private data class FuzzSnapshot(
        val roleStatuses: Map<String, String?>,
        val verdicts: Map<Uuid, VerificationState>,
        val seenSize: Int,
    )

    private class FuzzRoles(
        val harness: Harness,
        val genesisAccount: TestAccount,
        val victim: TestDevice,
        val banner: TestDevice,
        val sibling: TestDevice,
        val extraAccount: TestAccount?,
        val extraDevice: TestDevice?,
        val forgedDeviceIds: List<PeerId> = emptyList(),
    )

    private suspend fun snapshotOf(h: Harness, r: FuzzRoles): FuzzSnapshot {
        val roles = LinkedHashMap<String, String?>()
        roles["victim"] = h.identityRepo.getDeviceStatus(r.victim.deviceId)?.name
        roles["banner"] = h.identityRepo.getDeviceStatus(r.banner.deviceId)?.name
        roles["sibling"] = h.identityRepo.getDeviceStatus(r.sibling.deviceId)?.name
        roles["extra"] = r.extraDevice?.let { h.identityRepo.getDeviceStatus(it.deviceId)?.name }
        roles["genesisAdmin"] = h.identityRepo.isAccountAdmin(r.genesisAccount.accountId).toString()
        roles["extraAdmin"] =
            r.extraAccount?.let { h.identityRepo.isAccountAdmin(it.accountId).toString() }
        val verdicts = h.messageRepo.findAllInRoom(RoomId.GLOBAL)
            .associate { it.payload.messageId to it.verificationState }
        return FuzzSnapshot(roles, verdicts, h.seen.size)
    }

    /** Builds one fuzz world: honest prefix (identical for a given seed), then optional
     *  forgeries. The honest ban always lands at 14_000; forgeries declare earlier
     *  positions off stale frontiers. */
    private suspend fun TestScope.buildFuzzRoles(
        seed: Int,
        withForgeries: Boolean,
        counterBan: Boolean,
    ): FuzzRoles {
        val random = Random(seed)
        val crypto = DefaultCryptoProvider()
        val a0 = newAccount(crypto, "a0")
        val d0 = newDevice(crypto, a0, "d0")
        val h = Harness(crypto, a0, d0)
        h.startIn(backgroundScope)
        h.genesis()
        runCurrent()

        // Sibling always present: keeps banner/victim selection total.
        val d1 = newDevice(crypto, a0, "d1")
        h.tickTo(11_000L)
        h.projector.publishOwnAccountDevice(inviteFor(d1))
        runCurrent()

        // Optional sponsored account, optionally admin.
        var a1: TestAccount? = null
        var e0: TestDevice? = null
        var a1Admin = false
        if (random.nextBoolean()) {
            a1 = newAccount(crypto, "a1")
            e0 = newDevice(crypto, a1, "e0")
            a1Admin = random.nextBoolean()
            h.become(d0, admin = true)
            h.tickTo(11_500L)
            h.projector.publishSponsoredNewAccount(
                inviteFor(e0, a1, bindingSig(crypto, a1, e0)),
                grantAdmin = a1Admin,
            )
            runCurrent()
        }

        // Banner: random admin device distinct from victim; victim: random genesis device.
        // The genesis account is admin by definition, so d0/d1 always qualify as banners.
        val victim = if (random.nextBoolean()) d0 else d1
        val candidates = mutableListOf(d0, d1)
        if (a1Admin) candidates.add(e0!!)
        val banner = candidates.filter { it.deviceId != victim.deviceId }.random(random)
        h.become(banner, admin = true)
        h.tickTo(14_000L)
        h.projector.publishRemoveDevice(victim.deviceId)
        runCurrent()

        // Forged placement is pinned, not random: every forgery branches off the root
        // with a fixed per-index timestamp that avoids all honest ticks
        // (10_000/11_000/11_500/14_000). Random stale subsets and random timestamps were
        // tried here and produced run-varying canonical positions with no change in the
        // security-relevant shapes (backdated + concurrent + outside the ban's ancestry
        // hold in all cases) — pinning keeps the suite deterministic while preserving
        // them. (Multi-level forged chains and sequential-position forgeries are covered
        // by the deterministic backdated_add_cascade and sequential_duel tests.)
        val forgedIds = mutableListOf<PeerId>()
        if (withForgeries && !counterBan) {
            val root = h.rootId()
            val slots = longArrayOf(10_500L, 11_250L, 12_500L, 13_500L)
            repeat(1 + random.nextInt(4)) { i ->
                val at = epochSeconds(slots[i])
                when (random.nextInt(4)) {
                    // Backdated branch-1 add by the banned victim.
                    0 -> {
                        val fresh = newDevice(crypto, a0, "fuzz-$seed-$i")
                        forgedIds.add(fresh.deviceId)
                        h.store(
                            globalNode(
                                crypto, victim,
                                prevIds = listOf(root),
                                createdAt = at,
                                event = addDeviceBranch1(a0, fresh),
                            ),
                        )
                    }
                    // Backdated grant by the victim's (still admin) account.
                    1 -> {
                        val target =
                            if (a1 != null && random.nextBoolean()) a1 else a0
                        h.store(
                            globalNode(
                                crypto, victim,
                                prevIds = listOf(root),
                                createdAt = at,
                                event = GlobalEventPayload.GrantAdmin(target.accountId),
                            ),
                        )
                    }
                    // Duplicate re-add of the victim (tombstone stands).
                    2 -> {
                        val author = if (random.nextBoolean()) victim else banner
                        h.store(
                            globalNode(
                                crypto, author,
                                prevIds = listOf(root),
                                createdAt = at,
                                event = addDeviceBranch1(a0, victim),
                            ),
                        )
                    }
                    // Banned-author removal of an uninvolved third device: void, and
                    // (unlike a counter-ban of the banner, which forms a genuine opposing
                    // pair) it threatens no honest chain, so the loop converges without
                    // oscillation. The banner itself is excluded as a target here —
                    // counter-bans have dedicated tests.
                    else -> {
                        val pool = (listOf(d0, d1) + listOfNotNull(e0)).filter {
                            it.deviceId != victim.deviceId && it.deviceId != banner.deviceId
                        }
                        if (pool.isEmpty()) {
                            h.store(
                                globalNode(
                                    crypto, banner,
                                    prevIds = listOf(root),
                                    createdAt = at,
                                    event = addDeviceBranch1(a0, victim),
                                ),
                            )
                        } else {
                            h.store(
                                globalNode(
                                    crypto, victim,
                                    prevIds = listOf(root),
                                    createdAt = at,
                                    event = GlobalEventPayload.RemoveDevice(pool.random(random).deviceId),
                                ),
                            )
                        }
                    }
                }
                runCurrent()
            }
        }
        if (withForgeries && counterBan) {
            // Plausible counter-ban: positioned after the banner exists (its target must
            // exist at the forged position), concurrent ancestry (stale fork).
            h.store(
                globalNode(
                    crypto, victim,
                    prevIds = listOf(h.rootId()),
                    createdAt = epochSeconds(13_500L),
                    event = GlobalEventPayload.RemoveDevice(banner.deviceId),
                ),
            )
            runCurrent()
        }
        return FuzzRoles(h, a0, victim, banner, d1, a1, e0, forgedIds)
    }

    @Test
    fun fuzz_post_ban_forgeries_change_nothing() = runTest {
        repeat(25) { seed ->
            // Same honest prefix twice (identical randomness); forgeries only in run B.
            // Role outcomes must match exactly; forged devices must never materialize.
            val honest = buildFuzzRoles(seed, withForgeries = false, counterBan = false)
            val attacked = buildFuzzRoles(seed, withForgeries = true, counterBan = false)
            runCurrent()

            assertEquals(
                snapshotOf(honest.harness, honest).roleStatuses,
                snapshotOf(attacked.harness, attacked).roleStatuses,
                "seed $seed",
            )
            for (id in attacked.forgedDeviceIds) {
                assertNull(attacked.harness.identityRepo.getDeviceRecord(id), "seed $seed")
            }
            // Refold stability on the attacked world: re-triggering a fold over the
            // identical stored set changes no verdict, no status, no emission.
            val before = snapshotOf(attacked.harness, attacked)
            attacked.harness.store(
                attacked.harness.messageRepo.findAllInRoom(RoomId.GLOBAL).first().payload,
            )
            runCurrent()
            assertEquals(before, snapshotOf(attacked.harness, attacked), "seed $seed")
        }
    }

    @Test
    fun fuzz_counter_ban_never_rescues_attacker() = runTest {
        repeat(25) { seed ->
            val world = buildFuzzRoles(seed, withForgeries = true, counterBan = true)
            val h = world.harness
            // Mutual destruction or lone honest ban — but the attacker is banned in every
            // fixpoint, and refolds are stable.
            assertEquals(IdentityStatus.BANNED, h.identityRepo.getDeviceStatus(world.victim.deviceId))
            assertEquals(IdentityStatus.BANNED, h.identityRepo.getDeviceStatus(world.banner.deviceId))
            val before = snapshotOf(h, world)
            h.store(h.messageRepo.findAllInRoom(RoomId.GLOBAL).first().payload)
            runCurrent()
            assertEquals(before, snapshotOf(h, world), "seed $seed")
        }
    }

    @Test
    fun fuzz_counter_demotion_demotes_both() = runTest {
        repeat(25) { seed ->
            val crypto = DefaultCryptoProvider()
            val a0 = newAccount(crypto, "a0")
            val d0 = newDevice(crypto, a0, "d0")
            val h = Harness(crypto, a0, d0)
            h.startIn(backgroundScope)
            h.genesis()
            runCurrent()

            // Two grant-derived admin accounts; demoter demotes the other; forged
            // counter-demotion, positioned after the target account exists, concurrent
            // ancestry. (Genesis itself is irrevocable, so the duel avoids it.)
            val a1 = newAccount(crypto, "a1")
            val e0 = newDevice(crypto, a1, "e0")
            h.become(d0, admin = true)
            h.tickTo(11_000L)
            h.projector.publishSponsoredNewAccount(
                inviteFor(e0, a1, bindingSig(crypto, a1, e0)),
                grantAdmin = true,
            )
            runCurrent()
            val a2 = newAccount(crypto, "a2")
            val e1 = newDevice(crypto, a2, "e1")
            h.tickTo(11_500L)
            h.projector.publishSponsoredNewAccount(
                inviteFor(e1, a2, bindingSig(crypto, a2, e1)),
                grantAdmin = true,
            )
            runCurrent()
            h.become(e0, admin = true)
            h.tickTo(14_000L)
            h.projector.publishRemoveAdmin(a2.accountId)
            runCurrent()
            h.store(
                globalNode(
                    crypto, e1,
                    prevIds = listOf(h.rootId()),
                    createdAt = epochSeconds(13_500L),
                    event = GlobalEventPayload.RemoveAdmin(a1.accountId),
                ),
            )
            runCurrent()

            assertFalse(h.identityRepo.isAccountAdmin(a2.accountId), "seed $seed")
            assertFalse(h.identityRepo.isAccountAdmin(a1.accountId), "seed $seed")
            val seenSize = h.seen.size
            val verdictsBefore = h.messageRepo.findAllInRoom(RoomId.GLOBAL)
                .associate { it.payload.messageId to it.verificationState }
            h.store(h.messageRepo.findAllInRoom(RoomId.GLOBAL).first().payload)
            runCurrent()
            val verdictsAfter = h.messageRepo.findAllInRoom(RoomId.GLOBAL)
                .associate { it.payload.messageId to it.verificationState }
            assertEquals(verdictsBefore, verdictsAfter, "seed $seed")
            assertEquals(seenSize, h.seen.size, "seed $seed")
        }
    }
}
