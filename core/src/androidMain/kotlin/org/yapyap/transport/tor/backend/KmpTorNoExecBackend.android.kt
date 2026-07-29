package org.yapyap.transport.tor.backend

import io.matthewnelson.kmp.file.File
import io.matthewnelson.kmp.tor.common.api.ResourceLoader
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec

internal actual fun createResourceLoader(resourceDir: File): ResourceLoader.Tor {
    return ResourceLoaderTorExec.getOrCreate(resourceDir)
}