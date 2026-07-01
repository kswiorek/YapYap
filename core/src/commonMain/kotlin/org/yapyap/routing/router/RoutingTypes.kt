package org.yapyap.routing.router

import org.yapyap.protocol.envelopes.PacketNackReason

internal sealed interface InboundHandleResult {
    data object Success : InboundHandleResult
    data object Deferred : InboundHandleResult
    data class Rejected(val reason: PacketNackReason) : InboundHandleResult
}