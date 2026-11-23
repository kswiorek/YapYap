package org.yapyap.protection.service

import org.yapyap.protection.envelope.BootstrapProtection
import org.yapyap.protection.envelope.FileEnvelopeProtection
import org.yapyap.protection.envelope.MessageProtection
import org.yapyap.protection.envelope.SystemProtection
import org.yapyap.protection.envelope.WebRtcSignalProtection
import org.yapyap.protocol.PeerId
import org.yapyap.protocol.SignalSecurityScheme
import kotlin.time.Instant

/**
 * The protection layer's whole surface: one port per envelope family, grouped as the single
 * hand-off to routing. The group holds no behavior of its own — each family keeps the shape its
 * envelope (and crypto) needs, so a more complex family is not flattened into a `protect`/`open`
 * pair: FILE carries transfer keys, chunk AEAD and OFFER sealing ([FileEnvelopeProtection]),
 * bootstrap carries its own context and the AEAD intro gate ([BootstrapProtection]).
 *
 * File routing alone holds [file] (design §4); the other families are used by the routing
 * service that owns their packet type.
 */
internal interface EnvelopeProtections {
    val signal: WebRtcSignalProtection
    val message: MessageProtection
    val system: SystemProtection
    val bootstrap: BootstrapProtection
    val file: FileEnvelopeProtection
}

/**
 * Shared context for the families whose protection scheme comes from the caller rather than from
 * the payload (signal, message, system).
 */
internal data class EnvelopeProtectContext(
    val createdAt: Instant,
    val sourceDeviceId: PeerId,
    val targetDeviceId: PeerId,
    val securityScheme: SignalSecurityScheme,
)
