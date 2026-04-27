package org.yapyap.backend.crypto

class DefaultSignatureProvider(
    private val identityResolver: IdentityResolver,
    private val cryptoProvider: CryptoProvider,
) : SignatureProvider {

    override fun signDetached(message: ByteArray): ByteArray {
        val privateKey = identityResolver.loadLocalPrivateKey(
            purpose = IdentityKeyPurpose.SIGNING,
        )
        return cryptoProvider.signDetached(privateKey, message)
    }

    override fun verifyDetached(deviceId: String, message: ByteArray, signature: ByteArray): Boolean {
        val publicKey = identityResolver.resolvePeerIdentityRecord(deviceId)?.signing?.publicKey ?: return false

        return cryptoProvider.verifyDetached(publicKey, message, signature)
    }
}
