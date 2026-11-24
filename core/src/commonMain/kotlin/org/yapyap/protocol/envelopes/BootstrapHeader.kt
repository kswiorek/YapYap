package org.yapyap.protocol.envelopes

import org.yapyap.protocol.AccountId
import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.DeviceType
import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.identity.AccountIdentity
import org.yapyap.protocol.identity.DeviceIdentity
import org.yapyap.protocol.identity.EncryptionPublicKey
import org.yapyap.protocol.identity.SignedPreKey
import org.yapyap.protocol.identity.SignedPreKeyId
import org.yapyap.protocol.identity.SigningPublicKey
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The BOOTSTRAP family's routing header: how the content is protected ([scheme]) and the packet's
 * own id. The bootstrap content is the one family content a receiver may have to open before it
 * knows the sender's key (a newcomer, a recovering device), so it carries its own authentication:
 *
 * - SECRET_AEAD (INTRO): the content is AEAD ciphertext of an encoded [BootstrapPayload], keyed from
 *   the one-time shared secret carried in the newcomer's QR / recovery request (onboarding design
 *   doc, §8). This header plus the hop's source and target are bound as the AEAD AAD, so the packet
 *   cannot be re-targeted or replayed against another device.
 * - ACCOUNT_SIGNED (RECOVERY_REQUEST): the content is the plaintext payload, authenticated by the
 *   account-key signature inside it.
 *
 * [scheme] must be readable before the content is opened (an INTRO's kind byte is ciphertext), so
 * it lives here, never inside the payload. The INVITE ([BootstrapPayloadKind.INVITE]) is an
 * out-of-band QR/CLI artifact and never travels in an envelope.
 */
internal data class BootstrapHeader(
    val scheme: BootstrapSecurityScheme,
    val bootstrapEnvelopeId: Uuid,
) {
    fun encode(): ByteArray {
        val writer = ByteWriter(1 + Uuid.SIZE_BYTES)
        writer.writeByte(scheme.wireValue)
        writer.writeUuid(bootstrapEnvelopeId)
        return writer.toByteArray()
    }

    fun observableValues(): Map<String, Any?> = mapOf(
        Fields.SCHEME to scheme,
        Fields.BOOTSTRAP_ENVELOPE_ID to bootstrapEnvelopeId,
    )

    companion object {
        object Fields {
            const val SCHEME = "scheme"
            const val BOOTSTRAP_ENVELOPE_ID = "bootstrapEnvelopeId"
        }

        fun decode(bytes: ByteArray): BootstrapHeader {
            val reader = ByteReader(bytes)
            val header = BootstrapHeader(
                scheme = BootstrapSecurityScheme.fromWireValue(reader.readByte()),
                bootstrapEnvelopeId = reader.readUuid(),
            )
            reader.requireFullyRead()
            return header
        }
    }
}

/**
 * How a bootstrap envelope is protected. The payload kind lives *inside* the (opened) payload — this
 * header discriminator only tells the handler how to open it, mirroring
 * the scheme of the other families' content.
 */
internal enum class BootstrapSecurityScheme(val wireValue: Byte) {
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

internal enum class BootstrapPayloadKind(val wireValue: Byte) {
    /**
     * Sponsor → newcomer. Delivers the sponsor's identity (account + device keys, onion, DAG head)
     * to a newcomer before any DB rows exist. AEAD-encrypted in the bootstrap content, keyed from
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

    internal companion object {
        fun fromWireValue(value: Byte): BootstrapPayloadKind =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported bootstrap payload kind wire value: $value")
    }
}

/**
 * Bootstrap payload, one subtype per handshake direction (mirrors the [DagPayload] sealed shape —
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
internal sealed interface BootstrapPayload {
    val version: Int
    val kind: BootstrapPayloadKind
    val account: AccountIdentity?
    val device: DeviceIdentity
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
internal data class Intro(
    override val version: Int = 1,
    override val account: AccountIdentity,
    override val device: DeviceIdentity,
    override val deviceType: DeviceType,
    override val torEndpoint: TorEndpoint,
    /** The sponsor's chainable frontier of the global room at reply time. */
    val dagHeadTipIds: List<Uuid>,
) : BootstrapPayload {
    override val kind: BootstrapPayloadKind = BootstrapPayloadKind.INTRO

    init {
        require(version in 0..255) { "version must be in 0..255" }
    }

    override fun encode(): ByteArray {
        val writer = ByteWriter(256)
        writer.writeBootstrapPayloadPrefix(kind, version, account, device, deviceType, torEndpoint)
        writer.writeInt(dagHeadTipIds.size)
        dagHeadTipIds.forEach { writer.writeUuid(it) }
        return writer.toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        other as Intro
        return version == other.version &&
                dagHeadTipIds == other.dagHeadTipIds &&
                deviceType == other.deviceType &&
                torEndpoint == other.torEndpoint &&
                account == other.account &&
                device == other.device
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + dagHeadTipIds.hashCode()
        result = 31 * result + deviceType.hashCode()
        result = 31 * result + torEndpoint.onionAddress.hashCode()
        result = 31 * result + torEndpoint.port
        result = 31 * result + account.hashCode()
        result = 31 * result + device.hashCode()
        return result
    }

    companion object {
        fun decode(reader: ByteReader): Intro {
            val prefix = reader.readBootstrapPayloadPrefix()
            val dagHeadTipIds = List(reader.readInt()) { reader.readUuid() }
            reader.requireFullyRead()
            return Intro(
                version = prefix.version,
                account = prefix.account ?: error("INTRO requires the sponsor's account"),
                device = prefix.device,
                deviceType = prefix.deviceType,
                torEndpoint = prefix.torEndpoint,
                dagHeadTipIds = dagHeadTipIds,
            )
        }
    }
}

/** Newcomer → sponsor (out-of-band QR/CLI only): the newcomer's identity + one-time shared secret. */
internal class Invite(
    override val version: Int = 1,
    override val account: AccountIdentity?,
    override val device: DeviceIdentity,
    override val deviceType: DeviceType,
    override val torEndpoint: TorEndpoint,
    internal val sharedSecret: ByteArray,
    /**
     * The newcomer's account key over [accountSignedDeviceBindingBytes] (the device binding
     * consent). Required when [account] != null — the sponsor relays it as the back-to-back
     * AddDevice's `key_signature`. Null for the add-device-to-sponsor's-own-account case, where
     * the sponsor's own authorship (branch 1) is the authorization and no account key of the
     * newcomer exists.
     */
    internal val accountKeySignature: ByteArray? = null,
) : BootstrapPayload {
    override val kind: BootstrapPayloadKind = BootstrapPayloadKind.INVITE

    init {
        require(version in 0..255) { "version must be in 0..255" }
        require(sharedSecret.isNotEmpty()) { "INVITE requires a non-empty sharedSecret" }
        require((account == null) == (accountKeySignature == null)) {
            "INVITE requires accountKeySignature exactly when it carries a new account"
        }
        if (accountKeySignature != null) {
            require(accountKeySignature.isNotEmpty()) { "INVITE accountKeySignature must not be empty" }
        }
    }

    /** Canonical binding bytes this invite's [accountKeySignature] covers (new-account only). */
    internal fun accountSignedDeviceBindingBytes(): ByteArray {
        requireNotNull(account) { "INVITE account binding requires a new account" }
        return accountSignedDeviceBindingBytes(
            accountId = account.accountId,
            deviceId = device.deviceId,
            signingPublicKey = device.signingKey.bytes,
            encryptionPublicKey = device.encryptionKey.bytes,
            torEndpoint = torEndpoint,
            deviceType = deviceType,
        )
    }

    override fun encode(): ByteArray {
        val writer = ByteWriter(256)
        writer.writeBootstrapPayloadPrefix(kind, version, account, device, deviceType, torEndpoint)
        writer.writeNullableByteArray(sharedSecret)
        writer.writeNullableByteArray(accountKeySignature)
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
                accountKeySignature.contentEquals(other.accountKeySignature) &&
                account == other.account &&
                device == other.device
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + deviceType.hashCode()
        result = 31 * result + torEndpoint.onionAddress.hashCode()
        result = 31 * result + torEndpoint.port
        result = 31 * result + sharedSecret.contentHashCode()
        result = 31 * result + (accountKeySignature?.contentHashCode() ?: 0)
        result = 31 * result + account.hashCode()
        result = 31 * result + device.hashCode()
        return result
    }

    internal companion object {
        /**
         * Decode scanned invite bytes (the exact sponsor path:
         * `SetupResult.inviteBytes` → [Invite.encode] → paste/QR → here).
         * Routes through [BootstrapPayload.decode] so the leading kind byte is consumed
         * and validated — decoding the prefix directly would misread the kind as the version.
         * Throws on malformed input (callers map to MalformedInvite).
         */
        internal fun decode(bytes: ByteArray): Invite {
            val payload = BootstrapPayload.decode(bytes)
            require(payload is Invite) { "Expected INVITE payload, got ${payload.kind}" }
            return payload
        }

        internal fun decode(reader: ByteReader): Invite {
            val prefix = reader.readBootstrapPayloadPrefix()
            val sharedSecret = reader.readNullableByteArray()
                ?: error("INVITE requires a sharedSecret")
            val accountKeySignature = reader.readNullableByteArray()
            reader.requireFullyRead()
            return Invite(
                version = prefix.version,
                account = prefix.account,
                device = prefix.device,
                deviceType = prefix.deviceType,
                torEndpoint = prefix.torEndpoint,
                sharedSecret = sharedSecret,
                accountKeySignature = accountKeySignature,
            )
        }
    }
}

/** Recovering device → mesh node (account-recovery over the network): account-signed device binding. */
internal data class RecoveryRequest(
    override val version: Int = 1,
    override val account: AccountIdentity,
    override val device: DeviceIdentity,
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
        require(account.signingKey != null) { "RECOVERY_REQUEST requires the account record with its public key" }
    }

    /**
     * Canonical bytes the account key signs to bind [device] to the account — the recovery path's
     * possession proof at the responder *and* the `key_signature` the responder relays into the
     * AddDevice event, re-verifiable by every node's fold from the event fields alone. One shared
     * form with [Invite.accountSignedDeviceBindingBytes] (QR onboarding) and
     * [GlobalEventPayload.AddDevice.bindingBytes] (the fold's re-verification).
     */
    fun accountSignedDeviceBindingBytes(): ByteArray =
        accountSignedDeviceBindingBytes(
            accountId = account.accountId,
            deviceId = device.deviceId,
            signingPublicKey = device.signingKey.bytes,
            encryptionPublicKey = device.encryptionKey.bytes,
            torEndpoint = torEndpoint,
            deviceType = deviceType,
        )

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
                account == other.account &&
                device == other.device
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + deviceType.hashCode()
        result = 31 * result + torEndpoint.onionAddress.hashCode()
        result = 31 * result + torEndpoint.port
        result = 31 * result + sharedSecret.contentHashCode()
        result = 31 * result + accountSignature.contentHashCode()
        result = 31 * result + account.hashCode()
        result = 31 * result + device.hashCode()
        return result
    }

    companion object {
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

private fun ByteWriter.writeAccountIdentity(account: AccountIdentity) {
    writeString(account.accountId.id)
    writeString(account.displayName)
    if (account.signingKey == null) {
        writeByte(0)
    } else {
        writeByte(1)
        writeKey(SIGNING_KEY_TAG, account.signingKey.bytes)
    }
}

private fun ByteReader.readAccountIdentity(): AccountIdentity {
    val accountId = AccountId(readString())
    val displayName = readString()
    val signingKey = when (val marker = readUnsignedByte()) {
        0 -> null
        1 -> SigningPublicKey(readKey(SIGNING_KEY_TAG))
        else -> error("invalid nullable identity public key marker: $marker")
    }
    return AccountIdentity(accountId, displayName, signingKey)
}

private fun ByteWriter.writeNullableAccountIdentity(account: AccountIdentity?) {
    if (account == null) {
        writeByte(0)
    } else {
        writeByte(1)
        writeAccountIdentity(account)
    }
}

private fun ByteReader.readNullableAccountIdentity(): AccountIdentity? =
    when (val marker = readUnsignedByte()) {
        0 -> null
        1 -> readAccountIdentity()
        else -> error("invalid nullable account identity marker: $marker")
    }

/* ------------------------------ shared payload prefix codec ------------------------------ */

private const val PAYLOAD_VERSION: Byte = 1

private data class BootstrapPayloadPrefix(
    val version: Int,
    val account: AccountIdentity?,
    val device: DeviceIdentity,
    val deviceType: DeviceType,
    val torEndpoint: TorEndpoint,
)

/** Writes the fields common to every [BootstrapPayload] subtype (kind byte + identity + onion). */
private fun ByteWriter.writeBootstrapPayloadPrefix(
    kind: BootstrapPayloadKind,
    version: Int,
    account: AccountIdentity?,
    device: DeviceIdentity,
    deviceType: DeviceType,
    torEndpoint: TorEndpoint,
) {
    writeByte(kind.wireValue)
    writeByte(version)
    writeNullableAccountIdentity(account)
    writeDeviceIdentity(device)
    writeByte(deviceType.wireValue)
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
    val deviceType = DeviceType.fromWireValue(readByte())
    val onionAddress = readString()
    val port = readInt()
    return BootstrapPayloadPrefix(version, account, device, deviceType, TorEndpoint(onionAddress, port))
}

private fun ByteWriter.writeDeviceIdentity(device: DeviceIdentity) {
    writePeerId(device.deviceId)
    writeKey(SIGNING_KEY_TAG, device.signingKey.bytes)
    writeKey(ENCRYPTION_KEY_TAG, device.encryptionKey.bytes)
    writeNullableSignedPreKey(device.signedPreKey)
}

private fun ByteReader.readDeviceIdentity(): DeviceIdentity {
    val deviceId = readPeerId()
    val signingKey = SigningPublicKey(readKey(SIGNING_KEY_TAG))
    val encryptionKey = EncryptionPublicKey(readKey(ENCRYPTION_KEY_TAG))
    val signedPreKey = readNullableSignedPreKey()
    return DeviceIdentity(
        deviceId = deviceId,
        signingKey = signingKey,
        encryptionKey = encryptionKey,
        signedPreKey = signedPreKey,
    )
}

/** Identity keys are tagged on the wire (signing = 0, encryption = 1); a mismatched tag is a malformed payload. */
private const val SIGNING_KEY_TAG = 0
private const val ENCRYPTION_KEY_TAG = 1

private fun ByteWriter.writeKey(tag: Int, bytes: ByteArray) {
    writeByte(tag)
    writeByteArray(bytes)
}

private fun ByteReader.readKey(expectedTag: Int): ByteArray {
    val tag = readUnsignedByte()
    require(tag == expectedTag) { "Unexpected identity key tag: $tag (expected $expectedTag)" }
    return readByteArray()
}

/**
 * Public-only SPK serialization. The trailing active flag and creation time are kept for wire
 * compatibility: always written as active / unknown, ignored on read.
 */
private fun ByteWriter.writeSignedPreKey(spk: SignedPreKey) {
    writeByte(SIGNED_PREKEY_WIRE_VERSION.toInt())
    writeString(spk.id.value)
    writeByteArray(spk.publicKey.bytes)
    writeByteArray(spk.signature)
    writePeerId(spk.deviceId)
    writeByte(1)
    writeNullableEpochSeconds(null)
}

private fun ByteReader.readSignedPreKey(): SignedPreKey {
    val version = readByte().toInt() and 0xff
    require(version == SIGNED_PREKEY_WIRE_VERSION.toInt()) { "Unsupported signed prekey wire version: $version" }
    val id = SignedPreKeyId(readString())
    val publicKey = EncryptionPublicKey(readByteArray())
    val signature = readByteArray()
    val deviceId = readPeerId()
    readByte()
    readNullableInstant()
    return SignedPreKey(id = id, deviceId = deviceId, publicKey = publicKey, signature = signature)
}

private fun ByteWriter.writeNullableSignedPreKey(spk: SignedPreKey?) {
    if (spk == null) {
        writeByte(0)
    } else {
        writeByte(1)
        writeSignedPreKey(spk)
    }
}

private fun ByteReader.readNullableSignedPreKey(): SignedPreKey? =
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

