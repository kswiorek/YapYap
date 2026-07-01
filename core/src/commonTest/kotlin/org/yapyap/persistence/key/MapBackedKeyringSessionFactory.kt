package org.yapyap.persistence.key

/**
 * In-memory keyring backing — avoids OS keyring during CI / headless runs.
 */
internal class MapBackedKeyringSessionFactory(
    val storage: MutableMap<Pair<String, String>, String> = mutableMapOf(),
) : KeyringSessionFactory {

    override fun open(): KeyringSession =
        object : KeyringSession {
            override fun setPassword(serviceName: String, accountName: String, secret: String) {
                storage[serviceName to accountName] = secret
            }

            override fun getPassword(serviceName: String, accountName: String): String =
                storage[serviceName to accountName]
                    ?: error("no password")

            override fun deletePassword(serviceName: String, accountName: String) {
                storage.remove(serviceName to accountName)
            }

            override fun close() {}
        }
}
