package org.yapyap.crypto.e2ee

import kotlinx.coroutines.flow.MutableStateFlow
import org.yapyap.crypto.e2ee.maintenance.CryptoMaintenance
import org.yapyap.crypto.e2ee.manager.DefaultCryptoSessionManager
import org.yapyap.crypto.e2ee.manager.SessionUpgradePolicy
import org.yapyap.crypto.e2ee.session.CryptoLimits
import org.yapyap.crypto.e2ee.session.CryptoWireCodec
import org.yapyap.crypto.e2ee.session.X3dhHandshake
import org.yapyap.crypto.identity.*
import org.yapyap.crypto.keys.EncryptionPrivateKey
import org.yapyap.crypto.keys.SigningPrivateKey
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.store.CryptoSessionStore
import org.yapyap.crypto.store.OpkRepository
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.identity.AccountIdentity
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.SignedPreKey
import org.yapyap.protocol.identity.SignedPreKeyId
import org.yapyap.routing.directory.DeviceDirectory
import org.yapyap.routing.directory.DeviceRouteStore
import org.yapyap.routing.router.TransportLimits
import org.yapyap.testfixtures.FakeClock
import org.yapyap.testfixtures.epochSeconds
import org.yapyap.wiring.MessageLimits

internal data class TestPeerIdentity(
    val device: DeviceIdentity,
    val signingPrivateKey: SigningPrivateKey,
    val encryptionPrivateKey: EncryptionPrivateKey,
    val signedPreKey: LocalSignedPreKey,
)

internal class TestIdentityResolver(
    private val local: TestPeerIdentity,
    private val peers: Map<PeerId, TestPeerIdentity>,
    private val cryptoProvider: CryptoProvider = DefaultCryptoProvider(),
) : LocalIdentity, LocalKeys, PeerKeys, DeviceDirectory, DeviceRouteStore {

    override suspend fun getLocalDeviceIdentity(): DeviceIdentity = local.device

    override suspend fun getLocalAccountIdentity(): AccountIdentity =
        error("not used in crypto session tests")

    override suspend fun getDeviceStatus(deviceId: PeerId): IdentityStatus? = IdentityStatus.ACTIVE

    override suspend fun getAccountStatus(accountId: AccountId): IdentityStatus? =
        error("not used in crypto session tests")

    suspend fun isLocalAccountAdmin(): Boolean =
        error("not used in crypto session tests")

    suspend fun isLocalAccountOwner(): Boolean =
        error("not used in crypto session tests")

    override suspend fun localDeviceSigningKey(): SigningPrivateKey = local.signingPrivateKey

    override suspend fun localDeviceEncryptionKey(): EncryptionPrivateKey = local.encryptionPrivateKey

    override suspend fun localAccountSigningKey(): SigningPrivateKey =
        error("not used in crypto session tests")

    override suspend fun getLocalDeviceId(): PeerId = local.device.deviceId
    override suspend fun getLocalAccountId(): AccountId = error("not used in crypto session tests")

    override suspend fun resolvePeerIdentity(deviceId: PeerId): DeviceIdentity =
        peers[deviceId]?.device ?: error("Missing peer identity record for deviceId=$deviceId")

    override suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint? =
        TorEndpoint(onionAddress = "peer.onion", port = 80)

    override suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> =
        error("not used in crypto session tests")

    suspend fun getAllPeers(): List<PeerId> = peers.keys.toList()

    override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? =
        error("not used in crypto session tests")

    override suspend fun upsertPeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) =
        error("not used in crypto session tests")

    override suspend fun resolvePeerPreKeyBundle(
        deviceId: PeerId,
        signedPreKeyId: SignedPreKeyId?,
    ): PeerPreKeyBundle {
        val device = resolvePeerIdentity(deviceId)
        val signedPreKey = when {
            signedPreKeyId != null -> {
                device.signedPreKey?.takeIf { it.id == signedPreKeyId }
                    ?: error("Signed prekey not found: $signedPreKeyId")
            }

            else -> device.signedPreKey
                ?: error("Missing signed prekey on roster for deviceId=$deviceId")
        }
        require(
            cryptoProvider.verifyDetached(
                device.signingKey,
                signedPreKey.publicKey.bytes,
                signedPreKey.signature
            )
        ) {
            "failed to verify signed prekey signature"
        }
        return PeerPreKeyBundle(identityKey = device.encryptionKey, signedPreKey = signedPreKey)
    }

    override suspend fun getCurrentLocalSignedPreKey(): LocalSignedPreKey = local.signedPreKey

    override suspend fun resolveLocalSignedPreKey(id: SignedPreKeyId): LocalSignedPreKey {
        require(id == local.signedPreKey.id) { "Signed prekey not found: $id" }
        return local.signedPreKey
    }

    override suspend fun getAllActiveDeviceIds(): List<PeerId> =
        getAllPeers().filter { getDeviceStatus(it) != IdentityStatus.BANNED }

    override suspend fun getAllPeerDevicesForAccounts(accountIds: Collection<AccountId>): List<PeerId> =
        accountIds.flatMap { getAllPeerDevicesForAccount(it) }


    override suspend fun getDeclaredTorEndpoint(deviceId: PeerId): TorEndpoint? = resolveTorEndpointForDevice(deviceId)
}

