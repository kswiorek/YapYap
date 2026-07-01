package org.yapyap.protection.envelope

import org.yapyap.protection.ProtectionException
import org.yapyap.protection.service.EnvelopeProtectContext
import org.yapyap.protocol.FieldSensitivity
import kotlin.coroutines.cancellation.CancellationException

abstract class BaseProtection<I, E> {
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
