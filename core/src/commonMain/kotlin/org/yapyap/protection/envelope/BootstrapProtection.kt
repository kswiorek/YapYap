package org.yapyap.protection.envelope

import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.store.BootstrapKeySource
import org.yapyap.protection.AuthenticationReason
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.ByteWriter
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.envelopes.BootstrapHeader
import org.yapyap.protocol.envelopes.BootstrapPayload
import org.yapyap.protocol.envelopes.BootstrapSecurityScheme
import org.yapyap.protocol.envelopes.Intro
import org.yapyap.protocol.envelopes.Invite
import org.yapyap.protocol.envelopes.RecoveryRequest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * BOOTSTRAP content protection: [Intro] → SECRET_AEAD (AEAD under `HKDF(shared secret)`),
 * [RecoveryRequest] → ACCOUNT_SIGNED (plaintext + account-key signature over the device binding),
 * [Invite] never travels on the wire.
 *
 * Preshared-key primitive, not identity-backed: no DB lookups, no session manager. The key is
 * split by direction — [protect] takes the sender's in-memory secret (sponsor QR scan / responder
 * request copy, so neither role needs state), [open] resolves the newcomer's persisted secret from
 * [BootstrapKeySource].
 *
 * ChaCha20-Poly1305, library-managed IV; the AAD is the routing header plus the hop's source and
 * target, so the packet cannot be re-targeted or claim another sender.
 */
internal class BootstrapProtection(
    private val crypto: CryptoProvider,
    private val keySource: BootstrapKeySource,
) {
    /**
     * Protects [payload] from [source] to [target] by its kind: AEAD for INTRO ([sharedSecret] is
     * required), plaintext for RECOVERY_REQUEST (its account signature is the authentication).
     */
    suspend fun protect(
        payload: BootstrapPayload,
        source: PeerId,
        target: PeerId,
        sharedSecret: ByteArray? = null,
    ): ProtectedBootstrap = when (payload) {
        is Intro -> {
            val secret = requireNotNull(sharedSecret) { "INTRO protection requires the shared secret" }
            val header = BootstrapHeader(BootstrapSecurityScheme.SECRET_AEAD, Uuid.random())
            val ciphertext = crypto.encryptAead(deriveIntroKey(secret), payload.encode(), aad(header, source, target))
            ProtectedBootstrap(header, ciphertext)
        }

        is RecoveryRequest -> ProtectedBootstrap(
            BootstrapHeader(BootstrapSecurityScheme.ACCOUNT_SIGNED, Uuid.random()),
            payload.encode(),
        )

        is Invite -> error("The INVITE is an out-of-band QR/CLI payload and never travels in a bootstrap envelope")
    }

    /** True when this node holds an active onboarding session (the AEAD intro gate is armed). */
    suspend fun hasActiveSession(): Boolean = keySource.introKey() != null

    /** Opens bootstrap [content] by the header's scheme; returns the authenticated payload. */
    suspend fun open(header: BootstrapHeader, content: ByteArray, source: PeerId, target: PeerId): BootstrapPayload =
        when (header.scheme) {
            BootstrapSecurityScheme.SECRET_AEAD -> openIntro(header, content, source, target)
            BootstrapSecurityScheme.ACCOUNT_SIGNED -> openRecoveryRequest(content)
        }

    private suspend fun openIntro(header: BootstrapHeader, content: ByteArray, source: PeerId, target: PeerId): Intro {
        val introKey = activeIntroKey()
        val plaintext = try {
            crypto.decryptAead(introKey, content, aad(header, source, target))
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
    private suspend fun openRecoveryRequest(content: ByteArray): RecoveryRequest {
        val payload = try {
            BootstrapPayload.decode(content)
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

    /** Domain-separated AEAD key for an intro/reply. Deterministic per secret. */
    private suspend fun deriveIntroKey(sharedSecret: ByteArray): ByteArray =
        crypto.hkdf(
            ikm = sharedSecret,
            salt = null,
            info = INTRO_INFO,
            outputLength = INTRO_KEY_SIZE_BYTES,
        )

    private suspend fun activeIntroKey(): ByteArray {
        val secret = keySource.introKey() ?: throw ProtectionException.BootstrapSessionInactive()
        return deriveIntroKey(secret)
    }

    private fun aad(header: BootstrapHeader, source: PeerId, target: PeerId): ByteArray {
        val encoded = header.encode()
        val writer = ByteWriter(encoded.size + 160)
        writer.writeBytes(encoded)
        writer.writePeerId(source)
        writer.writePeerId(target)
        return writer.toByteArray()
    }

    companion object {
        private const val INTRO_KEY_SIZE_BYTES = 32
        private val INTRO_INFO = "yapyap-bootstrap-intro-v1".encodeToByteArray()
    }
}

/** Protected bootstrap traffic: the routing header and the content routing puts on the wire. */
internal class ProtectedBootstrap(val header: BootstrapHeader, val content: ByteArray)
