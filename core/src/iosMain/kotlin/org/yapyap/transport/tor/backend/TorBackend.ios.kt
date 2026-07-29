package org.yapyap.transport.tor.backend

import io.matthewnelson.kmp.file.File
import io.matthewnelson.kmp.tor.common.api.ResourceLoader
import io.matthewnelson.kmp.tor.resource.noexec.tor.ResourceLoaderTorNoExec

internal actual fun createResourceLoader(resourceDir: File): ResourceLoader.Tor {
    return ResourceLoaderTorNoExec.getOrCreate(resourceDir)
}