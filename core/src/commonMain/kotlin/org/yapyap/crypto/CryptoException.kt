package org.yapyap.crypto

sealed class CryptoException(message: String) : Exception(message) {
    class MissingPeerRecord(peerId: String) : CryptoException("Missing peer record: $peerId")
}