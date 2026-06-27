package org.yapyap.backend.crypto

import org.yapyap.backend.crypto.e2ee.CryptoSessionConfig
import org.yapyap.backend.crypto.e2ee.DefaultCryptoHousekeeping
import org.yapyap.backend.crypto.e2ee.DefaultCryptoSessionManager
import org.yapyap.backend.crypto.e2ee.OneTimePreKeyStore
import org.yapyap.backend.crypto.e2ee.SessionUpgradePolicy
import org.yapyap.backend.crypto.e2ee.X3dhHandshake
import org.yapyap.backend.crypto.e2ee.X3dhRemotePeerKeys
import org.yapyap.backend.db.CryptoSessionStore
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.time.EpochSecondsProvider
import org.yapyap.backend.time.SystemEpochSecondsProvider

internal data class TestPeerIdentity(
    val device: DeviceIdentityRecord,
    val signingPrivateKey: ByteArray,
    val encryptionPrivateKey: ByteArray,
    val signedPreKey: SignedPreKeyRecord,
)

internal class TestIdentityResolver(
    private val local: TestPeerIdentity,
    private val peers: Map<PeerId, TestPeerIdentity>,
    private val cryptoProvider: CryptoProvider = KmpCryptoProvider(),
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

    override suspend fun getLocalDeviceId(): PeerId = local.device.deviceId

    override suspend fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord? =
        peers[deviceId]?.device

    override fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint =
        TorEndpoint(onionAddress = "peer.onion", port = 80)

    override fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> =
        error("not used in crypto session tests")

    override fun updatePeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) =
        error("not used in crypto session tests")

    override suspend fun resolvePeerX3dhRemoteKeys(
        deviceId: PeerId,
        signedPreKeyId: String?,
    ): X3dhRemotePeerKeys {
        val device = resolvePeerIdentityRecord(deviceId)
            ?: error("Missing peer identity record for deviceId=$deviceId")
        val signedPreKey = when {
            signedPreKeyId != null -> {
                device.signedPreKey?.takeIf { it.keyId == signedPreKeyId }
                    ?: error("Signed prekey not found: $signedPreKeyId")
            }
            else -> device.signedPreKey
                ?: error("Missing signed prekey on roster for deviceId=$deviceId")
        }
        require(cryptoProvider.verifyDetached(device.signing.publicKey, signedPreKey.publicKey, signedPreKey.signature)) {
            "failed to verify signed prekey signature"
        }
        return X3dhRemotePeerKeys(
            identityEncryptionPublicKey = device.encryption.publicKey,
            signedPreKeyPublicKey = signedPreKey.publicKey,
            signedPreKeyId = signedPreKey.keyId,
        )
    }

    override suspend fun getCurrentLocalSignedPreKey(): SignedPreKeyRecord = local.signedPreKey

    override suspend fun resolveLocalSignedPreKey(signedPreKeyId: String): SignedPreKeyRecord {
        require(signedPreKeyId == local.signedPreKey.keyId) {
            "Signed prekey not found: $signedPreKeyId"
        }
        return local.signedPreKey
    }
}

internal suspend fun buildTestPeerIdentity(
    crypto: CryptoProvider,
    label: String,
): TestPeerIdentity {
    val signing = crypto.generateSigningKeyPair()
    val encryption = crypto.generateEncryptionKeyPair()
    val spk = crypto.generateEncryptionKeyPair()
    val deviceId = crypto.peerIdFromPublicKey(signing.publicKey)
    val encryptionKeyId = "encryption-$label"
    val spkId = "spk-$label"
    val spkSignature = crypto.signDetached(signing.privateKey, spk.publicKey)
    return TestPeerIdentity(
        signingPrivateKey = signing.privateKey,
        device = DeviceIdentityRecord(
            deviceId = deviceId,
            signing = IdentityPublicKeyRecord(
                keyId = "signing-$label",
                keyVersion = 0,
                purpose = IdentityKeyPurpose.SIGNING,
                publicKey = signing.publicKey,
            ),
            encryption = IdentityPublicKeyRecord(
                keyId = encryptionKeyId,
                keyVersion = 0,
                purpose = IdentityKeyPurpose.ENCRYPTION,
                publicKey = encryption.publicKey,
            ),
            signedPreKey = SignedPreKeyRecord(
                deviceId = deviceId,
                keyId = spkId,
                publicKey = spk.publicKey,
                signature = spkSignature,
                privateKey = null
            ),
            keySignature = crypto.signDetached(signing.privateKey, encryption.publicKey + encryptionKeyId.encodeToByteArray()),
        ),
        encryptionPrivateKey = encryption.privateKey,
        signedPreKey = SignedPreKeyRecord(
            deviceId = deviceId,
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
    oneTimePreKeyStore: OneTimePreKeyStore,
    upgradePolicy: SessionUpgradePolicy = SessionUpgradePolicy.NEVER,
    sessionConfig: CryptoSessionConfig = CryptoSessionConfig(),
    timeProvider: EpochSecondsProvider = SystemEpochSecondsProvider,
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
        timeProvider = timeProvider,
        upgradePolicy = upgradePolicy,
        sessionConfig = sessionConfig,
    )

internal fun cryptoHousekeepingFor(
    sessionStore: CryptoSessionStore,
    oneTimePreKeyStore: OneTimePreKeyStore,
    sessionConfig: CryptoSessionConfig = CryptoSessionConfig(),
    timeProvider: EpochSecondsProvider = SystemEpochSecondsProvider,
): DefaultCryptoHousekeeping =
    DefaultCryptoHousekeeping(
        sessionStore = sessionStore,
        oneTimePreKeyStore = oneTimePreKeyStore,
        sessionConfig = sessionConfig,
        timeProvider = timeProvider,
    )
