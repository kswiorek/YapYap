package org.yapyap.protection.service

import org.yapyap.protection.envelope.BootstrapProtection
import org.yapyap.protection.envelope.FileEnvelopeProtection
import org.yapyap.protection.envelope.MessageProtection
import org.yapyap.protection.envelope.SystemProtection
import org.yapyap.protection.envelope.WebRtcSignalProtection

/** The concrete protection layer: every family implementation, wired once by the composition root. */
internal class DefaultEnvelopeProtections(
    override val signal: WebRtcSignalProtection,
    override val message: MessageProtection,
    override val system: SystemProtection,
    override val bootstrap: BootstrapProtection,
    override val file: FileEnvelopeProtection,
) : EnvelopeProtections
