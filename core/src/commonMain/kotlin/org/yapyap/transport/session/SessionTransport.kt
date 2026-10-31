package org.yapyap.transport.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Lifecycle phase of one long-lived session (a WebRTC data channel, a Tor stream) to a remote.
 * Shared by every session-capable transport so the router can reason about links without
 * knowing which transport carries them.
 */
internal enum class SessionPhase {
    /** Being set up (WebRTC offer/answer/ICE, Tor dial + stream handshake). */
    NEGOTIATING,

    /** Usable right now: an envelope sent on it leaves immediately. */
    CONNECTED,

    /** The remote declined the session. */
    REJECTED,

    /** An established session ended (orderly close, idle timeout, remote gone). */
    CLOSED,

    /** Setting the session up failed. */
    FAILED,
}

/**
 * Last-known state of one session. Contract for every implementation: [phase] is
 * [SessionPhase.CONNECTED] if and only if the transport's `hasSession` is true for that key,
 * and every close or failure lands here, so a stored state never goes stale relative to the
 * session itself. Subscribers may treat a replayed CONNECTED as ground truth.
 */
internal interface SessionStatus {
    val phase: SessionPhase

    /** Why the session is not (or no longer) connected; null when connected or unknown. */
    val reason: String?
}

/**
 * A transport that keeps sessions to remotes identified by [K] (a device id for WebRTC, an
 * onion endpoint for Tor). Opening is idempotent and asynchronous: [openSession] starts (or
 * joins) the setup and returns; the outcome arrives on [sessionStates].
 */
internal interface SessionTransport<K> {
    /**
     * Per-remote last-known state, current value first. Absence means no session was ever
     * attempted with that remote (or the transport was stopped).
     */
    val sessionStates: Flow<Map<K, SessionStatus>>

    /** Current-state-then-updates for one remote (nothing until it first appears). */
    fun sessionStatesOf(key: K): Flow<SessionStatus> =
        sessionStates.mapNotNull { it[key] }.distinctUntilChanged()

    /** True iff a session to [key] is CONNECTED and can carry data immediately. */
    fun hasSession(key: K): Boolean

    suspend fun openSession(key: K)

    suspend fun closeSession(key: K)
}

/** Terminal (not connected, not in progress) phases. */
internal val SessionPhase.isTerminal: Boolean
    get() = this == SessionPhase.REJECTED || this == SessionPhase.CLOSED || this == SessionPhase.FAILED

/** Emits a key each time its session becomes CONNECTED (including ones already connected at collection). */
internal fun <K> Flow<Map<K, SessionStatus>>.connectedKeys(): Flow<K> = flow {
    var previous = emptySet<K>()
    collect { states ->
        val connected = states.filterValues { it.phase == SessionPhase.CONNECTED }.keys
        (connected - previous).forEach { emit(it) }
        previous = connected
    }
}

/** Emits a key each time its session stops being CONNECTED. */
internal fun <K> Flow<Map<K, SessionStatus>>.disconnectedKeys(): Flow<K> = flow {
    var previous = emptySet<K>()
    collect { states ->
        val connected = states.filterValues { it.phase == SessionPhase.CONNECTED }.keys
        (previous - connected).forEach { emit(it) }
        previous = connected
    }
}
