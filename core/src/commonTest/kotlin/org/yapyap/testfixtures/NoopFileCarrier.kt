package org.yapyap.testfixtures

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.routing.file.port.FileCarrier
import org.yapyap.routing.file.port.FileEvent
import org.yapyap.routing.file.port.FileSendState
import kotlin.time.Instant

/** File carrier fake for tests that never exercise file transfer. */
internal open class NoopFileCarrier : FileCarrier {
    override val fileEvents: Flow<FileEvent> = emptyFlow()

    override suspend fun pushFile(fileId: FileId, target: AccountId) = Unit

    override suspend fun cancelFile(fileId: FileId, target: AccountId) = Unit

    override suspend fun sendState(fileId: FileId): Map<AccountId, FileSendState> =
        emptyMap()

    override suspend fun startFileRequest(fileId: FileId, firstAttemptAt: Instant) = Unit

    override suspend fun abortFileFetch(fileId: FileId, reason: org.yapyap.protocol.envelopes.FileFetchAbortReason) =
        Unit
}
