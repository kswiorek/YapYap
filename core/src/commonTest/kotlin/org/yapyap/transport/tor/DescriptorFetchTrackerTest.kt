package org.yapyap.transport.tor

import org.yapyap.transport.tor.backend.DescriptorFetchTracker
import org.yapyap.transport.tor.backend.HsDescEvent
import org.yapyap.transport.tor.backend.parseHsDescEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class DescriptorFetchTrackerTest {

    private val onion = "36jd2sqzrtben226fy4iqisukivxjqunaawxklwtg6zjbzxkjygde6id"
    private val relayA = "\$23899620429B1B0E726AC927EF86BEBDE10F1D0C"
    private val relayB = "\$E240921C1614D4FB0467A47AC5ED8B23857D3260"

    @Test
    fun parse_requestedWithPrefix() {
        assertEquals(
            HsDescEvent("REQUESTED", onion, relayA),
            parseHsDescEvent("650 HS_DESC REQUESTED $onion NO_AUTH $relayA~mentoreth7 RuWnw7ubxqv"),
        )
    }

    @Test
    fun parse_bareLine_andUnknownHsDir() {
        assertEquals(
            HsDescEvent("RECEIVED", onion, relayB),
            parseHsDescEvent("RECEIVED $onion NO_AUTH $relayB~DFRI84 RuWnw7ubxqvnithBb2Q0cRVYW6PbkNL66SSCXd"),
        )
        assertEquals(
            HsDescEvent("CREATED", onion, null),
            parseHsDescEvent("CREATED $onion UNKNOWN UNKNOWN W00YA767cT0DuNOfjDh1dN8aJe"),
        )
        assertNull(parseHsDescEvent("650 HS_DESC"))
        assertNull(parseHsDescEvent(""))
    }

    @Test
    fun unansweredRequest_ages_untilAnswered() {
        val time = TestTimeSource()
        val tracker = DescriptorFetchTracker(time)
        tracker.onEvent(HsDescEvent("REQUESTED", onion, relayA))
        time += 25.seconds
        assertEquals(relayA to 25.seconds, tracker.oldestPending(onion))

        tracker.onEvent(HsDescEvent("FAILED", onion, relayA))
        assertNull(tracker.oldestPending(onion))
    }

    @Test
    fun oldestPending_isTheLongestWaiting_andRepeatRequestsKeepTheirStart() {
        val time = TestTimeSource()
        val tracker = DescriptorFetchTracker(time)
        tracker.onEvent(HsDescEvent("REQUESTED", onion, relayA))
        time += 5.seconds
        tracker.onEvent(HsDescEvent("REQUESTED", onion, relayB))
        tracker.onEvent(HsDescEvent("REQUESTED", onion, relayA))
        time += 5.seconds
        assertEquals(relayA to 10.seconds, tracker.oldestPending(onion))

        tracker.onEvent(HsDescEvent("RECEIVED", onion, relayA))
        assertEquals(relayB to 5.seconds, tracker.oldestPending(onion))

        tracker.clear()
        assertNull(tracker.oldestPending(onion))
    }

    @Test
    fun eventsWithoutHsDir_areIgnored() {
        val tracker = DescriptorFetchTracker(TestTimeSource())
        tracker.onEvent(HsDescEvent("REQUESTED", onion, null))
        assertNull(tracker.oldestPending(onion))
    }

    @Test
    fun fetchesAreTrackedPerOnion_withOrWithoutSuffix() {
        val time = TestTimeSource()
        val tracker = DescriptorFetchTracker(time)
        tracker.onEvent(HsDescEvent("REQUESTED", onion, relayA))
        tracker.onEvent(HsDescEvent("REQUESTED", "otheronionaddress", relayB))
        time += 3.seconds
        assertEquals(relayA to 3.seconds, tracker.oldestPending("$onion.onion"))
        assertEquals(relayB to 3.seconds, tracker.oldestPending("otheronionaddress"))
        assertNull(tracker.oldestPending("unknownonion"))
    }

    @Test
    fun since_countsWaitingFromTheCallersAttempt() {
        val time = TestTimeSource()
        val tracker = DescriptorFetchTracker(time)
        tracker.onEvent(HsDescEvent("REQUESTED", onion, relayA))
        time += 60.seconds
        val attemptStart = tracker.now()
        time += 5.seconds
        // An older fetch still hanging counts — our stream is likely waiting on it —
        // but only for as long as our attempt has been waiting.
        assertEquals(relayA to 5.seconds, tracker.oldestPending(onion, since = attemptStart))
        assertEquals(relayA to 65.seconds, tracker.oldestPending(onion))
    }
}
