package org.yapyap.transport.tor.backend

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/** One `HS_DESC` control event: `<action> <onion> <auth> <hsdir> ...`. [hsDir] is null when Tor says UNKNOWN. */
internal data class HsDescEvent(
    val action: String,
    val onion: String,
    val hsDir: String?,
)

/** Parses an `HS_DESC` line, with or without the leading `650 HS_DESC`. Null when malformed. */
internal fun parseHsDescEvent(data: String): HsDescEvent? {
    val tokens = data.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }.toMutableList()
    if (tokens.firstOrNull()?.all { it.isDigit() } == true) tokens.removeAt(0)
    if (tokens.firstOrNull() == "HS_DESC") tokens.removeAt(0)
    if (tokens.size < 2) return null
    // HSDIR is "$FINGERPRINT~nickname"; key on the fingerprint (nicknames are optional).
    val hsDir = tokens.getOrNull(3)
        ?.takeIf { it.startsWith("$") }
        ?.substringBefore('~')
    return HsDescEvent(action = tokens[0], onion = tokens[1], hsDir = hsDir)
}

/**
 * Descriptor fetches Tor started (`REQUESTED`) that no directory relay has
 * answered yet (`RECEIVED` / `FAILED`), per onion. A fetch that stays pending
 * for long means that relay is hanging: Tor keeps every stream waiting on it
 * open until its own two-minute SOCKS timeout.
 */
internal class DescriptorFetchTracker(
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    @OptIn(InternalCoroutinesApi::class)
    private val lock = SynchronizedObject()
    private val pending = HashMap<String, HashMap<String, ComparableTimeMark>>()

    fun now(): ComparableTimeMark = timeSource.markNow()

    @OptIn(InternalCoroutinesApi::class)
    fun onEvent(event: HsDescEvent) {
        val hsDir = event.hsDir ?: return
        val onion = onionKey(event.onion)
        synchronized(lock) {
            when (event.action) {
                "REQUESTED" -> pending.getOrPut(onion) { HashMap() }.getOrPut(hsDir) { timeSource.markNow() }
                "RECEIVED", "FAILED" -> {
                    pending[onion]?.remove(hsDir)
                    if (pending[onion]?.isEmpty() == true) pending.remove(onion)
                }
            }
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    fun clear() = synchronized(lock) { pending.clear() }

    /**
     * The longest-waiting unanswered fetch of [onion] (relay fingerprint and how
     * long it has waited), or null. With [since], waiting is counted from no
     * earlier than [since]: a caller watching its own attempt sees how long that
     * attempt has been stuck, even when Tor attached it to an older fetch.
     */
    @OptIn(InternalCoroutinesApi::class)
    fun oldestPending(onion: String, since: ComparableTimeMark? = null): Pair<String, Duration>? =
        synchronized(lock) {
            pending[onionKey(onion)]
                ?.minByOrNull { it.value }
                ?.let { (hsDir, started) ->
                    val from = if (since != null && since > started) since else started
                    hsDir to from.elapsedNow()
                }
        }

    private fun onionKey(onion: String): String = onion.trim().lowercase().removeSuffix(".onion")
}
