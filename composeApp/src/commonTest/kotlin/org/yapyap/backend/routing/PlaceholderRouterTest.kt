package org.yapyap.backend.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import org.yapyap.backend.directory.InMemoryPeerDirectory
import org.yapyap.backend.protocol.PeerAvailabilityClass
import org.yapyap.backend.protocol.PeerCapabilities
import org.yapyap.backend.protocol.PeerDescriptor
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.PeerIdentityKeys
import org.yapyap.backend.protocol.PeerRelayProfile
import org.yapyap.backend.protocol.PeerRole
import org.yapyap.backend.protocol.SignalSecurityScheme
import org.yapyap.backend.protocol.TorEndpoint

class PlaceholderRouterTest {
    @Test
    fun routeUsesDirectoryTargetDescriptor() {
        val peer = testPeerDescriptor(accountId = "acc-bob", deviceId = "dev-bob")
        val router = PlaceholderRouter(peerDirectory = InMemoryPeerDirectory(listOf(peer)))
        val route = router.routeForTarget(peer.id)

        assertEquals("acc-bob", route.destinationAccount)
        assertEquals("dev-bob", route.destinationDevice)
        assertEquals(null, route.nextHopDevice)
    }
}

private fun testPeerDescriptor(accountId: String, deviceId: String): PeerDescriptor =
    PeerDescriptor(
        id = PeerId(accountId = accountId, deviceId = deviceId),
        torEndpoint = TorEndpoint(onionAddress = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef12.onion"),
        role = PeerRole.USER_DEVICE,
        identity = PeerIdentityKeys(
            signingPublicKey = ByteArray(32) { it.toByte() },
            encryptionPublicKey = ByteArray(32) { (it + 1).toByte() },
            signingKeyId = "sign-key",
            encryptionKeyId = "enc-key",
        ),
        capabilities = PeerCapabilities(
            supportsWebRtcData = true,
            supportsWebRtcMedia = true,
            supportedSignalSecuritySchemes = setOf(SignalSecurityScheme.PLAINTEXT_TEST_ONLY),
            supportedProtocolVersions = setOf(1),
            isRelayAvailable = false,
            relayProfile = PeerRelayProfile(
                willingToStoreMessages = true,
                maxRetentionSecondsAdvertised = 60,
                maxStoreBytesAdvertised = 1024,
                expectedAvailabilityClass = PeerAvailabilityClass.DESKTOP_USUAL,
            ),
        ),
        descriptorVersion = 1,
        issuedAtEpochSeconds = 100,
        expiresAtEpochSeconds = 200,
        nonce = byteArrayOf(1),
        signature = byteArrayOf(2),
        signatureKeyId = "sign-key",
    )
