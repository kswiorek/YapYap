package org.yapyap.protection

import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.e2ee.MapBackedCryptoSessionStore
import org.yapyap.crypto.e2ee.buildTestPeerIdentity
import org.yapyap.crypto.e2ee.managerForPeer
import org.yapyap.crypto.e2ee.session.X3dhRemotePeerKeys
import org.yapyap.crypto.identity.*
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.crypto.primitives.EncryptionKeyPair
import org.yapyap.crypto.primitives.SigningKeyPair
import org.yapyap.crypto.signature.DefaultSignatureProvider
import org.yapyap.orchestrator.dag.RoomId
import org.yapyap.persistence.key.InMemoryOpkRepository
import org.yapyap.protection.envelope.FileProtection
import org.yapyap.protection.envelope.SignedAndEncryptedMessageProtection
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.*
import org.yapyap.protocol.packet.PacketType
import org.yapyap.transport.webrtc.types.WebRtcSignal
import org.yapyap.transport.webrtc.types.WebRtcSignalKind
import kotlin.uuid.Uuid

internal object FixturePeerIds {
    val A = PeerId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    val B = PeerId("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
}

internal fun nonce24(): ByteArray = ByteArray(SignalSecurityScheme.PLAINTEXT_TEST_ONLY.nonceSize) { 7 }

internal fun sampleEnvelopeContext(
    scheme: SignalSecurityScheme,
    source: PeerId,
    target: PeerId,
    createdAtEpochSeconds: Long = 1_700_000_000L,
): EnvelopeProtectContext = EnvelopeProtectContext(
    createdAtEpochSeconds = createdAtEpochSeconds,
    sourceDeviceId = source,
    targetDeviceId = target,
    securityScheme = scheme,
)

internal fun sampleTextPayload(): MessagePayload.Text =
    MessagePayload.Text(
        messageId = Uuid.random(),
        roomId = RoomId(Uuid.random()),
        senderAccountId = AccountId("acct-1"),
        prevId = null,
        lamportClock = 0L,
        createdAt = 0L,
        text = "hello",
        authorDeviceId = PeerId("test-device"),
        authorSignature = byteArrayOf(0x01, 0x02, 0x03),
    )

internal fun sampleWebRtcSignal(source: PeerId, target: PeerId): WebRtcSignal =
    WebRtcSignal(
        kind = WebRtcSignalKind.OFFER,
        source = source,
        target = target,
        payload = byteArrayOf(0x01, 0x02, 0x03),
    )

internal fun samplePacketAckPayload(
    packetId: Uuid = Uuid.random(),
    packetType: PacketType = PacketType.MESSAGE,
): SystemPayload.PacketAck =
    SystemPayload.PacketAck(
        packetId = packetId,
        packetType = packetType,
    )

internal fun samplePacketNackPayload(
    packetId: Uuid = Uuid.random(),
    packetType: PacketType = PacketType.MESSAGE,
    reason: PacketNackReason = PacketNackReason.PROTECTION_FAILED,
    reasonText: String? = "bad sig",
): SystemPayload.PacketNack =
    SystemPayload.PacketNack(
        packetId = packetId,
        packetType = packetType,
        reason = reason,
        reasonText = reasonText,
    )

internal fun sampleFileOfferPayload(): FilePayload.Offer =
    FilePayload.Offer(
        fileNameHint = "note.txt",
        mimeType = "text/plain",
        totalBytes = 100L,
        chunkSizeBytes = 10,
        chunkCount = 10,
        objectHash = ByteArray(32) { 9 },
        control = FileControlPayload(
            transferClass = FileTransferClass.SMALL_STORE_FORWARD,
            preferredTransport = FileTransportPreference.TOR,
            supportsResume = false,
            maxInFlightChunks = 4,
        ),
    )

/**
 * Local signing key plus two distinct [PeerId]s for source/target roles.
 */
internal suspend fun samplePeerTriplet(crypto: DefaultCryptoProvider): Triple<SigningKeyPair, PeerId, PeerId> {
    val localSigning = crypto.generateSigningKeyPair()
    val sourcePeer = crypto.peerIdFromPublicKey(localSigning.publicKey)
    val remoteSigning = crypto.generateSigningKeyPair()
    val targetPeer = crypto.peerIdFromPublicKey(remoteSigning.publicKey)
    return Triple(localSigning, sourcePeer, targetPeer)
}

internal data class SignedAndEncryptedProtectionPair(
    val sender: SignedAndEncryptedMessageProtection,
    val receiver: SignedAndEncryptedMessageProtection,
    val sourcePeer: PeerId,
    val targetPeer: PeerId,
)

internal suspend fun sampleSignedAndEncryptedProtectionPair(
    crypto: DefaultCryptoProvider,
): SignedAndEncryptedProtectionPair {
    val senderPeer = buildTestPeerIdentity(crypto, "sae-sender")
    val receiverPeer = buildTestPeerIdentity(crypto, "sae-receiver")
    val senderSession = managerForPeer(
        crypto = crypto,
        local = senderPeer,
        peer = receiverPeer,
        sessionStore = MapBackedCryptoSessionStore(),
        oneTimePreKeyStore = InMemoryOpkRepository(crypto),
    )
    val receiverSession = managerForPeer(
        crypto = crypto,
        local = receiverPeer,
        peer = senderPeer,
        sessionStore = MapBackedCryptoSessionStore(),
        oneTimePreKeyStore = InMemoryOpkRepository(crypto),
    )
    val senderSignatureProvider = DefaultSignatureProvider(
        FakeIdentityResolverForProtection(
            localSigningPrivateKey = senderPeer.signingPrivateKey,
            peerRecords = emptyMap(),
        ),
        crypto,
    )
    val receiverSignatureProvider = DefaultSignatureProvider(
        FakeIdentityResolverForProtection(
            localSigningPrivateKey = receiverPeer.signingPrivateKey,
            peerRecords = mapOf(senderPeer.device.deviceId to senderPeer.device),
        ),
        crypto,
    )
    return SignedAndEncryptedProtectionPair(
        sender = SignedAndEncryptedMessageProtection(senderSignatureProvider, senderSession, crypto),
        receiver = SignedAndEncryptedMessageProtection(receiverSignatureProvider, receiverSession, crypto),
        sourcePeer = senderPeer.device.deviceId,
        targetPeer = receiverPeer.device.deviceId,
    )
}

internal suspend fun deviceRecordFor(
    crypto: DefaultCryptoProvider,
    signingKeys: SigningKeyPair,
    encryptionKeys: EncryptionKeyPair,
): DeviceIdentityRecord {
    val peerId = crypto.peerIdFromPublicKey(signingKeys.publicKey)
    return DeviceIdentityRecord(
        deviceId = peerId,
        signing = IdentityPublicKeyRecord(
            keyId = "fixture-signing",
            keyVersion = 0,
            purpose = IdentityKeyPurpose.SIGNING,
            publicKey = signingKeys.publicKey,
        ),
        encryption = IdentityPublicKeyRecord(
            keyId = "fixture-encryption",
            keyVersion = 0,
            purpose = IdentityKeyPurpose.ENCRYPTION,
            publicKey = encryptionKeys.publicKey,
        ),
    )
}

/**
 * Minimal fake: local signing private key and optional peer lookup for verification.
 */
internal class FakeIdentityResolverForProtection(
    private val localSigningPrivateKey: ByteArray,
    private val peerRecords: Map<PeerId, DeviceIdentityRecord>,
) : IdentityResolver {

    override suspend fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord = error("not used")

    override suspend fun getLocalAccountIdentityRecord(): AccountIdentityRecord = error("not used")

    override suspend fun getLocalDevicePrivateKey(purpose: IdentityKeyPurpose): ByteArray {
        require(purpose == IdentityKeyPurpose.SIGNING) { "unexpected purpose $purpose" }
        return localSigningPrivateKey
    }

    override suspend fun getLocalAccountPrivateKey(purpose: IdentityKeyPurpose): ByteArray = error("not used")
    override suspend fun getLocalDeviceId(): PeerId = error("not used")
    override suspend fun getLocalAccountId(): AccountId = error("not used in test")

    override suspend fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord =
        peerRecords[deviceId] ?: throw CryptoException.MissingDeviceRecord(deviceId.id)

    override suspend fun resolveTorEndpointForDevice(deviceId: PeerId) = error("not used")

    override suspend fun getAllPeerDevicesForAccount(accountId: AccountId) = error("not used")

    override suspend fun getAllPeers(): List<PeerId> = error("not used")

    override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? = error("not used")

    override suspend fun updatePeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) = error("not used")

