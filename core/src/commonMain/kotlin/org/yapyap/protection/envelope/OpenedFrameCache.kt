package org.yapyap.protection.envelope

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.yapyap.protocol.PeerId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Plaintext of recently opened ratchet-sealed file frames (the OFFER's sealed half, the REQUEST
 * body), kept in memory for a short while.
 *
 * Opening a ratchet frame uses up its message key, so a byte-identical redelivery would fail as a
 * replay (PERMANENT). The receiver defers some frames after opening them (the receive engine is
 * still recovering, or a store call failed), and the sender then redelivers the same bytes. A hit
 * here lets that redelivery through. Only a frame whose author signature already verified reaches
 * this cache, and only the exact same sealed bytes from the same author hit it, so it admits
 * nothing a plain redelivery would not. Nothing is persisted: a redelivery after a restart still
 * fails as a replay.
 */
internal class OpenedFrameCache(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val ttl: Duration = DEFAULT_TTL,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private class Entry(val plaintext: ByteArray, val openedAt: TimeMark)

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>()

    /** Returns the cached plaintext for [sealed] from [source], or opens it with [open] and caches it. */
    suspend fun openOnce(
        source: PeerId,
        sealed: ByteArray,
        digest: suspend (ByteArray) -> ByteArray,
        open: suspend () -> ByteArray,
    ): ByteArray {
        val key = source.id + ":" + digest(sealed).toHexString()
        mutex.withLock {
            val hit = entries[key]
            if (hit != null && hit.openedAt.elapsedNow() < ttl) return hit.plaintext.copyOf()
            if (hit != null) entries.remove(key)
        }
        val plaintext = open()
        mutex.withLock {
            entries.remove(key)
            entries[key] = Entry(plaintext.copyOf(), timeSource.markNow())
            while (entries.size > capacity) entries.remove(entries.keys.first())
        }
        return plaintext
    }

    private companion object {
        const val DEFAULT_CAPACITY = 256
        val DEFAULT_TTL = 10.minutes
    }
}
