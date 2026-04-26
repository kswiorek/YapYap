package org.yapyap.backend.protocol

data class UnsignedPeerDescriptor(
    val id: PeerId,
    val torEndpoint: TorEndpoint,
    val role: PeerRole,
    val identity: PeerIdentityKeys,
    val capabilities: PeerCapabilities,
    val descriptorVersion: Long,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val nonce: ByteArray,
) {
    init {
        require(descriptorVersion >= 1) { "descriptorVersion must be >= 1" }
        require(expiresAtEpochSeconds > issuedAtEpochSeconds) { "expiresAt must be > issuedAt" }
        require(nonce.isNotEmpty()) { "nonce must not be empty" }
    }
}

data class SignedPeerDescriptor(
    val unsigned: UnsignedPeerDescriptor,
    val signature: ByteArray,
    val signatureKeyId: String,
) {
    init {
        require(signature.isNotEmpty()) { "signature must not be empty" }
        require(signatureKeyId.isNotBlank()) { "signatureKeyId must not be blank" }
    }

    fun toPeerDescriptor(): PeerDescriptor =
        PeerDescriptor(
            id = unsigned.id,
            torEndpoint = unsigned.torEndpoint,
            role = unsigned.role,
            identity = unsigned.identity,
            capabilities = unsigned.capabilities,
            descriptorVersion = unsigned.descriptorVersion,
            issuedAtEpochSeconds = unsigned.issuedAtEpochSeconds,
            expiresAtEpochSeconds = unsigned.expiresAtEpochSeconds,
            nonce = unsigned.nonce,
            signature = signature,
            signatureKeyId = signatureKeyId,
        )
}
