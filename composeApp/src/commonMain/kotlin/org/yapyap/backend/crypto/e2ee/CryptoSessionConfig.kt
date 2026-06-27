package org.yapyap.backend.crypto.e2ee

data class CryptoSessionConfig(
    /** Keep superseded rows for late decrypt, then delete this long after supersede (`updatedAtEpochSeconds`). */
    val supersededRetentionSeconds: Long = 2 * 24 * 60 * 60,
    /** Delete OFFERED OPKs not consumed within this window (aligned with message lifetime). */
    val offeredOpkRetentionSeconds: Long = 2 * 24 * 60 * 60,
    /** Delete unpromoted epoch-2 initiator rows after this window (abandoned upgrade attempts). */
    val pendingEpoch2RetentionSeconds: Long = 2 * 24 * 60 * 60,
    /** Mark a canonical ACTIVE session superseded after this idle period (future job). */
    val canonicalIdleSupersedeSeconds: Long = 14 * 24 * 60 * 60,
    /** Mark non-canonical duplicate rows superseded immediately after simultaneous-init tie-break. */
    val supersedeRogueSessionsAfterSimultaneousInit: Boolean = true,
) {
    init {
        require(supersededRetentionSeconds > 0) { "supersededRetentionSeconds must be > 0" }
        require(offeredOpkRetentionSeconds > 0) { "offeredOpkRetentionSeconds must be > 0" }
        require(pendingEpoch2RetentionSeconds > 0) { "pendingEpoch2RetentionSeconds must be > 0" }
        require(canonicalIdleSupersedeSeconds > 0) { "canonicalIdleSupersedeSeconds must be > 0" }
    }
}
