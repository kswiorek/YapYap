package org.yapyap.backend.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.XDH
import kotlinx.coroutines.runBlocking
import org.yapyap.backend.logging.AppLogger
import org.yapyap.backend.logging.LogComponent
import org.yapyap.backend.logging.LogEvent
import org.yapyap.backend.logging.NoopAppLogger

class KmpCryptoProvider(
    private val provider: CryptographyProvider = CryptographyProvider.Default,
    private val logger: AppLogger = NoopAppLogger,
) : CryptoProvider {
    private val edDsa: EdDSA by lazy { provider.get(EdDSA) }
    private val xdh: XDH by lazy { provider.get(XDH) }

    override fun sha256(bytes: ByteArray): ByteArray = runBlocking {
        provider.get(SHA256).hasher().hash(bytes)
    }

    override fun generateSigningKeyPair(): SigningKeyPair = runBlocking {
        val keyPair = edDsa.keyPairGenerator(EdDSA.Curve.Ed25519).generateKey()
        logger.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.CRYPTO_KEYPAIR_GENERATED,
            message = "Generated signing key pair",
            fields = mapOf("keyPurpose" to IdentityKeyPurpose.SIGNING.name),
        )
        SigningKeyPair(
            publicKey = keyPair.publicKey.encodeToByteArray(EdDSA.PublicKey.Format.DER),
            privateKey = keyPair.privateKey.encodeToByteArray(EdDSA.PrivateKey.Format.DER),
        )
    }

    override fun generateEncryptionKeyPair(): EncryptionKeyPair = runBlocking {
        val keyPair = xdh.keyPairGenerator(XDH.Curve.X25519).generateKey()
        logger.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.CRYPTO_KEYPAIR_GENERATED,
            message = "Generated encryption key pair",
            fields = mapOf("keyPurpose" to IdentityKeyPurpose.ENCRYPTION.name),
        )
        EncryptionKeyPair(
            publicKey = keyPair.publicKey.encodeToByteArray(XDH.PublicKey.Format.DER),
            privateKey = keyPair.privateKey.encodeToByteArray(XDH.PrivateKey.Format.DER),
        )
    }

    override fun signDetached(privateSigningKey: ByteArray, message: ByteArray): ByteArray = runBlocking {
        val privateKey = edDsa.privateKeyDecoder(EdDSA.Curve.Ed25519).decodeFromByteArray(
            format = EdDSA.PrivateKey.Format.DER,
            bytes = privateSigningKey,
        )
        privateKey.signatureGenerator().generateSignature(message)
    }

    override fun verifyDetached(publicSigningKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = runBlocking {
        val publicKey = edDsa.publicKeyDecoder(EdDSA.Curve.Ed25519).decodeFromByteArray(
            format = EdDSA.PublicKey.Format.DER,
            bytes = publicSigningKey,
        )
        publicKey.signatureVerifier().tryVerifySignature(message, signature)
    }
}
