package org.yapyap.crypto

import com.github.javakeyring.Keyring
import org.yapyap.persistence.key.KeyringSession
import org.yapyap.persistence.key.KeyringSessionFactory
import org.yapyap.persistence.key.keyringServiceName
import org.yapyap.persistence.key.validateKeyringNamespace

internal class JavaKeyringSessionFactory(
    private val keyringNamespace: String? = null,
) : KeyringSessionFactory {
    init {
        validateKeyringNamespace(keyringNamespace)
    }
    override fun open(): KeyringSession {
        val keyring = Keyring.create()
        return object : KeyringSession {
            override fun setPassword(serviceName: String, accountName: String, secret: String) {
                keyring.setPassword(keyringServiceName(serviceName, keyringNamespace), accountName, secret)
            }

            override fun getPassword(serviceName: String, accountName: String): String =
                keyring.getPassword(keyringServiceName(serviceName, keyringNamespace), accountName)

            override fun deletePassword(serviceName: String, accountName: String) {
                keyring.deletePassword(keyringServiceName(serviceName, keyringNamespace), accountName)
            }

            override fun close() {
                keyring.close()
            }
        }
    }
}
