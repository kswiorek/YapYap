package org.yapyap.protection.envelope

import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.store.BootstrapKeySource
import org.yapyap.protection.AuthenticationReason
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.EnvelopeObservability
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BootstrapEnvelope
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.protocol.envelopes.BootstrapSecurityScheme
import org.yapyap.protocol.envelopes.Intro
import org.yapyap.protocol.envelopes.Invite
import org.yapyap.protocol.envelopes.RecoveryRequest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Bootstrap-family envelope protection: [Intro] → SECRET_AEAD (AEAD under `HKDF(shared secret)`),
 * [RecoveryRequest] → ACCOUNT_SIGNED (plaintext + account-key signature over the device binding),
 * [Invite] never travels on the wire.
 *
 * Preshared-key primitive, not identity-backed: no DB lookups, no session manager. The key is
 * split by direction — [BaseProtection.protect] takes the sender's in-memory secret through
 * [BootstrapProtectContext.sharedSecret] (sponsor QR scan / responder request copy, so neither
 * role needs state), [BaseProtection.open] resolves the newcomer's persisted secret from
 * [BootstrapKeySource].
 *
 * ChaCha20-Poly1305, library-managed IV; the header is bound as AEAD AAD (`aadBytes`).
 */
internal class BootstrapProtection(
    private val crypto: CryptoProvider,
    private val keySource: BootstrapKeySource,
) : BaseProtection<BootstrapPayload, BootstrapEnvelope, BootstrapPayload, BootstrapProtectContext>() {

    /** Domain-separated AEAD key for an intro/reply. Deterministic per secret. */
    private suspend fun deriveIntroKey(sharedSecret: ByteArray): ByteArray =
        crypto.hkdf(
            ikm = sharedSecret,
            salt = null,
            info = INTRO_INFO,
            outputLength = INTRO_KEY_SIZE_BYTES,
        )

    /**
     * Protect by payload kind: AEAD for INTRO (the shared secret is required), plaintext for
     * RECOVERY_REQUEST (its account signature is the authentication). The scheme is chosen per
     * kind, never from the context.
     */
    override suspend fun doProtect(
        input: BootstrapPayload,
        context: BootstrapProtectContext,
    ): BootstrapEnvelope = when (input) {
        is Intro -> protectIntro(
            input,
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAt = context.createdAt,
            sharedSecret = requireNotNull(context.sharedSecret) { "INTRO protection requires the shared secret" },
        )

        is RecoveryRequest -> BootstrapEnvelope(
            scheme = BootstrapSecurityScheme.ACCOUNT_SIGNED,
            bootstrapEnvelopeId = Uuid.random(),
            source = context.sourceDeviceId,
            target = context.targetDeviceId,
            createdAt = context.createdAt,
            payload = input.encode(),
        )

        is Invite -> error("The INVITE is an out-of-band QR/CLI payload and never travels in a bootstrap envelope")
    }

    /** True when this node holds an active onboarding session (the AEAD intro gate is armed). */
    suspend fun hasActiveSession(): Boolean = keySource.introKey() != null

    /** Opens a bootstrap envelope by its protection scheme; returns the authenticated payload. */
    override suspend fun doOpen(envelope: BootstrapEnvelope): BootstrapPayload = when (envelope.scheme) {
        BootstrapSecurityScheme.SECRET_AEAD -> openIntro(envelope)
        BootstrapSecurityScheme.ACCOUNT_SIGNED -> openRecoveryRequest(envelope)
    }

    override fun observableHeaderValues(envelope: BootstrapEnvelope): Map<String, Any?> =
        envelope.observableHeaderValues()

    override fun observabilityPolicy() = EnvelopeObservability.bootstrapEnvelope.fields

    override fun envelopeLabel(): String = "Bootstrap envelope"

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
        val accountKey = payload.account.signingKey
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

/**
 * Bootstrap protection context. Like [FileProtectContext] it is family-specific: the scheme comes
 * from the payload kind, so only the sender's in-memory [sharedSecret] (INTRO) needs carrying.
 */
internal data class BootstrapProtectContext(
    val sourceDeviceId: PeerId,
    val targetDeviceId: PeerId,
    val createdAt: Instant,
    /** Sender's in-memory secret, required for INTRO; unused for RECOVERY_REQUEST. */
    val sharedSecret: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        other as BootstrapProtectContext
        return sourceDeviceId == other.sourceDeviceId &&
                targetDeviceId == other.targetDeviceId &&
                createdAt == other.createdAt &&
                (sharedSecret?.contentEquals(other.sharedSecret) ?: (other.sharedSecret == null))
    }

    override fun hashCode(): Int {
        var result = sourceDeviceId.hashCode()
        result = 31 * result + targetDeviceId.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (sharedSecret?.contentHashCode() ?: 0)
        return result
    }
}
