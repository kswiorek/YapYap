package org.yapyap.backend.db

interface MasterKeyProvider {
    fun getOrCreateMasterKey(): ByteArray
}
