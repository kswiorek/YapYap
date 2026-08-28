package org.yapyap.config

// ---------------------------------------------------------------------------
// The fields. This is the single place a setting is defined.
// ---------------------------------------------------------------------------

val FIELDS: List<Field> = listOf(
    NumberField(
        id = "outboxMaxSizeBytes",
        title = "Outbox max size",
        description = "Maximum bytes of outbound packets buffered while offline.",
        group = "Router",
        source = FieldSource.USER,
        unit = "bytes",
        min = 1L,
        readValue = { it.router.outboxMaxSizeBytes },
        writeValue = { cfg, v -> cfg.copy(router = cfg.router.copy(outboxMaxSizeBytes = v)) },
    ),
    NumberField(
        id = "ackLifetimeSeconds",
        title = "ACK lifetime",
        description = "How long to wait for an acknowledgement before retrying.",
        group = "Router",
        source = FieldSource.USER,
        unit = "seconds",
        min = 1L,
        readValue = { it.router.ackLifetimeSeconds },
        writeValue = { cfg, v -> cfg.copy(router = cfg.router.copy(ackLifetimeSeconds = v)) },
    ),
    NumberField(
        id = "gracePeriodSeconds",
        title = "Sync grace period",
        description = "Delay before an out-of-order message is considered missing.",
        group = "Sync",
        source = FieldSource.USER,
        unit = "seconds",
        min = 0L,
        readValue = { it.orchestrator.syncGracePeriodSeconds },
        writeValue = { cfg, v -> cfg.copy(orchestrator = cfg.orchestrator.copy(syncGracePeriodSeconds = v)) },
    ),
    NumberField(
        id = "messageLifetimeSeconds",
        title = "Message lifetime",
        description = "How long messages are retained. Controlled by the network.",
        group = "Router",
        source = FieldSource.NETWORK,
        unit = "seconds",
        min = 1L,
        readValue = { it.router.binaryEnvelopeLifetimeSeconds },
        writeValue = { cfg, v -> cfg.copy(router = cfg.router.copy(binaryEnvelopeLifetimeSeconds = v)) },
    ),
    NumberField(
        id = "dedupRetentionSeconds",
        title = "Dedup retention",
        description = "How long deduplication entries are retained. Controlled by the network.",
        group = "Router",
        source = FieldSource.NETWORK,
        unit = "seconds",
        readValue = { it.router.dedupRetentionSeconds },
        writeValue = { cfg, v -> cfg.copy(router = cfg.router.copy(dedupRetentionSeconds = v)) },
    ),
)
