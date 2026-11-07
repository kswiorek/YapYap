package org.yapyap

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The layer rules from `docs/guide.md`, checked on core's main sources:
 *
 * 1. **Dependencies** — a layer imports only the layers its row allows (§3). `logging`,
 *    `config` (shared settings and limit types) and `persistence` (database infrastructure and
 *    generated queries) are usable everywhere; `wiring` may import anything.
 * 2. **Data ownership** — each generated `*Queries` class is used only by the package that owns
 *    its tables (§4), so a table cannot quietly gain a second writer.
 *
 * Known debt is listed in [knownDebt] by file and import: an entry there is a violation that is
 * scheduled to go, not an exception to the rule. A new violation fails this test; so does a
 * debt entry that no longer occurs (delete it).
 */
class ArchitectureTest {

    private val layers = listOf("protocol", "transport", "crypto", "protection", "routing", "orchestrator")

    /** Top-level package → packages (or package prefixes) it may import. */
    private val allowed: Map<String, List<String>> = mapOf(
        "protocol" to emptyList(),
        "transport" to listOf("protocol"),
        "crypto" to listOf("protocol"),
        "protection" to listOf("crypto", "protocol"),
        // The router reads identity through crypto's read-only views (LocalIdentity, DeviceIdentity).
        "routing" to listOf("transport", "protection", "protocol", "crypto.identity"),
        // The orchestrator talks to the router through its API and vocabulary, and implements the
        // ports the router declares for it (directory, sync content, ping frontiers).
        "orchestrator" to listOf(
            "routing.router",
            "routing.directory",
            "routing.sync.SyncPayloadProvider",
            "routing.ping.FrontierSnapshotProvider",
            "crypto",
            "protocol",
        ),
    )

    private val everywhere = listOf("logging", "config", "persistence")

    /** Generated queries class → the only package prefix allowed to use it. */
    private val queryOwners: Map<String, String> = mapOf(
        "identityQueries" to "orchestrator.identity",
        "roomQueries" to "orchestrator",
        "messageQueries" to "orchestrator.store",
        "messageDeliveryQueries" to "orchestrator.store",
        "syncQueries" to "orchestrator.store",
        "cryptoQueries" to "crypto.store",
        "outboxQueries" to "routing.store",
        "dedupQueries" to "routing.store",
        "syncAttemptQueries" to "routing.store",
        "routesQueries" to "routing.store",
    )

    /** "file (relative to org/yapyap) -> imported name" pairs that are known, scheduled debt. */
    private val knownDebt: Set<String> = setOf(
        // Wire types still depend on crypto and transport (review point 5: protocol at the bottom).
        "protocol/envelopes/BootstrapEnvelope.kt -> crypto.identity.*",
        "protocol/envelopes/BootstrapEnvelope.kt -> crypto.keys.EncryptionPublicKey",
        "protocol/envelopes/BootstrapEnvelope.kt -> crypto.keys.SignedPreKeyId",
        "protocol/envelopes/BootstrapEnvelope.kt -> crypto.keys.SigningPublicKey",
        "protocol/envelopes/MessageEnvelope.kt -> crypto.primitives.CryptoProvider",
        "protocol/envelopes/WebRtcSignalEnvelope.kt -> transport.webrtc.types.WebRtcSignalKind",
        "protection/envelope/WebRtcSignalProtection.kt -> transport.webrtc.types.WebRtcSignal",
        "protection/service/DefaultEnvelopeProtectionService.kt -> transport.webrtc.types.WebRtcSignal",
        "protection/service/EnvelopeProtectionService.kt -> transport.webrtc.types.WebRtcSignal",
        // Tor status types still reach the orchestrator (review point 3: the router's own vocabulary).
        "orchestrator/TransportStatusMapping.kt -> transport.tor.backend.TorBootstrapProblem",
        "orchestrator/TransportStatusMapping.kt -> transport.tor.backend.TorHealth",
        "orchestrator/TransportStatusMapping.kt -> transport.tor.backend.TorPublishState",
        "orchestrator/TransportStatusMapping.kt -> transport.tor.backend.TorRecoveryStep",
    )

    private fun mainSources(): List<File> {
        val base = if (File("src").isDirectory) File("src") else File("core/src")
        assertTrue(base.isDirectory, "Cannot locate core sources from ${File(".").absolutePath}")
        return base.listFiles()!!
            .filter { it.isDirectory && it.name.endsWith("Main") }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    }

    private fun relative(file: File): String = file.invariantSeparatorsPath.substringAfter("org/yapyap/")

    private fun packageOf(text: String): String? =
        Regex("""^package\s+org\.yapyap\.([\w.]+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)

    private fun matches(name: String, prefix: String): Boolean = name == prefix || name.startsWith("$prefix.")

    @Test
    fun `layers import only what the dependency table allows`() {
        val violations = mutableListOf<String>()
        val debtSeen = mutableSetOf<String>()
        for (file in mainSources()) {
            val text = file.readText()
            val pkg = packageOf(text) ?: continue
            val layer = pkg.substringBefore('.')
            if (layer !in layers) continue
            for (import in Regex("""^import\s+org\.yapyap\.([\w.*]+)""", RegexOption.MULTILINE).findAll(text)) {
                val name = import.groupValues[1]
                val target = name.substringBefore('.')
                if (target == layer || everywhere.any { matches(name, it) }) continue
                // An import "org.yapyap.orchestrator" from "orchestrator.x" is in-layer; anything else is checked.
                if (allowed.getValue(layer).any { matches(name, it) || matches(name.removeSuffix(".*"), it) }) continue
                val key = "${relative(file)} -> $name"
                if (key in knownDebt) debtSeen += key else violations += key
            }
        }
        val stale = knownDebt - debtSeen
        assertTrue(
            violations.isEmpty() && stale.isEmpty(),
            buildString {
                if (violations.isNotEmpty()) append(
                    "Imports the dependency table forbids:\n  ${
                        violations.joinToString(
                            "\n  "
                        )
                    }\n"
                )
                if (stale.isNotEmpty()) append(
                    "Known debt that no longer occurs (remove it from knownDebt):\n  ${
                        stale.joinToString(
                            "\n  "
                        )
                    }\n"
                )
            },
        )
    }

    @Test
    fun `generated queries are used only by the layer owning their tables`() {
        val violations = mutableListOf<String>()
        for (file in mainSources()) {
            val text = file.readText()
            val pkg = packageOf(text) ?: continue
            for (use in Regex("""\.(\w+Queries)\b""").findAll(text)) {
                val queries = use.groupValues[1]
                val owner = queryOwners[queries]
                if (owner == null) {
                    violations += "${relative(file)} uses $queries, which has no owner in queryOwners"
                } else if (!matches(pkg, owner)) {
                    violations += "${relative(file)} ($pkg) uses $queries, owned by $owner"
                }
            }
        }
        assertTrue(violations.isEmpty(), "Table ownership broken:\n  ${violations.distinct().joinToString("\n  ")}")
    }
}
