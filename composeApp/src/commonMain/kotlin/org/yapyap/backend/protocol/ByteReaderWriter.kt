package org.yapyap.backend.protocol

class ByteReader(private val bytes: ByteArray) {
    private var position: Int = 0

    fun readByte(): Byte {
        require(position < bytes.size) { "Unexpected end of envelope" }
        return bytes[position++]
    }

    fun readUnsignedByte(): Int = readByte().toInt() and 0xff

    fun readBytes(size: Int): ByteArray {
        require(size >= 0) { "Size must be non-negative" }
        require(position + size <= bytes.size) { "Unexpected end of envelope" }
        val out = bytes.copyOfRange(position, position + size)
        position += size
        return out
    }

    fun readLong(): Long {
        var value = 0L
        repeat(8) {
            value = (value shl 8) or readUnsignedByte().toLong()
        }
        return value
    }

    fun readInt(): Int {
        var value = 0
        repeat(4) {
            value = (value shl 8) or readUnsignedByte()
        }
        return value
    }

    fun readShort(): Int {
        val high = readUnsignedByte()
        val low = readUnsignedByte()
        return (high shl 8) or low
    }

    fun readString(): String {
        val len = readShort()
        require(len != 0xffff) { "String cannot be null" }
        return readBytes(len).decodeToString()
    }

    fun readNullableString(): String? {
        val len = readShort()
        if (len == 0xffff) return null
        return readBytes(len).decodeToString()
    }

    fun readByteArray(): ByteArray {
        val len = readInt()
        require(len >= 0) { "Byte array length must be >= 0" }
        return readBytes(len)
    }

    fun readNullableByteArray(): ByteArray? {
        val len = readInt()
        if (len < 0) return null
        return readBytes(len)
    }

    fun requireFullyRead() {
        require(position == bytes.size) { "Envelope has trailing bytes" }
    }
}

class ByteWriter(initialCapacity: Int) {
    private var buffer = ByteArray(initialCapacity)
    private var size = 0

    fun writeByte(value: Int) {
        ensureCapacity(1)
        buffer[size++] = value.toByte()
    }

    fun writeBytes(value: ByteArray) {
        ensureCapacity(value.size)
        value.copyInto(buffer, destinationOffset = size)
        size += value.size
    }

    fun writeLong(value: Long) {
        var shift = 56
        while (shift >= 0) {
            writeByte(((value ushr shift) and 0xff).toInt())
            shift -= 8
        }
    }

    fun writeInt(value: Int) {
        var shift = 24
        while (shift >= 0) {
            writeByte((value ushr shift) and 0xff)
            shift -= 8
        }
    }

    fun writeShort(value: Int) {
        writeByte((value ushr 8) and 0xff)
        writeByte(value and 0xff)
    }

    fun writeString(value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= Short.MAX_VALUE) { "String exceeds max size" }
        writeShort(bytes.size)
        writeBytes(bytes)
    }

    fun writeNullableString(value: String?) {
        if (value == null) {
            writeShort(0xffff)
            return
        }
        writeString(value)
    }

    fun writeByteArray(value: ByteArray) {
        writeInt(value.size)
        writeBytes(value)
    }

    fun writeNullableByteArray(value: ByteArray?) {
        if (value == null) {
            writeInt(-1)
            return
        }
        writeByteArray(value)
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun ensureCapacity(extraBytes: Int) {
        val minCapacity = size + extraBytes
        if (minCapacity <= buffer.size) return

        var newSize = buffer.size * 2
        while (newSize < minCapacity) {
            newSize *= 2
        }
        buffer = buffer.copyOf(newSize)
    }
}