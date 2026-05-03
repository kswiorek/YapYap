package org.yapyap.backend.logging

/**
 * Captures log calls for assertions in tests.
 */
class RecordingAppLogger : AppLogger {

    data class Entry(
        val level: LogLevel,
        val component: LogComponent,
        val event: LogEvent,
        val message: String,
        val fields: Map<String, Any?>,
        val throwable: Throwable?,
    )

    val entries = mutableListOf<Entry>()

    fun clear() {
        entries.clear()
    }

    override fun debug(
        component: LogComponent,
        event: LogEvent,
        message: String,
        fields: Map<String, Any?>,
    ) {
        entries.add(Entry(LogLevel.DEBUG, component, event, message, fields, null))
    }

    override fun info(
        component: LogComponent,
        event: LogEvent,
        message: String,
        fields: Map<String, Any?>,
    ) {
        entries.add(Entry(LogLevel.INFO, component, event, message, fields, null))
    }

    override fun warn(
        component: LogComponent,
        event: LogEvent,
        message: String,
        fields: Map<String, Any?>,
    ) {
        entries.add(Entry(LogLevel.WARN, component, event, message, fields, null))
    }

    override fun error(
        component: LogComponent,
        event: LogEvent,
        message: String,
        throwable: Throwable?,
        fields: Map<String, Any?>,
    ) {
        entries.add(Entry(LogLevel.ERROR, component, event, message, fields, throwable))
    }
}
