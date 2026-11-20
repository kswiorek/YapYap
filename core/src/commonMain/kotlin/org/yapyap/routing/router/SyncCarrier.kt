package org.yapyap.routing.router

import kotlinx.coroutines.flow.Flow
import org.yapyap.protocol.AccountId
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The pull path: presence pings with frontiers, and asking peers for missing history. */
internal interface SyncCarrier {
    /**
     * Hot stream of inbound pings, resolved to the sender's account (null when
     * the device is unknown). One element per received ping, probes and
     * replies alike, since both carry the sender's latest frontiers.
     */
    val pingPayloads: Flow<PingFrontiers>

    /**
     * Immediately pings every known peer, advertising our presence and exchanging
     * frontier snapshots so listeners can trigger frontier syncs.
     *
     * The orchestrator calls this once, after the subsystems that consume [pingPayloads] (e.g. the
     * sync coordinator) are running, rather than having it fire inside [Router.start]. Idempotent
     * and best-effort: failures to individual peers are swallowed. Returns once the pings are handed
     * to the transport.
     */
    suspend fun announceOnline()

    /**
     * Starts asking candidate devices for pending sync [syncId] (idempotent), first at
     * [firstAttemptAt]. What to ask for and which accounts may answer come from the
     * [org.yapyap.routing.sync.SyncPayloadProvider]; the router asks until that reports the
     * sync satisfied or gone.
     */
    suspend fun startSync(syncId: Uuid, firstAttemptAt: Instant)

    /**
     * [account] asserts it can serve [syncId] after all (e.g. its ping advertises the sync's
     * room): if every candidate device has declined, that account's devices are asked again.
     */
    suspend fun reopenSync(syncId: Uuid, account: AccountId)
}
