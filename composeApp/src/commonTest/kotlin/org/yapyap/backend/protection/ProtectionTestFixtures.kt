package org.yapyap.backend.protection

import org.yapyap.backend.crypto.AccountIdentityRecord
import org.yapyap.backend.crypto.DeviceIdentityRecord
import org.yapyap.backend.crypto.IdentityKeyPurpose
import org.yapyap.backend.crypto.IdentityPublicKeyRecord
import org.yapyap.backend.crypto.IdentityResolver
import org.yapyap.backend.crypto.LocalSignedPreKey
import org.yapyap.backend.crypto.EncryptionKeyPair
import org.yapyap.backend.crypto.KmpCryptoProvider
import org.yapyap.backend.crypto.SigningKeyPair
import org.yapyap.backend.db.MessageLifecycleState
import org.yapyap.backend.protocol.FileChunk
import org.yapyap.backend.protocol.FileControlPayload
import org.yapyap.backend.protocol.FileEnvelope
import org.yapyap.backend.protocol.FilePayload
import org.yapyap.backend.protocol.FileType
import org.yapyap.backend.protocol.OpenedFileEnvelope
import org.yapyap.backend.protocol.FileTransferClass
import org.yapyap.backend.protocol.FileTransportPreference
import org.yapyap.backend.protocol.MessagePayload
import org.yapyap.backend.protocol.PacketId
import org.yapyap.backend.protocol.PacketNackReason
import org.yapyap.backend.protocol.PacketType
import org.yapyap.backend.protocol.SystemPayload
import org.yapyap.backend.crypto.AccountId
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.SignalSecurityScheme
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.transport.webrtc.types.WebRtcSignal
import org.yapyap.backend.transport.webrtc.types.WebRtcSignalKind

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

    override fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord? = peerRecords[deviceId]

    override fun resolveTorEndpointForDevice(deviceId: PeerId) = error("not used")

    override fun getAllPeerDevicesForAccount(accountId: AccountId) = error("not used")

    override fun updatePeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) = error("not used")

    override suspend fun getCurrentLocalSignedPreKey(): LocalSignedPreKey = error("not used")
}

/**
 * No real crypto — wraps payloads in [FileEnvelope] / [OpenedFileEnvelope] for contract tests only.
 * Production code has no [FileProtection] implementation yet.
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
            payload = input,
        )

    override suspend fun open(input: FileEnvelope): OpenedFileEnvelope =
        OpenedFileEnvelope(
            transferId = input.transferId,
            source = input.source.id,
            target = input.target.id,
            createdAtEpochSeconds = input.createdAtEpochSeconds,
            securityScheme = input.securityScheme,
            payload = input.payload,
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
