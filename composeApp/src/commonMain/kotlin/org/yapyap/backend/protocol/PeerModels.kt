package org.yapyap.backend.protocol

enum class PeerRole {
    USER_DEVICE,
    HEADLESS_RELAY,
}

data class PeerId(
    val accountName: String,
    val deviceId: String,
) {
    init {
        require(accountName.isNotBlank()) { "accountName must not be blank" }
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
    }
}

data class TorEndpoint(
    val onionAddress: String,
    val port: Int = 80,
) {
    init {
        require(onionAddress.endsWith(".onion")) { "onionAddress must end with .onion" }
        require(port in 1..65535) { "port must be in range 1..65535" }
    }
}

data class PeerDescriptor(
    val id: PeerId,
    val torEndpoint: TorEndpoint,
    val role: PeerRole,
    val announcedAtEpochSeconds: Long,
)

