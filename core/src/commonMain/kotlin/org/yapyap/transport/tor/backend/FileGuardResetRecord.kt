package org.yapyap.transport.tor.backend

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.time.Instant

/**
 * [GuardResetRecord] in a one-line file (epoch millis) beside Tor's own
 * directories, so the once-per-interval limit survives app restarts. A missing
 * or unreadable file means "never reset".
 */
internal class FileGuardResetRecord(private val file: Path) : GuardResetRecord {

    override fun lastReset(): Instant? = runCatching {
        if (!SystemFileSystem.exists(file)) return null
        SystemFileSystem.source(file).buffered().use { it.readString() }.trim().toLong()
    }.getOrNull()?.let { Instant.fromEpochMilliseconds(it) }

    override fun recordReset(at: Instant) {
        runCatching {
            SystemFileSystem.sink(file).buffered().use { it.writeString(at.toEpochMilliseconds().toString()) }
        }
    }
}
