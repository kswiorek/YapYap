package org.yapyap.protection.service

import org.yapyap.protection.envelope.BootstrapProtection
import org.yapyap.protection.envelope.FileProtection
import org.yapyap.protection.envelope.HopProtection
import org.yapyap.protection.envelope.MessageProtection
import org.yapyap.protection.envelope.WebRtcSignalProtection

/**
 * The protection layer's whole surface, grouped as the single hand-off to routing: the hop layer
 * every family shares ([hop]) and one content port per family that has content. SYSTEM has none:
 * it is header-only and hop-signed. Each family keeps the shape its content needs — FILE carries
 * seals, chunk AEAD and REQUESTs, bootstrap its AEAD intro gate.
 *
 * File routing alone holds [file]; the other families are used by the routing service that owns
 * their packet type.
 */
internal class EnvelopeProtections(
    val hop: HopProtection,
    val signal: WebRtcSignalProtection,
    val message: MessageProtection,
    val bootstrap: BootstrapProtection,
    val file: FileProtection,
)
