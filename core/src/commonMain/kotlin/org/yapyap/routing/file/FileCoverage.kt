package org.yapyap.routing.file

import org.yapyap.protocol.envelopes.ByteRange

/**
 * Durable held coverage of one file for building a sealed REQUEST (design §3.2, §9):
 * the contiguous prefix in bytes plus the capped missing ranges beyond it.
 */
internal data class FileCoverage(
    val contiguousBytes: Long,
    val missingRanges: List<ByteRange>,
)
