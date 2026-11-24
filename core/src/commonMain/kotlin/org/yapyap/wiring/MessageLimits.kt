package org.yapyap.wiring

import org.yapyap.crypto.e2ee.session.CryptoLimits
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.protocol.envelopes.DagPayload
import org.yapyap.protocol.envelopes.MessageHeader
import org.yapyap.routing.router.TransportLimits

/**
 * All message-size limits, derived from the transport backend configs — not independently
 * configurable. Each layer subtracts its own framing ([BinaryEnvelope.maxContentBytes] with the
 * [MessageHeader], 
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
            // The text travels as a u16-length string, so the frame's room is capped at what
            // that prefix can carry: a longer text would pass this check and fail to encode.
            val maxText = minOf(
                cryptoLimitsFor(transport.maxRoutableBytes).maxInnerPlaintextBytes -
                        DagPayload.Text.ENCODED_HEADER_RESERVE_BYTES,
                Short.MAX_VALUE.toInt(),
            )
            return MessageLimits(transport, crypto, maxText)
        }

        private fun cryptoLimitsFor(binaryEnvelopeBytes: Int): CryptoLimits =
            CryptoLimits.forSessionWireFrameBudget(
                BinaryEnvelope.maxContentBytes(binaryEnvelopeBytes, MessageHeader.ENCODED_BYTES),
            )
    }
}
