package org.yapyap.protocol.envelopes

import org.yapyap.crypto.identity.*
import org.yapyap.persistence.db.DeviceType
import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BootstrapPayload.Companion.decode
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Out-of-band bootstrap transport envelope: the authenticated control message that delivers
 * the sponsor's identity (account + device keys, onion, DAG head) to a newcomer before any DB rows
 * exist. Nested inside a [BinaryEnvelope] with [org.yapyap.protocol.packet.PacketType.BOOTSTRAP] so
 * dedup, expiry, and target-check come from the existing inbound machinery.
 *
 * [scheme] is the *protection* scheme in plaintext so the inbound handler can open the envelope
 * before decoding — the payload kind is deliberately NOT in the header (mirroring
 * [MessageEnvelope], which carries a security scheme rather than a payload type). It must be in the
 * header because the payload kind is not always visible through the protection: an INTRO payload is
 * AEAD ciphertext, so its kind byte is unreachable until you have already picked the right scheme.
 * The [BootstrapPayloadKind.INVITE] is an out-of-band QR/CLI artifact and never travels in an
 * envelope.
 *
 * For SECRET_AEAD, [payload] is AEAD ciphertext of an encoded [BootstrapPayload], keyed from the
 * one-time shared secret carried in the newcomer's QR / recovery request (see the onboarding design
 * doc, §8). The header — scheme, envelope id, source, target, createdAt — is bound into the AEAD
 * AAD ([aadBytes]) so the header cannot be swapped, re-targeted, or replayed against a different
 * device. The cipher's IV is embedded in the AEAD output (library-managed), so no separate nonce
 * field is needed.
 */
data class BootstrapEnvelope(
    val scheme: BootstrapSecurityScheme,
    val bootstrapEnvelopeId: Uuid,
    val source: PeerId,
    val target: PeerId,
    val createdAt: Instant,
    val payload: ByteArray,
) {
    fun encode(): ByteArray {
        val writer = ByteWriter(32 + payload.size)
        writeHeader(writer)
        writer.writeByteArray(payload)
        return writer.toByteArray()
    }

    /** Everything except the payload, bound as AEAD AAD on open. */
    fun aadBytes(): ByteArray {
        val writer = ByteWriter(32)
        writeHeader(writer)
        return writer.toByteArray()
    }

    fun decodePayload(): BootstrapPayload = BootstrapPayload.decode(payload)

    private fun writeHeader(writer: ByteWriter) {
        writer.writeBytes(MAGIC)
        writer.writeByte(VERSION.toInt())
        writer.writeByte(scheme.wireValue.toInt())
        writer.writeUuid(bootstrapEnvelopeId)
        writer.writePeerId(source)
        writer.writePeerId(target)
        writer.writeLong(createdAt.epochSeconds)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as BootstrapEnvelope
        return scheme == other.scheme &&
                bootstrapEnvelopeId == other.bootstrapEnvelopeId &&
                source == other.source &&
                target == other.target &&
                createdAt == other.createdAt &&
                payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = scheme.hashCode()
        result = 31 * result + bootstrapEnvelopeId.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + target.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        private val MAGIC = byteArrayOf('Y'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte())
        private const val VERSION: Byte = 1

        fun decode(bytes: ByteArray): BootstrapEnvelope {
            val reader = ByteReader(bytes)
            val magic = reader.readBytes(MAGIC.size)
            require(magic.contentEquals(MAGIC)) { "Invalid bootstrap envelope magic" }
            val version = reader.readByte()
            require(version == VERSION) { "Unsupported bootstrap envelope version: $version" }
            val scheme = BootstrapSecurityScheme.fromWireValue(reader.readByte())
            val envelopeId = reader.readUuid()
            val source = reader.readPeerId()
            val target = reader.readPeerId()
            val createdAt = Instant.fromEpochSeconds(reader.readLong())
            val payload = reader.readByteArray()
            reader.requireFullyRead()
            return BootstrapEnvelope(scheme, envelopeId, source, target, createdAt, payload)
        }
    }
}

/**
 * How a bootstrap envelope is protected. The payload kind lives *inside* the (opened) payload — this
 * header discriminator only tells the handler how to open it, mirroring
 * [MessageEnvelope.securityScheme].
 */
enum class BootstrapSecurityScheme(val wireValue: Byte) {
    /** INTRO: AEAD under the one-time session secret (QR shared secret / recovery request secret). */
    SECRET_AEAD(1),

    /** RECOVERY_REQUEST: plaintext + account-key signature over the device binding. */
    ACCOUNT_SIGNED(2);

