package org.yapyap.backend.protocol

data class TorEndpoint(
    val onionAddress: String,
    val port: Int = 80,
) {
    init {
        require(onionAddress.endsWith(".onion")) { "onionAddress must end with .onion" }
        require(port in 1..65535) { "port must be in range 1..65535" }
    }
}

enum class SignalSecurityScheme(val wireValue: Byte, val nonceSize: Int) {
    PLAINTEXT_TEST_ONLY(0, 24),
    SIGNED(1, 24),
    ENCRYPTED_AND_SIGNED(2, 24);

    companion object {
        fun fromWireValue(value: Byte): SignalSecurityScheme =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unsupported signal security scheme wire value: $value")
    }
}