package org.yapyap.backend.crypto

import org.yapyap.backend.protocol.DeviceAddress

class DefaultSignatureProvider(
    private val localAddress: DeviceAddress,
    private val identityKeyService: IdentityKeyService,
    private val cryptoProvider: CryptoProvider,
) : SignatureProvider {
    override fun resolveLocalSigningKeyId(): String {
        return identityKeyService.resolveLocalSigningKeyId(localAddress)
    }

    override fun signDetached(keyId: String, message: ByteArray): ByteArray {
        val privateKey = identityKeyService.loadLocalPrivateKey(
            keyId = keyId,
            purpose = IdentityKeyPurpose.SIGNING,
        )
        return cryptoProvider.signDetached(privateKey, message)
    }

    override fun verifyDetached(source: DeviceAddress, message: ByteArray, signature: ByteArray): Boolean {
        val publicKey = identityKeyService.resolvePeerPublicKey(
            source = source,
            purpose = IdentityKeyPurpose.SIGNING,
        ) ?: return false
        return cryptoProvider.verifyDetached(publicKey, message, signature)
    }
}
