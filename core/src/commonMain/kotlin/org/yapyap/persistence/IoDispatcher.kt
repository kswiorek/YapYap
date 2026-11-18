package org.yapyap.persistence

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Shared blocking-I/O dispatcher for common code (file hashing, keystore, Tor selector,
 * single-threaded database access via [databaseDispatcher]).
 *
 * `Dispatchers.IO` is JVM/Android-only API: on Kotlin/Native it exists but is internal,
 * so common code cannot touch it. Platform actuals provide the closest equivalent
 * (IO on JVM/Android, Default on iOS).
 */
internal expect val ioDispatcher: CoroutineDispatcher
