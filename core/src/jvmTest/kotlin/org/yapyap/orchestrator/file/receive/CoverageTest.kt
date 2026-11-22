package org.yapyap.orchestrator.file.receive

import org.yapyap.protocol.envelopes.ByteRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverageTest {
    @Test
    fun uncovered_skipsStagedAndFloor() {
        val staged = listOf(StagedSpan(100, 50), StagedSpan(200, 50))
        // [0, 300) with P=0, staged [100,150) and [200,250): uncovered [0,100), [150,200), [250,300).
        val out = Coverage.uncovered(0, 300, 0, staged)
        assertEquals(listOf(0L to 100L, 150L to 50L, 250L to 50L), out)
        // Floor at P=120: [120,150) is covered, so uncovered starts at 150.
        val out2 = Coverage.uncovered(0, 300, 120, staged)
        assertEquals(listOf(150L to 50L, 250L to 50L), out2)
    }

    @Test
    fun extendsPrefix_trueOnlyWhenPrefixGrows() {
        val staged = listOf(StagedSpan(0, 100))
        assertEquals(true, Coverage.extendsPrefix(0, staged, listOf(StagedSpan(100, 50))))
        assertEquals(false, Coverage.extendsPrefix(0, staged, listOf(StagedSpan(200, 50))))
        // Staged already covers to 100; adding a duplicate does not extend.
        assertEquals(false, Coverage.extendsPrefix(0, staged, listOf(StagedSpan(0, 100))))
        assertEquals(true, Coverage.extendsPrefix(100, listOf(StagedSpan(0, 100)), listOf(StagedSpan(100, 10))))
    }

    @Test
    fun drainPlan_contiguousPrefix_neverSplitsARow() {
        val staged = listOf(StagedSpan(0, 100), StagedSpan(100, 50), StagedSpan(200, 50))
        // Gap at 150: drain to 150 (rows end exactly at the new prefix).
        assertEquals(150L, Coverage.drainPlan(0, staged).newP)
        // An overlapping chain drains to its furthest end, so no row straddles the new prefix.
        val overlapping = listOf(StagedSpan(0, 100), StagedSpan(50, 150), StagedSpan(60, 20))
        val plan = Coverage.drainPlan(0, overlapping)
        assertEquals(200L, plan.newP)
        assertTrue(overlapping.all { it.start + it.length <= plan.newP || it.start > plan.newP })
    }

    @Test
    fun heldCoverage_capsMissing() {
        val staged = listOf(StagedSpan(100, 50), StagedSpan(200, 50))
        val coverage = Coverage.heldCoverage(100, staged, 16, 1000)
        assertEquals(100L, coverage.contiguousBytes)
        assertEquals(listOf(ByteRange(150, 50)), coverage.missingRanges)
        // Heavy fragmentation degrades to contiguous-only.
        val many = (0 until 20).map { StagedSpan(100L + it * 100, 50) }
        val degraded = Coverage.heldCoverage(100, many, 16, 100000)
        assertEquals(100L, degraded.contiguousBytes)
        assertTrue(degraded.missingRanges.isEmpty())
    }
}
