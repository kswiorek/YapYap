package org.yapyap.backend.protocol

enum class PeerRole {
    USER_DEVICE,
    HEADLESS_RELAY,
}

enum class SignalSecurityScheme(val wireValue: Byte) {
    PLAINTEXT_TEST_ONLY(0),
    SIGNED(1),
    ENCRYPTED_AND_SIGNED(2);

    companion object {
        fun fromWireValue(value: Byte): SignalSecurityScheme =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported signal security scheme wire value: $value")
    }
}

data class PeerId(
    val accountName: String,
    val deviceId: String,
) {
    init {
        require(accountName.isNotBlank()) { "accountName must not be blank" }
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
    }
}

data class TorEndpoint(
    val onionAddress: String,
    val port: Int = 80,
) {
    init {
        require(onionAddress.endsWith(".onion")) { "onionAddress must end with .onion" }
        require(port in 1..65535) { "port must be in range 1..65535" }
    }
}

data class PeerIdentityKeys(
    val signingPublicKey: ByteArray,
    val encryptionPublicKey: ByteArray,
    val signingKeyId: String,
    val encryptionKeyId: String,
) {
    init {
        require(signingPublicKey.isNotEmpty()) { "signingPublicKey must not be empty" }
        require(encryptionPublicKey.isNotEmpty()) { "encryptionPublicKey must not be empty" }
        require(signingKeyId.isNotBlank()) { "signingKeyId must not be blank" }
        require(encryptionKeyId.isNotBlank()) { "encryptionKeyId must not be blank" }
    }
}

data class PeerCapabilities(
    val supportsWebRtcData: Boolean,
    val supportsWebRtcMedia: Boolean,
    val supportedSignalSecuritySchemes: Set<SignalSecurityScheme>,
    val supportedProtocolVersions: Set<Int>,
    val isRelayAvailable: Boolean,
) {
    init {
        require(supportedSignalSecuritySchemes.isNotEmpty()) {
            "supportedSignalSecuritySchemes must not be empty"
        }
        require(supportedProtocolVersions.isNotEmpty()) { "supportedProtocolVersions must not be empty" }
    }
}

data class PeerDescriptor(
    val id: PeerId,
    val torEndpoint: TorEndpoint,
    val role: PeerRole,
    val identity: PeerIdentityKeys,
    val capabilities: PeerCapabilities,
    val descriptorVersion: Long,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val nonce: ByteArray,
    val signature: ByteArray,
    val signatureKeyId: String,
) {
    init {
        require(descriptorVersion >= 1) { "descriptorVersion must be >= 1" }
        require(expiresAtEpochSeconds > issuedAtEpochSeconds) { "expiresAt must be > issuedAt" }
        require(nonce.isNotEmpty()) { "nonce must not be empty" }
        require(signature.isNotEmpty()) { "signature must not be empty" }
        require(signatureKeyId.isNotBlank()) { "signatureKeyId must not be blank" }
    }
}

