package org.yapyap.protection.envelope

import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.persistence.key.BootstrapKeySource
import org.yapyap.protection.AuthenticationReason
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Bootstrap-family envelope protection: [Intro] → SECRET_AEAD (AEAD under `HKDF(shared secret)`),
 * [RecoveryRequest] → ACCOUNT_SIGNED (plaintext + account-key signature over the device binding),
 * [Invite] never travels on the wire.
 *
 * Preshared-key primitive, not identity-backed: no DB lookups, no session manager. The key is
 * split by direction — [protect] takes the sender's in-memory secret explicitly (sponsor QR
 * scan / responder request copy, so neither role needs state), [open] resolves the newcomer's
 * persisted secret from [BootstrapKeySource].
 *
 * ChaCha20-Poly1305, library-managed IV; the header is bound as AEAD AAD ([aadBytes]).
 */
class BootstrapProtection(
    private val crypto: CryptoProvider,
    private val keySource: BootstrapKeySource,
) {

    /** Domain-separated AEAD key for an intro/reply. Deterministic per secret. */
    suspend fun deriveIntroKey(sharedSecret: ByteArray): ByteArray =
        crypto.hkdf(
            ikm = sharedSecret,
            salt = null,
            info = INTRO_INFO,
            outputLength = INTRO_KEY_SIZE_BYTES,
        )

    /** Protect by kind (AEAD for INTRO, plaintext for RECOVERY_REQUEST).
     * @param sharedSecret sender's in-memory secret, required for INTRO; unused for RECOVERY_REQUEST. */
    suspend fun protect(
        payload: BootstrapPayload,
        source: PeerId,
        target: PeerId,
        createdAt: Instant,
        sharedSecret: ByteArray? = null,
    ): BootstrapEnvelope = when (payload) {
        is Intro -> protectIntro(
            payload,
            source,
            target,
            createdAt,
            requireNotNull(sharedSecret) { "INTRO protection requires the shared secret" },
        )

        is RecoveryRequest -> BootstrapEnvelope(
            scheme = BootstrapSecurityScheme.ACCOUNT_SIGNED,
            bootstrapEnvelopeId = Uuid.random(),
            source = source,
            target = target,
            createdAt = createdAt,
            payload = payload.encode(),
        )

        is Invite -> error("The INVITE is an out-of-band QR/CLI payload and never travels in a bootstrap envelope")
    }

    /** Opens a bootstrap envelope by its protection scheme; returns the authenticated payload. */
    suspend fun open(envelope: BootstrapEnvelope): BootstrapPayload = when (envelope.scheme) {
        BootstrapSecurityScheme.SECRET_AEAD -> openIntro(envelope)
        BootstrapSecurityScheme.ACCOUNT_SIGNED -> openRecoveryRequest(envelope)
    }

    /** Protects an INTRO (or an AEAD-bound recovery reply) under the sender's in-memory secret. */
    private suspend fun protectIntro(
        payload: Intro,
        source: PeerId,
        target: PeerId,
        createdAt: Instant,
        sharedSecret: ByteArray,
    ): BootstrapEnvelope {
        val introKey = deriveIntroKey(sharedSecret)
        val envelope = BootstrapEnvelope(
            scheme = BootstrapSecurityScheme.SECRET_AEAD,
            bootstrapEnvelopeId = Uuid.random(),
            source = source,
            target = target,
            createdAt = createdAt,
            payload = payload.encode(),
        )
        val ciphertext = crypto.encryptAead(introKey, envelope.payload, envelope.aadBytes())
        return envelope.copy(payload = ciphertext)
    }

    private suspend fun openIntro(envelope: BootstrapEnvelope): Intro {
        val introKey = activeIntroKey()
        val plaintext = try {
            crypto.decryptAead(introKey, envelope.payload, envelope.aadBytes())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ProtectionException.AuthenticationFailed(AuthenticationReason.DECRYPT_AUTH_FAILED, e)
        }
        return try {
            val payload = BootstrapPayload.decode(plaintext)
            require(payload is Intro) { "Bootstrap envelope must carry an INTRO payload" }
            payload
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ProtectionException.InvalidEnvelope(e)
        }
    }

    /**
     * Opens a plaintext RECOVERY_REQUEST by verifying the account-key signature over the canonical
     * device binding. The account pub key travels inside the payload itself (the responder has no
     * pre-existing row for a recovering device's account key material beyond the mesh's own records;
     * account existence/status is an orchestrator check, §8.2 of the design doc).
     */
    private suspend fun openRecoveryRequest(envelope: BootstrapEnvelope): RecoveryRequest {
        val payload = try {
            BootstrapPayload.decode(envelope.payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ProtectionException.InvalidEnvelope(e)
        }
        if (payload !is RecoveryRequest) {
            throw ProtectionException.AuthenticationFailed(AuthenticationReason.INVALID_SIGNATURE)
        }
        val accountKey = payload.account.key?.publicKey
            ?: throw ProtectionException.AuthenticationFailed(AuthenticationReason.INVALID_SIGNATURE)
        val verified = try {
            crypto.verifyDetached(accountKey, payload.accountSignedDeviceBindingBytes(), payload.accountSignature)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ProtectionException.InvalidEnvelope(e)
        }
        if (!verified) {
            throw ProtectionException.AuthenticationFailed(AuthenticationReason.INVALID_SIGNATURE)
        }
        return payload
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