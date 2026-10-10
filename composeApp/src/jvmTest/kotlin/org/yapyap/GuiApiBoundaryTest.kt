package org.yapyap

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * GUI boundary enforcement, consumer side (companion: `PublicApiSurfaceTest` in `:core`).
 *
 * The PoC GUI in `:composeApp` may only use the orchestrator entry points, the runtime
 * services, and the shared vocabulary — every interaction goes through
 * `Orchestrator.runtime()`. Any other `org.yapyap.*` import fails this test: that is a
 * missing runtime API, not a license to bypass (ask, don't import).
 *
 * Allowed imports:
 * - `org.yapyap.orchestrator.runtime.*` (the whole subtree is GUI API by design),
 * - the orchestrator entry points, `OnboardingState` and `ResetReason` (exact list below),
 * - the shared vocabulary types (exact list below),
 * - `:composeApp`'s own code (`org.yapyap.*` outside the core packages above).
 */
class GuiApiBoundaryTest {

    private val allowedExactImports: Set<String> = setOf(
        "org.yapyap.orchestrator.Orchestrator",
        "org.yapyap.orchestrator.OrchestratorFactory",
        "org.yapyap.orchestrator.OrchestratorState",
        "org.yapyap.orchestrator.NodeMode",
        "org.yapyap.orchestrator.SetupIntent",
        "org.yapyap.orchestrator.SetupResult",
        "org.yapyap.orchestrator.BootstrapEndpoint",
        "org.yapyap.orchestrator.onboarding.OnboardingState",
        "org.yapyap.orchestrator.boot.ResetReason",
        "org.yapyap.protocol.PeerId",
        "org.yapyap.protocol.RoomId",
        "org.yapyap.protocol.TorEndpoint",
        "org.yapyap.protocol.AccountId",
        "org.yapyap.protocol.DeviceType",
        "org.yapyap.protocol.RoomType",
        "org.yapyap.protocol.RoomMemberRole",
        "org.yapyap.protocol.RoomMemberStatus",
        "org.yapyap.protocol.AccountRole",
        "org.yapyap.protocol.IdentityStatus",
        "org.yapyap.config.Setting",
        "org.yapyap.config.ConfigValue",
        "org.yapyap.config.UpdateResult",
        "org.yapyap.config.NumberSetting",
        "org.yapyap.config.TextSetting",
        "org.yapyap.config.ToggleSetting",
        "org.yapyap.config.PeriodSetting",
    )

    private val allowedSubtrees: Set<String> = setOf(
        "org.yapyap.orchestrator.runtime.",
    )

    /** Core packages the GUI must never import (outside the allow-lists above). */
    private val corePackages = setOf(
        "config", "crypto", "logging", "orchestrator",
        "persistence", "protection", "protocol", "routing", "transport",
    )

    @Test
    fun `GUI only imports the orchestrator API`() {
        val base = if (File("src").isDirectory) File("src") else File("composeApp/src")
        assertTrue(base.isDirectory, "Cannot locate composeApp sources from ${File(".").absolutePath}")
        val offenders = mutableListOf<String>()
        base.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val rel = base.toPath().relativize(file.toPath()).toString()
                file.readLines().forEachIndexed { index, raw ->
                    val line = raw.trim()
                    if (!line.startsWith("import org.yapyap.")) return@forEachIndexed
                    val fqn = line.removePrefix("import ").trim()
                    if (fqn in allowedExactImports) return@forEachIndexed
                    if (allowedSubtrees.any { fqn.startsWith(it) }) return@forEachIndexed
                    val secondSegment = fqn.removePrefix("org.yapyap.").substringBefore('.')
                    if (secondSegment !in corePackages) return@forEachIndexed // own app code
                    offenders += "$rel:${index + 1}: $line"
                }
            }
        assertTrue(
            offenders.isEmpty(),
            "GUI imports outside the orchestrator API (${offenders.size}) — " +
                    "this is a missing runtime API, not a license to bypass:\n" +
                    offenders.joinToString("\n") { "  $it" },
        )
    }
}
