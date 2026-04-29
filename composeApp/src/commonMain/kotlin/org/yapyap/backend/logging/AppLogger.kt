package org.yapyap.backend.logging

interface AppLogger {
    fun debug(
        component: LogComponent,
        event: LogEvent,
        message: String,
        fields: Map<String, Any?> = emptyMap(),
    )

    fun info(
        component: LogComponent,
        event: LogEvent,
        message: String,
        fields: Map<String, Any?> = emptyMap(),
    )

    fun warn(
        component: LogComponent,
        event: LogEvent,
        message: String,
        fields: Map<String, Any?> = emptyMap(),
    )

    fun error(
        component: LogComponent,
        event: LogEvent,
        message: String,
        throwable: Throwable? = null,
        fields: Map<String, Any?> = emptyMap(),
    )
}

enum class LogComponent {
    ROUTER,
    WEBRTC_TRANSPORT,
    WEBRTC_BACKEND,
    TOR_TRANSPORT,
    TOR_BACKEND,
    CRYPTO,
    DATABASE,
}

enum class LogEvent {
    STARTED,
    STOPPED,
    INBOUND_ENVELOPE_RECEIVED,
    ENVELOPE_DEDUPLICATED,
    ENVELOPE_WRONG_TARGET,
    ENVELOPE_DECODE_FAILED,
    UNSUPPORTED_PACKET_TYPE,
    SIGNAL_INBOUND_DROPPED_WRONG_TARGET,
    SIGNAL_INBOUND_HANDLED,
    SIGNAL_OUTBOUND_EMITTED,
    SESSION_STATE_CHANGED,
    SESSION_FAILED,
    ROUTER_STARTED,
    IDENTITY_DEVICE_RECORD_FOUND,
    IDENTITY_DEVICE_RECORD_MISSING,
    IDENTITY_DEVICE_RECORD_CREATED,
    IDENTITY_ACCOUNT_RECORD_FOUND,
    IDENTITY_ACCOUNT_RECORD_CREATED,
    DEDUP_CACHE_HIT,
    DEDUP_CACHE_MISS,
    DEDUP_PRUNED,
    DATABASE_INITIALIZED,
    DATABASE_MIGRATED,
    PACKET_ALLOCATOR_DEVICE_ASSIGNED,
    PACKET_ID_ALLOCATED,
    PACKET_ID_ALLOCATION_FAILED,
    CRYPTO_KEYPAIR_GENERATED,
    KEY_STORED,
    KEY_LOOKUP_MISS,
    SIGNATURE_SIGNED,
    SIGNATURE_VERIFIED,
    SIGNATURE_VERIFICATION_FAILED,
}

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

object NoopAppLogger : AppLogger {
    override fun debug(component: LogComponent, event: LogEvent, message: String, fields: Map<String, Any?>) = Unit

    override fun info(component: LogComponent, event: LogEvent, message: String, fields: Map<String, Any?>) = Unit

    override fun warn(component: LogComponent, event: LogEvent, message: String, fields: Map<String, Any?>) = Unit

    override fun error(
        component: LogComponent,
        event: LogEvent,
        message: String,
        throwable: Throwable?,
        fields: Map<String, Any?>,
    ) = Unit
}