package org.yapyap.backend.crypto

data class PrivateKeyRef(
    val deviceId: String,
    val keyId: String,
    val purpose: IdentityKeyPurpose,
) {
    init {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        require(keyId.isNotBlank()) { "keyId must not be blank" }
    }
}

interface PrivateKeyStore {
    fun putPrivateKey(ref: PrivateKeyRef, privateKey: ByteArray)

    fun getPrivateKey(ref: PrivateKeyRef): ByteArray?
}
