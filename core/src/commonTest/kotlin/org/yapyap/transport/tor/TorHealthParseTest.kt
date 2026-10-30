package org.yapyap.transport.tor

import org.yapyap.transport.tor.backend.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TorHealthParseTest {

    private val stuckLine = "WARN BOOTSTRAP PROGRESS=5 TAG=conn SUMMARY=\"Connecting to a relay\" " +
            "WARNING=\"Connection refused\" REASON=CONNECTREFUSED COUNT=12 RECOMMENDATION=ignore " +
            "HOSTID=\"0123456789ABCDEF0123456789ABCDEF01234567\" HOSTADDR=\"198.51.100.7:9001\""

    @Test
    fun statusEvent_withPrefix_parsesQuotedArgs() {
        val event = assertNotNull(
            parseTorStatusEvent("650 STATUS_CLIENT NOTICE BOOTSTRAP PROGRESS=5 TAG=conn SUMMARY=\"Connecting to a relay\""),
        )
        assertEquals("NOTICE", event.severity)
        assertEquals("BOOTSTRAP", event.action)
        assertEquals("5", event.args["PROGRESS"])
        assertEquals("Connecting to a relay", event.args["SUMMARY"])
    }

    @Test
    fun statusEvent_escapedQuote_isUnescaped() {
        val event = assertNotNull(parseTorStatusEvent("WARN BOOTSTRAP PROGRESS=5 WARNING=\"say \\\"hi\\\"\""))
        assertEquals("say \"hi\"", event.args["WARNING"])
    }

    @Test
    fun statusEvent_malformed_returnsNull() {
        assertNull(parseTorStatusEvent(""))
        assertNull(parseTorStatusEvent("650 STATUS_CLIENT"))
        assertNull(parseTorStatusEvent("NOTICE"))
    }

    @Test
    fun warnBootstrap_reportsProblem() {
        val health = TorHealth.Starting.afterBootstrapEvent(assertNotNull(parseTorStatusEvent(stuckLine)))
        assertEquals(
            TorHealth.Bootstrapping(
                progress = 5,
                problem = TorBootstrapProblem(
                    reason = "CONNECTREFUSED",
                    warning = "Connection refused",
                    count = 12,
                    hostAddr = "198.51.100.7:9001",
                ),
            ),
            health,
        )
    }

    @Test
    fun noticeAtSameProgress_keepsProblem_progressClearsIt() {
        val stuck = TorHealth.Starting.afterBootstrapEvent(assertNotNull(parseTorStatusEvent(stuckLine)))
        val sameProgress =
            stuck.afterBootstrapEvent(assertNotNull(parseTorStatusEvent("NOTICE BOOTSTRAP PROGRESS=5 TAG=conn")))
        assertEquals(stuck, sameProgress)

        val moved =
            sameProgress.afterBootstrapEvent(assertNotNull(parseTorStatusEvent("NOTICE BOOTSTRAP PROGRESS=10 TAG=conn_done")))
        assertEquals(TorHealth.Bootstrapping(10, null), moved)
    }

    @Test
    fun hundredPercent_isReady() {
        val event = assertNotNull(parseTorStatusEvent("NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\""))
        assertEquals(TorHealth.Ready, TorHealth.Bootstrapping(95, null).afterBootstrapEvent(event))
    }

    @Test
    fun nonBootstrapEvents_leaveHealthUnchanged() {
        val event = assertNotNull(parseTorStatusEvent("NOTICE CIRCUIT_ESTABLISHED"))
        assertEquals(TorHealth.Bootstrapping(30, null), TorHealth.Bootstrapping(30, null).afterBootstrapEvent(event))
    }

    @Test
    fun liveness_parsesWithAndWithoutPrefix() {
        assertEquals(true, parseNetworkLiveness("650 NETWORK_LIVENESS UP"))
        assertEquals(false, parseNetworkLiveness("DOWN"))
        assertNull(parseNetworkLiveness("SIDEWAYS"))
        assertNull(parseNetworkLiveness(""))
    }

    @Test
    fun liveness_onlyTogglesOnceBootstrapped() {
        assertEquals(TorHealth.NetworkDown, TorHealth.Ready.afterLiveness(up = false))
        assertEquals(TorHealth.Ready, TorHealth.NetworkDown.afterLiveness(up = true))
        assertEquals(TorHealth.Bootstrapping(5, null), TorHealth.Bootstrapping(5, null).afterLiveness(up = false))
    }

    @Test
    fun socksListeners_parsesFirstPort() {
        assertEquals(45651, socksPortFromListeners("\"127.0.0.1:45651\""))
        assertEquals(9050, socksPortFromListeners("\"[::1]:9050\" \"127.0.0.1:9051\""))
        assertEquals(9150, socksPortFromListeners("127.0.0.1:9150"))
    }

    @Test
    fun socksListeners_noneYet_returnsNull() {
        assertNull(socksPortFromListeners(null))
        assertNull(socksPortFromListeners(""))
        assertNull(socksPortFromListeners("\"unix:/tmp/socks\""))
    }
}
