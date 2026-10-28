package org.yapyap.persistence.key

internal fun interface KeyringSessionFactory {
    fun open(): KeyringSession
}

/**
 * Resolves the effective OS-keyring service name for [baseServiceName] under
 * [namespace]. A null namespace keeps the legacy unprefixed service (production
 * behavior, zero migration); any other value is validated fail-fast and appended.
 */
internal fun keyringServiceName(baseServiceName: String, namespace: String?): String {
    validateKeyringNamespace(namespace)
    if (namespace == null) return baseServiceName
    return "$baseServiceName.$namespace"
}

/** Fail-fast namespace check; implementations call this from `init`. */
internal fun validateKeyringNamespace(namespace: String?) {
    if (namespace == null) return
    require(namespace.isNotBlank()) { "keyring namespace must not be blank" }
    require(namespace.length <= 64) { "keyring namespace must be at most 64 chars" }
    require(namespace.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }) {
        "keyring namespace must match [A-Za-z0-9._-]"
    }
}

internal interface KeyringSession : AutoCloseable {
    fun setPassword(serviceName: String, accountName: String, secret: String)

    fun getPassword(serviceName: String, accountName: String): String

    fun deletePassword(serviceName: String, accountName: String)
}
