package org.yapyap.backend.protocol

data class MessageEnvelope(
    val transferId: String,
    val source: String,
    val target: String,
    val createdAtEpochSeconds: Long,
    val nonce: ByteArray,
    val securityScheme: SignalSecurityScheme,
    val signature: ByteArray?,
    val payload: FilePayload,
)

data class MessagePayload(
    val messageId: String,
    val conversationId: String,
    val senderId: String,
    val content: String,
)