    companion object {
        fun fromWireValue(value: Byte): BootstrapSecurityScheme =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported bootstrap security scheme wire value: $value")
    }
}

enum class BootstrapPayloadKind(val wireValue: Byte) {
    /**
     * Sponsor → newcomer. Delivers the sponsor's identity (account + device keys, onion, DAG head)
     * to a newcomer before any DB rows exist. AEAD-encrypted inside [BootstrapEnvelope], keyed from
     * the newcomer's one-time shared secret — hence it never carries the secret itself.
     */
    INTRO(1),

    /**
     * Newcomer → sponsor. Carried by the out-of-band QR / CLI channel (the QR *is* the secure
     * channel, §8 of the onboarding design doc), so the one-time shared secret rides it in the clear.
     * `account == null` means the newcomer is joining an existing account — the sponsor's own by §3
     * authorization — and the sponsor appends only `AddDevice`; otherwise the account record (with
     * its public key) is published via a back-to-back `AddAccount` + `AddDevice`. The newcomer has
     * no DAG yet, so the head is always empty.
     */
    INVITE(2),

    /**
     * Recovering device → a mesh node, for account-recovery onboarding over the network. Plaintext +
     * the account-key signature (possession proof *and* the future AddDevice `key_signature`); carries
     * a one-time shared secret so the responder's intro reply is AEAD-bound to this request. See the
     * onboarding design doc, §8.2.
     */
    RECOVERY_REQUEST(3);

    companion object {
        fun fromWireValue(value: Byte): BootstrapPayloadKind =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported bootstrap payload kind wire value: $value")
    }
}

/**
 * Bootstrap payload, one subtype per handshake direction (mirrors the [MessagePayload] sealed shape —
 * the concrete type is the kind, so dispatch is type-safe `is` rather than a runtime tag):
 *  - [Intro] (sponsor → newcomer): sponsor's own [account] + DAG head; no secret.
 *  - [Invite] (newcomer → sponsor, out-of-band QR/CLI): a one-time shared secret; [account] may be
 *    null (add-device to the sponsor's account) or a full new-account record.
 *  - [RecoveryRequest] (recovering device → mesh node): shared secret + account signature over
 *    [RecoveryRequest.accountSignedDeviceBindingBytes].
 *
 * The wire kind byte selects which subtype to decode ([decode]), so each subtype's fields are
 * non-nullable where they belong — no nullable soup, no `init` validity table.
 */
sealed interface BootstrapPayload {
    val version: Int
    val kind: BootstrapPayloadKind
    val account: AccountIdentityRecord?
    val device: DeviceIdentityRecord
    val deviceType: DeviceType
    val torEndpoint: TorEndpoint

    fun encode(): ByteArray

    companion object {
        fun decode(bytes: ByteArray): BootstrapPayload {
            val reader = ByteReader(bytes)
            val kind = BootstrapPayloadKind.fromWireValue(reader.readByte())
            return when (kind) {
                BootstrapPayloadKind.INTRO -> Intro.decode(reader)
                BootstrapPayloadKind.INVITE -> Invite.decode(reader)
                BootstrapPayloadKind.RECOVERY_REQUEST -> RecoveryRequest.decode(reader)
            }
        }
    }
}

