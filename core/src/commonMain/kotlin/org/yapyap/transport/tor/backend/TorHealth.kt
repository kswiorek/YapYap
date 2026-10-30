package org.yapyap.transport.tor.backend

/**
 * Daemon-level Tor health, independent of our onion's publish state
 * ([TorPublishState]). Transport vocabulary only: upper layers map it to
 * their own types, the same way [TorPublishState] is mapped.
 */
internal sealed interface TorHealth {
    /** No daemon running (before start, after stop). */
    data object Stopped : TorHealth

    /** Daemon launched, no bootstrap status observed yet. */
    data object Starting : TorHealth

    /**
     * Bootstrap below 100%. [problem] is Tor's own explanation when it reports
     * one (a `WARN BOOTSTRAP` status event) and clears once progress moves.
     */
    data class Bootstrapping(val progress: Int, val problem: TorBootstrapProblem?) : TorHealth

    /** Bootstrapped and Tor considers the network live. */
    data object Ready : TorHealth

    /** Bootstrapped earlier, but Tor reports `NETWORK_LIVENESS DOWN`. */
    data object NetworkDown : TorHealth

    /**
     * The device itself has no usable network (the platform network monitor
     * says so) and Tor's network is disabled. Tor's own events can't clear it;
     * only the monitor reporting the network back does.
     */
    data object NoNetwork : TorHealth
}

/**
 * Why bootstrap is not progressing, as reported by Tor in a
 * `STATUS_CLIENT WARN BOOTSTRAP ... WARNING=... REASON=... COUNT=... HOSTADDR=...` event.
 * [reason] is Tor's machine-readable code (`CONNECTREFUSED`, `NOROUTE`, `TIMEOUT`, ...).
 */
internal data class TorBootstrapProblem(
    val reason: String,
    val warning: String?,
    val count: Int?,
    val hostAddr: String?,
)

/**
 * One `STATUS_CLIENT` / `STATUS_GENERAL` event, e.g.
 * `WARN BOOTSTRAP PROGRESS=5 TAG=conn SUMMARY="Connecting to a relay" REASON=NOROUTE`.
 */
internal data class TorStatusEvent(
    val severity: String,
    val action: String,
    val args: Map<String, String>,
)

/**
 * Parses a status event line, with or without the leading `650 STATUS_CLIENT`
 * prefix (and the `status/bootstrap-phase` GETINFO value, which has the same
 * shape). Quoted values may contain spaces and `\"` escapes. Null when the line
 * has no severity + action.
 */
internal fun parseTorStatusEvent(data: String): TorStatusEvent? {
    val tokens = tokenizeTorLine(data).toMutableList()
    if (tokens.firstOrNull()?.all { it.isDigit() } == true) tokens.removeAt(0)
    if (tokens.firstOrNull()?.startsWith("STATUS_") == true) tokens.removeAt(0)
    if (tokens.size < 2) return null
    val args = tokens.drop(2).mapNotNull { token ->
        val eq = token.indexOf('=')
        if (eq <= 0) null else token.substring(0, eq) to token.substring(eq + 1)
    }.toMap()
    return TorStatusEvent(severity = tokens[0], action = tokens[1], args = args)
}

/** `UP` / `DOWN` from a `NETWORK_LIVENESS` event, with or without the `650 NETWORK_LIVENESS` prefix. */
internal fun parseNetworkLiveness(data: String): Boolean? =
    when (tokenizeTorLine(data).lastOrNull()?.uppercase()) {
        "UP" -> true
        "DOWN" -> false
        else -> null
    }

internal fun TorStatusEvent.toBootstrapProblem(): TorBootstrapProblem? {
    if (action != "BOOTSTRAP" || severity != "WARN") return null
    return TorBootstrapProblem(
        reason = args["REASON"] ?: "UNKNOWN",
        warning = args["WARNING"],
        count = args["COUNT"]?.toIntOrNull(),
        hostAddr = args["HOSTADDR"],
    )
}

/**
 * Next health after a `BOOTSTRAP` status event. A NOTICE at the same progress
 * keeps an already-reported problem (Tor interleaves both); only progress clears it.
 */
internal fun TorHealth.afterBootstrapEvent(event: TorStatusEvent): TorHealth {
    if (event.action != "BOOTSTRAP" || this == TorHealth.NoNetwork) return this
    val progress = event.args["PROGRESS"]?.toIntOrNull() ?: return this
    if (progress >= 100) return if (this == TorHealth.NetworkDown) this else TorHealth.Ready
    val previous = this as? TorHealth.Bootstrapping
    val problem = event.toBootstrapProblem()
        ?: previous?.problem?.takeIf { previous.progress == progress }
    return TorHealth.Bootstrapping(progress, problem)
}

/**
 * Next health after a `NETWORK_LIVENESS` event. Only meaningful once
 * bootstrapped: during bootstrap the [TorHealth.Bootstrapping.problem] already
 * says why Tor can't reach the network.
 */
internal fun TorHealth.afterLiveness(up: Boolean): TorHealth = when {
    !up && this == TorHealth.Ready -> TorHealth.NetworkDown
    up && this == TorHealth.NetworkDown -> TorHealth.Ready
    else -> this
}

private fun tokenizeTorLine(data: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < data.length) {
        val c = data[i]
        when {
            inQuotes && c == '\\' && i + 1 < data.length -> {
                current.append(data[i + 1])
                i++
            }

            c == '"' -> inQuotes = !inQuotes
            !inQuotes && c.isWhitespace() -> {
                if (current.isNotEmpty()) tokens.add(current.toString())
                current.clear()
            }

            else -> current.append(c)
        }
        i++
    }
    if (current.isNotEmpty()) tokens.add(current.toString())
    return tokens
}
