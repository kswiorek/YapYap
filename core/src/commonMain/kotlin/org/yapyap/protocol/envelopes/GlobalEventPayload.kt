package org.yapyap.protocol.envelopes

import org.yapyap.crypto.identity.AccountId
import org.yapyap.persistence.db.DeviceType
import org.yapyap.persistence.db.MessagePayloadType
import org.yapyap.protocol.ByteReader
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TorEndpoint

/**
 * Typed global control event carried inside [MessagePayload.GlobalEvent.eventBytes].
 *
 * Two-level dispatch mirrors BinaryEnvelope → BootstrapEnvelope → BootstrapPayload:
 * [MessagePayloadType.GLOBAL_EVENT] selects the DAG node type, [GlobalEventKind] selects the event.
 *
 * Signature layering (see docs/global events.md §1.5) — three signatures, three jobs:
 *  - node-level [MessagePayload.authorSignature]: INSERTION authorization (the author device must
 *    be known + ACTIVE in fold state at that position);
 *  - [AddDevice.keySignature]: BINDING authorization (the target account consents to the device
 *    keys), produced over [accountSignedDeviceBindingBytes];
 *  - envelope-level signature: transport integrity only.
 * The node header's `senderAccountId`/`authorDeviceId` identify the INSERTER; [AddDevice.accountId]
 * identifies the TARGET account. Never conflate them.
 */
sealed interface GlobalEventPayload {
    val kind: GlobalEventKind

    fun encode(): ByteArray

    /**
     * Publishes an account (its signing pub key + display name) to the mesh. An account with no
     * devices is inert — it becomes operational only when a paired [AddDevice] lands.
     *
     * The genesis variant is structural, not a flag: the AddAccount whose DAG node has
     * `prevId == null` is admin by definition (§3) and its pub key is the network's trust root.
     *
     * [accountId] is derivable from [accountSigningPublicKey] (`accountIdFromPublicKey`); it is
     * carried explicitly for decode-time readability and asserted by the fold.
     */
    data class AddAccount(
        val accountId: AccountId,
        val accountSigningPublicKey: ByteArray,
        val displayName: String,
    ) : GlobalEventPayload {
        override val kind: GlobalEventKind = GlobalEventKind.ADD_ACCOUNT

        init {
            require(accountSigningPublicKey.isNotEmpty()) { "accountSigningPublicKey must not be empty" }
            require(displayName.isNotEmpty()) { "displayName must not be empty" }
        }

        override fun encode(): ByteArray {
            val writer = ByteWriter(64 + displayName.length)
            writer.writeByte(kind.wireValue.toInt())
            writer.writeString(accountId.id)
            writer.writeByteArray(accountSigningPublicKey)
            writer.writeString(displayName)
            return writer.toByteArray()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as AddAccount
            return kind == other.kind &&
                    accountId == other.accountId &&
                    accountSigningPublicKey.contentEquals(other.accountSigningPublicKey) &&
                    displayName == other.displayName
        }

        override fun hashCode(): Int {
            var result = kind.hashCode()
            result = 31 * result + accountId.hashCode()
            result = 31 * result + accountSigningPublicKey.contentHashCode()
            result = 31 * result + displayName.hashCode()
            return result
        }

        companion object {
            fun decode(bytes: ByteArray): AddAccount {
                val reader = ByteReader(bytes)
                require(GlobalEventKind.fromWireValue(reader.readByte()) == GlobalEventKind.ADD_ACCOUNT) {
                    "Expected ADD_ACCOUNT event kind"
                }
                val accountId = AccountId(reader.readString())
                val accountSigningPublicKey = reader.readByteArray()
                val displayName = reader.readString()
                reader.requireFullyRead()
                return AddAccount(accountId, accountSigningPublicKey, displayName)
            }
        }
    }

