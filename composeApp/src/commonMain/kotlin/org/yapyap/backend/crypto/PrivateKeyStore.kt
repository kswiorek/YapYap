package org.yapyap.backend.crypto

enum class KeyType {
    PUBLIC,
    PRIVATE,
}

data class KeyReference(
    val keyId: String,
    val purpose: IdentityKeyPurpose,
    val type: KeyType,
) {
    init {
        require(keyId.isNotBlank()) { "keyId must not be blank" }
    }
}

interface PrivateKeyStore {
    fun putKey(ref: KeyReference, key: ByteArray)

    fun getKey(ref: KeyReference): ByteArray?
}
