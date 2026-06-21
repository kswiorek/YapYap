package org.yapyap.backend.crypto

import com.github.javakeyring.Keyring

object JavaKeyringSessionFactory : KeyringSessionFactory {
    override fun open(): KeyringSession {
        val keyring = Keyring.create()
        return object : KeyringSession {
            override fun setPassword(serviceName: String, accountName: String, secret: String) {
                keyring.setPassword(serviceName, accountName, secret)
            }

            override fun getPassword(serviceName: String, accountName: String): String =
                keyring.getPassword(serviceName, accountName)

            override fun close() {
                keyring.close()
            }
        }
    }
}
