package org.yapyap.crypto


internal sealed class CryptoException(message: String) : Exception(message) {
    class MissingDeviceRecord(peerId: String) : CryptoException("Missing peer record: $peerId")
    class MissingAccountRecord(accountId: String) : CryptoException("Missing account record: $accountId")
    class MissingKey(what: String) : CryptoException("Missing key: $what")

    class IncompleteRecord(message: String) : CryptoException(message)
    class InvalidRecoveryKey(message: String) : CryptoException("Invalid recovery key: $message")
}