/** Sponsor → newcomer: sponsor's identity + DAG head; AEAD-encrypted under the newcomer's secret. */
data class Intro(
    override val version: Int = 1,
    override val account: AccountIdentityRecord,
    override val device: DeviceIdentityRecord,
    override val deviceType: DeviceType,
    override val torEndpoint: TorEndpoint,
    val dagHeadMessageId: Uuid?,
    val dagHeadLamport: Long,
) : BootstrapPayload {
    override val kind: BootstrapPayloadKind = BootstrapPayloadKind.INTRO

    init {
        require(version in 0..255) { "version must be in 0..255" }
        require(dagHeadLamport >= 0) { "dagHeadLamport must be >= 0" }
    }

    override fun encode(): ByteArray {
        val writer = ByteWriter(256)
        writer.writeBootstrapPayloadPrefix(kind, version, account, device, deviceType, torEndpoint)
        writer.writeNullableUuid(dagHeadMessageId)
        writer.writeLong(dagHeadLamport)
        return writer.toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        other as Intro
        return version == other.version &&
                dagHeadLamport == other.dagHeadLamport &&
                dagHeadMessageId == other.dagHeadMessageId &&
                deviceType == other.deviceType &&
                torEndpoint == other.torEndpoint &&
                bootstrapPayloadAccountEquals(account, other.account) &&
                bootstrapPayloadDeviceEquals(device, other.device)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + dagHeadLamport.hashCode()
        result = 31 * result + (dagHeadMessageId?.hashCode() ?: 0)
        result = 31 * result + deviceType.hashCode()
        result = 31 * result + torEndpoint.onionAddress.hashCode()
        result = 31 * result + torEndpoint.port
        result = 31 * result + bootstrapPayloadAccountHashCode(account)
        result = 31 * result + bootstrapPayloadDeviceHashCode(device)
        return result
    }

    companion object {
        fun decode(reader: ByteReader): Intro {
            val prefix = reader.readBootstrapPayloadPrefix()
            val dagHeadMessageId = reader.readNullableUuid()
            val dagHeadLamport = reader.readLong()
            reader.requireFullyRead()
            return Intro(
                version = prefix.version,
                account = prefix.account ?: error("INTRO requires the sponsor's account"),
                device = prefix.device,
                deviceType = prefix.deviceType,
                torEndpoint = prefix.torEndpoint,
                dagHeadMessageId = dagHeadMessageId,
                dagHeadLamport = dagHeadLamport,
            )
        }
    }
}

/** Newcomer → sponsor (out-of-band QR/CLI only): the newcomer's identity + one-time shared secret. */
data class Invite(
    override val version: Int = 1,
    override val account: AccountIdentityRecord?,
    override val device: DeviceIdentityRecord,
    override val deviceType: DeviceType,
    override val torEndpoint: TorEndpoint,
    val sharedSecret: ByteArray,
) : BootstrapPayload {
    override val kind: BootstrapPayloadKind = BootstrapPayloadKind.INVITE

    init {
        require(version in 0..255) { "version must be in 0..255" }
        require(sharedSecret.isNotEmpty()) { "INVITE requires a non-empty sharedSecret" }
    }

    override fun encode(): ByteArray {
        val writer = ByteWriter(256)
        writer.writeBootstrapPayloadPrefix(kind, version, account, device, deviceType, torEndpoint)
        writer.writeNullableByteArray(sharedSecret)
        return writer.toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        other as Invite
        return version == other.version &&
                deviceType == other.deviceType &&
                torEndpoint == other.torEndpoint &&
                sharedSecret.contentEquals(other.sharedSecret) &&
                bootstrapPayloadAccountEquals(account, other.account) &&
                bootstrapPayloadDeviceEquals(device, other.device)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + deviceType.hashCode()
        result = 31 * result + torEndpoint.onionAddress.hashCode()
        result = 31 * result + torEndpoint.port
        result = 31 * result + sharedSecret.contentHashCode()
        result = 31 * result + bootstrapPayloadAccountHashCode(account)
        result = 31 * result + bootstrapPayloadDeviceHashCode(device)
        return result
    }

    companion object {
        fun decode(reader: ByteReader): Invite {
            val prefix = reader.readBootstrapPayloadPrefix()
            val sharedSecret = reader.readNullableByteArray()
                ?: error("INVITE requires a sharedSecret")
            reader.requireFullyRead()
            return Invite(
                version = prefix.version,
                account = prefix.account,
                device = prefix.device,
                deviceType = prefix.deviceType,
                torEndpoint = prefix.torEndpoint,
                sharedSecret = sharedSecret,
            )
        }
    }
}

