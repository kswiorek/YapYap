package org.yapyap.backend.testutil

import org.yapyap.backend.protocol.PeerCapabilities
import org.yapyap.backend.protocol.PeerDescriptor
import org.yapyap.backend.protocol.PeerId
import org.yapyap.backend.protocol.PeerAvailabilityClass
import org.yapyap.backend.protocol.PeerIdentityKeys
import org.yapyap.backend.protocol.PeerRelayProfile
import org.yapyap.backend.protocol.PeerRole
import org.yapyap.backend.protocol.SignalSecurityScheme
import org.yapyap.backend.protocol.TorEndpoint

fun testPeer(
    account: String,
    device: String,
    onion: String,
    role: PeerRole = PeerRole.USER_DEVICE,
): PeerDescriptor {
    val id = PeerId(accountName = account, deviceId = device)
    return PeerDescriptor(
        id = id,
        torEndpoint = TorEndpoint(onionAddress = onion),
        role = role,
        identity = PeerIdentityKeys(
            signingPublicKey = ByteArray(32) { (account.length + it).toByte() },
            encryptionPublicKey = ByteArray(32) { (device.length + it).toByte() },
            signingKeyId = "${account}_${device}_sign_v1",
            encryptionKeyId = "${account}_${device}_enc_v1",
        ),
        capabilities = PeerCapabilities(
            supportsWebRtcData = true,
            supportsWebRtcMedia = true,
            supportedSignalSecuritySchemes = setOf(
                SignalSecurityScheme.PLAINTEXT_TEST_ONLY,
                SignalSecurityScheme.SIGNED,
                SignalSecurityScheme.ENCRYPTED_AND_SIGNED,
            ),
            supportedProtocolVersions = setOf(1),
            isRelayAvailable = role == PeerRole.HEADLESS_RELAY,
            relayProfile =
                if (role == PeerRole.HEADLESS_RELAY) {
                    PeerRelayProfile(
                        willingToStoreMessages = true,
                        maxRetentionSecondsAdvertised = 7 * 24 * 60 * 60,
                        maxStoreBytesAdvertised = 512L * 1024L * 1024L,
                        expectedAvailabilityClass = PeerAvailabilityClass.HEADLESS_ALWAYS_ON,
                    )
                } else {
                    PeerRelayProfile(
                        willingToStoreMessages = true,
                        maxRetentionSecondsAdvertised = 2 * 24 * 60 * 60,
                        maxStoreBytesAdvertised = 128L * 1024L * 1024L,
                        expectedAvailabilityClass = PeerAvailabilityClass.MOBILE_INTERMITTENT,
                    )
                },
        ),
        descriptorVersion = 1,
        issuedAtEpochSeconds = 1_700_000_000L,
        expiresAtEpochSeconds = 1_700_086_400L,
        nonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
        signature = ByteArray(64) { 42 },
        signatureKeyId = "${account}_${device}_sign_v1",
    )
}
