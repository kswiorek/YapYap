package org.yapyap.crypto.e2ee

import org.yapyap.protocol.PeerId

/**
 * Runtime failures that can occur while handling live crypto sessions (peer traffic, timing,
 * store state). Programming mistakes and invalid local API use should throw [IllegalArgumentException]
 * via [require] or [error] instead.
 */
sealed class CryptoSessionException(message: String) : Exception(message) {

    class NoSession(peerDeviceId: PeerId, sessionEpoch: Int) :
        CryptoSessionException("No crypto session for peer=$peerDeviceId epoch=$sessionEpoch")

    class HandshakeRequired(peerDeviceId: PeerId) :
        CryptoSessionException("Handshake required for peer=$peerDeviceId")

    class HandshakeMismatch(detail: String) :
        CryptoSessionException("Handshake mismatch: $detail")

    class MissingInitiatorEphemeral(peerDeviceId: PeerId) :
        CryptoSessionException("Missing initiator ephemeral key for peer=$peerDeviceId")

    class MissingOfferedOpk(peerDeviceId: PeerId) :
        CryptoSessionException("Missing offered OPK for peer=$peerDeviceId")

    class OpkConsumeFailed(opkId: String) :
        CryptoSessionException("Failed to consume one-time prekey id=$opkId")

    class MessageSkipExceeded(recvMessageNumber: Int, until: Int) :
        CryptoSessionException(
            "Too many skipped messages: current=$recvMessageNumber, until=$until",
        )

    class SupersededDhChain(messageNumber: Int) :
        CryptoSessionException(
            "No skipped message key for superseded DH chain (messageNumber=$messageNumber)",
        )

    class Replay(messageNumber: Int) :
        CryptoSessionException("Ratchet message replay (messageNumber=$messageNumber)")
}
