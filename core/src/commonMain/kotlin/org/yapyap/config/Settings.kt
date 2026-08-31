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
    PeriodField(
        id = "ackLifetime",
        title = "ACK lifetime",
        description = "How long to wait for an acknowledgement before retrying.",
        group = "Router",
        source = FieldSource.USER,
        readValue = { it.router.ackLifetime },
        writeValue = { cfg, v -> cfg.copy(router = cfg.router.copy(ackLifetime = v)) },
    ),
    PeriodField(
        id = "gracePeriod",
        title = "Sync grace period",
        description = "Delay before an out-of-order message is considered missing.",
        group = "Sync",
        source = FieldSource.USER,
        readValue = { it.orchestrator.syncGracePeriod },
        writeValue = { cfg, v -> cfg.copy(orchestrator = cfg.orchestrator.copy(syncGracePeriod = v)) },
    ),
    PeriodField(
        id = "messageLifetime",
        title = "Message lifetime",
        description = "How long messages are retained. Controlled by the network.",
        group = "Router",
        source = FieldSource.NETWORK,

        readValue = { it.router.binaryEnvelopeLifetime },
        writeValue = { cfg, v -> cfg.copy(router = cfg.router.copy(binaryEnvelopeLifetime = v)) },
    ),
    PeriodField(
        id = "dedupRetention",
        title = "Dedup retention",
        description = "How long deduplication entries are retained. Controlled by the network.",
        group = "Router",
        source = FieldSource.NETWORK,
        readValue = { it.router.dedupRetention },
        writeValue = { cfg, v -> cfg.copy(router = cfg.router.copy(dedupRetention = v)) },
    ),
)
