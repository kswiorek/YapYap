package org.yapyap.backend.crypto

import org.yapyap.backend.crypto.e2ee.DefaultCryptoSessionManager
import org.yapyap.backend.crypto.e2ee.SessionUpgradePolicy
import org.yapyap.backend.crypto.e2ee.X3dhHandshake
import org.yapyap.backend.db.CryptoSessionStore
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint

internal data class TestPeerIdentity(
    val device: DeviceIdentityRecord,
    val encryptionPrivateKey: ByteArray,
    val signedPreKey: LocalSignedPreKey,
)

internal class TestIdentityResolver(
    private val local: TestPeerIdentity,
    private val peers: Map<PeerId, TestPeerIdentity>,
) : IdentityResolver {

    override suspend fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord = local.device

    override suspend fun getLocalAccountIdentityRecord(): AccountIdentityRecord =
        error("not used in crypto session tests")

    override suspend fun getLocalDevicePrivateKey(purpose: IdentityKeyPurpose): ByteArray =
        when (purpose) {
            IdentityKeyPurpose.ENCRYPTION -> local.encryptionPrivateKey
            else -> error("unexpected purpose $purpose")
        }

    override suspend fun getLocalAccountPrivateKey(purpose: IdentityKeyPurpose): ByteArray =
        error("not used in crypto session tests")

    override fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord? =
        peers[deviceId]?.device

    override fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint =
        TorEndpoint(onionAddress = "peer.onion", port = 80)

    override fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> =
        error("not used in crypto session tests")

    override fun updatePeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) =
        error("not used in crypto session tests")

    override suspend fun getCurrentLocalSignedPreKey(): LocalSignedPreKey = local.signedPreKey
}

internal suspend fun buildTestPeerIdentity(
    crypto: CryptoProvider,
    label: String,
): TestPeerIdentity {
    val signing = crypto.generateSigningKeyPair()
    val encryption = crypto.generateEncryptionKeyPair()
    val spk = crypto.generateEncryptionKeyPair()
    val deviceId = crypto.peerIdFromPublicKey(signing.publicKey)
    val spkId = "spk-$label"
    val spkSignature = crypto.signDetached(signing.privateKey, spk.publicKey)
    return TestPeerIdentity(
        device = DeviceIdentityRecord(
            deviceId = deviceId,
            signing = IdentityPublicKeyRecord(
                keyId = "signing-$label",
                keyVersion = 0,
                purpose = IdentityKeyPurpose.SIGNING,
                publicKey = signing.publicKey,
            ),
            encryption = IdentityPublicKeyRecord(
                keyId = "encryption-$label",
                keyVersion = 0,
                purpose = IdentityKeyPurpose.ENCRYPTION,
                publicKey = encryption.publicKey,
            ),
            signedPreKey = SignedPreKeyRecord(
                keyId = spkId,
                publicKey = spk.publicKey,
                signature = spkSignature,
            ),
        ),
        encryptionPrivateKey = encryption.privateKey,
        signedPreKey = LocalSignedPreKey(
            keyId = spkId,
            publicKey = spk.publicKey,
            privateKey = spk.privateKey,
            signature = spkSignature,
        ),
    )
}

internal fun managerForPeer(
    crypto: CryptoProvider,
    local: TestPeerIdentity,
    peer: TestPeerIdentity,
    sessionStore: CryptoSessionStore,
    oneTimePreKeyStore: InMemoryOneTimePreKeyStore,
    upgradePolicy: SessionUpgradePolicy = SessionUpgradePolicy.NEVER,
): DefaultCryptoSessionManager =
    DefaultCryptoSessionManager(
        crypto = crypto,
        x3dh = X3dhHandshake(crypto),
        sessionStore = sessionStore,
        identityResolver = TestIdentityResolver(
            local = local,
            peers = mapOf(peer.device.deviceId to peer),
        ),
        oneTimePreKeyStore = oneTimePreKeyStore,
        upgradePolicy = upgradePolicy,
    )
