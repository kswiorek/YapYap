package org.yapyap.orchestrator.message

import org.yapyap.crypto.identity.AccountId

data class MessageDisplayItem(
    val accountId: AccountId,
    val text: String,
    //TODO get timestamp here
    val lamportClock: Long,
    val isOrphaned: Boolean,
)