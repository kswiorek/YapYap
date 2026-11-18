package org.yapyap.orchestrator.file

import kotlinx.serialization.Serializable
import org.yapyap.protocol.SMALL_FILE_MAX_BYTES
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * File config (design §15). Lives next to the file machinery; `wiring`
 * aggregates it into `RuntimeConfig` and checks the cross-layer invariants.
 */
@Serializable
internal data class FileServiceConfig(
    /** "Keep a copy" default threshold; at or below → copy, above → reference. */
    val copyIntoStoreMaxBytes: Long = SMALL_FILE_MAX_BYTES,
    /** Quota over COPY descriptors only; references are never deleted by retention. */
    val storeQuotaBytes: Long = 1024L * 1024L * 1024L,
    /** Staging bound (design §8): chunks beyond P that cannot extend it are DEFERred. */
    val maxStagedBytes: Long = 32L * 1024L * 1024L,
    /** Block mismatches before a receive FAILs (design §8). */
    val fileHashRetryLimit: Int = 3,
    /** Large files at or below this are fetched automatically (design §8). */
    val autoDownloadMaxBytes: Long = 250L * 1024L * 1024L,
    /** Checkpoint cadence: append, one fsync, one P commit (design §8). */
    val fileCheckpointBytes: Long = 8L * 1024L * 1024L,
    val fileCheckpointInterval: Duration = 2.seconds,
) {
    init {
        require(copyIntoStoreMaxBytes >= 0) { "copyIntoStoreMaxBytes must be >= 0" }
        require(storeQuotaBytes > 0) { "storeQuotaBytes must be > 0" }
        require(maxStagedBytes > 0) { "maxStagedBytes must be > 0" }
        require(fileHashRetryLimit > 0) { "fileHashRetryLimit must be > 0" }
        require(autoDownloadMaxBytes >= 0) { "autoDownloadMaxBytes must be >= 0" }
        require(fileCheckpointBytes > 0) { "fileCheckpointBytes must be > 0" }
        require(fileCheckpointInterval > Duration.ZERO) { "fileCheckpointInterval must be > 0" }
        require(maxStagedBytes >= fileCheckpointBytes) {
            "maxStagedBytes must hold a checkpoint's worth"
        }
    }
}
