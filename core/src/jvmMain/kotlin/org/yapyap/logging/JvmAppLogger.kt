package org.yapyap.logging
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class JvmAppLogger(
    logDirectory: Path,
    fileName: String = "yapyap.log",
    private val minLevel: LogLevel = LogLevel.DEBUG,
) : AppLogger {

    private val logFile: Path = Path(logDirectory, fileName)
    private val writeLock = Any()

    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneOffset.UTC)

    init {
        SystemFileSystem.createDirectories(logDirectory)
        if (!SystemFileSystem.exists(logFile)) {
            SystemFileSystem.sink(logFile).close()
        }
    }

    override fun debug(component: LogComponent, event: LogEvent, message: String, fields: Map<String, Any?>) {
        log(LogLevel.DEBUG, component, event, message, null, fields)
    }

    override fun info(component: LogComponent, event: LogEvent, message: String, fields: Map<String, Any?>) {
        log(LogLevel.INFO, component, event, message, null, fields)
    }

    override fun warn(component: LogComponent, event: LogEvent, message: String, fields: Map<String, Any?>) {
        log(LogLevel.WARN, component, event, message, null, fields)
    }

    override fun error(
        component: LogComponent,
        event: LogEvent,
        message: String,
        throwable: Throwable?,
        fields: Map<String, Any?>,
    ) {
        log(LogLevel.ERROR, component, event, message, throwable, fields)
    }

    private fun log(
        level: LogLevel,
        component: LogComponent,
        event: LogEvent,
        message: String,
        throwable: Throwable?,
        fields: Map<String, Any?>,
    ) {
        if (!shouldLog(level)) return

        val ts = timestampFormatter.format(Instant.now())
        val tags = "[$component][$level][$event]"
        val fieldsPart = formatFields(fields)
        val line = if (fieldsPart.isEmpty()) "$ts $tags $message" else "$ts $tags $message | $fieldsPart"

        val output = buildString {
            append(line)
            if (throwable != null) {
                appendLine()
                append(renderStackTrace(throwable))
            }
        }

        synchronized(writeLock) {
            println(output)
            val buffer = Buffer()
            buffer.writeString(output)
            buffer.writeByte('\n'.code.toByte())
            SystemFileSystem.sink(logFile, append = true).use { sink ->
                sink.write(buffer, buffer.size)
                sink.flush()
            }
        }
    }

    private fun shouldLog(level: LogLevel): Boolean {
        return level.ordinal >= minLevel.ordinal
    }

    private fun formatFields(fields: Map<String, Any?>): String {
        if (fields.isEmpty()) return ""
        return fields.entries.joinToString(", ") { (k, v) -> "$k=${v ?: "null"}" }
    }

    private fun renderStackTrace(throwable: Throwable): String =
        throwable.stackTraceToString().trimEnd()
}