/** Recovering device → mesh node (account-recovery over the network): account-signed device binding. */
data class RecoveryRequest(
    override val version: Int = 1,
    override val account: AccountIdentityRecord,
    override val device: DeviceIdentityRecord,
    override val deviceType: DeviceType,
    override val torEndpoint: TorEndpoint,
    val sharedSecret: ByteArray,
    val accountSignature: ByteArray,
) : BootstrapPayload {
    override val kind: BootstrapPayloadKind = BootstrapPayloadKind.RECOVERY_REQUEST

    init {
        require(version in 0..255) { "version must be in 0..255" }
        require(sharedSecret.isNotEmpty()) { "RECOVERY_REQUEST requires a non-empty sharedSecret" }
        require(accountSignature.isNotEmpty()) { "RECOVERY_REQUEST requires the account signature over the device binding" }
        require(account.key != null) { "RECOVERY_REQUEST requires the account record with its public key" }
    }

    /**
     * Canonical bytes the account key signs to bind [device] to the account — the recovery path's
     * possession proof at the responder *and* the `key_signature` the responder relays into the
     * AddDevice event, re-verifiable by every node's fold from [device]/[deviceType] alone.
     */
    fun accountSignedDeviceBindingBytes(): ByteArray {
        val writer = ByteWriter(256)
        writer.writeByte(ACCOUNT_SIGNED_BINDING_VERSION.toInt())
        writer.writeByteArray(device.signing.publicKey)
        writer.writeString(device.signing.keyId)
        writer.writeByteArray(device.encryption.publicKey)
        writer.writeString(device.encryption.keyId)
        writer.writeByte(deviceType.ordinal)
        return writer.toByteArray()
    }

    override fun encode(): ByteArray {
        val writer = ByteWriter(256)
        writer.writeBootstrapPayloadPrefix(kind, version, account, device, deviceType, torEndpoint)
        writer.writeNullableByteArray(sharedSecret)
        writer.writeNullableByteArray(accountSignature)
        return writer.toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        other as RecoveryRequest
        return version == other.version &&
                deviceType == other.deviceType &&
                torEndpoint == other.torEndpoint &&
                sharedSecret.contentEquals(other.sharedSecret) &&
                accountSignature.contentEquals(other.accountSignature) &&
                bootstrapPayloadAccountEquals(account, other.account) &&
                bootstrapPayloadDeviceEquals(device, other.device)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + deviceType.hashCode()
        result = 31 * result + torEndpoint.onionAddress.hashCode()
        result = 31 * result + torEndpoint.port
        result = 31 * result + sharedSecret.contentHashCode()
        result = 31 * result + accountSignature.contentHashCode()
        result = 31 * result + bootstrapPayloadAccountHashCode(account)
        result = 31 * result + bootstrapPayloadDeviceHashCode(device)
        return result
    }

    companion object {
        private const val ACCOUNT_SIGNED_BINDING_VERSION: Byte = 1

        fun decode(reader: ByteReader): RecoveryRequest {
            val prefix = reader.readBootstrapPayloadPrefix()
            val sharedSecret = reader.readNullableByteArray()
                ?: error("RECOVERY_REQUEST requires a sharedSecret")
            val accountSignature = reader.readNullableByteArray()
                ?: error("RECOVERY_REQUEST requires the account signature over the device binding")
            reader.requireFullyRead()
            return RecoveryRequest(
                version = prefix.version,
                account = prefix.account ?: error("RECOVERY_REQUEST requires the account record"),
                device = prefix.device,
                deviceType = prefix.deviceType,
                torEndpoint = prefix.torEndpoint,
                sharedSecret = sharedSecret,
                accountSignature = accountSignature,
            )
        }
    }
}

/* ------------------------------ identity record codecs ------------------------------ */

private const val SIGNED_PREKEY_WIRE_VERSION: Byte = 1

private fun ByteWriter.writeAccountIdentity(account: AccountIdentityRecord) {
    writeString(account.accountId.id)
    writeString(account.displayName)
    writeNullableIdentityPublicKey(account.key)
}

private fun ByteReader.readAccountIdentity(): AccountIdentityRecord {
    val accountId = AccountId(readString())
    val displayName = readString()
    val key = readNullableIdentityPublicKey()
    return AccountIdentityRecord(accountId, displayName, key)
}

private fun ByteWriter.writeNullableAccountIdentity(account: AccountIdentityRecord?) {
    if (account == null) {
        writeByte(0)
    } else {
        writeByte(1)
        writeAccountIdentity(account)
    }
}

private fun ByteReader.readNullableAccountIdentity(): AccountIdentityRecord? =
    when (val marker = readUnsignedByte()) {
        0 -> null
        1 -> readAccountIdentity()
        else -> error("invalid nullable account identity marker: $marker")
    }

/* ------------------------------ shared payload prefix codec ------------------------------ */

private const val PAYLOAD_VERSION: Byte = 1

private data class BootstrapPayloadPrefix(
    val version: Int,
    val account: AccountIdentityRecord?,
    val device: DeviceIdentityRecord,
    val deviceType: DeviceType,
    val torEndpoint: TorEndpoint,
)

/** Writes the fields common to every [BootstrapPayload] subtype (kind byte + identity + onion). */
private fun ByteWriter.writeBootstrapPayloadPrefix(
    kind: BootstrapPayloadKind,
    version: Int,
    account: AccountIdentityRecord?,
    device: DeviceIdentityRecord,
    deviceType: DeviceType,
    torEndpoint: TorEndpoint,
) {
    writeByte(kind.wireValue.toInt())
    writeByte(version)
    writeNullableAccountIdentity(account)
    writeDeviceIdentity(device)
    writeByte(deviceType.ordinal.toByte().toInt())
    writeString(torEndpoint.onionAddress)
    writeInt(torEndpoint.port)
}

