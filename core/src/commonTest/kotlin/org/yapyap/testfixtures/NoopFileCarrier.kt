package org.yapyap.testfixtures

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId
import org.yapyap.protocol.envelopes.FileCancelReason
import org.yapyap.routing.file.FileCarrier
import org.yapyap.routing.file.FileEvent

/** File carrier fake for tests that never exercise file transfer. */
internal class NoopFileCarrier : FileCarrier {
    override val fileEvents: Flow<FileEvent> = emptyFlow()

    override suspend fun pushFile(fileId: FileId, target: AccountId) = Unit

    override suspend fun cancelFile(fileId: FileId, target: AccountId) = Unit

    override suspend fun fileReady(fileId: FileId) = Unit

    override suspend fun cancelIncoming(fileId: FileId, reason: FileCancelReason) = Unit

    override suspend fun emit(event: FileEvent) = Unit
}
