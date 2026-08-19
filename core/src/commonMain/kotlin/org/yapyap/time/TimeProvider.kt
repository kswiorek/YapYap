package org.yapyap.time

import kotlin.time.Clock

/**
 * Provides current Unix epoch time in seconds.
 */
interface EpochProvider {
    fun nowEpochSeconds(): Long
    fun nowEpochMilliseconds(): Long
}

/**
 * Multiplatform default implementation backed by Kotlin's system clock.
 */
object SystemEpochProvider : EpochProvider {
    override fun nowEpochSeconds(): Long =
        Clock.System.now().epochSeconds

    override fun nowEpochMilliseconds(): Long =
        Clock.System.now().toEpochMilliseconds()
}

/**
 * Returns a constant epoch second value — useful for deterministic encode/decode tests.
 */
class FixedEpochProvider(private var epochSeconds: Long) : EpochProvider {
    override fun nowEpochSeconds(): Long = epochSeconds
    override fun nowEpochMilliseconds(): Long = epochSeconds * 1000L

    fun advanceTo(epochSeconds: Long) {
        this.epochSeconds = epochSeconds
    }

    fun advanceBy(seconds: Long) {
        advanceTo(epochSeconds + seconds)
    }
}

