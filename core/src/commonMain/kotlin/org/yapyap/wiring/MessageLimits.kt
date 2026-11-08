package org.yapyap.wiring

import org.yapyap.crypto.e2ee.session.CryptoLimits
import org.yapyap.protocol.envelopes.MessageEnvelope
import org.yapyap.protocol.envelopes.MessagePayload
import org.yapyap.routing.router.TransportLimits

/**
 * All message-size limits, derived from the transport backend configs — not independently
 * configurable. Each layer subtracts its own framing ([MessageEnvelope.maxPayloadBytes],
 * [CryptoLimits.forSessionWireFrameBudget]); this only chains them and hands each consumer
 * its piece:
 *  - the router gets [transport]
 *  - crypto gets [crypto]
 *  - the messaging service / GUI gets [maxTextMessageBytes]
 */
internal data class MessageLimits(
    val transport: TransportLimits,
    val crypto: CryptoLimits,
    /** Max text bytes the GUI may submit via `MessagingService.sendTextMessage`. */
    val maxTextMessageBytes: Int,
) {
    companion object {
        fun from(config: RuntimeConfig): MessageLimits =
            from(
                TransportLimits(
                    torMaxPayloadBytes = config.tor.maxPayloadBytes,
                    webRtcMaxPayloadBytes = config.webRtc.maxPayloadBytes,
                )
            )

        fun from(transport: TransportLimits): MessageLimits {
            // Crypto must handle anything any transport can carry → use max.
            val crypto = cryptoLimitsFor(transport.maxTransportableBytes)
            // Messages must fit through EITHER transport → use min.
            val maxText = (cryptoLimitsFor(transport.maxRoutableBytes).maxInnerPlaintextBytes
                    - MessagePayload.Text.ENCODED_HEADER_RESERVE_BYTES)
            return MessageLimits(transport, crypto, maxText)
        }

        private fun cryptoLimitsFor(binaryEnvelopeBytes: Int): CryptoLimits =
            CryptoLimits.forSessionWireFrameBudget(MessageEnvelope.maxPayloadBytes(binaryEnvelopeBytes))
    }
}
