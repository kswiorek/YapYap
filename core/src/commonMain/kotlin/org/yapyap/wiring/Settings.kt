package org.yapyap.wiring

// ---------------------------------------------------------------------------
// The fields. This is the single place a setting is defined.
// ---------------------------------------------------------------------------

internal val FIELDS: List<Field> = listOf(
    NumberField(
        id = "relayStoragePledgeBytes",
        title = "Relay storage",
        description = "Space this device lends to hold messages and files for other devices while they are offline.",
        group = "Router",
        source = FieldSource.USER,
        unit = "bytes",
        min = 1L,
        readValue = { it.router.relayStoragePledgeBytes },
        writeValue = { cfg, v -> cfg.copy(router = cfg.router.copy(relayStoragePledgeBytes = v)) },
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
    NumberField(
        id = "smallFileMaxBytes",
        title = "Small file limit",
        description = "Files up to this size transfer directly; larger ones are pulled over WebRTC. Controlled by the network.",
        group = "Files",
        source = FieldSource.NETWORK,
        unit = "bytes",
        min = 1L,
        readValue = { it.router.smallFileMaxBytes },
        writeValue = { cfg, v -> cfg.copy(router = cfg.router.copy(smallFileMaxBytes = v)) },
    ),
    NumberField(
        id = "autoDownloadMaxBytes",
        title = "Automatic download limit",
        description = "Files up to this size download without asking.",
        group = "Files",
        source = FieldSource.USER,
        unit = "bytes",
        min = 0L,
        readValue = { it.orchestrator.autoDownloadMaxBytes },
        writeValue = { cfg, v -> cfg.copy(orchestrator = cfg.orchestrator.copy(autoDownloadMaxBytes = v)) },
    ),
    NumberField(
        id = "storeQuotaBytes",
        title = "File storage",
        description = "Space for files kept in the app. The oldest copies are removed past it.",
        group = "Files",
        source = FieldSource.USER,
        unit = "bytes",
        min = 1L,
        readValue = { it.orchestrator.storeQuotaBytes },
        writeValue = { cfg, v -> cfg.copy(orchestrator = cfg.orchestrator.copy(storeQuotaBytes = v)) },
    ),
)
