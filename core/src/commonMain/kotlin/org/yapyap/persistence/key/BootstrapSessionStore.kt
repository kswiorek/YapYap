package org.yapyap.persistence.key

import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import kotlin.time.Instant

/** Source of the active one-time bootstrap secret. Null when no session is active — the "not on-boarding" gate. */
fun interface BootstrapKeySource {
    suspend fun introKey(): ByteArray?
}

/** One-time secret plus its absolute expiry deadline (boot-anchored: restarts keep the remaining budget, never a fresh window). */
data class BootstrapSession(
    val secret: ByteArray,
    val deadline: Instant,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        other as BootstrapSession
        return deadline == other.deadline && secret.contentEquals(other.secret)
    }

    override fun hashCode(): Int = 31 * deadline.hashCode() + secret.contentHashCode()
}

/**
 * This node's own onboarding session (newcomer only). [BootstrapKeySource] for the inbound
 * AEAD gate; null reads as "not on-boarding".
 *
 * Dumb persistence: many readers, one writer (the provider). Uncached keyring reads, like the
 * signing path; secret and deadline share one entry ([burn] deletes both, no drift states).
 * Missing/unreadable reads as null (gate closed); corrupt entries are repaired away.
 */
class BootstrapSessionStore(
    private val keyStore: KeyStore,
) : BootstrapKeySource {
    override suspend fun introKey(): ByteArray? = session()?.secret

    /** The active session, or null when no onboarding is in flight. Corrupt entries are repaired. */
    suspend fun session(): BootstrapSession? {
        val raw = keyStore.getKey(SECRET_REF) ?: return null
        return decodeSession(raw) ?: run {
            AppLog.warn(
                component = LogComponent.DATABASE,
                event = LogEvent.ENVELOPE_DECODE_FAILED,
                message = "Dropped corrupt bootstrap session entry",
                fields = mapOf("size" to raw.size),
            )
            keyStore.deleteKey(SECRET_REF)
            null
        }
    }

    /**
     * Persist first: a rejected write propagates and no secret is held that wasn't durably kept.
     * @param deadline absolute expiry of the wait (the provider arms its timer against it).
     */
    suspend fun setActiveSecret(secret: ByteArray, deadline: Instant) {
        require(secret.isNotEmpty()) { "bootstrap secret must not be empty" }
        keyStore.putKey(SECRET_REF, encodeSession(secret, deadline))
    }

    /** Move the deadline (e.g. intro received → sync-phase budget). No-op without a session. */
    suspend fun extendDeadline(deadline: Instant) {
        val current = session() ?: return
        keyStore.putKey(SECRET_REF, encodeSession(current.secret, deadline))
    }

    /** Burn the secret (complete / timeout / cancel). */
    suspend fun burn() {
        keyStore.deleteKey(SECRET_REF)
    }

    companion object {
        private val SECRET_REF = KeyReference(
            keyId = "yapyap:bootstrap:secret",
            purpose = IdentityKeyPurpose.BOOTSTRAP_SECRET,
            type = KeyType.PRIVATE,
        )

        /** Magic + 8-byte big-endian deadline epoch-millis + raw secret. The magic fails stale bare-secret entries closed as absent. */
        private val MAGIC = byteArrayOf('Y'.code.toByte(), 'B'.code.toByte(), 'B'.code.toByte(), 'S'.code.toByte())
        private const val DEADLINE_PREFIX_BYTES = Long.SIZE_BYTES

        internal fun encodeSession(secret: ByteArray, deadline: Instant): ByteArray {
            val epochMillis = deadline.toEpochMilliseconds()
            val out = ByteArray(MAGIC.size + DEADLINE_PREFIX_BYTES + secret.size)
            MAGIC.copyInto(out)
            for (i in 0 until DEADLINE_PREFIX_BYTES) {
                out[MAGIC.size + i] = (epochMillis ushr ((DEADLINE_PREFIX_BYTES - 1 - i) * 8)).toByte()
            }
            secret.copyInto(out, destinationOffset = MAGIC.size + DEADLINE_PREFIX_BYTES)
            return out
        }

        internal fun decodeSession(raw: ByteArray): BootstrapSession? {
            if (raw.size <= MAGIC.size + DEADLINE_PREFIX_BYTES) return null
            if (!raw.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
            var epochMillis = 0L
            for (i in 0 until DEADLINE_PREFIX_BYTES) {
                epochMillis = (epochMillis shl 8) or (raw[MAGIC.size + i].toLong() and 0xFF)
            }
            val secret = raw.copyOfRange(MAGIC.size + DEADLINE_PREFIX_BYTES, raw.size)
            if (secret.isEmpty()) return null
            return BootstrapSession(secret, Instant.fromEpochMilliseconds(epochMillis))
        }
    }
}