    /**
     * Binds a device to [accountId] (the TARGET account — the inserter is in the node header).
     * Valid iff (fold state at that position):
     *  1. the inserter's account == [accountId] (own-device add; [keySignature] is absent — a
     *     sibling device does not hold the account private key), OR
     *  2. [accountId] was absent before an AddAccount([accountId]) by the same inserter earlier
     *     in canonical order, AND [keySignature] verifies under that AddAccount's pub key
     *     (new-account onboarding), OR
     *  3. [accountId] exists and is ACTIVE, AND [keySignature] verifies under its pub key
     *     (recovery relay; also covers the genesis self-introduction where the inserter IS the
     *     added device and its key resolves from this very event).
     */
    data class AddDevice(
        val accountId: AccountId,
        val deviceId: PeerId,
        val signingPublicKey: ByteArray,
        val encryptionPublicKey: ByteArray,
        val torEndpoint: TorEndpoint,
        val deviceType: DeviceType,
        /** Target account key over [accountSignedDeviceBindingBytes] — the binding consent.
         *  Absent only for branch-1 own-device adds (see above). */
        val keySignature: ByteArray?,
    ) : GlobalEventPayload {
        override val kind: GlobalEventKind = GlobalEventKind.ADD_DEVICE

        init {
            require(signingPublicKey.isNotEmpty()) { "signingPublicKey must not be empty" }
            require(encryptionPublicKey.isNotEmpty()) { "encryptionPublicKey must not be empty" }
            require(torEndpoint.onionAddress.isNotBlank()) { "torEndpoint.onionAddress must not be blank" }
            if (keySignature != null) {
                require(keySignature.isNotEmpty()) { "keySignature must not be empty" }
            }
        }

        /**
         * Canonical bytes the fold recomputes and verifies [keySignature] against.
         * Only valid for branches 2/3, which always carry a signature.
         */
        fun bindingBytes(): ByteArray = accountSignedDeviceBindingBytes(
            accountId = accountId,
            deviceId = deviceId,
            signingPublicKey = signingPublicKey,
            encryptionPublicKey = encryptionPublicKey,
            torEndpoint = torEndpoint,
            deviceType = deviceType,
        )

        override fun encode(): ByteArray {
            val writer = ByteWriter(128 + torEndpoint.onionAddress.length)
            writer.writeByte(kind.wireValue.toInt())
            writer.writeString(accountId.id)
            writer.writePeerId(deviceId)
            writer.writeByteArray(signingPublicKey)
            writer.writeByteArray(encryptionPublicKey)
            writer.writeString(torEndpoint.onionAddress)
            writer.writeInt(torEndpoint.port)
            writer.writeByte(deviceType.ordinal)
            writer.writeNullableByteArray(keySignature)
            return writer.toByteArray()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as AddDevice
            return kind == other.kind &&
                    accountId == other.accountId &&
                    deviceId == other.deviceId &&
                    signingPublicKey.contentEquals(other.signingPublicKey) &&
                    encryptionPublicKey.contentEquals(other.encryptionPublicKey) &&
                    torEndpoint == other.torEndpoint &&
                    deviceType == other.deviceType &&
                    keySignature.contentEquals(other.keySignature)
        }

        override fun hashCode(): Int {
            var result = kind.hashCode()
            result = 31 * result + accountId.hashCode()
            result = 31 * result + deviceId.hashCode()
            result = 31 * result + signingPublicKey.contentHashCode()
            result = 31 * result + encryptionPublicKey.contentHashCode()
            result = 31 * result + torEndpoint.hashCode()
            result = 31 * result + deviceType.hashCode()
            result = 31 * result + keySignature.contentHashCode()
            return result
        }

        companion object {
            fun decode(bytes: ByteArray): AddDevice {
                val reader = ByteReader(bytes)
                require(GlobalEventKind.fromWireValue(reader.readByte()) == GlobalEventKind.ADD_DEVICE) {
                    "Expected ADD_DEVICE event kind"
                }
                val accountId = AccountId(reader.readString())
                val deviceId = reader.readPeerId()
                val signingPublicKey = reader.readByteArray()
                val encryptionPublicKey = reader.readByteArray()
                val onionAddress = reader.readString()
                val port = reader.readInt()
                val deviceTypeOrdinal = reader.readUnsignedByte()
                val deviceType = DeviceType.entries.getOrNull(deviceTypeOrdinal)
                    ?: error("Unsupported device type wire value: $deviceTypeOrdinal")
                val keySignature = reader.readNullableByteArray()
                reader.requireFullyRead()
                return AddDevice(
                    accountId = accountId,
                    deviceId = deviceId,
                    signingPublicKey = signingPublicKey,
                    encryptionPublicKey = encryptionPublicKey,
                    torEndpoint = TorEndpoint(onionAddress, port),
                    deviceType = deviceType,
                    keySignature = keySignature,
                )
            }
        }
    }

