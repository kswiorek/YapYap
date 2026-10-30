package org.yapyap.gui.screens.shell

import org.yapyap.orchestrator.runtime.message.MessageDisplayItem
import org.yapyap.protocol.AccountId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class ChatRowsTest {

    private val alice = AccountId("alice")
    private val bob = AccountId("bob")
    private val now = Clock.System.now()

    private fun text(author: AccountId, secondsAgo: Int, id: Uuid = Uuid.random()) =
        MessageDisplayItem.Text(id, author, now - secondsAgo.seconds, "hi")

    /**
     * The crash: an orphan whose gap precedes it and which starts a new group (different
     * author). Gap and message share a message id; their row keys must still differ.
     */
    @Test
    fun orphanStartingANewGroup_hasUniqueRowKeys() {
        val orphanId = Uuid.random()
        val orphan = text(bob, secondsAgo = 10, id = orphanId)
        val gap = MessageDisplayItem.Gap(orphanId, bob, orphan.timestamp, missingPrevIds = listOf(Uuid.random()))
        val rows = groupItems(listOf(text(alice, secondsAgo = 60), gap, orphan))

        val keys = rows.map { rowKey(it) }
        assertEquals(keys.size, keys.toSet().size, "duplicate LazyColumn keys: $keys")
        assertEquals(listOf("gap:$orphanId", "grp:$orphanId"), keys.takeLast(2))
    }

    @Test
    fun gapIsItsOwnRow_andItsMessageStartsTheNextOne() {
        val orphanId = Uuid.random()
        val orphan = text(alice, secondsAgo = 5, id = orphanId)
        val gap = MessageDisplayItem.Gap(orphanId, alice, orphan.timestamp, missingPrevIds = listOf(Uuid.random()))
        val rows = groupItems(listOf(text(alice, secondsAgo = 30), gap, orphan))

        val groups = rows.filterIsInstance<ChatRow.Group>()
        assertEquals(3, groups.size)
        assertEquals(listOf<MessageDisplayItem>(gap), groups[1].items)
        assertEquals(listOf<MessageDisplayItem>(orphan), groups[2].items)
    }
}
