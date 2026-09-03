package org.yapyap.routing.policy

import kotlinx.coroutines.flow.StateFlow
import org.yapyap.protocol.PeerId
import org.yapyap.routing.router.PeerAvailabilityRegistry
import org.yapyap.routing.router.RouterConfig
import org.yapyap.routing.router.RoutingContext

/**
 * Picks the relay (store-and-forward) peers a message for [targetDevice] should be deposited with,
 * to be held until the recipient surfaces. Used when there is no direct WebRTC session.
 */
interface RelaySelectionPolicy {
    /** Relays to deposit with, best first, or empty if none qualify. */
    suspend fun selectRelays(targetDevice: PeerId): List<PeerId>
}

/**
 * Selects relays by their effective reliability scores ([PeerAvailabilityRegistry.reliabilityScore]),
 * greedy best-first, until the chance that at least one is online reaches
 * [RouterConfig.relayTargetSuccessProbability] (capped at [RouterConfig.maxRelays]). Peers with no
 * score, or below [RouterConfig.minRelayScore], are never used — a peer that reports 0 has opted out
 * of relaying and can never contribute probability.
 */
internal class DefaultRelaySelectionPolicy(
    private val ctx: RoutingContext,
    private val peerAvailabilityRegistry: PeerAvailabilityRegistry,
    private val routerConfig: StateFlow<RouterConfig>,
) : RelaySelectionPolicy {

    override suspend fun selectRelays(targetDevice: PeerId): List<PeerId> {
        val config = routerConfig.value
        val candidates = ctx.identityResolver.getAllPeers()
            .filter { it != ctx.localDeviceId && it != targetDevice }
        val scored = candidates.mapNotNull { peer ->
            peerAvailabilityRegistry.reliabilityScore(peer)?.let { peer to it }
        }
        return selectRelaysByScores(
            scored = scored,
            targetProbability = config.relayTargetSuccessProbability,
            maxRelays = config.maxRelays,
            minScore = config.minRelayScore,
        )
    }
}

/**
 * Pure greedy selection: take the highest-scoring peers until the chance that at least one is online
 * (`1 - product of (1 - score)`) reaches [relayTargetProbability] or [maxRelays] peers are taken.
 * Peers at or below [minScore] (score 0 = opted out of relaying, so it can never help) stop the pick.
 */
internal fun selectRelaysByScores(
    scored: List<Pair<PeerId, Double>>,
    targetProbability: Double,
    maxRelays: Int,
    minScore: Double,
): List<PeerId> {
    if (targetProbability <= 0.0) return emptyList()
    val selected = mutableListOf<PeerId>()
    var failureProbability = 1.0
    for ((peer, score) in scored.sortedByDescending { it.second }) {
        if (score <= 0.0 || score < minScore || selected.size >= maxRelays) break
        failureProbability *= (1.0 - score)
        selected.add(peer)
        if (failureProbability <= 1.0 - targetProbability) break
    }
    return selected
}