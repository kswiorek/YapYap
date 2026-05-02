package org.yapyap.backend.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class PacketIdTest {

    @Test
    fun fromHex_toHex_roundTrips() {
        val hex = "0".repeat(PacketId.SIZE_BYTES) + "f".repeat(PacketId.SIZE_BYTES)
        val id = PacketId.fromHex(hex)
        assertEquals(hex, id.toHex())
        assertContentEquals(PacketId.fromHex(hex).toByteArray(), id.toByteArray())
    }

    @Test
    fun fromBytes_preservesBytes() {
        val bytes = ByteArray(PacketId.SIZE_BYTES) { it.toByte() }
        val id = PacketId.fromBytes(bytes)
        assertContentEquals(bytes, id.toByteArray())
    }

    @Test
    fun fromHex_rejectsWrongLength() {
        assertFailsWith<IllegalArgumentException> {
            PacketId.fromHex("ab")
        }
    }

    @Test
    fun equals_andHashCode_byValue() {
        val a = PacketId.fromHex("00".repeat(PacketId.SIZE_BYTES))
        val b = PacketId.fromHex("00".repeat(PacketId.SIZE_BYTES))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun random_producesValidId() {
        val r = Random(42L)
        val a = PacketId.random(r)
        val b = PacketId.random(r)
        assertNotEquals(a, b)
        assertEquals(PacketId.SIZE_BYTES, a.toByteArray().size)
    }
}
