package org.yapyap.backend.protocol

data class DeviceAddress(
    val accountId: String,
    val deviceId: String,
) {
    init {
        require(accountId.isNotBlank()) { "accountId must not be blank" }
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

enum class SignalSecurityScheme(val wireValue: Byte) {
    PLAINTEXT_TEST_ONLY(0),
    SIGNED(1),
    ENCRYPTED_AND_SIGNED(2);

    companion object {
        fun fromWireValue(value: Byte): SignalSecurityScheme =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported signal security scheme wire value: $value")
    }
}