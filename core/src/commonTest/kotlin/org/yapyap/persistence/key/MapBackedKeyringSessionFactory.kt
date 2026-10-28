package org.yapyap.persistence.key

/**
 * In-memory keyring backing — avoids OS keyring during CI / headless runs.
 */
internal class MapBackedKeyringSessionFactory(
    val storage: MutableMap<Pair<String, String>, String> = mutableMapOf(),
    private val keyringNamespace: String? = null,
) : KeyringSessionFactory {

    init {
        validateKeyringNamespace(keyringNamespace)
    }

    override fun open(): KeyringSession =
        object : KeyringSession {
            override fun setPassword(serviceName: String, accountName: String, secret: String) {
                storage[keyringServiceName(serviceName, keyringNamespace) to accountName] = secret
            }

            override fun getPassword(serviceName: String, accountName: String): String =
                storage[keyringServiceName(serviceName, keyringNamespace) to accountName]
                    ?: error("no password")

            override fun deletePassword(serviceName: String, accountName: String) {
                storage.remove(keyringServiceName(serviceName, keyringNamespace) to accountName)
            }

            override fun close() {}
        }
}
