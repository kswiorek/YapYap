package org.yapyap.orchestrator

interface OrchestratorRuntime {
    // --- GUI-facing domain services (build these now, stub Sprint 2/4 pieces) ---
//    val messaging: MessagingService
//    val identity: IdentityQueryService        // read-only views for UI
//    val rooms: RoomQueryService               // SQLDelight-backed flows
//    val sync: SyncStatusService               // orphan/gap state — Sprint 2
//    val roster: DeviceRosterService           // global control DAG — Sprint 4
//
//    // --- Observability ---
//    val connectivity: StateFlow<ConnectivitySnapshot>
    // local tor endpoint, router running, peer reachability later

    // --- Intentionally NOT exposed to GUI ---
    // internal val router: Router
    // internal val bootRecovery: BootRecovery
}