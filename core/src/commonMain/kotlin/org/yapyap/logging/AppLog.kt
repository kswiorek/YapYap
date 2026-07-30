package org.yapyap.logging

object AppLog {
    private var delegate: AppLogger = NoopAppLogger

    fun init(logger: AppLogger) {
        delegate = logger
    }

    fun reset() {
        delegate = NoopAppLogger
    }

    fun debug(
        component: LogComponent,
        event: LogEvent,
        message: String,
        fields: Map<String, Any?> = emptyMap(),
    ) = delegate.debug(component, event, message, fields)

    fun info(
        component: LogComponent,
        event: LogEvent,
        message: String,
        fields: Map<String, Any?> = emptyMap(),
    ) = delegate.info(component, event, message, fields)

    fun warn(
        component: LogComponent,
        event: LogEvent,
        message: String,
        fields: Map<String, Any?> = emptyMap(),
    ) = delegate.warn(component, event, message, fields)

    fun error(
        component: LogComponent,
        event: LogEvent,
        message: String,
        throwable: Throwable? = null,
        fields: Map<String, Any?> = emptyMap(),
    ) = delegate.error(component, event, message, throwable, fields)
}