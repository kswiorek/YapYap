package org.yapyap.orchestrator.fold.graph

import org.yapyap.persistence.messaging.MessageRow
import kotlin.uuid.Uuid

/**
 * Pure graph helpers over stored rows, shared by the sibling fold tiers
 * (docs/room events.md §4: mechanical extraction, no tier logic moves).
 *
 * Extracted verbatim from `DefaultGlobalEventProjector`; the global dynamics fuzzer
 * and projector tests run unchanged as the equivalence proof. `foldInputSet`
 * diverges by tier and is deliberately NOT shared: the room tier filters on the
 * stored verdict-aware flag, the global tier computes inline eligibility.
 */

/** Stored-graph child adjacency, for the topo sort and genesis descent. */
internal fun childAdjacency(byId: Map<Uuid, MessageRow>): Map<Uuid, List<Uuid>> {
    val children = HashMap<Uuid, MutableList<Uuid>>()
    for ((id, row) in byId) {
        for (parent in row.payload.prevIds) {
            if (byId.containsKey(parent)) {
                children.getOrPut(parent) { mutableListOf() }.add(id)
            }
        }
    }
    return children
}

/**
 * Canonical order: topological over stored `prevIds` (`createdAt, messageId`
 * tiebreak). Unorderable cycles replay last, deterministically.
 */
internal fun canonicalOrder(byId: Map<Uuid, MessageRow>, children: Map<Uuid, List<Uuid>>): List<Uuid> {
    val orderOf = Comparator<Uuid> { a, b ->
        val ra = byId.getValue(a).payload
        val rb = byId.getValue(b).payload
        val c = ra.createdAt.compareTo(rb.createdAt)
        if (c != 0) c else a.compareTo(b)
    }
    val indegree = HashMap<Uuid, Int>()
    for ((id, row) in byId) {
        indegree[id] = row.payload.prevIds.count { byId.containsKey(it) }
    }
    // Plain-list priority scan (common-safe; the global log is tiny — swap in a real
    // priority structure only if the room ever outgrows the 10–20-user profile).
    val ready = ArrayList<Uuid>()
    for ((id, degree) in indegree) {
        if (degree == 0) ready.add(id)
    }
    val order = ArrayList<Uuid>(byId.size)
    while (ready.isNotEmpty()) {
        val id = ready.minWith(orderOf)
        ready.remove(id)
        order.add(id)
        for (child in children[id].orEmpty()) {
            val remaining = indegree.getValue(child) - 1
            indegree[child] = remaining
            if (remaining == 0) ready.add(child)
        }
    }
    if (order.size < byId.size) {
        val replayed = order.toSet()
        order.addAll(byId.keys.filter { it !in replayed }.sortedWith(orderOf))
    }
    return order
}

/** Memoized transitive `prevIds` closures (the seals' vouching sets). */
internal fun ancestorClosures(byId: Map<Uuid, MessageRow>): Map<Uuid, Set<Uuid>> {
    val memo = HashMap<Uuid, Set<Uuid>>()
    fun ancestorsOf(node: Uuid): Set<Uuid> = memo.getOrPut(node) {
        val seen = HashSet<Uuid>()
        val stack = ArrayDeque<Uuid>()
        for (parent in byId.getValue(node).payload.prevIds) {
            if (seen.add(parent)) stack.add(parent)
        }
        while (stack.isNotEmpty()) {
            for (parent in byId[stack.removeLast()]?.payload?.prevIds.orEmpty()) {
                if (seen.add(parent)) stack.add(parent)
            }
        }
        seen
    }
    for (id in byId.keys) ancestorsOf(id)
    return memo
}
