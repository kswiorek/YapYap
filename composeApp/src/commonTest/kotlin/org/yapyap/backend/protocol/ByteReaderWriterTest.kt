package org.yapyap.backend.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ByteReaderWriterTest {

    @Test
    fun string_roundTrip_ascii() {
        val w = ByteWriter(16)
        w.writeString("hello")
        val r = ByteReader(w.toByteArray())
        assertEquals("hello", r.readString())
        r.requireFullyRead()
    }

    @Test
    fun nullableString_null_roundTrip() {
        val w = ByteWriter(8)
        w.writeNullableString(null)
        val r = ByteReader(w.toByteArray())
        assertNull(r.readNullableString())
        r.requireFullyRead()
    }

    @Test
    fun byteArray_empty_roundTrip() {
        val w = ByteWriter(8)
        w.writeByteArray(byteArrayOf())
        val r = ByteReader(w.toByteArray())
        assertContentEquals(byteArrayOf(), r.readByteArray())
        r.requireFullyRead()
    }

    @Test
    fun nullableByteArray_null_roundTrip() {
        val w = ByteWriter(8)
        w.writeNullableByteArray(null)
        val r = ByteReader(w.toByteArray())
        assertNull(r.readNullableByteArray())
        r.requireFullyRead()
    }

    @Test
    fun long_int_roundTrip() {
        val w = ByteWriter(32)
        w.writeLong(Long.MIN_VALUE)
        w.writeLong(Long.MAX_VALUE)
        w.writeInt(-1)
        w.writeInt(0x7fff_ffff)
        val r = ByteReader(w.toByteArray())
        assertEquals(Long.MIN_VALUE, r.readLong())
        assertEquals(Long.MAX_VALUE, r.readLong())
        assertEquals(-1, r.readInt())
        assertEquals(0x7fff_ffff, r.readInt())
        r.requireFullyRead()
    }

    @Test
    fun readByte_failsAtEof() {
        val r = ByteReader(byteArrayOf())
        assertFailsWith<IllegalArgumentException> {
            r.readByte()
        }
    }

    @Test
    fun readString_rejectsNullMarkerForNonNullRead() {
        val w = ByteWriter(8)
        w.writeShort(0xffff)
        val r = ByteReader(w.toByteArray())
        assertFailsWith<IllegalArgumentException> {
            r.readString()
        }
    }

    @Test
    fun requireFullyRead_detectsTrailingBytes() {
        val w = ByteWriter(8)
        w.writeByte(1)
        w.writeByte(2)
        val r = ByteReader(w.toByteArray())
        r.readByte()
        assertFailsWith<IllegalArgumentException> {
            r.requireFullyRead()
        }
    }

    @Test
    fun readBytes_negativeSize_rejected() {
        assertFailsWith<IllegalArgumentException> {
            ByteReader(byteArrayOf()).readBytes(-1)
        }
    }
}
