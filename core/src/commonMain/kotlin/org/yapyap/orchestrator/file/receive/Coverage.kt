package org.yapyap.orchestrator.file.receive

import org.yapyap.protocol.envelopes.ByteRange
import org.yapyap.routing.file.port.FileCoverage

/**
 * Pure byte-coverage arithmetic over staged ranges (design §8). No suspend, no I/O:
 * the receive engine loads blobs only at drain, while every coverage decision runs
 * on offsets and lengths.
 */
internal data class StagedSpan(val start: Long, val length: Long)

/**
 * The new prefix a drain reaches. No staged row ever straddles it: the plan stops at the furthest
 * end of the rows it walked, and every row starting before that was walked, so every staged row
 * ends at or below [newP] or starts past it.
 */
internal data class DrainPlan(val newP: Long)

internal object Coverage {
    /**
     * Slices of `[start, end)` neither below [p] nor already staged, as (offset, length)
     * pairs into the caller's byte window. Empty when everything is covered.
     */
    fun uncovered(start: Long, end: Long, p: Long, staged: List<StagedSpan>): List<Pair<Long, Long>> {
        if (end <= start) return emptyList()
        val floor = maxOf(start, p)
        if (floor >= end) return emptyList()
        val out = mutableListOf<Pair<Long, Long>>()
        var cursor = floor
        for (range in staged.sortedBy { it.start }) {
            val rangeEnd = range.start + range.length
            if (rangeEnd <= cursor) continue
            if (range.start >= end) break
            if (range.start > cursor) {
                val gapEnd = minOf(range.start, end)
                out += cursor to (gapEnd - cursor)
                cursor = gapEnd
            }
            if (range.start <= cursor) {
                cursor = maxOf(cursor, minOf(rangeEnd, end))
            }
            if (cursor >= end) break
        }
        if (cursor < end) out += cursor to (end - cursor)
        return out
    }

    /** Would adding [add] to [staged] extend the contiguous prefix at [p]? */
    fun extendsPrefix(p: Long, staged: List<StagedSpan>, add: List<StagedSpan>): Boolean {
        fun prefix(base: Long, ranges: List<StagedSpan>): Long {
            var cursor = base
            for (range in ranges.sortedBy { it.start }) {
                if (range.start > cursor) break
                val end = range.start + range.length
                if (end > cursor) cursor = end
            }
            return cursor
        }
        return prefix(p, staged + add) > prefix(p, staged)
    }

    /** Contiguous drainable prefix from [p]: everything reachable without a gap. */
    fun drainPlan(p: Long, staged: List<StagedSpan>): DrainPlan {
        var cursor = p
        for (range in staged.sortedBy { it.start }) {
            if (range.start > cursor) break
            if (range.start + range.length <= cursor) continue
            cursor = range.start + range.length
        }
        return DrainPlan(cursor)
    }

    /**
     * Durable held coverage for building a REQUEST (design §3.2, §9): the contiguous
     * prefix plus the capped missing ranges beyond it. Heavy fragmentation degrades to
     * contiguous-only — a coarser serve with more duplicates, still correct.
     */
    fun heldCoverage(p: Long, staged: List<StagedSpan>, cap: Int, totalBytes: Long): FileCoverage {
        val sorted = staged.sortedBy { it.start }
        var cursor = p
        // Advance over ranges that extend the prefix.
        var advanced = true
        while (advanced) {
            advanced = false
            for (range in sorted) {
                val end = range.start + range.length
                if (end <= cursor) continue
                if (range.start <= cursor && end > cursor) {
                    cursor = end
                    advanced = true
                }
            }
        }
        if (cursor >= totalBytes) return FileCoverage(cursor, emptyList())
        val missing = mutableListOf<ByteRange>()
        for (range in sorted) {
            val end = range.start + range.length
            if (end <= cursor) continue
            if (range.start > cursor) {
                if (missing.size >= cap) return FileCoverage(p, emptyList())
                missing += ByteRange(cursor, range.start - cursor)
            }
            cursor = maxOf(cursor, end)
            if (cursor >= totalBytes) break
        }
        return FileCoverage(p, missing)
    }
}