    /**
     * Grants admin to [targetAccountId]. Valid iff the inserter's account `is_admin` at that fold
     * position. Admin status is derived from the log — never a field of [AddAccount].
     */
    data class GrantAdmin(val targetAccountId: AccountId) : GlobalEventPayload {
        override val kind: GlobalEventKind = GlobalEventKind.GRANT_ADMIN

        override fun encode(): ByteArray {
            val writer = ByteWriter(64)
            writer.writeByte(kind.wireValue.toInt())
            writer.writeString(targetAccountId.id)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): GrantAdmin {
                val reader = ByteReader(bytes)
                require(GlobalEventKind.fromWireValue(reader.readByte()) == GlobalEventKind.GRANT_ADMIN) {
                    "Expected GRANT_ADMIN event kind"
                }
                val targetAccountId = AccountId(reader.readString())
                reader.requireFullyRead()
                return GrantAdmin(targetAccountId)
            }
        }
    }

    /** Revokes admin from [targetAccountId]; same authorization as [GrantAdmin]. */
    data class RemoveAdmin(val targetAccountId: AccountId) : GlobalEventPayload {
        override val kind: GlobalEventKind = GlobalEventKind.REMOVE_ADMIN

        override fun encode(): ByteArray {
            val writer = ByteWriter(64)
            writer.writeByte(kind.wireValue.toInt())
            writer.writeString(targetAccountId.id)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): RemoveAdmin {
                val reader = ByteReader(bytes)
                require(GlobalEventKind.fromWireValue(reader.readByte()) == GlobalEventKind.REMOVE_ADMIN) {
                    "Expected REMOVE_ADMIN event kind"
                }
                val targetAccountId = AccountId(reader.readString())
                reader.requireFullyRead()
                return RemoveAdmin(targetAccountId)
            }
        }
    }

    /**
     * Removes [targetAccountId] and all its devices (status flip to tombstone, never row
     * deletion — the keys must stay resolvable for historical verification). Valid iff the
     * inserter's account `is_admin` at that fold position, OR the inserter belongs to
     * [targetAccountId] (own-account removal; non-admins can remove their own account).
     */
    data class RemoveAccount(val targetAccountId: AccountId) : GlobalEventPayload {
        override val kind: GlobalEventKind = GlobalEventKind.REMOVE_ACCOUNT

        override fun encode(): ByteArray {
            val writer = ByteWriter(64)
            writer.writeByte(kind.wireValue.toInt())
            writer.writeString(targetAccountId.id)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): RemoveAccount {
                val reader = ByteReader(bytes)
                require(GlobalEventKind.fromWireValue(reader.readByte()) == GlobalEventKind.REMOVE_ACCOUNT) {
                    "Expected REMOVE_ACCOUNT event kind"
                }
                val targetAccountId = AccountId(reader.readString())
                reader.requireFullyRead()
                return RemoveAccount(targetAccountId)
            }
        }
    }

    /**
     * Removes [targetDeviceId] (status flip to tombstone). Valid iff the inserter's account
     * `is_admin` at that fold position, OR the inserter belongs to the target device's account.
     * Re-adding after removal requires a fresh key set (new device id) — removal is a ban.
     */
    data class RemoveDevice(val targetDeviceId: PeerId) : GlobalEventPayload {
        override val kind: GlobalEventKind = GlobalEventKind.REMOVE_DEVICE

        override fun encode(): ByteArray {
            val writer = ByteWriter(1 + 66)
            writer.writeByte(kind.wireValue.toInt())
            writer.writePeerId(targetDeviceId)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): RemoveDevice {
                val reader = ByteReader(bytes)
                require(GlobalEventKind.fromWireValue(reader.readByte()) == GlobalEventKind.REMOVE_DEVICE) {
                    "Expected REMOVE_DEVICE event kind"
                }
                val targetDeviceId = reader.readPeerId()
                reader.requireFullyRead()
                return RemoveDevice(targetDeviceId)
            }
        }
    }

    companion object {
        fun decode(bytes: ByteArray): GlobalEventPayload {
            val kind = GlobalEventKind.fromWireValue(ByteReader(bytes).readByte())
            return when (kind) {
                GlobalEventKind.ADD_ACCOUNT -> AddAccount.decode(bytes)
                GlobalEventKind.ADD_DEVICE -> AddDevice.decode(bytes)
                GlobalEventKind.GRANT_ADMIN -> GrantAdmin.decode(bytes)
                GlobalEventKind.REMOVE_ADMIN -> RemoveAdmin.decode(bytes)
                GlobalEventKind.REMOVE_ACCOUNT -> RemoveAccount.decode(bytes)
                GlobalEventKind.REMOVE_DEVICE -> RemoveDevice.decode(bytes)
            }
        }
    }
}

