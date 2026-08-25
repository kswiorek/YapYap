package org.yapyap.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds.*
import kotlin.time.Duration.Companion.milliseconds

class JvmConfigFileWatcher(
    private val configFile: Path,
) : ConfigFileWatcher {

    @OptIn(FlowPreview::class)
    override fun changes(): Flow<Unit> = callbackFlow {
        val nio = java.nio.file.Path.of(configFile.toString()).toAbsolutePath()

        val watchService = withContext(Dispatchers.IO) {
            FileSystems.getDefault().newWatchService().also { service ->
                nio.parent!!.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
            }
        }

        launch(Dispatchers.IO) {
            try {
                while (true) {
                    val key = watchService.take()
                    val changed = key.pollEvents().any { event ->
                        (event.context() as? java.nio.file.Path) == nio.fileName
                    }
                    val valid = key.reset()
                    if (!valid) break
                    if (changed) trySend(Unit)
                }
            } catch (_: ClosedWatchServiceException) {
                // watch service closed on cancellation
            }
        }

        awaitClose { watchService.close() }
    }.debounce(200.milliseconds)
}
