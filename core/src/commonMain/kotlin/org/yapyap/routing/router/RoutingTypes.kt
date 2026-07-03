package org.yapyap.routing.router

import org.yapyap.protocol.envelopes.PacketNackReason

enum class SendMessageStatus {
    SUCCESS,
    PARTIAL,
    FAILURE,
}

enum class SendFailureKind {
    NO_PEERS,
    NOT_READY,
    PERMANENT,
    MIXED,
}

data class SendMessageResult(
    val status: SendMessageStatus,
    val peersTotal: Int,
    val peersQueued: Int,
    val failureKind: SendFailureKind?,
)

internal sealed interface InboundHandleResult {
    data object Success : InboundHandleResult
    data object Deferred : InboundHandleResult
    data class Rejected(val reason: PacketNackReason) : InboundHandleResult
}

internal sealed interface PeerSendOutcome {
    data object Queued : PeerSendOutcome
    data object NotReady : PeerSendOutcome
    data object PermanentFailure : PeerSendOutcome
}

internal fun aggregateSendResults(outcomes: List<PeerSendOutcome>): SendMessageResult {
    val peersTotal = outcomes.size
    val peersQueued = outcomes.count { it is PeerSendOutcome.Queued }
    val notReady = outcomes.count { it is PeerSendOutcome.NotReady }
    val permanent = outcomes.count { it is PeerSendOutcome.PermanentFailure }

    val status = when {
        peersQueued == peersTotal -> SendMessageStatus.SUCCESS
        peersQueued == 0 -> SendMessageStatus.FAILURE
        else -> SendMessageStatus.PARTIAL
    }

    val failureKind = when (status) {
        SendMessageStatus.SUCCESS -> null
        SendMessageStatus.FAILURE -> when {
            notReady == peersTotal -> SendFailureKind.NOT_READY
            permanent == peersTotal -> SendFailureKind.PERMANENT
            else -> SendFailureKind.MIXED
        }
        SendMessageStatus.PARTIAL -> when {
            permanent > 0 -> SendFailureKind.MIXED
            notReady > 0 -> SendFailureKind.NOT_READY
            else -> SendFailureKind.MIXED
        }
    }

    return SendMessageResult(
        status = status,
        peersTotal = peersTotal,
        peersQueued = peersQueued,
        failureKind = failureKind,
    )
}
