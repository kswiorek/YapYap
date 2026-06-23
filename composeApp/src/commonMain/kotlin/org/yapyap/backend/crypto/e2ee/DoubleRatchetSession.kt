package org.yapyap.backend.crypto.e2ee

import org.yapyap.backend.crypto.CryptoProvider
import org.yapyap.backend.crypto.KmpCryptoProvider

private const val KEY_SIZE = KmpCryptoProvider.AEAD_KEY_SIZE_BYTES
private const val MAX_SKIP = 256
private val ZERO_SALT = ByteArray(KEY_SIZE)

class DoubleRatchetSession private constructor(
    private val crypto: CryptoProvider,
    private var state: MutableRatchetState,
) {
    suspend fun encrypt(plaintext: ByteArray): RatchetCiphertext {
        val sendChainKey = state.sendChainKey ?: error("send chain is not initialized")
        val (nextChainKey, messageKey) = kdfChainKey(sendChainKey)
        state.sendChainKey = nextChainKey
        val messageNumber = state.sendMessageNumber
        state.sendMessageNumber = messageNumber + 1
        val body = crypto.encryptAead(messageKey, plaintext)
        return RatchetCiphertext(
            dhPublicKey = state.localDhPublicKey.copyOf(),
            messageNumber = messageNumber,
            previousChainLength = state.previousSendChainLength,
            body = body,
        )
    }

    suspend fun decrypt(frame: RatchetCiphertext): ByteArray {
        trySkippedMessageKey(frame)?.let { return crypto.decryptAead(it, frame.body) }

        if (state.remoteDhPublicKey == null || !state.remoteDhPublicKey.contentEquals(frame.dhPublicKey)) {
            skipMessageKeys(frame.previousChainLength)
            val remoteDhPublicKey = frame.dhPublicKey.copyOf()
            state.remoteDhPublicKey = remoteDhPublicKey
            val recvDh = crypto.deriveSharedSecret(state.localDhPrivateKey, remoteDhPublicKey)
            val (rootAfterRecv, recvChainKey) = kdfRootKey(state.rootKey, recvDh)
            state.rootKey = rootAfterRecv
            state.recvChainKey = recvChainKey
            state.recvMessageNumber = 0
            generateLocalDhKeyPair()
            val sendDh = crypto.deriveSharedSecret(state.localDhPrivateKey, remoteDhPublicKey)
            val (rootAfterSend, sendChainKey) = kdfRootKey(state.rootKey, sendDh)
            state.rootKey = rootAfterSend
            state.sendChainKey = sendChainKey
            state.previousSendChainLength = state.sendMessageNumber
            state.sendMessageNumber = 0
        }

        skipMessageKeys(frame.messageNumber)
        val recvChainKey = state.recvChainKey ?: error("receive chain is not initialized")
        val (nextChainKey, messageKey) = kdfChainKey(recvChainKey)
        state.recvChainKey = nextChainKey
        state.recvMessageNumber = state.recvMessageNumber + 1
        return crypto.decryptAead(messageKey, frame.body)
    }

    fun snapshot(): RatchetSessionState = state.toImmutable()

    companion object {
        suspend fun createInitiator(
            crypto: CryptoProvider,
            bootstrap: RatchetBootstrap,
        ): DoubleRatchetSession {
            val remoteDhPublicKey = bootstrap.remoteDhPublicKey
                ?: error("initiator bootstrap requires remoteDhPublicKey")
            val state = MutableRatchetState(
                rootKey = bootstrap.sharedSecret.copyOf(),
                sendChainKey = null,
                recvChainKey = null,
                sendMessageNumber = 0,
                recvMessageNumber = 0,
                previousSendChainLength = 0,
                localDhPrivateKey = ByteArray(0),
                localDhPublicKey = ByteArray(0),
                remoteDhPublicKey = remoteDhPublicKey.copyOf(),
                skippedMessageKeys = mutableMapOf(),
            )
            val session = DoubleRatchetSession(crypto, state)
            session.generateLocalDhKeyPair()
            val dhOutput = crypto.deriveSharedSecret(state.localDhPrivateKey, remoteDhPublicKey)
            val (rootKey, sendChainKey) = session.kdfRootKey(state.rootKey, dhOutput)
            state.rootKey = rootKey
            state.sendChainKey = sendChainKey
            return session
        }

        suspend fun createResponder(
            crypto: CryptoProvider,
            bootstrap: RatchetBootstrap,
        ): DoubleRatchetSession {
            val state = MutableRatchetState(
                rootKey = bootstrap.sharedSecret.copyOf(),
                sendChainKey = null,
                recvChainKey = null,
                sendMessageNumber = 0,
                recvMessageNumber = 0,
                previousSendChainLength = 0,
                localDhPrivateKey = ByteArray(0),
                localDhPublicKey = ByteArray(0),
                remoteDhPublicKey = bootstrap.remoteDhPublicKey?.copyOf(),
                skippedMessageKeys = mutableMapOf(),
            )
            val session = DoubleRatchetSession(crypto, state)
            val localPrivate = bootstrap.localDhPrivateKey
            val localPublic = bootstrap.localDhPublicKey
            if (localPrivate != null && localPublic != null) {
                state.localDhPrivateKey = localPrivate.copyOf()
                state.localDhPublicKey = localPublic.copyOf()
            } else {
                session.generateLocalDhKeyPair()
            }
            return session
        }

        fun fromState(
            crypto: CryptoProvider,
            state: RatchetSessionState,
        ): DoubleRatchetSession =
            DoubleRatchetSession(
                crypto = crypto,
                state = MutableRatchetState.fromImmutable(state),
            )
    }

    private suspend fun generateLocalDhKeyPair() {
        val generated = crypto.generateEncryptionKeyPair()
        state.localDhPrivateKey = generated.privateKey
        state.localDhPublicKey = generated.publicKey
    }

    private suspend fun kdfRootKey(rootKey: ByteArray, dhOutput: ByteArray): Pair<ByteArray, ByteArray> {
        val derived = crypto.hkdf(
            ikm = dhOutput,
            salt = rootKey,
            info = ROOT_KDF_INFO,
            outputLength = KEY_SIZE * 2,
        )
        return derived.copyOfRange(0, KEY_SIZE) to derived.copyOfRange(KEY_SIZE, KEY_SIZE * 2)
    }

    private suspend fun kdfChainKey(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
        val derived = crypto.hkdf(
            ikm = chainKey,
            salt = ZERO_SALT,
            info = CHAIN_KDF_INFO,
            outputLength = KEY_SIZE * 2,
        )
        return derived.copyOfRange(0, KEY_SIZE) to derived.copyOfRange(KEY_SIZE, KEY_SIZE * 2)
    }

    private suspend fun skipMessageKeys(until: Int) {
        if (until <= state.recvMessageNumber) return
        require(state.recvMessageNumber + MAX_SKIP >= until) {
            "too many skipped messages: current=${state.recvMessageNumber}, until=$until"
        }
        var recvChainKey = state.recvChainKey ?: error("receive chain is not initialized")
        while (state.recvMessageNumber < until) {
            val (nextChainKey, messageKey) = kdfChainKey(recvChainKey)
            recvChainKey = nextChainKey
            val remoteDh = state.remoteDhPublicKey ?: error("remote DH public key is not set")
            state.skippedMessageKeys[RatchetSkippedKeyId(remoteDh, state.recvMessageNumber)] = messageKey
            state.recvMessageNumber += 1
        }
        state.recvChainKey = recvChainKey
    }

    private fun trySkippedMessageKey(frame: RatchetCiphertext): ByteArray? {
        val remoteDh = state.remoteDhPublicKey ?: return null
        if (!remoteDh.contentEquals(frame.dhPublicKey)) return null
        return state.skippedMessageKeys.remove(RatchetSkippedKeyId(remoteDh, frame.messageNumber))
    }
}

