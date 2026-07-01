package org.yapyap.crypto

import org.yapyap.crypto.identity.IdentityKeyPurpose
import org.yapyap.persistence.key.KeyType

sealed class CryptoException(message: String) : Exception(message) {
    class MissingDeviceRecord(peerId: String) : CryptoException("Missing peer record: $peerId")
    class MissingAccountRecord(accountId: String) : CryptoException("Missing account record: $accountId")
    class MissingKey(keyId: String, purpose: IdentityKeyPurpose, keyType: KeyType) : CryptoException("Missing ${keyType.name.lowercase()} key: $keyId, ${purpose.name}")
    class IncompleteRecord(message: String) : CryptoException(message)
}
