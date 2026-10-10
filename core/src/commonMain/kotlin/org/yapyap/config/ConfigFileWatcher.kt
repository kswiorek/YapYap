package org.yapyap.config

import kotlinx.coroutines.flow.Flow

internal interface ConfigFileWatcher {
    /** Emits a debounced notification whenever the watched config file changes. */
    fun changes(): Flow<Unit>
}