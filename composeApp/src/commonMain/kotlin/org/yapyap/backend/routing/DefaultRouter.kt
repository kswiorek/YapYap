package org.yapyap.backend.routing

import org.yapyap.backend.crypto.IdentityResolver
import org.yapyap.backend.crypto.SignatureProvider
import org.yapyap.backend.protocol.TorEndpoint
import org.yapyap.backend.transport.tor.TorBackend
import org.yapyap.backend.transport.tor.TorTransport
import org.yapyap.backend.transport.webrtc.WebRtcBackend
import org.yapyap.backend.transport.webrtc.WebRtcTransport

class DefaultRouter(
    val torBackend: TorBackend,
    val webRtcBackend: WebRtcBackend,
    val torTransport: TorTransport,
    val webRtcTransport: WebRtcTransport,
    val identityResolver: IdentityResolver,
    val signatureProvider: SignatureProvider,
): Router {
    var started = false
    var torEndpoint: TorEndpoint? = null

    override suspend fun start() {
        try {
            torEndpoint = torBackend.start()
            torTransport.start()


            webRtcBackend.start()
            webRtcTransport.start()
            started = true
        }
        catch (e: Exception) {
            webRtcTransport.stop()
            torTransport.stop()
            webRtcBackend.stop()
            torBackend.stop()
            throw e
        }
    }
}