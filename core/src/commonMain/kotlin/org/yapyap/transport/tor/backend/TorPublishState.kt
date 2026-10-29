package org.yapyap.transport.tor.backend

/**
 * Backend-local onion publish state. Internal: the orchestrator maps this to
 * the public [org.yapyap.orchestrator.LocalTransportState] so no layer below
 * the orchestrator imports orchestrator vocabulary.
 */
internal enum class TorPublishState {
    PUBLISHING,
    PUBLISHED,
    DEGRADED,
}
