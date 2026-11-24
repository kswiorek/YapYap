package org.yapyap.protection.envelope

import org.yapyap.crypto.e2ee.manager.CryptoSessionManager
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.PeerId
import kotlin.coroutines.cancellation.CancellationException

/**
 * End-to-end content through the Double Ratchet: the one way message, signal, seal and REQUEST
 * content is encrypted from its origin to its final target. Crypto failures leave as
 * [ProtectionException] verdicts; a frame opened before is [ProtectionException.Duplicate].
 */
internal class RatchetSealer(private val sessions: CryptoSessionManager) {
    /** Encrypts [plaintext] to [target]; [what] names the content in logs. */
    suspend fun seal(plaintext: ByteArray, target: PeerId, what: String): ByteArray = try {
        sessions.encryptMessage(remoteDeviceId = target, bytes = plaintext)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.error(
            component = LogComponent.CRYPTO,
            event = LogEvent.ENCRYPTION_FAILED,
            message = "Failed to encrypt $what",
            throwable = e,
        )
        throw ProtectionException.mapEncryptFailure(e)
    }

    /** Decrypts a frame [origin] encrypted to us; [what] names the content in logs. */
    suspend fun open(frame: ByteArray, origin: PeerId, what: String): ByteArray = try {
        sessions.decryptMessage(remoteDeviceId = origin, frameBytes = frame)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val mapped = ProtectionException.mapEncryptDecryptFailure(e)
        if (mapped is ProtectionException.Duplicate) {
            AppLog.debug(
                component = LogComponent.CRYPTO,
                event = LogEvent.DECRYPTION_FAILED,
                message = "Duplicate $what: opened before",
                fields = mapOf("origin" to origin),
            )
        } else {
            AppLog.error(
                component = LogComponent.CRYPTO,
                event = LogEvent.DECRYPTION_FAILED,
                message = "Failed to decrypt $what",
                throwable = e,
            )
        }
        throw mapped
    }

    /** Decodes opened bytes; a decode failure is an invalid envelope, never a raw exception. */
    inline fun <T> decode(what: String, decode: () -> T): T = try {
        decode()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.error(
            component = LogComponent.CRYPTO,
            event = LogEvent.ENVELOPE_DECODE_FAILED,
            message = "Failed to decode $what",
            throwable = e,
        )
        throw ProtectionException.InvalidEnvelope(e)
    }
}