/**
 * Reads the shared prefix (the top-level [BootstrapPayload.decode] already consumed the kind byte).
 * Returns the common fields; each subtype then reads and validates its own trailing fields.
 */
private fun ByteReader.readBootstrapPayloadPrefix(): BootstrapPayloadPrefix {
    val version = readByte().toInt() and 0xff
    require(version == PAYLOAD_VERSION.toInt()) { "Unsupported bootstrap payload version: $version" }
    val account = readNullableAccountIdentity()
    val device = readDeviceIdentity()
    val deviceTypeOrdinal = readUnsignedByte()
    val deviceType = DeviceType.entries.getOrNull(deviceTypeOrdinal)
        ?: error("Unsupported device type wire value: $deviceTypeOrdinal")
    val onionAddress = readString()
    val port = readInt()
    return BootstrapPayloadPrefix(version, account, device, deviceType, TorEndpoint(onionAddress, port))
}

private fun ByteWriter.writeDeviceIdentity(device: DeviceIdentityRecord) {
    writePeerId(device.deviceId)
    writeIdentityPublicKey(device.signing)
    writeIdentityPublicKey(device.encryption)
    writeNullableSignedPreKey(device.signedPreKey)
    writeNullableByteArray(device.keySignature)
}

private fun ByteReader.readDeviceIdentity(): DeviceIdentityRecord {
    val deviceId = readPeerId()
    val signing = readIdentityPublicKey()
    val encryption = readIdentityPublicKey()
    val signedPreKey = readNullableSignedPreKey()
    val keySignature = readNullableByteArray()
    return DeviceIdentityRecord(
        deviceId = deviceId,
        signing = signing,
        encryption = encryption,
        signedPreKey = signedPreKey,
        keySignature = keySignature,
    )
}

private fun ByteWriter.writeIdentityPublicKey(record: IdentityPublicKeyRecord) {
    writeString(record.keyId)
    writeLong(record.keyVersion)
    writeByte(record.purpose.ordinal)
    writeByteArray(record.publicKey)
}

private fun ByteReader.readIdentityPublicKey(): IdentityPublicKeyRecord {
    val keyId = readString()
    val keyVersion = readLong()
    val purposeOrdinal = readUnsignedByte()
    val purpose = IdentityKeyPurpose.entries.firstOrNull { it.ordinal == purposeOrdinal }
        ?: error("Unsupported identity key purpose: $purposeOrdinal")
    val publicKey = readByteArray()
    return IdentityPublicKeyRecord(keyId, keyVersion, purpose, publicKey)
}

private fun ByteWriter.writeNullableIdentityPublicKey(record: IdentityPublicKeyRecord?) {
    if (record == null) {
        writeByte(0)
    } else {
        writeByte(1)
        writeIdentityPublicKey(record)
    }
}

private fun ByteReader.readNullableIdentityPublicKey(): IdentityPublicKeyRecord? =
    when (val marker = readUnsignedByte()) {
        0 -> null
        1 -> readIdentityPublicKey()
        else -> error("invalid nullable identity public key marker: $marker")
    }

/** Public-only SPK serialization — [SignedPreKeyRecord.privateKey] is deliberately excluded. */
private fun ByteWriter.writeSignedPreKey(spk: SignedPreKeyRecord) {
    writeByte(SIGNED_PREKEY_WIRE_VERSION.toInt())
    writeString(spk.keyId)
    writeByteArray(spk.publicKey)
    writeByteArray(spk.signature)
    writePeerId(spk.deviceId)
    writeByte(if (spk.isActive) 1 else 0)
    writeNullableEpochSeconds(spk.createdAt)
}

private fun ByteReader.readSignedPreKey(): SignedPreKeyRecord {
    val version = readByte().toInt() and 0xff
    require(version == SIGNED_PREKEY_WIRE_VERSION.toInt()) { "Unsupported signed prekey wire version: $version" }
    val keyId = readString()
    val publicKey = readByteArray()
    val signature = readByteArray()
    val deviceId = readPeerId()
    val isActive = readByte().toInt() == 1
    val createdAt = readNullableInstant()
    return SignedPreKeyRecord(
        keyId = keyId,
        publicKey = publicKey,
        signature = signature,
        privateKey = null,
        deviceId = deviceId,
        isActive = isActive,
        createdAt = createdAt,
    )
}

