package org.yapyap.backend.protection

import org.yapyap.backend.crypto.AccountIdentityRecord
import org.yapyap.backend.crypto.DeviceIdentityRecord
import org.yapyap.backend.crypto.IdentityKeyPurpose
import org.yapyap.backend.crypto.IdentityPublicKeyRecord
import org.yapyap.backend.crypto.IdentityResolver
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
    nonce: ByteArray = nonce24(),
): EnvelopeProtectContext = EnvelopeProtectContext(
    createdAtEpochSeconds = createdAtEpochSeconds,
    nonce = nonce,
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
internal fun samplePeerTriplet(crypto: KmpCryptoProvider): Triple<SigningKeyPair, PeerId, PeerId> {
    val localSigning = crypto.generateSigningKeyPair()
    val sourcePeer = crypto.peerIdFromPublicKey(localSigning.publicKey)
    val remoteSigning = crypto.generateSigningKeyPair()
    val targetPeer = crypto.peerIdFromPublicKey(remoteSigning.publicKey)
    return Triple(localSigning, sourcePeer, targetPeer)
}

internal fun deviceRecordFor(
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

    override fun getLocalDeviceIdentityRecord(): DeviceIdentityRecord = error("not used")

    override fun getLocalAccountIdentityRecord(): AccountIdentityRecord = error("not used")

    override fun loadLocalPrivateKey(purpose: IdentityKeyPurpose): ByteArray {
        require(purpose == IdentityKeyPurpose.SIGNING) { "unexpected purpose $purpose" }
        return localSigningPrivateKey
    }

    override fun resolvePeerIdentityRecord(deviceId: PeerId): DeviceIdentityRecord? = peerRecords[deviceId]

    override fun resolveTorEndpointForDevice(deviceId: PeerId) = error("not used")

    override fun getAllPeerDevicesForAccount(accountId: AccountId) = error("not used")

    override fun updatePeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) = error("not used")
}

/**
 * No real crypto — wraps payloads in [FileEnvelope] / [OpenedFileEnvelope] for contract tests only.
 * Production code has no [FileProtection] implementation yet.
 */
internal class PassthroughFileProtection : FileProtection {

    override fun protect(input: FilePayload, context: EnvelopeProtectContext): FileEnvelope =
        FileEnvelope(
            transferId = "fixture-transfer-id",
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAtEpochSeconds = context.createdAtEpochSeconds,
            nonce = context.nonce,
            securityScheme = context.securityScheme,
            signature = null,
            payload = input,
        )

    override fun open(input: FileEnvelope): OpenedFileEnvelope =
        OpenedFileEnvelope(
            transferId = input.transferId,
            source = input.source.id,
            target = input.target.id,
            createdAtEpochSeconds = input.createdAtEpochSeconds,
            securityScheme = input.securityScheme,
            payload = input.payload,
        )

    override fun decryptChunk(chunk: FilePayload.EncryptedChunk): FileChunk =
        FileChunk(
            fileName = "fixture.bin",
            chunkIndex = chunk.chunkIndex,
            chunkCount = chunk.chunkCount,
            type = FileType.GENERIC,
            fileData = chunk.chunkCiphertext,
        )
}
