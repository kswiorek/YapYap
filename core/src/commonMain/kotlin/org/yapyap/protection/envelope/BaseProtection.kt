package org.yapyap.protection.envelope

import org.yapyap.crypto.signature.SignatureProvider
import org.yapyap.protection.AuthenticationReason
import org.yapyap.protection.ProtectionException
import org.yapyap.protocol.FieldSensitivity
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import kotlin.coroutines.cancellation.CancellationException

internal abstract class BaseProtection<I, E, O, C> {
    suspend fun protect(input: I, context: C): E {
        val envelope = try {
            doProtect(input, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw ProtectionException.InvalidEnvelope(e)
        } catch (e: Exception) {
            throw ProtectionException.map(e)
        }
        assertObservabilityContract(
            observableHeaderValues = observableHeaderValues(envelope),
            policy = observabilityPolicy(),
            envelopeLabel = envelopeLabel(),
        )
        assertPayloadContract(
            observablePayloadValues = observablePayloadValuesForProtect(input),
            policy = payloadObservabilityPolicy(),
            envelopeLabel = envelopeLabel(),
        )
        return envelope
    }

    suspend fun open(envelope: E): O {
        assertObservabilityContract(
            observableHeaderValues = observableHeaderValues(envelope),
            policy = observabilityPolicy(),
            envelopeLabel = envelopeLabel(),
        )
        val opened = try {
            doOpen(envelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw ProtectionException.InvalidEnvelope(e)
        } catch (e: Exception) {
            throw ProtectionException.map(e)
        }
        assertPayloadContract(
            observablePayloadValues = observablePayloadValuesForOpen(opened),
            policy = payloadObservabilityPolicy(),
            envelopeLabel = envelopeLabel(),
        )
        return opened
    }

    protected abstract suspend fun doProtect(input: I, context: C): E

    protected abstract suspend fun doOpen(envelope: E): O

    protected abstract fun observableHeaderValues(envelope: E): Map<String, Any?>

    protected abstract fun observabilityPolicy(): Map<String, FieldSensitivity>

    protected abstract fun envelopeLabel(): String

    /** Public payload fields visible on the wire (file OFFER half, CHUNK index, ACK, COMPLETE, CANCEL reason). */
    protected open fun observablePayloadValuesForProtect(input: I): Map<String, Any?> = emptyMap()

    /** Same as above, derived from the opened result (file needs the decoded payload). */
    protected open fun observablePayloadValuesForOpen(opened: O): Map<String, Any?> = emptyMap()

    protected open fun payloadObservabilityPolicy(): Map<String, FieldSensitivity> = emptyMap()

    protected suspend fun requireValidSignature(
        expectedScheme: SignalSecurityScheme,
        actualScheme: SignalSecurityScheme,
        signature: ByteArray?,
        source: PeerId,
        signingBytes: ByteArray,
        signatureProvider: SignatureProvider,
    ) {
        require(actualScheme == expectedScheme) {
            "Expected $expectedScheme security scheme but got $actualScheme"
        }
        val present = signature
            ?: throw ProtectionException.AuthenticationFailed(AuthenticationReason.MISSING_SIGNATURE)
        val valid = signatureProvider.verify(
            deviceId = source,
            message = signingBytes,
            signature = present,
        )
        if (!valid) {
            throw ProtectionException.AuthenticationFailed(AuthenticationReason.INVALID_SIGNATURE)
        }
    }

    protected fun assertObservabilityContract(
        observableHeaderValues: Map<String, Any?>,
        policy: Map<String, FieldSensitivity>,
        envelopeLabel: String,
    ) {
        val unexpectedProtectedCleartext = observableHeaderValues.keys
            .filter { policy[it] == FieldSensitivity.PROTECTED }
        require(unexpectedProtectedCleartext.isEmpty()) {
            "$envelopeLabel exposes protected fields in cleartext: $unexpectedProtectedCleartext"
        }
    }

    private fun assertPayloadContract(
        observablePayloadValues: Map<String, Any?>,
        policy: Map<String, FieldSensitivity>,
        envelopeLabel: String,
    ) {
        val unexpectedProtectedPayload = observablePayloadValues.keys
            .filter { policy[it] == FieldSensitivity.PROTECTED }
        require(unexpectedProtectedPayload.isEmpty()) {
            "$envelopeLabel exposes protected payload fields in cleartext: $unexpectedProtectedPayload"
        }
    }

}
