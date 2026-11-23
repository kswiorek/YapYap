package org.yapyap.wiring

/**
 * Relations between two layers' config values. A layer's config checks its own values in its
 * `init`; it can't see another layer's, so every relation that spans two layers lives here.
 * [ConfigStore] refuses a settings change that breaks one, and drops an override that breaks one
 * when loading.
 */
internal object ConfigInvariants {

    /** What [config] breaks, one message per broken relation; empty when it holds. */
    fun violations(config: RuntimeConfig): List<String> = buildList {
        val router = config.router
        val crypto = config.crypto
        val orchestrator = config.orchestrator

        // A ratchet-sealed OFFER half replayed at the transfer TTL edge must still decrypt
        // (design §3.3).
        if (router.fileTransferTtl >= crypto.supersededSessionRetention) {
            add(
                "File transfer TTL (${router.fileTransferTtl}) must be shorter than the superseded " +
                        "session retention (${crypto.supersededSessionRetention})",
            )
        }

        // A pushed or relayed file can arrive up to a transfer TTL before its message; the
        // unreferenced receive has to outlive that window.
        if (orchestrator.unreferencedFileRetention < router.fileTransferTtl) {
            add(
                "Unreferenced file retention (${orchestrator.unreferencedFileRetention}) must be at least " +
                        "the file transfer TTL (${router.fileTransferTtl})",
            )
        }

        // The push keeps up to maxInFlightChunks chunks in flight; staging must hold a full
        // window that arrives out of order, or a reordered window can never land.
        val limits = try {
            fileLimitsFor(config)
        } catch (e: IllegalArgumentException) {
            add(e.message ?: "Transport frames are too small for file chunks")
            null
        }
        if (limits != null) {
            val window = router.maxInFlightChunks.toLong() * limits.chunkSizeBytes
            if (orchestrator.maxStagedBytes < window) {
                add(
                    "Max staged bytes (${orchestrator.maxStagedBytes}) must hold the push window " +
                            "(${router.maxInFlightChunks} chunks of ${limits.chunkSizeBytes} bytes = $window)",
                )
            }
        }

        // The fetch holder pauses after a window without PROGRESS, and the receiver reports
        // PROGRESS at checkpoints: a window no larger than a checkpoint would leave the holder
        // waiting for the timed checkpoint on every window.
        if (router.fileStreamWindowBytes <= orchestrator.fileCheckpointBytes) {
            add(
                "Fetch window (${router.fileStreamWindowBytes} bytes) must be larger than the checkpoint " +
                        "size (${orchestrator.fileCheckpointBytes} bytes)",
            )
        }

        // The sender deposits at relays after chunkAckTimeout without ACKs; the receiver's gap
        // trigger must give it that chance before re-requesting.
        if (orchestrator.fileStallTimeout <= router.chunkAckTimeout) {
            add(
                "File stall timeout (${orchestrator.fileStallTimeout}) must be longer than the chunk ACK " +
                        "timeout (${router.chunkAckTimeout})",
            )
        }

        // The newcomer's session covers the whole human and network round trip, while the
        // bootstrap envelope TTL only bounds replay; a session shorter than the envelope would
        // burn the secret while the intro can still arrive.
        if (orchestrator.onboardingSessionLifetime <= router.bootstrapIntroLifetime) {
            add(
                "Onboarding session lifetime (${orchestrator.onboardingSessionLifetime}) must be longer than " +
                        "the bootstrap intro lifetime (${router.bootstrapIntroLifetime})",
            )
        }
    }
}
