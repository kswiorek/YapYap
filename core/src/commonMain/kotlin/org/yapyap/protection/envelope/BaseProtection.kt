package org.yapyap.protection.envelope

import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.FieldSensitivity

abstract class BaseProtection<I, E> {
    suspend fun protect(input: I, context: EnvelopeProtectContext): E {
        val envelope = doProtect(input, context)
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
        return doOpen(envelope)
    }

    protected abstract suspend fun doProtect(input: I, context: EnvelopeProtectContext): E

    protected abstract suspend fun doOpen(envelope: E): I

    protected abstract fun observableHeaderValues(envelope: E): Map<String, Any?>

    protected abstract fun observabilityPolicy(): Map<String, FieldSensitivity>

    protected abstract fun envelopeLabel(): String

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
