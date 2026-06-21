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

interface KeyStore {
    suspend fun putKey(ref: KeyReference, key: ByteArray)

    suspend fun getKey(ref: KeyReference): ByteArray?
}
