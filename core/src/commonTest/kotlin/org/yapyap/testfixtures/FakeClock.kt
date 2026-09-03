package org.yapyap.testfixtures

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A deterministic [Clock] for tests: time advances only when the test says so.
 */
class FakeClock(start: Instant = Instant.fromEpochSeconds(0)) : Clock {
    var now: Instant = start
        private set

    override fun now(): Instant = now

    fun advanceTo(instant: Instant) {
        now = instant
    }

    fun advanceBy(duration: Duration) {
        now += duration
    }
}

/**
 * Shorthand for tests that reason in whole epoch seconds without carrying the unit in every name.
 */
fun epochSeconds(seconds: Long): Instant = Instant.fromEpochSeconds(seconds)