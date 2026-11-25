package org.yapyap.routing.file.seal

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.yapyap.protocol.PeerId
import org.yapyap.routing.file.SealTransferHarness
import org.yapyap.routing.file.ackOffer
import org.yapyap.routing.file.allTransfers
import org.yapyap.routing.file.awaitOffers
import org.yapyap.routing.file.chunkIndices
import org.yapyap.routing.file.fixtureAccount
import org.yapyap.routing.file.fixtureFileId
import org.yapyap.routing.file.peerA
import org.yapyap.routing.file.peerB
import org.yapyap.routing.file.relay1
import org.yapyap.routing.file.relay2
import org.yapyap.routing.file.sealTransferHarness
import org.yapyap.routing.file.transfersFor
import org.yapyap.routing.policy.RelaySelectionPolicy
import org.yapyap.routing.router.RouterConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SealDepositTest {

    private fun depositHarness(
        relays: List<PeerId> = listOf(relay1, relay2),
    ): SealTransferHarness = sealTransferHarness(
        relaySelection = object : RelaySelectionPolicy {
            override suspend fun selectRelays(targetDevice: PeerId) = relays
        },
        routerConfig = RouterConfig(
            relayBackupAfter = 200.milliseconds,
            chunkAckTimeout = 500.milliseconds,
        ),
    )

    private suspend fun SealTransferHarness.drivePushToOffered() = apply {
        seals.push(fixtureFileId, fixtureAccount)
        awaitOffers(2)
    }

    private suspend fun SealTransferHarness.depositsFor(device: PeerId = peerA): List<org.yapyap.routing.store.OutgoingTransfer> {
        val sealIds = sealStore.byFileAndTarget(fixtureFileId, device).map { it.sealId }.toSet()
        return allTransfers().filter { it.sealId in sealIds && (it.peer == relay1 || it.peer == relay2) }
    }

    @Test
    fun depositRound_isAtMostOnce_acrossTicksAndRestart() = runTest {
        val h = depositHarness()
        h.engine.runIn(backgroundScope)
        h.seals.runIn(backgroundScope)
        h.drivePushToOffered()
        // No link to the target: the tick's gone signal fires one deposit round.
        withTimeout(10.seconds) {
            while (h.depositsFor().size < 2) delay(50.milliseconds)
        }
        assertEquals(2, h.depositsFor().size, "one round per seal")

        // Ten more seconds of ticks: the claim survives, so no second round.
        delay(2.seconds)
        assertEquals(2, h.depositsFor().size, "ticks never open a second round")

        // The seal's deposit round is claimed durably: a second claim fails.
        val sealId = h.sealStore.byFileAndTarget(fixtureFileId, peerA).single().sealId
        assertEquals(false, h.sealStore.claimDeposit(sealId, h.clock.now()), "restart never re-deposits")
    }

    @Test
    fun linkDownWhileChunksQueued_tickDepositsOnce() = runTest {
        val h = depositHarness()
        h.engine.runIn(backgroundScope)
        h.seals.runIn(backgroundScope)
        h.drivePushToOffered()
        val transfer = h.transfersFor(peerA).single()
        // ACK the offer so chunks flow, then let the no-link tick deposit.
        h.engine.onOutcome(h.ackOffer(transfer.transferId, peerA))
        withTimeout(5.seconds) { while (h.chunkIndices().isEmpty()) delay(20.milliseconds) }
        withTimeout(10.seconds) {
            while (h.depositsFor().size < 2) delay(50.milliseconds)
        }
        assertEquals(2, h.depositsFor().size)
        delay(2.seconds)
        assertEquals(2, h.depositsFor().size, "ticks never open a second round")
    }

    @Test
    fun depositMarker_neverChases() = runTest {
        val h = depositHarness()
        h.engine.runIn(backgroundScope)
        h.seals.runIn(backgroundScope)
        h.drivePushToOffered()
        withTimeout(10.seconds) {
            while (h.depositsFor().size < 2) delay(50.milliseconds)
        }
        // A deposit is never re-deposited: only the seal's direct transfer acts on Gone.
        // The deposit transfers themselves stay live without spawning more.
        delay(2.seconds)
        assertEquals(2, h.depositsFor().size)
        assertTrue(
            h.allTransfers().none { it.peer != peerA && it.peer != peerB && it.peer != relay1 && it.peer != relay2 })
    }

    @Test
    fun noRelaySelected_noDeposit() = runTest {
        val h = sealTransferHarness(
            routerConfig = RouterConfig(
                relayBackupAfter = 200.milliseconds,
                chunkAckTimeout = 500.milliseconds,
            ),
        )
        h.engine.runIn(backgroundScope)
        h.seals.runIn(backgroundScope)
        h.seals.push(fixtureFileId, fixtureAccount)
        h.awaitOffers(2)
        delay(2.seconds)
        assertTrue(
            h.transfersFor(relay1).isEmpty() && h.transfersFor(relay2).isEmpty(),
            "empty selection means no deposit"
        )
    }

    @Test
    fun fullyAckedTransfer_noDeposit() = runTest {
        val h = depositHarness()
        h.engine.runIn(backgroundScope)
        h.seals.runIn(backgroundScope)
        h.drivePushToOffered()
        val transfer = h.transfersFor(peerA).single()
        h.engine.onOutcome(h.ackOffer(transfer.transferId, peerA))
        withTimeout(5.seconds) { while (h.chunkIndices().isEmpty()) delay(20.milliseconds) }
        // Cover the whole file: the direct transfer finishes, the seal delivers, no deposit.
        val count = h.transferStore.find(transfer.transferId)?.shape?.chunkCount ?: 5
        h.engine.onAck(transfer.transferId, peerA, highestContiguousIndex = count - 1)
        withTimeout(5.seconds) {
            while (h.transferStore.find(transfer.transferId)?.status?.isLive == true) delay(20.milliseconds)
        }
        delay(1.seconds)
        assertTrue(h.depositsFor().isEmpty(), "a finished direct transfer needs no insurance")
    }

    @Test
    fun noLinkAtClaim_deposits() = runTest {
        val h = depositHarness()
        h.engine.runIn(backgroundScope)
        h.seals.runIn(backgroundScope)
        h.drivePushToOffered()
        // WebRTC link absent and Tor link absent: linksOf(peerA) is empty in the harness,
        // so the tick path deposits without waiting for the timeout.
        withTimeout(10.seconds) { while (h.depositsFor().isEmpty()) delay(50.milliseconds) }
        assertEquals(2, h.depositsFor().size, "a missing link deposits at once")
    }
}
