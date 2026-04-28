package org.yapyap.backend.time

import kotlin.time.Clock

/**
 * Provides current Unix epoch time in seconds.
 */
fun interface EpochSecondsProvider {
    fun nowEpochSeconds(): Long
}

/**
 * Provides current Unix epoch time in milliseconds.
 */
fun interface EpochMillisecondsProvider {
    fun nowEpochMilliseconds(): Long
}

/**
 * Multiplatform default implementation backed by Kotlin's system clock.
 */
object SystemEpochMillisecondsProvider : EpochMillisecondsProvider {
    override fun nowEpochMilliseconds(): Long = Clock.System.now().toEpochMilliseconds()
}

/**
 * Multiplatform default implementation backed by Kotlin's system clock.
 */
object SystemEpochSecondsProvider : EpochSecondsProvider {
    override fun nowEpochSeconds(): Long =
        SystemEpochMillisecondsProvider.nowEpochMilliseconds() / 1_000L
}

