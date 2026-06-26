package org.yapyap.backend.crypto.e2ee

data class CryptoSessionConfig(
    /** Keep superseded rows for late decrypt, then delete after this period of inactivity. */
    val supersededRetentionSeconds: Long = 2 * 24 * 60 * 60,
    /** Mark a canonical ACTIVE session superseded after this idle period (future job). */
    val canonicalIdleSupersedeSeconds: Long = 14 * 24 * 60 * 60,
    /** Mark non-canonical duplicate rows superseded immediately after simultaneous-init tie-break. */
    val supersedeRogueSessionsAfterSimultaneousInit: Boolean = true,
) {
    init {
        require(supersededRetentionSeconds > 0) { "supersededRetentionSeconds must be > 0" }
        require(canonicalIdleSupersedeSeconds > 0) { "canonicalIdleSupersedeSeconds must be > 0" }
    }
}
