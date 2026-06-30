package org.yapyap.persistence.key

fun interface KeyringSessionFactory {
    fun open(): KeyringSession
}

interface KeyringSession : AutoCloseable {
    fun setPassword(serviceName: String, accountName: String, secret: String)

    fun getPassword(serviceName: String, accountName: String): String

    fun deletePassword(serviceName: String, accountName: String)
}