data class RatchetCiphertext(
    val dhPublicKey: ByteArray,
    val messageNumber: Int,
    val previousChainLength: Int,
    val body: ByteArray,
) {
    fun encode(): ByteArray {
        val dhSize = dhPublicKey.size
        val bodySize = body.size
        val bytes = ByteArray(4 + dhSize + 4 + 4 + 4 + bodySize)
        var offset = 0
        writeInt(bytes, offset, dhSize)
        offset += 4
        dhPublicKey.copyInto(bytes, offset)
        offset += dhSize
        writeInt(bytes, offset, messageNumber)
        offset += 4
        writeInt(bytes, offset, previousChainLength)
        offset += 4
        writeInt(bytes, offset, bodySize)
        offset += 4
        body.copyInto(bytes, offset)
        return bytes
    }

    companion object {
        fun decode(bytes: ByteArray): RatchetCiphertext {
            var offset = 0
            val dhSize = readInt(bytes, offset)
            offset += 4
            val dhPublicKey = bytes.copyOfRange(offset, offset + dhSize)
            offset += dhSize
            val messageNumber = readInt(bytes, offset)
            offset += 4
            val previousChainLength = readInt(bytes, offset)
            offset += 4
            val bodySize = readInt(bytes, offset)
            offset += 4
            val body = bytes.copyOfRange(offset, offset + bodySize)
            require(offset + bodySize == bytes.size) { "trailing bytes in ratchet ciphertext" }
            return RatchetCiphertext(
                dhPublicKey = dhPublicKey,
                messageNumber = messageNumber,
                previousChainLength = previousChainLength,
                body = body,
            )
        }
    }
}

data class RatchetSessionState(
    val rootKey: ByteArray,
    val sendChainKey: ByteArray?,
    val recvChainKey: ByteArray?,
    val sendMessageNumber: Int,
    val recvMessageNumber: Int,
    val previousSendChainLength: Int,
    val localDhPrivateKey: ByteArray,
    val localDhPublicKey: ByteArray,
    val remoteDhPublicKey: ByteArray?,
    val skippedMessageKeys: Map<RatchetSkippedKeyId, ByteArray>,
)

