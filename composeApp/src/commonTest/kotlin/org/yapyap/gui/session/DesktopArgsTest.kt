package org.yapyap.gui.session

import org.yapyap.orchestrator.LoopbackTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DesktopArgsTest {

    @Test
    fun emptyArgs_meanProductionDefaults() {
        assertEquals(DesktopArgs(), parseDesktopArgs(emptyArray()))
    }

    @Test
    fun fullDevProfile_parses() {
        val parsed = parseDesktopArgs(
            arrayOf("--data-dir", ".yapyap-dev-a", "--loopback", "--keyring-ns", "dev-a"),
        )
        assertEquals(".yapyap-dev-a", parsed.dataDir)
        assertEquals(LoopbackTransport.TOR, parsed.loopback)
        assertEquals("dev-a", parsed.keyringNamespace)
    }

    @Test
    fun equalsForm_parses() {
        val parsed = parseDesktopArgs(arrayOf("--data-dir=.yapyap-dev-b", "--keyring-ns=dev-b"))
        assertEquals(".yapyap-dev-b", parsed.dataDir)
        assertEquals("dev-b", parsed.keyringNamespace)
        assertEquals(LoopbackTransport.NONE, parsed.loopback)
    }

    @Test
    fun unknownFlag_failsFast() {
        assertFailsWith<IllegalArgumentException> {
            parseDesktopArgs(arrayOf("--profile", "dev-a"))
        }
    }

    @Test
    fun missingValue_failsFast() {
        assertFailsWith<IllegalArgumentException> {
            parseDesktopArgs(arrayOf("--data-dir"))
        }
    }

    @Test
    fun flagAsValue_failsFast() {
        assertFailsWith<IllegalArgumentException> {
            parseDesktopArgs(arrayOf("--data-dir", "--loopback"))
        }
    }

    @Test
    fun loopbackValue_failsFast() {
        assertFailsWith<IllegalArgumentException> {
            parseDesktopArgs(arrayOf("--loopback=yes"))
        }
    }

    @Test
    fun lastFlagWins() {
        val parsed = parseDesktopArgs(arrayOf("--keyring-ns", "a", "--keyring-ns=b"))
        assertEquals("b", parsed.keyringNamespace)
        assertNull(parseDesktopArgs(arrayOf("--loopback", "--data-dir", "x")).keyringNamespace)
    }
}
