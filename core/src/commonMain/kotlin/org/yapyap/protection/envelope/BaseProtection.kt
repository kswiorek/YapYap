package org.yapyap.protection.envelope

import org.yapyap.crypto.signature.SignatureProvider
import org.yapyap.protection.AuthenticationReason
import org.yapyap.protection.ProtectionException
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.FieldSensitivity
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import kotlin.coroutines.cancellation.CancellationException

internal abstract class BaseProtection<I, E> {
    suspend fun protect(input: I, context: EnvelopeProtectContext): E {
        val envelope = try {
            doProtect(input, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw ProtectionException.map(e)
        }
        assertObservabilityContract(
            observableHeaderValues = observableHeaderValues(envelope),
            policy = observabilityPolicy(),
            envelopeLabel = envelopeLabel(),
        )
        return envelope
    }

    suspend fun open(envelope: E): I {
        assertObservabilityContract(
            observableHeaderValues = observableHeaderValues(envelope),
            policy = observabilityPolicy(),
            envelopeLabel = envelopeLabel(),
        )
        return try {
            doOpen(envelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtectionException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw ProtectionException.map(e)
        }
    }

    protected abstract suspend fun doProtect(input: I, context: EnvelopeProtectContext): E

    protected abstract suspend fun doOpen(envelope: E): I

    protected abstract fun observableHeaderValues(envelope: E): Map<String, Any?>

    protected abstract fun observabilityPolicy(): Map<String, FieldSensitivity>

    protected abstract fun envelopeLabel(): String

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

}
