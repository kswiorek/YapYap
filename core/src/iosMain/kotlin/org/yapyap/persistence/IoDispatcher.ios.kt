package org.yapyap.persistence

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * No `Dispatchers.IO` on Kotlin/Native (it exists but is internal): file hashing and
 * keystore traffic runs on Default instead. Acceptable — these are small, infrequent
 * blocking sections, and nothing here needs IO's unbounded pool.
 */
internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
