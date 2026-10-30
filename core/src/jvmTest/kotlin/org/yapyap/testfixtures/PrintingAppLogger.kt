package org.yapyap.testfixtures

import org.yapyap.logging.AppLogger
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import kotlin.time.TimeSource

/**
 * Prints every log line with elapsed time, so live (real network) integration
 * tests carry the Tor/router log in their test report. Without it a timeout in
 * those tests is undiagnosable.
 */
internal class PrintingAppLogger : AppLogger {
    private val start = TimeSource.Monotonic.markNow()

    private fun print(
        level: String,
        component: LogComponent,
        event: LogEvent,
        message: String,
        fields: Map<String, Any?>,
        throwable: Throwable? = null
    ) {
        println("[+${start.elapsedNow().inWholeMilliseconds}ms][$component][$level][$event] $message $fields")
        throwable?.printStackTrace(System.out)
    }

    override fun debug(component: LogComponent, event: LogEvent, message: String, fields: Map<String, Any?>) =
        print("DEBUG", component, event, message, fields)

    override fun info(component: LogComponent, event: LogEvent, message: String, fields: Map<String, Any?>) =
        print("INFO", component, event, message, fields)

    override fun warn(component: LogComponent, event: LogEvent, message: String, fields: Map<String, Any?>) =
        print("WARN", component, event, message, fields)

    override fun error(
        component: LogComponent,
        event: LogEvent,
        message: String,
        throwable: Throwable?,
        fields: Map<String, Any?>
    ) =
        print("ERROR", component, event, message, fields, throwable)
}