enum class GlobalEventKind(val wireValue: Byte) {
    ADD_ACCOUNT(1),
    ADD_DEVICE(2),
    GRANT_ADMIN(3),
    REMOVE_ADMIN(4),
    REMOVE_ACCOUNT(5),
    REMOVE_DEVICE(6);

    companion object {
        fun fromWireValue(value: Byte): GlobalEventKind =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported global event kind wire value: $value")
    }
}

/**
 * Canonical account→device binding bytes — ONE form, THREE consumers: the newcomer's QR invite
 * ([Invite.accountKeySignature]), the recovering device's [RecoveryRequest.accountSignature], and
 * the fold's re-verification from [GlobalEventPayload.AddDevice] fields alone.
 *
 * Covers the full binding INCLUDING the onion endpoint: a signature omitting the endpoint would
 * let anyone capturing a plaintext recovery request relay the AddDevice with a substituted onion.
 * [DEVICE_BINDING_MAGIC] domain-separates the bytes so an account signature can never be replayed
 * as a different signed structure.
 *
 * Deliberately carries no key ids/versions — those are locally-minted DB bookkeeping, not
 * identity (§1: events carry only keys and bindings that cannot be derived).
 */
fun accountSignedDeviceBindingBytes(
    accountId: AccountId,
    deviceId: PeerId,
    signingPublicKey: ByteArray,
    encryptionPublicKey: ByteArray,
    torEndpoint: TorEndpoint,
    deviceType: DeviceType,
): ByteArray {
    val writer = ByteWriter(128 + torEndpoint.onionAddress.length)
    writer.writeBytes(DEVICE_BINDING_MAGIC)
    writer.writeString(accountId.id)
    writer.writePeerId(deviceId)
    writer.writeByteArray(signingPublicKey)
    writer.writeByteArray(encryptionPublicKey)
    writer.writeString(torEndpoint.onionAddress)
    writer.writeInt(torEndpoint.port)
    writer.writeByte(deviceType.ordinal)
    return writer.toByteArray()
}

private val DEVICE_BINDING_MAGIC =
    byteArrayOf('Y'.code.toByte(), 'S'.code.toByte(), 'D'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
