package org.yapyap.logging

import io.github.aakira.napier.Antilog
import io.github.aakira.napier.LogLevel
import java.nio.file.Path

class FileAntilog(private val logFile: Path) : Antilog() {
    override fun performLog(
        priority: LogLevel, tag: String?, throwable: Throwable?, message: String?
    ) {
        // your existing file-writing logic here
    }
}