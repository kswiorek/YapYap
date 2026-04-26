package org.yapyap.backend.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.runBlocking

class KmpCryptoProvider(
    private val provider: CryptographyProvider = CryptographyProvider.Default,
) : CryptoProvider {
    override fun sha256(bytes: ByteArray): ByteArray = runBlocking {
        provider.get(SHA256).hasher().hash(bytes)
    }
}
