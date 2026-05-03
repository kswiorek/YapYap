package org.yapyap.backend.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RecordingAppLoggerTest {

    @Test
    fun recordsDebugInfoWarnError_withFieldsAndThrowable() {
        val log = RecordingAppLogger()
        val ex = RuntimeException("x")

        log.debug(LogComponent.DATABASE, LogEvent.DEDUP_CACHE_HIT, "d", mapOf("a" to 1))
        log.info(LogComponent.CRYPTO, LogEvent.STARTED, "i", emptyMap())
        log.warn(LogComponent.ROUTER, LogEvent.MESSAGE_NO_PEERS, "w", mapOf("p" to "q"))
        log.error(LogComponent.TOR_TRANSPORT, LogEvent.ENVELOPE_HANDLE_FAILED, "e", ex, mapOf())

        assertEquals(4, log.entries.size)

        assertSame(LogLevel.DEBUG, log.entries[0].level)
        assertEquals(LogComponent.DATABASE, log.entries[0].component)
        assertEquals(LogEvent.DEDUP_CACHE_HIT, log.entries[0].event)

        assertSame(LogLevel.INFO, log.entries[1].level)
        assertEquals(LogEvent.STARTED, log.entries[1].event)

        assertSame(LogLevel.WARN, log.entries[2].level)
        assertEquals("q", log.entries[2].fields["p"])

        assertSame(LogLevel.ERROR, log.entries[3].level)
        assertSame(ex, log.entries[3].throwable)
    }

    @Test
    fun clear_removesEntries() {
        val log = RecordingAppLogger()
        log.info(LogComponent.CRYPTO, LogEvent.STARTED, "m", emptyMap())
        assertEquals(1, log.entries.size)
        log.clear()
        assertEquals(0, log.entries.size)
    }
}
