package org.yapyap.config

import kotlinx.coroutines.flow.Flow

interface ConfigFileWatcher {
    /** Emits a debounced notification whenever the watched config file changes. */
    fun changes(): Flow<Unit>
}