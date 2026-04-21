package org.yapyap.backend.protocol

import kotlin.random.Random

class PacketId private constructor(private val bytes: ByteArray) {
    init {
        require(bytes.size == SIZE_BYTES) { "PacketId must be $SIZE_BYTES bytes" }
    }

    fun toByteArray(): ByteArray = bytes.copyOf()

    fun toHex(): String = bytes.joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PacketId) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "PacketId(${toHex()})"

    companion object {
        const val SIZE_BYTES: Int = 16

        fun random(random: Random = Random.Default): PacketId {
            return PacketId(random.nextBytes(SIZE_BYTES))
        }

        fun fromBytes(value: ByteArray): PacketId = PacketId(value.copyOf())

        fun fromHex(hex: String): PacketId {
            require(hex.length == SIZE_BYTES * 2) { "PacketId hex length must be ${SIZE_BYTES * 2}" }
            val out = ByteArray(SIZE_BYTES)
            var index = 0
            while (index < SIZE_BYTES) {
                val start = index * 2
                out[index] = hex.substring(start, start + 2).toInt(16).toByte()
                index++
            }
            return PacketId(out)
        }
    }
}

