package org.yapyap.routing.file.push

import org.yapyap.protocol.PeerId
import org.yapyap.protocol.TransferId
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.yapyap.routing.policy.RelaySelectionPolicy
import org.yapyap.routing.store.FilePushKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FileDepositPolicyTest {

    private fun depositHarness(
        relays: List<PeerId> = listOf(relay1, relay2),
    ): PushHarness = pushHarness(
        relaySelection = object : RelaySelectionPolicy {
            override suspend fun selectRelays(targetDevice: PeerId) = relays
        },
    )

    private suspend fun PushHarness.drivePushToInFlight(device: PeerId = peerA): TransferId {
        sender.pushFile(fixtureFileId, fixtureAccount)
        awaitLive(device)
        val transfer = transferIdsFor(device).single { pushes.find(it)?.kind == FilePushKind.DIRECT }
        engine.onOutboxOutcome(ackMarker(transfer, device))
        withTimeout(5.seconds) { while (chunkIndices().isEmpty()) delay(10) }
        return transfer
    }

    private suspend fun PushHarness.depositsFor(device: PeerId = peerA) =
        pushes.live().filter { it.targetDevice == device && it.kind == FilePushKind.DEPOSIT }

    @Test
    fun depositRound_isAtMostOnce_acrossTicksAndRestart() = runTest {
        val h = depositHarness()
        h.engine.runIn(backgroundScope)
        val transfer = h.drivePushToInFlight()
        // Ten ticks: one round only (deposited_at claim).
        repeat(10) { h.engine.depositRemainder(transfer) }
        assertEquals(2, h.depositsFor().size, "one round per (file, target)")

        // Restart: the claim survives, so no second round.
        val h2 = pushHarness(
            relaySelection = object : RelaySelectionPolicy {
                override suspend fun selectRelays(targetDevice: PeerId) = listOf(relay1, relay2)
            },
        )
        for (row in h.pushes.live()) h2.pushes.insert(row)
        assertEquals(0, h2.engine.depositRemainder(transfer), "restart never re-deposits")
        assertEquals(2, h2.pushes.live().count { it.kind == FilePushKind.DEPOSIT })
    }

    @Test
    fun shouldDeposit_timeoutRule() = runTest {
        val h = depositHarness()
        h.engine.runIn(backgroundScope)
        val transfer = h.drivePushToInFlight()
        val row = h.pushes.find(transfer)!!
        val policy = FileDepositPolicy(h.pushes)
        val timeout = 45.seconds

        // Chunk ACK within the timeout: no deposit.
        assertFalse(
            policy.shouldDeposit(
                row,
                row.createdAt + 10.seconds,
                row.createdAt,
                hasQueued = true,
                markerAcked = true,
                timeout
            )
        )
        // Silence past the timeout with rows queued: deposit.
        assertTrue(
            policy.shouldDeposit(
                row,
                row.createdAt + 46.seconds,
                row.createdAt,
                hasQueued = true,
                markerAcked = true,
                timeout
            )
        )
        // Marker not yet ACKed, nothing queued, expired, or not DIRECT/SERVE: never.
        assertFalse(
            policy.shouldDeposit(
                row,
                row.createdAt + 46.seconds,
                row.createdAt,
                hasQueued = true,
                markerAcked = false,
                timeout
            )
        )
        assertFalse(
            policy.shouldDeposit(
                row,
                row.createdAt + 46.seconds,
                row.createdAt,
                hasQueued = false,
                markerAcked = true,
                timeout
            )
        )
        assertFalse(
            policy.shouldDeposit(
                row,
                row.expiresAt + 1.seconds,
                row.createdAt,
                hasQueued = true,
                markerAcked = true,
                timeout
            )
        )
        assertFalse(
            policy.shouldDeposit(
                row.copy(kind = FilePushKind.DEPOSIT),
                row.createdAt + 46.seconds, row.createdAt, hasQueued = true, markerAcked = true, timeout,
            ),
        )
    }

    @Test
    fun linkDownWhileChunksQueued_tickDepositsOnce() = runTest {
        val h = depositHarness()
        h.engine.runIn(backgroundScope)
        val transfer = h.drivePushToInFlight()
        // No ACKs, no link: the ticker's deposit sweep fires the one round.
        withTimeout(120.seconds) {
            while (h.depositsFor().size < 2) delay(100)
        }
        assertEquals(2, h.depositsFor().size)
        delay(30.seconds)
        assertEquals(2, h.depositsFor().size, "ticks never open a second round")
    }

    @Test
    fun depositMarker_neverChases() = runTest {
        val h = depositHarness()
        h.engine.runIn(backgroundScope)
        h.drivePushToInFlight()
        val transfer = h.transferIdsFor(peerA).single { h.pushes.find(it)?.kind == FilePushKind.DIRECT }
        h.engine.depositRemainder(transfer)
        withTimeout(5.seconds) {
            while (h.depositsFor().size < 2 || h.depositsFor().any { it.markerPacketId == null }) delay(10)
        }
        // A deposit never re-deposits: the round is claimed once per source transfer, and
        // deposit rows are not DIRECT/SERVE so depositRemainder refuses them outright.
        for (deposit in h.depositsFor()) {
            assertEquals(0, h.engine.depositRemainder(deposit.transferId))
        }
        assertEquals(2, h.depositsFor().size)
    }

    @Test
    fun noRelaySelected_noDeposit() = runTest {
        val h = pushHarness()

        h.engine.runIn(backgroundScope)
        val transfer = h.drivePushToInFlight()
        assertEquals(0, h.engine.depositRemainder(transfer), "empty selection means no deposit")
    }

    @Test
    fun fullyAckedTransfer_noDeposit() = runTest {
        val h = depositHarness()
        h.engine.runIn(backgroundScope)
        val transfer = h.drivePushToInFlight()
        // prefix [0..4] ACKed on a 5-chunk file: nothing unacked, nothing to insure.
        h.engine.coverageAck(transfer, peerA, 4, emptyList())
        withTimeout(5.seconds) {
            while (h.pushes.find(transfer)?.contiguousAcked != 4) delay(10)
        }
        assertEquals(0, h.engine.depositRemainder(transfer))
        assertTrue(h.depositsFor().isEmpty())
    }

    @Test
    fun noLinkAtClaim_deposits() = runTest {
        val h = depositHarness()
        h.engine.runIn(backgroundScope)
        h.drivePushToInFlight()
        val transfer = h.transferIdsFor(peerA).single { h.pushes.find(it)?.kind == FilePushKind.DIRECT }
        // WebRTC link absent and Tor link absent: linksOf(peerA) is empty in the harness,
        // so the tick path deposits without waiting for the timeout.
        h.engine.onLinkClosed(peerA)
        withTimeout(5.seconds) { while (h.depositsFor().isEmpty()) delay(10) }
        assertEquals(2, h.depositsFor().size, "a closed link deposits at once")
    }
}
