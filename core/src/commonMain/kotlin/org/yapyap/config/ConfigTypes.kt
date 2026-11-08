package org.yapyap.config

public sealed interface UpdateResult {
    public data object Success : UpdateResult
    public data class Failure(val reason: String) : UpdateResult
}
