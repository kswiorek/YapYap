package org.yapyap.routing.file

import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import org.yapyap.logging.LogLevel
import kotlin.coroutines.cancellation.CancellationException

/**
 * The file tree's one catch-and-log shape (docs/file/cleanup.md §2.11.1): runs [block] as work
 * whose failure must not take its caller down — a guarded launch, a best-effort send, a
 * fail-closed lookup. Cancellation propagates; any other exception is logged as [event] at
 * [level] with [message] and the result is null, which the caller maps to its own fallback.
 */
internal inline fun <T> fileGuarded(
    message: String,
    fields: Map<String, Any?> = emptyMap(),
    level: LogLevel = LogLevel.ERROR,
    event: LogEvent = LogEvent.FILE_TASK_FAILED,
    block: () -> T,
): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logFileFailure(message, fields, level, event, e)
        null
    }

internal fun logFileFailure(
    message: String,
    fields: Map<String, Any?>,
    level: LogLevel,
    event: LogEvent,
    error: Exception,
) {
    val component = LogComponent.FILE
    when (level) {
        LogLevel.DEBUG -> AppLog.debug(component, event, message, fields + ("error" to error.toString()))
        LogLevel.INFO -> AppLog.info(component, event, message, fields + ("error" to error.toString()))
        LogLevel.WARN -> AppLog.warn(component, event, message, fields + ("error" to error.toString()))
        LogLevel.ERROR -> AppLog.error(component, event, message, error, fields)
    }
}
