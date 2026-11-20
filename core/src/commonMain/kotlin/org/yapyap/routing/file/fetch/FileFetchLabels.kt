package org.yapyap.routing.file.fetch

/** Per-fetch file-channel label prefix; must match the WebRTC backend's file prefix. */
internal const val FILE_FETCH_LABEL_PREFIX = "yapyap-file-"

/** Buffered-amount pause/resume watermarks for the holder (design §6.6). */
internal const val FILE_FETCH_BUFFERED_PAUSE_BYTES: Long = 2L * 1024L * 1024L
internal const val FILE_FETCH_BUFFERED_RESUME_BYTES: Long = 1L * 1024L * 1024L

/** One fetch channel between two devices. */
internal data class FetchKey(val peer: org.yapyap.protocol.PeerId, val channelLabel: String)

internal fun newFetchLabel(): String = "$FILE_FETCH_LABEL_PREFIX${kotlin.uuid.Uuid.random()}"
