package org.yapyap.routing.file.port

import org.yapyap.protocol.AccountId
import org.yapyap.protocol.FileId

/**
 * Serve gate (design §9), declared by routing and implemented by the orchestrator's request
 * coordinator. Without it, anyone who has or guesses a file's content could pull it from any holder
 * by its hash.
 */
internal interface FileServeGate {
    /** True when [requester] may see a message referencing [fileId] (the same rule as sync's). */
    suspend fun canServe(fileId: FileId, requester: AccountId): Boolean
}
