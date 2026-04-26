package org.yapyap.backend.testutil

import org.yapyap.backend.protocol.DeviceAddress
import org.yapyap.backend.protocol.TorEndpoint

data class TestPeer(
    val address: DeviceAddress,
    val torEndpoint: TorEndpoint,
)

fun testDevice(
    account: String,
    device: String,
    onion: String,
): TestPeer {
    val address = DeviceAddress(accountId = account, deviceId = device)
    return TestPeer(
        address = address,
        torEndpoint = TorEndpoint(onionAddress = onion),
    )
}
