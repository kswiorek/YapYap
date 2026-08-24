package org.yapyap.config

import org.yapyap.crypto.e2ee.session.CryptoLimits
import org.yapyap.crypto.e2ee.session.RatchetCiphertext
import org.yapyap.crypto.e2ee.session.RatchetInnerPlaintext
import org.yapyap.crypto.e2ee.session.SessionWireFrame
import org.yapyap.crypto.primitives.DefaultCryptoProvider
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.MessageEnvelope
import org.yapyap.protocol.envelopes.MessagePayload

/**
 * Single source of truth for all message-size limits.
 *
 * Derived from transport backend configs ([TransportLimits]) — not independently configurable.
 * Computed once at boot by the orchestrator and distributed to consumers:
 *  - crypto layer gets [crypto]
 *  - router gets [transport]
 *  - messaging service / GUI gets [maxTextMessageBytes]
 *
 * The derivation lives here (the config layer) because it needs to sum wire-format overhead
 * constants from both the protocol layer ([BinaryEnvelope], [MessageEnvelope]) and the crypto
 * layer ([SessionWireFrame], [RatchetCiphertext], [RatchetInnerPlaintext]).
 */
data class MessageLimits(
    val transport: TransportLimits,
    val crypto: CryptoLimits,
    /** Max text bytes the GUI may submit via `MessagingService.sendTextMessage`. */
    val maxTextMessageBytes: Int,
) {
    companion object {
        fun from(config: RuntimeConfig): MessageLimits =
            from(TransportLimits.from(config))

        fun from(transport: TransportLimits): MessageLimits {
            // Crypto must handle anything any transport can carry → use max.
            val cryptoBudget = cryptoBudget(transport.maxTransportableBytes)
            val crypto = CryptoLimits(
                maxSessionWireFrameBytes = cryptoBudget.maxSessionWireFrameBytes,
                maxInnerPlaintextBytes = cryptoBudget.maxInnerPlaintextBytes,
                maxRatchetBodyBytes = cryptoBudget.maxRatchetBodyBytes,
            )
            // Messages must fit through EITHER transport → use min.
            val maxText = (cryptoBudget(transport.maxRoutableBytes).maxInnerPlaintextBytes
                - MessagePayload.Text.ENCODED_HEADER_RESERVE_BYTES)
            return MessageLimits(transport, crypto, maxText)
        }

        /**
         * Computes the session-wire-frame / ratchet-body / inner-plaintext budgets for a given
         * BinaryEnvelope byte budget, by subtracting each envelope's wire-format overhead.
         */
        private fun cryptoBudget(envelopeBytes: Int): CryptoBudget {
            val maxSessionWireFrame = (envelopeBytes
                - BinaryEnvelope.ENCODED_HEADER_BYTES
                - MessageEnvelope.ENCODED_OVERHEAD_BYTES)

            val maxRatchetBody = (maxSessionWireFrame
                - SessionWireFrame.MAX_HEADER_BYTES
                - RatchetCiphertext.HEADER_BYTES)

            val maxInnerPlaintext = (maxRatchetBody
                - DefaultCryptoProvider.AEAD_OVERHEAD_BYTES
                - RatchetInnerPlaintext.ENCODED_OVERHEAD)

            return CryptoBudget(
                maxSessionWireFrameBytes = maxSessionWireFrame,
                maxRatchetBodyBytes = maxRatchetBody,
                maxInnerPlaintextBytes = maxInnerPlaintext,
            )
        }
    }

    private data class CryptoBudget(
        val maxSessionWireFrameBytes: Int,
        val maxRatchetBodyBytes: Int,
        val maxInnerPlaintextBytes: Int,
    )
}
