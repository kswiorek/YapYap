package org.yapyap.crypto.primitives

import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.ChaCha20Poly1305
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.XDH
import dev.whyoleg.cryptography.random.CryptographyRandom
import org.kotlincrypto.error.SignatureException
import org.yapyap.crypto.identity.IdentityKeyPurpose
import kotlin.random.Random
import org.yapyap.logging.AppLogger
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.logging.NoopAppLogger
import org.yapyap.protocol.SignalSecurityScheme

/**
 * Reference [CryptoProvider] backed by [dev.whyoleg.cryptography].
 *
 * On JVM and Android, depend on `cryptography-provider-jdk-bc` so Ed25519/X25519 public keys can be
 * derived from private key material (stock JDK JCA does not support this).
 */
class KmpCryptoProvider(
    private val provider: CryptographyProvider = CryptographyProvider.Default,
    private val random: Random = CryptographyRandom.Default,
    private val logger: AppLogger = NoopAppLogger,
) : CryptoProvider {
    private val edDsa: EdDSA by lazy { provider.get(EdDSA) }
    private val xdh: XDH by lazy { provider.get(XDH) }
    private val hkdf: HKDF by lazy { provider.get(HKDF) }
    private val chacha20Poly1305: ChaCha20Poly1305 by lazy { provider.get(ChaCha20Poly1305) }

    companion object {
        const val AEAD_KEY_SIZE_BYTES: Int = 32
    }

    override suspend fun sha256(bytes: ByteArray): ByteArray =
        provider.get(SHA256).hasher().hash(bytes)

    override fun randomBytes(size: Int): ByteArray {
        require(size > 0) { "size must be greater than 0" }
        return random.nextBytes(size)
    }

    override fun generateNonce(scheme: SignalSecurityScheme): ByteArray =
        random.nextBytes(scheme.nonceSize)

    override suspend fun deriveSharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(privateKey.isNotEmpty()) { "privateKey must not be empty" }
        require(publicKey.isNotEmpty()) { "publicKey must not be empty" }
        val privateXdhKey = xdh.privateKeyDecoder(XDH.Curve.X25519).decodeFromByteArray(
            format = XDH.PrivateKey.Format.DER,
            bytes = privateKey,
        )
        val publicXdhKey = xdh.publicKeyDecoder(XDH.Curve.X25519).decodeFromByteArray(
            format = XDH.PublicKey.Format.DER,
            bytes = publicKey,
        )
        return privateXdhKey.sharedSecretGenerator().generateSharedSecretToByteArray(publicXdhKey)
    }

    override suspend fun hkdf(
        ikm: ByteArray,
        salt: ByteArray?,
        info: ByteArray,
        outputLength: Int,
    ): ByteArray {
        require(ikm.isNotEmpty()) { "ikm must not be empty" }
        require(outputLength > 0) { "outputLength must be greater than 0" }
        return hkdf.secretDerivation(
            digest = SHA256,
            outputSize = outputLength.bytes,
            salt = salt,
            info = info,
        ).deriveSecretToByteArray(ikm)
    }

    override suspend fun encryptAead(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray?): ByteArray =
        aeadKey(key).cipher().encrypt(
            plaintext = plaintext,
            associatedData = associatedData,
        )

    override suspend fun decryptAead(key: ByteArray, ciphertext: ByteArray, associatedData: ByteArray?): ByteArray =
        aeadKey(key).cipher().decrypt(
            ciphertext = ciphertext,
            associatedData = associatedData,
        )

    override suspend fun privateSigningKeyToPublicKey(privateKey: ByteArray): ByteArray {
        val privateEdDsaKey =  edDsa.privateKeyDecoder(EdDSA.Curve.Ed25519).decodeFromByteArray(EdDSA.PrivateKey.Format.DER, privateKey)

        return  privateEdDsaKey.getPublicKey().encodeToByteArray(EdDSA.PublicKey.Format.DER)
    }

    override suspend fun privateEncryptionKeyToPublicKey(privateKey: ByteArray): ByteArray {
        val privateXdhKey =  xdh.privateKeyDecoder(XDH.Curve.X25519).decodeFromByteArray(XDH.PrivateKey.Format.DER, privateKey)

        return  privateXdhKey.getPublicKey().encodeToByteArray(XDH.PublicKey.Format.DER)
    }

    private suspend fun aeadKey(key: ByteArray): ChaCha20Poly1305.Key {
        require(key.size == AEAD_KEY_SIZE_BYTES) {
            "AEAD key must be $AEAD_KEY_SIZE_BYTES bytes but was ${key.size}"
        }
        return chacha20Poly1305.keyDecoder().decodeFromByteArray(
            format = ChaCha20Poly1305.Key.Format.RAW,
            bytes = key,
        )
    }

    override suspend fun generateSigningKeyPair(): SigningKeyPair {
        val keyPair = edDsa.keyPairGenerator(EdDSA.Curve.Ed25519).generateKey()
        logger.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.CRYPTO_KEYPAIR_GENERATED,
            message = "Generated signing key pair",
            fields = mapOf("keyPurpose" to IdentityKeyPurpose.SIGNING.name),
        )
        return SigningKeyPair(
            publicKey = keyPair.publicKey.encodeToByteArray(EdDSA.PublicKey.Format.DER),
            privateKey = keyPair.privateKey.encodeToByteArray(EdDSA.PrivateKey.Format.DER),
        )
    }

    override suspend fun generateEncryptionKeyPair(): EncryptionKeyPair {
        val keyPair = xdh.keyPairGenerator(XDH.Curve.X25519).generateKey()
        logger.info(
            component = LogComponent.CRYPTO,
            event = LogEvent.CRYPTO_KEYPAIR_GENERATED,
            message = "Generated encryption key pair",
            fields = mapOf("keyPurpose" to IdentityKeyPurpose.ENCRYPTION.name),
        )
        return EncryptionKeyPair(
            publicKey = keyPair.publicKey.encodeToByteArray(XDH.PublicKey.Format.DER),
            privateKey = keyPair.privateKey.encodeToByteArray(XDH.PrivateKey.Format.DER),
        )
    }

    override suspend fun signDetached(privateSigningKey: ByteArray, message: ByteArray): ByteArray {
        val privateKey = edDsa.privateKeyDecoder(EdDSA.Curve.Ed25519).decodeFromByteArray(
            format = EdDSA.PrivateKey.Format.DER,
            bytes = privateSigningKey,
        )
        return privateKey.signatureGenerator().generateSignature(message)
    }

    override suspend fun verifyDetached(publicSigningKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val publicKey = edDsa.publicKeyDecoder(EdDSA.Curve.Ed25519).decodeFromByteArray(
            format = EdDSA.PublicKey.Format.DER,
            bytes = publicSigningKey,
        )
        return try {
            publicKey.signatureVerifier().tryVerifySignature(message, signature)
        } catch (e: SignatureException) {
            logger.warn(
                component = LogComponent.CRYPTO,
                event = LogEvent.SIGNATURE_VERIFICATION_FAILED,
                message = "Detached signature verification failed with exception",
                fields = mapOf("messageLength" to message.size, "exception" to e.toString()),
            )
            false
        }
    }
}
