package org.yapyap.protection.envelope

import org.yapyap.crypto.CryptoException
import org.yapyap.crypto.primitives.CryptoProvider
import org.yapyap.crypto.signature.SignatureProvider
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.ContentId
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.UnsignedEnvelope
import kotlin.coroutines.cancellation.CancellationException

/**
 * The hop layer every family shares (docs/envelope-redesign.md §2, §3.1): this device signs every
 * envelope it sends — relay forwards included — and a receiver verifies the hop's source before
 * anything else reads the packet. The signature covers `hop header ‖ routing header ‖ contentId`.
 */
internal interface HopProtection {
    /** Signs [envelope] as its source (this device). */
    suspend fun sign(envelope: UnsignedEnvelope): BinaryEnvelope

    /** True when [envelope] is signed by its source's known key; false otherwise (bad or unknown key). */
    suspend fun verify(envelope: BinaryEnvelope): Boolean

    /** SHA-256 of [content]: what the signature covers, and a file OFFER's seal id. */
    suspend fun contentId(content: ByteArray): ContentId
}

internal class SignedHopProtection(
    private val signatures: SignatureProvider,
    private val crypto: CryptoProvider,
) : HopProtection {
    override suspend fun sign(envelope: UnsignedEnvelope): BinaryEnvelope {
        val input = envelope.signingInput(contentId(envelope.content).bytes)
        val signature = try {
            signatures.sign(input)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CryptoException) {
            AppLog.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.SIGNATURE_SIGN_FAILED,
                message = "Failed to sign envelope",
                throwable = e,
            )
            throw ProtectionException.IdentityNotReady(e)
        }
        return envelope.signed(signature)
    }

    override suspend fun verify(envelope: BinaryEnvelope): Boolean {
        val unsigned = envelope.unsigned()
        val input = unsigned.signingInput(contentId(unsigned.content).bytes)
        return try {
            signatures.verify(deviceId = envelope.source, message = input, signature = envelope.signature)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No key for the source (or an unreadable one): nothing it signed can be trusted.
            AppLog.debug(
                component = LogComponent.CRYPTO,
                event = LogEvent.SIGNATURE_VERIFICATION_FAILED,
                message = "Hop signature not checkable: no usable key for the source",
                fields = mapOf("source" to envelope.source, "error" to e.toString()),
            )
            false
        }
    }

    override suspend fun contentId(content: ByteArray): ContentId = ContentId(crypto.sha256(content))
}