    override suspend fun resolvePeerX3dhRemoteKeys(
        deviceId: PeerId,
        signedPreKeyId: String?,
    ): X3dhRemotePeerKeys = error("not used in test")

    override suspend fun getCurrentLocalSignedPreKey(): SignedPreKeyRecord = error("not used")

    override suspend fun resolveLocalSignedPreKey(signedPreKeyId: String): SignedPreKeyRecord = error("not used")
}

/**
 * No real crypto — wraps payloads in [FileEnvelope] / [OpenedFileEnvelope] for contract tests only.
 * Production code has no [org.yapyap.protection.envelope.FileProtection] implementation yet.
 */
internal class PassthroughFileProtection : FileProtection {

    override suspend fun protect(input: FilePayload, context: EnvelopeProtectContext): FileEnvelope =
        FileEnvelope(
            transferId = Uuid.random(),
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAtEpochSeconds = context.createdAtEpochSeconds,
            nonce = ByteArray(context.securityScheme.nonceSize) { 7 },
            securityScheme = context.securityScheme,
            signature = null,
            payload = input.encode(),
        )

    override suspend fun open(input: FileEnvelope): OpenedFileEnvelope =
        OpenedFileEnvelope(
            transferId = input.transferId,
            source = input.source.id,
            target = input.target.id,
            createdAtEpochSeconds = input.createdAtEpochSeconds,
            securityScheme = input.securityScheme,
            payload = input.decodePayload(),
        )

    override suspend fun decryptChunk(chunk: FilePayload.EncryptedChunk): FileChunk =
        FileChunk(
            fileName = "fixture.bin",
            chunkIndex = chunk.chunkIndex,
            chunkCount = chunk.chunkCount,
            type = FileType.GENERIC,
            fileData = chunk.chunkCiphertext,
        )
}