private fun ByteWriter.writeNullableSignedPreKey(spk: SignedPreKeyRecord?) {
    if (spk == null) {
        writeByte(0)
    } else {
        writeByte(1)
        writeSignedPreKey(spk)
    }
}

private fun ByteReader.readNullableSignedPreKey(): SignedPreKeyRecord? =
    when (val marker = readUnsignedByte()) {
        0 -> null
        1 -> readSignedPreKey()
        else -> error("invalid nullable signed prekey marker: $marker")
    }

private fun ByteWriter.writeNullableEpochSeconds(value: Instant?) {
    if (value == null) {
        writeByte(0)
    } else {
        writeByte(1)
        writeLong(value.epochSeconds)
    }
}

private fun ByteReader.readNullableInstant(): Instant? =
    when (val marker = readUnsignedByte()) {
        0 -> null
        1 -> Instant.fromEpochSeconds(readLong())
        else -> error("invalid nullable instant marker: $marker")
    }

/* ------------------------------ deep equality for [BootstrapPayload] ------------------------------ */

private fun bootstrapPayloadAccountEquals(a: AccountIdentityRecord?, b: AccountIdentityRecord?): Boolean {
    if (a === b) return true
    if (a == null || b == null) return false
    return a.accountId == b.accountId &&
            a.displayName == b.displayName &&
            identityPublicKeyEquals(a.key, b.key)
}

private fun identityPublicKeyEquals(a: IdentityPublicKeyRecord?, b: IdentityPublicKeyRecord?): Boolean {
    if (a === b) return true
    if (a == null || b == null) return false
    return a.keyId == b.keyId &&
            a.keyVersion == b.keyVersion &&
            a.purpose == b.purpose &&
            a.publicKey.contentEquals(b.publicKey)
}

private fun bootstrapPayloadDeviceEquals(a: DeviceIdentityRecord, b: DeviceIdentityRecord): Boolean {
    if (a === b) return true
    return a.deviceId == b.deviceId &&
            identityPublicKeyEquals(a.signing, b.signing) &&
            identityPublicKeyEquals(a.encryption, b.encryption) &&
            signedPreKeyEquals(a.signedPreKey, b.signedPreKey) &&
            a.keySignature.contentEquals(b.keySignature)
}

private fun signedPreKeyEquals(a: SignedPreKeyRecord?, b: SignedPreKeyRecord?): Boolean {
    if (a === b) return true
    if (a == null || b == null) return false
    return a.keyId == b.keyId &&
            a.publicKey.contentEquals(b.publicKey) &&
            a.signature.contentEquals(b.signature) &&
            a.privateKey.contentEquals(b.privateKey) &&
            a.deviceId == b.deviceId &&
            a.isActive == b.isActive &&
            a.createdAt == b.createdAt
}

private fun bootstrapPayloadAccountHashCode(account: AccountIdentityRecord?): Int {
    if (account == null) return 0
    var result = account.accountId.hashCode()
    result = 31 * result + account.displayName.hashCode()
    result = 31 * result + identityPublicKeyHashCode(account.key)
    return result
}

private fun identityPublicKeyHashCode(key: IdentityPublicKeyRecord?): Int {
    if (key == null) return 0
    var result = key.keyId.hashCode()
    result = 31 * result + key.keyVersion.hashCode()
    result = 31 * result + key.purpose.hashCode()
    result = 31 * result + key.publicKey.contentHashCode()
    return result
}

private fun bootstrapPayloadDeviceHashCode(device: DeviceIdentityRecord): Int {
    var result = device.deviceId.hashCode()
    result = 31 * result + identityPublicKeyHashCode(device.signing)
    result = 31 * result + identityPublicKeyHashCode(device.encryption)
    result = 31 * result + signedPreKeyHashCode(device.signedPreKey)
    result = 31 * result + (device.keySignature?.contentHashCode() ?: 0)
    return result
}

private fun signedPreKeyHashCode(spk: SignedPreKeyRecord?): Int {
    if (spk == null) return 0
    var result = spk.keyId.hashCode()
    result = 31 * result + spk.publicKey.contentHashCode()
    result = 31 * result + spk.signature.contentHashCode()
    result = 31 * result + (spk.privateKey?.contentHashCode() ?: 0)
    result = 31 * result + spk.deviceId.hashCode()
    result = 31 * result + spk.isActive.hashCode()
    result = 31 * result + (spk.createdAt?.hashCode() ?: 0)
    return result
}