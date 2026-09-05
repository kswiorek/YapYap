package org.yapyap.protocol.envelopes

import org.yapyap.crypto.identity.*
import org.yapyap.persistence.db.DeviceType
import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Out-of-band bootstrap transport envelope: the single authenticated control message that delivers
 * the sponsor's identity (account + device keys, onion, DAG head) to a newcomer before any DB rows
 * exist. Nested inside a [BinaryEnvelope] with [org.yapyap.protocol.packet.PacketType.BOOTSTRAP] so
 * dedup, expiry, and target-check come from the existing inbound machinery.
 *
 * The [payload] is AEAD ciphertext of an encoded [BootstrapIntroPayload], keyed from the one-time
 * shared secret carried by the newcomer's QR code (see the onboarding design doc, §8). The header —
 * envelope id, source, target, createdAt — is bound into the AEAD AAD ([aadBytes]) so the header
 * cannot be swapped, re-targeted, or replayed against a different device. The cipher's IV is
 * embedded in the AEAD output (library-managed), so no separate nonce field is needed.
 */
data class BootstrapEnvelope @OptIn(ExperimentalUuidApi::class) constructor(
    val bootstrapEnvelopeId: Uuid,
    val source: PeerId,
    val target: PeerId,
    val createdAt: Instant,
    val payload: ByteArray,
) {
    fun encode(): ByteArray {
        val writer = ByteWriter(16 + payload.size)
        writeHeader(writer)
        writer.writeByteArray(payload)
        return writer.toByteArray()
    }

    /** Everything except the ciphertext, bound as AEAD AAD on open. */
    fun aadBytes(): ByteArray {
        val writer = ByteWriter(16)
        writeHeader(writer)
        return writer.toByteArray()
    }

    fun decodePayload(): BootstrapIntroPayload = BootstrapIntroPayload.decode(payload)

    private fun writeHeader(writer: ByteWriter) {
        writer.writeBytes(MAGIC)
        writer.writeByte(VERSION.toInt())
        writer.writeUuid(bootstrapEnvelopeId)
        writer.writePeerId(source)
        writer.writePeerId(target)
        writer.writeLong(createdAt.epochSeconds)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as BootstrapEnvelope
        return bootstrapEnvelopeId == other.bootstrapEnvelopeId &&
                source == other.source &&
                target == other.target &&
                createdAt == other.createdAt &&
                payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = bootstrapEnvelopeId.hashCode()
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
            val envelopeId = reader.readUuid()
            val source = reader.readPeerId()
            val target = reader.readPeerId()
            val createdAt = Instant.fromEpochSeconds(reader.readLong())
            val payload = reader.readByteArray()
            reader.requireFullyRead()
            return BootstrapEnvelope(envelopeId, source, target, createdAt, payload)
        }
    }
}

enum class BootstrapPayloadKind(val wireValue: Byte) {
    INTRO(1);

    companion object {
        fun fromWireValue(value: Byte): BootstrapPayloadKind =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported bootstrap payload kind wire value: $value")
    }
}

/**
 * Plaintext sponsor identity, AEAD-encrypted inside [BootstrapEnvelope.payload].
 *
 * Carries only public key material — the sponsor's private keys never leave the device.
 * The DAG head lets the newcomer immediately request the global room range up to it.
 */
data class BootstrapIntroPayload(
    val version: Int = 1,
    val account: AccountIdentityRecord,
    val device: DeviceIdentityRecord,
    val deviceType: DeviceType,
    val torEndpoint: TorEndpoint,
    val dagHeadMessageId: Uuid?,
    val dagHeadLamport: Long,
) {
    init {
        require(version in 0..255) { "version must be in 0..255" }
        require(dagHeadLamport >= 0) { "dagHeadLamport must be >= 0" }
    }

    fun encode(): ByteArray {
        val writer = ByteWriter(512)
        writer.writeByte(BootstrapPayloadKind.INTRO.wireValue.toInt())
        writer.writeByte(version)
        writer.writeAccountIdentity(account)
        writer.writeDeviceIdentity(device)
        writer.writeByte(deviceType.ordinal.toByte().toInt())
        writer.writeString(torEndpoint.onionAddress)
        writer.writeInt(torEndpoint.port)
        writer.writeNullableUuid(dagHeadMessageId)
        writer.writeLong(dagHeadLamport)
        return writer.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): BootstrapIntroPayload {
            val reader = ByteReader(bytes)
            require(BootstrapPayloadKind.fromWireValue(reader.readByte()) == BootstrapPayloadKind.INTRO) {
                "Expected INTRO bootstrap payload kind"
            }
            val version = reader.readByte().toInt() and 0xff
            require(version == 1) { "Unsupported bootstrap intro version: $version" }
            val account = reader.readAccountIdentity()
            val device = reader.readDeviceIdentity()
            val deviceTypeOrdinal = reader.readUnsignedByte()
            val deviceType = DeviceType.entries.getOrNull(deviceTypeOrdinal)
                ?: error("Unsupported device type wire value: $deviceTypeOrdinal")
            val onionAddress = reader.readString()
            val port = reader.readInt()
            val dagHeadMessageId = reader.readNullableUuid()
            val dagHeadLamport = reader.readLong()
            reader.requireFullyRead()
            return BootstrapIntroPayload(
                version = version,
                account = account,
                device = device,
                deviceType = deviceType,
                torEndpoint = TorEndpoint(onionAddress, port),
                dagHeadMessageId = dagHeadMessageId,
                dagHeadLamport = dagHeadLamport,
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