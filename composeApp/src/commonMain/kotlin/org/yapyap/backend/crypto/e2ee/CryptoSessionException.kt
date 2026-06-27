package org.yapyap.backend.crypto.e2ee

import org.yapyap.backend.protocol.PeerId

sealed class CryptoSessionException(message: String) : Exception(message) {
    class NoSession(peerDeviceId: PeerId, sessionEpoch: Int) :
        CryptoSessionException("No crypto session for peer=$peerDeviceId epoch=$sessionEpoch")

    class HandshakeRequired(peerDeviceId: PeerId) :
        CryptoSessionException("Handshake required for peer=$peerDeviceId")

    class MissingInitiatorEphemeral(peerDeviceId: PeerId) :
        CryptoSessionException("Missing initiator ephemeral key for peer=$peerDeviceId")

    class MissingOfferedOpk(peerDeviceId: PeerId) :
        CryptoSessionException("Missing offered OPK for peer=$peerDeviceId")

    class OpkConsumeFailed(opkId: String) :
        CryptoSessionException("Failed to consume one-time prekey id=$opkId")

    class SupersededDhChain(messageNumber: Int) :
        CryptoSessionException(
            "No skipped message key for superseded DH chain (messageNumber=$messageNumber)",
        )
}