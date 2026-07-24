package org.yapyap.persistence.messaging

import org.yapyap.protocol.envelopes.MessagePayload

interface MessageRepository {
    fun getMessagesInRoom(roomId: String): List<MessagePayload>
}