internal suspend fun buildTestPeerIdentity(
    crypto: CryptoProvider,
    label: String,
): TestPeerIdentity {
    val signing = crypto.generateSigningKeyPair()
    val encryption = crypto.generateEncryptionKeyPair()
    val spk = crypto.generateEncryptionKeyPair()
    val deviceId = crypto.peerIdFromPublicKey(signing.publicKey)
    val spkId = SignedPreKeyId("spk-$label")
    val spkSignature = crypto.signDetached(signing.privateKey, spk.publicKey.bytes)
    val signedPreKey =
        SignedPreKey(id = spkId, deviceId = deviceId, publicKey = spk.publicKey, signature = spkSignature)
    return TestPeerIdentity(
        signingPrivateKey = signing.privateKey,
        device = DeviceIdentity(
            deviceId = deviceId,
            signingKey = signing.publicKey,
            encryptionKey = encryption.publicKey,
            signedPreKey = signedPreKey,
        ),
        encryptionPrivateKey = encryption.privateKey,
        signedPreKey = LocalSignedPreKey(signedPreKey, spk.privateKey),
    )
}

internal fun managerForPeer(
    crypto: CryptoProvider,
    local: TestPeerIdentity,
    peer: TestPeerIdentity,
    sessionStore: CryptoSessionStore,
    oneTimePreKeyStore: OpkRepository,
    upgradePolicy: SessionUpgradePolicy = SessionUpgradePolicy.NEVER,
    sessionConfig: CryptoSessionConfig = CryptoSessionConfig(),
    clock: FakeClock = FakeClock(epochSeconds(0)),
    cryptoLimits: CryptoLimits = testCryptoLimits(),
): DefaultCryptoSessionManager {
    val identity = TestIdentityResolver(
        local = local,
        peers = mapOf(peer.device.deviceId to peer),
    )
    return DefaultCryptoSessionManager(
        crypto = crypto,
        x3dh = X3dhHandshake(crypto),
        sessionStore = sessionStore,
        localIdentity = identity,
        localKeys = identity,
        peerKeys = identity,
        opkRepository = oneTimePreKeyStore,
        clock = clock,
        upgradePolicy = upgradePolicy,
        sessionConfig = MutableStateFlow(sessionConfig),
        cryptoLimits = MutableStateFlow(cryptoLimits),
    )
}

/** Legacy-equivalent capacity limits for tests (matches the pre-config crypto constants). */
internal fun testCryptoLimits() = CryptoLimits(
    maxSessionWireFrameBytes = 4 * 1024 * 1024,
    maxInnerPlaintextBytes = 256 * 1024,
    maxRatchetBodyBytes = 256 * 1024,
)

internal fun testCryptoWireCodec() = CryptoWireCodec(MutableStateFlow(testCryptoLimits()))

internal fun testTransportLimits() = TransportLimits(
    torMaxPayloadBytes = 4 * 1024 * 1024,
    webRtcMaxPayloadBytes = 1024 * 1024,
)

internal fun testMessageLimits() = MessageLimits.from(testTransportLimits())

internal fun cryptoHousekeepingFor(
    sessionStore: CryptoSessionStore,
    opkRepository: OpkRepository,
    sessionConfig: CryptoSessionConfig = CryptoSessionConfig(),
    clock: FakeClock = FakeClock(epochSeconds(0)),
): CryptoMaintenance =
    CryptoMaintenance(
        sessionStore = sessionStore,
        opkRepository = opkRepository,
        sessionConfig = MutableStateFlow(sessionConfig),
        clock = clock,
    )
