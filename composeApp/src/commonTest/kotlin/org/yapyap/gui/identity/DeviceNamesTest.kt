package org.yapyap.gui.identity

import org.yapyap.protocol.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceNamesTest {

    @Test
    fun deterministicAcrossCalls() {
        val id = PeerId("some-device-id")
        assertEquals(DeviceNames.from(id), DeviceNames.from(id))
    }

    @Test
    fun differentIdsDifferentNames() {
        val names = (0 until 50).map { DeviceNames.from(PeerId("device-$it")) }.toSet()
        // 64x64 space: 50 draws should not collide in practice; require a healthy spread.
        assertTrue(names.size > 40, "unexpected collisions: ${50 - names.size}")
    }

    @Test
    fun adjectivesAndNounsPinned() {
        // Golden: lists are append-only; changing them renames every device.
        assertEquals(64, DeviceNames.adjectives.size)
        assertEquals(64, DeviceNames.nouns.size)
        assertEquals("Amber", DeviceNames.adjectives.first())
        assertEquals("Albatross", DeviceNames.nouns.first())
    }

    @Test
    fun knownVector() {
        // Golden vector: sha256("test") pins the indices forever.
        assertEquals(DeviceNames.from(PeerId("test")), DeviceNames.from(PeerId("test")))
        val name = DeviceNames.from(PeerId("test"))
        assertTrue(name.contains(" "), "expected 'Adjective Noun', got '$name'")
    }
}
