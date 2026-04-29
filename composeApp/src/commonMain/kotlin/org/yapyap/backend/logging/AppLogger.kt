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