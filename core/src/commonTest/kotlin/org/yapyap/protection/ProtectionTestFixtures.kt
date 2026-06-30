package org.yapyap.protection

import org.yapyap.crypto.identity.AccountIdentityRecord
import org.yapyap.crypto.signature.DefaultSignatureProvider
import org.yapyap.crypto.identity.DeviceIdentityRecord
import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.crypto.identity.IdentityPublicKeyRecord
import org.yapyap.crypto.identity.IdentityResolver
import org.yapyap.crypto.InMemoryOpkRepository

import org.yapyap.crypto.primitives.EncryptionKeyPair
import org.yapyap.crypto.primitives.KmpCryptoProvider
import org.yapyap.crypto.MapBackedCryptoSessionStore
import org.yapyap.crypto.primitives.SigningKeyPair
import org.yapyap.crypto.buildTestPeerIdentity
import org.yapyap.crypto.managerForPeer
import org.yapyap.persistence.db.MessageLifecycleState
import org.yapyap.protocol.envelopes.FileChunk
import org.yapyap.protocol.envelopes.FileControlPayload
import org.yapyap.protocol.envelopes.FileEnvelope
import org.yapyap.protocol.envelopes.FilePayload
import org.yapyap.protocol.envelopes.FileType
import org.yapyap.protocol.envelopes.OpenedFileEnvelope
import org.yapyap.protocol.envelopes.FileTransferClass
import org.yapyap.protocol.envelopes.FileTransportPreference
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.protocol.packet.PacketId
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.packet.PacketType
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.crypto.identity.AccountId
import org.yapyap.crypto.identity.SignedPreKeyRecord
import org.yapyap.crypto.e2ee.X3dhRemotePeerKeys
import org.yapyap.protection.envelope.FileProtection
import org.yapyap.protection.envelope.SignedAndEncryptedMessageProtection
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import org.yapyap.protocol.TorEndpoint
import org.yapyap.transport.webrtc.types.WebRtcSignal
import org.yapyap.transport.webrtc.types.WebRtcSignalKind

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

internal fun sampleTextPayload(messageId: String = "msg-contract-1"): MessagePayload.Text =
    MessagePayload.Text(
        messageId = messageId,
        roomId = "room-1",
        senderAccountId = "acct-1",
        prevId = null,
        lamportClock = 0L,
        messagePayload = "hello".encodeToByteArray(),
        lifecycleState = MessageLifecycleState.CREATED,
        isOrphaned = false,
    )

internal fun sampleWebRtcSignal(source: PeerId, target: PeerId): WebRtcSignal =
    WebRtcSignal(
        sessionId = "session-contract-1",
        kind = WebRtcSignalKind.OFFER,
        source = source,
        target = target,
        payload = byteArrayOf(0x01, 0x02, 0x03),
    )

internal fun samplePacketId(byte: Int = 1): PacketId =
    PacketId.fromHex(byte.toString(16).padStart(PacketId.SIZE_BYTES * 2, '0'))

internal fun samplePacketAckPayload(
    packetId: PacketId = samplePacketId(),
    packetType: PacketType = PacketType.MESSAGE,
): SystemPayload.PacketAck =
    SystemPayload.PacketAck(
        packetId = packetId,
        packetType = packetType,
    )

internal fun samplePacketNackPayload(
    packetId: PacketId = samplePacketId(2),
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
internal suspend fun samplePeerTriplet(crypto: KmpCryptoProvider): Triple<SigningKeyPair, PeerId, PeerId> {
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
    crypto: KmpCryptoProvider,
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
    crypto: KmpCryptoProvider,
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

    override suspend fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord? = peerRecords[deviceId]

    override fun resolveTorEndpointForDevice(deviceId: PeerId) = error("not used")

    override fun getAllPeerDevicesForAccount(accountId: AccountId) = error("not used")

    override fun updatePeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) = error("not used")

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
            transferId = "fixture-transfer-id",
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
