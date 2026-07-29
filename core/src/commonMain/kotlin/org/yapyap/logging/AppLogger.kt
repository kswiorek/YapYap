package org.yapyap.logging

interface AppLogger {
    fun debug(
        component: LogComponent,
        event: LoggingTypes,
        message: String,
        fields: Map<String, Any?> = emptyMap(),
    )

    fun info(
        component: LogComponent,
        event: LoggingTypes,
        message: String,
        fields: Map<String, Any?> = emptyMap(),
    )

    fun warn(
        component: LogComponent,
        event: LoggingTypes,
        message: String,
        fields: Map<String, Any?> = emptyMap(),
    )

    fun error(
        component: LogComponent,
        event: LoggingTypes,
        message: String,
        throwable: Throwable? = null,
        fields: Map<String, Any?> = emptyMap(),
    )
}

object NoopAppLogger : AppLogger {
    override fun debug(component: LogComponent, event: LoggingTypes, message: String, fields: Map<String, Any?>) = Unit

    override fun info(component: LogComponent, event: LoggingTypes, message: String, fields: Map<String, Any?>) = Unit

    override fun warn(component: LogComponent, event: LoggingTypes, message: String, fields: Map<String, Any?>) = Unit

    override fun error(
        component: LogComponent,
        event: LoggingTypes,
        message: String,
        throwable: Throwable?,
        fields: Map<String, Any?>,
    ) = Unit
}