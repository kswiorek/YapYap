package org.yapyap.transport.tor

import org.yapyap.protocol.TorEndpoint
import org.yapyap.protocol.envelopes.BinaryEnvelope
import org.yapyap.transport.session.SessionPhase
import org.yapyap.transport.session.SessionStatus
import kotlin.jvm.JvmInline

internal data class TorIncomingFrame(
    val source: TorEndpoint,
    val payload: ByteArray,
)

/**
 * @param source the endpoint the packet arrived from: the onion we dialed (session we opened),
 *   or the endpoint the dialer claims (one-shot frame, or a stream someone else opened).
 * @param link the open stream it arrived on, null for one-shot frames. Replies sent with
 *   `via = link` go back to whoever actually sent the packet, without a new dial.
 */
internal data class TorIncomingEnvelope(
    val source: TorEndpoint,
    val envelope: BinaryEnvelope,
    val link: TorLinkId? = null,
)

/** Identifies one open Tor stream (either direction) for the life of the transport. */
@JvmInline
internal value class TorLinkId(val value: Long)

/** Last-known session state for one onion endpoint (see [SessionStatus] for the contract). */
internal data class TorSessionState(
    val endpoint: TorEndpoint,
    override val phase: SessionPhase,
    override val reason: String? = null,
) : SessionStatus {
    companion object {
        /** [reason] when the dial failed because Tor found no descriptor for the onion. */
        const val REASON_ONION_NOT_FOUND: String = "onion-not-found"
    }
}
