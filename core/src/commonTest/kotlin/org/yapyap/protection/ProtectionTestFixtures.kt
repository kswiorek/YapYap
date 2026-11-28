package org.yapyap.protection

import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.e2ee.MapBackedCryptoSessionStore
import org.yapyap.crypto.e2ee.buildTestPeerIdentity
import org.yapyap.crypto.e2ee.managerForPeer
import org.yapyap.crypto.identity.LocalIdentity
import org.yapyap.crypto.identity.LocalKeys
import org.yapyap.crypto.identity.LocalSignedPreKey
import org.yapyap.crypto.identity.PeerKeys
import org.yapyap.crypto.identity.PeerPreKeyBundle
import org.yapyap.crypto.keys.EncryptionKeyPair
import org.yapyap.crypto.keys.EncryptionPrivateKey
import org.yapyap.crypto.keys.SigningKeyPair
import org.yapyap.crypto.keys.SigningPrivateKey
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.protection.envelope.FileProtection
import org.yapyap.protection.envelope.MessageProtection
import org.yapyap.protection.envelope.RatchetFileProtection
import org.yapyap.protection.envelope.RatchetMessageProtection
import org.yapyap.protection.envelope.RatchetSealer
import org.yapyap.protection.envelope.RatchetWebRtcSignalProtection
import org.yapyap.protection.envelope.WebRtcSignalProtection
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.ContentId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.IdentityStatus
import org.yapyap.protocol.MessageId
import org.yapyap.protocol.PacketType
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.RoomId
import org.yapyap.protocol.SealKey
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.DagPayload
import org.yapyap.protocol.envelopes.FileRequest
import org.yapyap.protocol.envelopes.FileSeal
import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.protocol.envelopes.SystemPayload
import org.yapyap.protocol.envelopes.WebRtcSignal
import org.yapyap.protocol.envelopes.WebRtcSignalKind
import org.yapyap.protocol.identity.AccountIdentity
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.SignedPreKeyId
import org.yapyap.routing.router.DeviceDirectory
import org.yapyap.routing.router.DeviceRouteStore
import org.yapyap.testfixtures.InMemoryOpkRepository
import org.yapyap.testfixtures.epochSeconds
import kotlin.uuid.Uuid

