package org.yapyap.protection.envelope

import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.protection.AuthenticationReason
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BootstrapEnvelope
import org.yapyap.protocol.envelopes.BootstrapIntroPayload
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Source of the active one-time bootstrap secret. Returns null when no onboarding session is active —
 * both the newcomer-side gate (a packet aimed at a node that isn't on-boarding) and the sponsor-side
 * guard (no scanned QR in flight) fall out of that.
 */
fun interface BootstrapKeySource {
    suspend fun introKey(): ByteArray?
}

/**
 * Out-of-band bootstrap envelope protection: AEAD under `HKDF(QR shared secret)`.
 *
 * Deliberately NOT a mirror of the other protections: it is a *preshared-key* primitive, not an
 * identity-backed scheme — it resolves no keys from the DB (no [org.yapyap.crypto.signature.SignatureProvider],
 * no session manager), extends no [BaseProtection], and is not part of the [org.yapyap.protocol.SignalSecurityScheme]
 * set. The key comes from [BootstrapKeySource] (the orchestrator's onboarding session store), never
 * as a caller-passed secret.
 *
 * The cipher is ChaCha20-Poly1305 with a library-managed IV embedded in the output; the envelope
 * header (id/source/target/createdAt) is bound as AEAD AAD.
 */
class BootstrapIntroProtection(
    private val crypto: CryptoProvider,
    private val keySource: BootstrapKeySource,
) {

    /** Domain-separated AEAD key for an intro. Deterministic per secret. */
    suspend fun deriveIntroKey(sharedSecret: ByteArray): ByteArray =
        crypto.hkdf(
            ikm = sharedSecret,
            salt = null,
            info = INTRO_INFO,
            outputLength = INTRO_KEY_SIZE_BYTES,
        )

    suspend fun protectIntro(
        payload: BootstrapIntroPayload,
        source: PeerId,
        target: PeerId,
        createdAt: Instant,
    ): BootstrapEnvelope {
        val introKey = activeIntroKey()
        val envelope = BootstrapEnvelope(
            bootstrapEnvelopeId = Uuid.random(),
            source = source,
            target = target,
            createdAt = createdAt,
            payload = payload.encode(),
        )
        val ciphertext = crypto.encryptAead(introKey, envelope.payload, envelope.aadBytes())
        return envelope.copy(payload = ciphertext)
    }

    suspend fun openIntro(envelope: BootstrapEnvelope): BootstrapIntroPayload {
        val introKey = activeIntroKey()
        val plaintext = try {
            crypto.decryptAead(introKey, envelope.payload, envelope.aadBytes())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ProtectionException.AuthenticationFailed(AuthenticationReason.DECRYPT_AUTH_FAILED, e)
        }
        return try {
            BootstrapIntroPayload.decode(plaintext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ProtectionException.InvalidEnvelope(e)
        }
    }

    private suspend fun activeIntroKey(): ByteArray {
        val secret = keySource.introKey() ?: throw ProtectionException.BootstrapSessionInactive()
        return deriveIntroKey(secret)
    }

    companion object {
        private const val INTRO_KEY_SIZE_BYTES = 32
        private val INTRO_INFO = "yapyap-bootstrap-intro-v1".encodeToByteArray()
    }
}