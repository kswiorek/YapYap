package org.yapyap.backend.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

class KmpCryptoProviderTest {
    private val crypto = KmpCryptoProvider()

    @Test
    fun sha256MatchesKnownVector() {
        val digest = crypto.sha256("abc".encodeToByteArray())
        val digestHex = crypto.toHex(digest)
        assertEquals(
            expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            actual = digestHex,
        )
    }

    @Test
    fun accountAndDeviceIdsUseSha256Fingerprint() {
        val accountPk = byteArrayOf(1, 2, 3, 4)
        val devicePk = byteArrayOf(4, 3, 2, 1)
        val accountId = crypto.accountIdFromPublicKey(accountPk)
        val deviceId = crypto.deviceIdFromPublicKey(devicePk)
        assertEquals(64, accountId.length)
        assertEquals(64, deviceId.length)
    }
}