internal object FixturePeerIds {
    val A = PeerId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    val B = PeerId("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
}

internal fun sampleTextPayload(): DagPayload.Text =
    DagPayload.Text(
        messageId = MessageId(Uuid.random()),
        roomId = RoomId(Uuid.random()),
        senderAccountId = AccountId("acct-1"),
        prevIds = emptyList(),
        createdAt = epochSeconds(0L),
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
    reason: PacketNackReason = PacketNackReason.PERMANENT_PROTECTION_FAILED,
    reasonText: String? = "bad sig",
): SystemPayload.PacketNack =
    SystemPayload.PacketNack(
        packetId = packetId,
        packetType = packetType,
        reason = reason,
        reasonText = reasonText,
    )

internal fun sampleFileSeal(key: SealKey): FileSeal =
    FileSeal(
        fileId = FileId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
        totalBytes = 1000L,
        blockSize = 1024 * 1024,
        blockHashes = listOf(ByteArray(32) { 1 }),
        fileName = "photo.jpg",
        mimeType = "image/jpeg",
        key = key,
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

internal suspend fun deviceRecordFor(
    crypto: DefaultCryptoProvider,
    signingKeys: SigningKeyPair,
    encryptionKeys: EncryptionKeyPair,
): DeviceIdentity {
    val peerId = crypto.peerIdFromPublicKey(signingKeys.publicKey)
    return DeviceIdentity(
        deviceId = peerId,
        signingKey = signingKeys.publicKey,
        encryptionKey = encryptionKeys.publicKey,
    )
}

/** One ratchet sealer per direction over real crypto sessions between two fresh test peers. */
internal data class RatchetPair(
    val sender: RatchetSealer,
    val receiver: RatchetSealer,
    val sourcePeer: PeerId,
    val targetPeer: PeerId,
)

internal suspend fun sampleRatchetPair(crypto: DefaultCryptoProvider): RatchetPair {
    val senderPeer = buildTestPeerIdentity(crypto, "rp-sender")
    val receiverPeer = buildTestPeerIdentity(crypto, "rp-receiver")
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
    return RatchetPair(
        sender = RatchetSealer(senderSession),
        receiver = RatchetSealer(receiverSession),
        sourcePeer = senderPeer.device.deviceId,
        targetPeer = receiverPeer.device.deviceId,
    )
}

internal data class MessageProtectionPair(
    val sender: MessageProtection,
    val receiver: MessageProtection,
    val sourcePeer: PeerId,
    val targetPeer: PeerId,
)

internal suspend fun sampleMessageProtectionPair(crypto: DefaultCryptoProvider): MessageProtectionPair {
    val ratchets = sampleRatchetPair(crypto)
    return MessageProtectionPair(
        sender = RatchetMessageProtection(ratchets.sender),
        receiver = RatchetMessageProtection(ratchets.receiver),
        sourcePeer = ratchets.sourcePeer,
        targetPeer = ratchets.targetPeer,
    )
}

internal data class SignalProtectionPair(
    val sender: WebRtcSignalProtection,
    val receiver: WebRtcSignalProtection,
    val sourcePeer: PeerId,
    val targetPeer: PeerId,
)

internal suspend fun sampleSignalProtectionPair(crypto: DefaultCryptoProvider): SignalProtectionPair {
    val ratchets = sampleRatchetPair(crypto)
    return SignalProtectionPair(
        sender = RatchetWebRtcSignalProtection(ratchets.sender),
        receiver = RatchetWebRtcSignalProtection(ratchets.receiver),
        sourcePeer = ratchets.sourcePeer,
        targetPeer = ratchets.targetPeer,
    )
}

internal data class FileProtectionPair(
    val sender: FileProtection,
    val receiver: FileProtection,
    val sourcePeer: PeerId,
    val targetPeer: PeerId,
)

internal suspend fun sampleFileProtectionPair(crypto: DefaultCryptoProvider): FileProtectionPair {
    val ratchets = sampleRatchetPair(crypto)
    return FileProtectionPair(
        sender = RatchetFileProtection(ratchets.sender, crypto),
        receiver = RatchetFileProtection(ratchets.receiver, crypto),
        sourcePeer = ratchets.sourcePeer,
        targetPeer = ratchets.targetPeer,
    )
}

/**
 * Minimal fake: local signing private key plus optional peer lookup for verification.
 */
internal class FakeIdentityResolverForProtection(
    private val localSigningPrivateKey: SigningPrivateKey,
    private val peerRecords: Map<PeerId, DeviceIdentity>,
) : LocalIdentity, LocalKeys, PeerKeys, DeviceDirectory, DeviceRouteStore {

    override suspend fun getLocalDeviceIdentity(): DeviceIdentity = error("not used")

    override suspend fun getLocalAccountIdentity(): AccountIdentity = error("not used")
    override suspend fun getDeviceStatus(deviceId: PeerId): IdentityStatus? = IdentityStatus.ACTIVE
    override suspend fun getAccountStatus(accountId: AccountId): IdentityStatus? = error("not used")

    override suspend fun localDeviceSigningKey(): SigningPrivateKey = localSigningPrivateKey

    override suspend fun localDeviceEncryptionKey(): EncryptionPrivateKey = error("not used")

    override suspend fun localAccountSigningKey(): SigningPrivateKey = error("not used")
    override suspend fun getLocalDeviceId(): PeerId = error("not used in test")
    override suspend fun getLocalAccountId(): AccountId = error("not used in test")

    override suspend fun resolvePeerIdentity(deviceId: PeerId): DeviceIdentity =
        peerRecords[deviceId] ?: throw CryptoException.MissingDeviceRecord(deviceId.id)

    override suspend fun resolveTorEndpointForDevice(deviceId: PeerId): TorEndpoint? = null

    override suspend fun getAllPeerDevicesForAccount(accountId: AccountId): List<PeerId> = emptyList()

    override suspend fun getAccountIdForDevice(deviceId: PeerId): AccountId? = null

    override suspend fun upsertPeerTorEndpoint(deviceId: PeerId, torEndpoint: TorEndpoint) = error("not used")

    override suspend fun resolvePeerPreKeyBundle(deviceId: PeerId, signedPreKeyId: SignedPreKeyId?): PeerPreKeyBundle =
        error("not used in test")

    override suspend fun getCurrentLocalSignedPreKey(): LocalSignedPreKey = error("not used")

    override suspend fun resolveLocalSignedPreKey(id: SignedPreKeyId): LocalSignedPreKey = error("not used")

    override suspend fun getAllActiveDeviceIds(): List<PeerId> = peerRecords.keys.toList()

    override suspend fun getAllPeerDevicesForAccounts(accountIds: Collection<AccountId>): List<PeerId> = emptyList()

    override suspend fun getDeclaredTorEndpoint(deviceId: PeerId): TorEndpoint? = null
}

/**
 * No crypto — content passes through in the clear, for routing-adjacent contract tests only.
 * Chunk "ciphertext" is the plaintext plus [FileProtection.CHUNK_OVERHEAD_BYTES] of padding,
 * so every size a relay or receiver checks matches the real protection's.
 */
internal class PassthroughFileProtection : FileProtection {
    override suspend fun seal(seal: FileSeal, target: PeerId): ByteArray = seal.encode()

    override suspend fun openSeal(content: ByteArray, origin: PeerId): FileSeal = FileSeal.decode(content)

    override suspend fun encryptChunk(
        sealId: ContentId,
        key: SealKey,
        offset: Long,
        plaintext: ByteArray,
    ): ByteArray = plaintext + ByteArray(FileProtection.CHUNK_OVERHEAD_BYTES)

    override suspend fun decryptChunk(
        sealId: ContentId,
        key: SealKey,
        offset: Long,
        ciphertext: ByteArray,
    ): ByteArray =
        ciphertext.copyOfRange(0, ciphertext.size - FileProtection.CHUNK_OVERHEAD_BYTES)

    override suspend fun sealRequest(request: FileRequest, target: PeerId): ByteArray = request.encode()

    override suspend fun openRequest(content: ByteArray, source: PeerId): FileRequest =
        FileRequest.decode(content)

    override fun generateSealKey(): SealKey = SealKey(ByteArray(32))
}

/** No crypto — the payload rides in the clear, for routing-adjacent contract tests only. */
internal class PassthroughMessageProtection : MessageProtection {
    override suspend fun seal(
        payload: org.yapyap.protocol.envelopes.MessagePayload,
        finalTarget: PeerId,
    ): ByteArray = payload.encode()

    override suspend fun open(
        content: ByteArray,
        origin: PeerId,
    ): org.yapyap.protocol.envelopes.MessagePayload =
        org.yapyap.protocol.envelopes.MessagePayload.decode(content)
}

/** No crypto — the signal payload rides in the clear, for routing-adjacent contract tests only. */
internal class PassthroughSignalProtection : WebRtcSignalProtection {
    override suspend fun seal(signal: WebRtcSignal): ByteArray = signal.payload

    override suspend fun open(
        content: ByteArray,
        kind: WebRtcSignalKind,
        source: PeerId,
        target: PeerId,
    ): WebRtcSignal = WebRtcSignal(kind = kind, source = source, target = target, payload = content)
}
