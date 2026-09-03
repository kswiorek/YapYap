package org.yapyap.crypto.e2ee

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

@Serializable
data class CryptoSessionConfig(
    /** Keep superseded rows for late decrypt, then delete this long after supersede (`updatedAtEpochSeconds`). */
    val supersededRetention: Duration = 2.days,
    /** Delete OFFERED OPKs not consumed within this window (aligned with message lifetime). */
    val offeredOpkRetention: Duration = 2.days,
    /** Delete unpromoted epoch-2 initiator rows after this window (abandoned upgrade attempts). */
    val pendingEpoch2Retention: Duration = 2.days,
    /** Mark a canonical ACTIVE session superseded after this idle period (future job). */
    val canonicalIdleSupersede: Duration = 14.days,
    /** Mark non-canonical duplicate rows superseded immediately after simultaneous-init tie-break. */
    val supersedeRogueSessionsAfterSimultaneousInit: Boolean = true,
) {
    init {
        require(supersededRetention > Duration.ZERO) { "supersededRetention must be > 0" }
        require(offeredOpkRetention > Duration.ZERO) { "offeredOpkRetention must be > 0" }
        require(pendingEpoch2Retention > Duration.ZERO) { "pendingEpoch2Retention must be > 0" }
        require(canonicalIdleSupersede > Duration.ZERO) { "canonicalIdleSupersede must be > 0" }
    }
}