data class RatchetSkippedKeyId(
    val dhPublicKey: ByteArray,
    val messageNumber: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RatchetSkippedKeyId) return false
        return messageNumber == other.messageNumber && dhPublicKey.contentEquals(other.dhPublicKey)
    }

    override fun hashCode(): Int {
        var result = messageNumber
        result = 31 * result + dhPublicKey.contentHashCode()
        return result
    }
}

private data class MutableRatchetState(
    var rootKey: ByteArray,
    var sendChainKey: ByteArray?,
    var recvChainKey: ByteArray?,
    var sendMessageNumber: Int,
    var recvMessageNumber: Int,
    var previousSendChainLength: Int,
    var localDhPrivateKey: ByteArray,
    var localDhPublicKey: ByteArray,
    var remoteDhPublicKey: ByteArray?,
    val skippedMessageKeys: MutableMap<RatchetSkippedKeyId, ByteArray>,
) {
    fun toImmutable(): RatchetSessionState =
        RatchetSessionState(
            rootKey = rootKey.copyOf(),
            sendChainKey = sendChainKey?.copyOf(),
            recvChainKey = recvChainKey?.copyOf(),
            sendMessageNumber = sendMessageNumber,
            recvMessageNumber = recvMessageNumber,
            previousSendChainLength = previousSendChainLength,
            localDhPrivateKey = localDhPrivateKey.copyOf(),
            localDhPublicKey = localDhPublicKey.copyOf(),
            remoteDhPublicKey = remoteDhPublicKey?.copyOf(),
            skippedMessageKeys = skippedMessageKeys.mapValues { (_, value) -> value.copyOf() },
        )

    companion object {
        fun fromImmutable(state: RatchetSessionState): MutableRatchetState =
            MutableRatchetState(
                rootKey = state.rootKey.copyOf(),
                sendChainKey = state.sendChainKey?.copyOf(),
                recvChainKey = state.recvChainKey?.copyOf(),
                sendMessageNumber = state.sendMessageNumber,
                recvMessageNumber = state.recvMessageNumber,
                previousSendChainLength = state.previousSendChainLength,
                localDhPrivateKey = state.localDhPrivateKey.copyOf(),
                localDhPublicKey = state.localDhPublicKey.copyOf(),
                remoteDhPublicKey = state.remoteDhPublicKey?.copyOf(),
                skippedMessageKeys = state.skippedMessageKeys
                    .mapValues { (_, value) -> value.copyOf() }
                    .toMutableMap(),
            )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as MutableRatchetState

        if (sendMessageNumber != other.sendMessageNumber) return false
        if (recvMessageNumber != other.recvMessageNumber) return false
        if (previousSendChainLength != other.previousSendChainLength) return false
        if (!rootKey.contentEquals(other.rootKey)) return false
        if (!sendChainKey.contentEquals(other.sendChainKey)) return false
        if (!recvChainKey.contentEquals(other.recvChainKey)) return false
        if (!localDhPrivateKey.contentEquals(other.localDhPrivateKey)) return false
        if (!localDhPublicKey.contentEquals(other.localDhPublicKey)) return false
        if (!remoteDhPublicKey.contentEquals(other.remoteDhPublicKey)) return false
        if (skippedMessageKeys != other.skippedMessageKeys) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sendMessageNumber
        result = 31 * result + recvMessageNumber
        result = 31 * result + previousSendChainLength
        result = 31 * result + rootKey.contentHashCode()
        result = 31 * result + (sendChainKey?.contentHashCode() ?: 0)
        result = 31 * result + (recvChainKey?.contentHashCode() ?: 0)
        result = 31 * result + localDhPrivateKey.contentHashCode()
        result = 31 * result + localDhPublicKey.contentHashCode()
        result = 31 * result + (remoteDhPublicKey?.contentHashCode() ?: 0)
        result = 31 * result + skippedMessageKeys.hashCode()
        return result
    }
}

private val ROOT_KDF_INFO = "YapYapDR_RK".encodeToByteArray()
private val CHAIN_KDF_INFO = "YapYapDR_CK".encodeToByteArray()

private fun writeInt(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value ushr 24).toByte()
    target[offset + 1] = (value ushr 16).toByte()
    target[offset + 2] = (value ushr 8).toByte()
    target[offset + 3] = value.toByte()
}

private fun readInt(bytes: ByteArray, offset: Int): Int {
    require(offset + 4 <= bytes.size) { "unexpected end of ratchet ciphertext" }
    return ((bytes[offset].toInt() and 0xff) shl 24) or
        ((bytes[offset + 1].toInt() and 0xff) shl 16) or
        ((bytes[offset + 2].toInt() and 0xff) shl 8) or
        (bytes[offset + 3].toInt() and 0xff)
}
