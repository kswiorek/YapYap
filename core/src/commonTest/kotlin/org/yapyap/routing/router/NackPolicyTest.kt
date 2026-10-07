package org.yapyap.routing.router

import org.yapyap.protocol.envelopes.PacketNackReason
import org.yapyap.routing.policy.NackAction
import org.yapyap.routing.policy.nackActionFor
import kotlin.test.Test
import kotlin.test.assertEquals

class NackPolicyTest {

    @Test
    fun terminalReasons_removeOutboxRow() {
        listOf(
            PacketNackReason.PERMANENT_PROTECTION_FAILED,
            PacketNackReason.DECODE_FAILED,
            PacketNackReason.UNSUPPORTED_TYPE,
            PacketNackReason.EXPIRED,
            PacketNackReason.DECLINED,
        ).forEach { reason ->
            assertEquals(NackAction.REMOVE, nackActionFor(reason), "reason=$reason")
        }
    }

    @Test
    fun wrongTarget_keepsOutboxRow() {
        assertEquals(NackAction.KEEP, nackActionFor(PacketNackReason.WRONG_TARGET))
    }

    @Test
    fun unrecognizedReasonByte_keepsOutboxRow() {
        // Never act destructively on confusion with a newer peer.
        assertEquals(NackAction.KEEP, nackActionFor(null))
    }

    @Test
    fun wireValuesDistinct() {
        val wires = PacketNackReason.entries.map { it.wireValue }.toSet()
        assertEquals(PacketNackReason.entries.size, wires.size)
    }
}
