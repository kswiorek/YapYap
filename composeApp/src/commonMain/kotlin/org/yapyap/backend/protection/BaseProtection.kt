package org.yapyap.backend.protection

import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.routing.FieldSensitivity
import kotlin.io.encoding.Base64.Default.encodeToByteArray

abstract class BaseProtection<I, E> {
    fun protect(input: I, context: EnvelopeProtectContext): E {
        val envelope = doProtect(input, context)
        assertObservabilityContract(
            observableHeaderValues = observableHeaderValues(envelope),
            policy = observabilityPolicy(),
            envelopeLabel = envelopeLabel(),
        )
        return envelope
    }

    fun open(envelope: E): I {
        assertObservabilityContract(
            observableHeaderValues = observableHeaderValues(envelope),
            policy = observabilityPolicy(),
            envelopeLabel = envelopeLabel(),
        )
        return doOpen(envelope)
    }

    open fun openLazy(envelope: E): Lazy<I> =
        lazy(LazyThreadSafetyMode.NONE) { open(envelope) }

    protected abstract fun doProtect(input: I, context: EnvelopeProtectContext): E

    protected abstract fun doOpen(envelope: E): I

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

    protected fun buildSigningPayload(
        envelopeId: String,
        kindWireValue: Byte,
        source: PeerId,
        target: PeerId,
        createdAtEpochSeconds: Long,
        nonce: ByteArray,
        protectedPayload: ByteArray,
    ): ByteArray {
        val sourceBytes = source.id.encodeToByteArray()
        val targetBytes = target.id.encodeToByteArray()
        val headerBytes = buildString {
            append(envelopeId)
            append('|')
            append(kindWireValue.toInt())
            append('|')
            append(createdAtEpochSeconds)
            append('|')
        }.encodeToByteArray()
        val result = ByteArray(
            headerBytes.size + sourceBytes.size + targetBytes.size + nonce.size + protectedPayload.size + 2
        )
        var offset = 0
        headerBytes.copyInto(result, destinationOffset = offset)
        offset += headerBytes.size
        sourceBytes.copyInto(result, destinationOffset = offset)
        offset += sourceBytes.size
        result[offset++] = '|'.code.toByte()
        targetBytes.copyInto(result, destinationOffset = offset)
        offset += targetBytes.size
        result[offset++] = '|'.code.toByte()
        nonce.copyInto(result, destinationOffset = offset)
        offset += nonce.size
        protectedPayload.copyInto(result, destinationOffset = offset)
        return result
    }
}
