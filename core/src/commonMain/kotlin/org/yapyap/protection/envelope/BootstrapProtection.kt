package org.yapyap.protection.envelope

import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.protection.AuthenticationReason
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.*
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
 * Bootstrap-family envelope protection, exposed as one symmetric [protect] / [open] pair that
 * dispatches on the payload kind / [BootstrapEnvelope.scheme]:
 *  - [Intro] → SECRET_AEAD: AEAD under `HKDF(QR shared secret)` — the preshared-key gate for sponsor
 *    intros and recovery replies;
 *  - [RecoveryRequest] → ACCOUNT_SIGNED: plaintext + account-key signature verification over the
 *    device binding ([RecoveryRequest.accountSignedDeviceBindingBytes]) — the request has no
 *    pre-shared secret to encrypt under, its authentication *is* the account signature.
 *  - [Invite] is out-of-band only and never carried in an envelope.
 *
 * The SECRET_AEAD path is deliberately a *preshared-key* primitive, not an identity-backed scheme —
 * it resolves no keys from the DB (no [org.yapyap.crypto.signature.SignatureProvider], no session
 * manager), extends no [BaseProtection], and is not part of the [org.yapyap.protocol.SignalSecurityScheme]
 * set. The key comes from [BootstrapKeySource] (the orchestrator's onboarding session store), never
 * as a caller-passed secret.
 *
 * The cipher is ChaCha20-Poly1305 with a library-managed IV embedded in the output; the envelope
 * header (scheme/id/source/target/createdAt) is bound as AEAD AAD.
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

    /** Protects a bootstrap payload into an envelope by its kind (AEAD for INTRO, plaintext for RECOVERY_REQUEST). */
    suspend fun protect(
        payload: BootstrapPayload,
        source: PeerId,
        target: PeerId,
        createdAt: Instant,
    ): BootstrapEnvelope = when (payload) {
        is Intro -> protectIntro(payload, source, target, createdAt)
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

    /** Protects an INTRO (or an AEAD-bound recovery reply) under the active session secret. */
    private suspend fun protectIntro(
        payload: Intro,
        source: PeerId,
        target: PeerId,
        createdAt: Instant,
    ): BootstrapEnvelope {
        val introKey = activeIntroKey()
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