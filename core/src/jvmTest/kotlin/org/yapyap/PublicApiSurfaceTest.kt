package org.yapyap

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * GUI boundary enforcement, core side (companion: `GuiApiBoundaryTest` in `:composeApp`).
 *
 * `:core` compiles as a single module per target, so `internal` is usable from every core
 * source set (common + platform) yet invisible to `:composeApp`. This test locks that in:
 *
 * 1. Every declaration in core's main sources carries an explicit visibility modifier
 *    (top-level and direct members; `override` members, enum entries and data-class
 *    constructor properties are exempt, matching explicit-API rules). A missing modifier
 *    fails the build — no silent `public` defaults.
 *
 *    Known gap: constructor properties of non-`data` classes are indistinguishable from
 *    `data`-class ones to this line scan, so those rely on the explicit-API compiler
 *    warnings in build output instead.
 * 2. Every `public` top-level declaration is on the whitelist below. Anything else must be
 *    `internal`. Adding a new `public` type fails until it is deliberately listed here.
 *
 * The whitelist has two tiers: GUI API (importable by `:composeApp`, see
 * `GuiApiBoundaryTest`) and shared-internal vocabulary (public only because
 * SQLDelight-generated code or a public signature references it — never imported by the GUI).
 */
class PublicApiSurfaceTest {

    private val publicTopLevelWhitelist: Set<String> = setOf(
        // --- GUI API: orchestrator entry points -------------------------------------
        "org.yapyap.orchestrator.Orchestrator",
        "org.yapyap.orchestrator.OrchestratorFactory",
        "org.yapyap.orchestrator.OrchestratorState",
        "org.yapyap.orchestrator.NodeMode",
        "org.yapyap.orchestrator.SetupIntent",
        "org.yapyap.orchestrator.SetupResult",
        "org.yapyap.orchestrator.BootstrapEndpoint",
        "org.yapyap.orchestrator.runtime.OrchestratorRuntime",
        "org.yapyap.orchestrator.runtime.account.AccountService",
        "org.yapyap.orchestrator.runtime.admin.AdminService",
        "org.yapyap.orchestrator.runtime.config.ConfigService",
        "org.yapyap.orchestrator.runtime.identity.IdentityService",
        "org.yapyap.orchestrator.runtime.identity.AccountView",
        "org.yapyap.orchestrator.runtime.identity.DeviceView",
        "org.yapyap.orchestrator.runtime.identity.AccountAvailability",
        "org.yapyap.orchestrator.runtime.identity.AvailabilityLabel",
        "org.yapyap.orchestrator.runtime.message.MessagingService",
        "org.yapyap.orchestrator.runtime.message.MessageDisplayItem",
        "org.yapyap.orchestrator.runtime.message.IncomingMessageEvent",
        "org.yapyap.orchestrator.runtime.message.RoomPreview",
        "org.yapyap.orchestrator.runtime.message.RoomMessageWindow",
        "org.yapyap.orchestrator.runtime.message.SendTextResult",
        "org.yapyap.orchestrator.runtime.message.SendRefusal",
        "org.yapyap.orchestrator.runtime.message.FanoutReport",
        "org.yapyap.orchestrator.runtime.room.RoomService",
        "org.yapyap.orchestrator.runtime.room.RoomDetails",
        "org.yapyap.orchestrator.runtime.room.RoomMemberView",
        "org.yapyap.orchestrator.runtime.room.CreateRoomResult",
        "org.yapyap.orchestrator.runtime.room.CreateRoomRefusal",
        "org.yapyap.orchestrator.runtime.room.RoomEventOutcome",
        "org.yapyap.orchestrator.runtime.room.RoomEventRefusal",
        "org.yapyap.orchestrator.runtime.onboarding.OnboardingService",
        "org.yapyap.orchestrator.runtime.onboarding.SponsorOutcome",
        "org.yapyap.orchestrator.runtime.onboarding.SponsorRefusal",
        "org.yapyap.orchestrator.runtime.onboarding.InviteDefect",
        "org.yapyap.orchestrator.runtime.globalevent.GlobalEventOutcome",
        "org.yapyap.orchestrator.runtime.globalevent.GlobalEventRefusal",
        "org.yapyap.orchestrator.onboarding.OnboardingState",
        "org.yapyap.orchestrator.boot.ResetReason",
        // --- GUI API: shared vocabulary ----------------------------------------------
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
        // --- Shared-internal vocabulary (public for codegen/signatures; NOT GUI API) --
        "org.yapyap.protocol.MessagePayloadType",
        "org.yapyap.protocol.envelopes.PacketNackReason",
        "org.yapyap.crypto.e2ee.session.SessionRole",
        "org.yapyap.crypto.e2ee.session.SessionStatus",
        "org.yapyap.crypto.e2ee.session.X3dhMode",
        "org.yapyap.persistence.db.VerificationState",
        "org.yapyap.persistence.db.FileTransferStatus",
        "org.yapyap.persistence.db.FileChunkStatus",
        "org.yapyap.persistence.db.OpkStatus",
    )

    private val visibilityModifiers = setOf("public", "internal", "private", "protected")

    private val declarationKeywords = setOf(
        "class", "interface", "object", "fun", "val", "var", "typealias",
        "companion", "enum", "sealed", "data", "value", "annotation",
        "abstract", "open", "expect", "actual", "const", "suspend", "inline",
        "infix", "operator", "tailrec", "external", "inner", "lateinit",
    )

    private val softKeywords = setOf(
        "expect", "actual", "sealed", "data", "value", "enum", "annotation",
        "abstract", "open", "const", "suspend", "inline", "infix", "operator",
        "tailrec", "external", "inner", "lateinit",
    )

    private val hardKeywords = setOf(
        "class", "interface", "object", "fun", "val", "var", "typealias", "companion",
    )

    /** Top-level keywords that open a member scope (for tracking the enclosing type). */
    private val typeScopeKeywords = setOf("class", "interface", "object", "enum", "annotation")

    private data class Violation(val file: String, val line: Int, val text: String, val reason: String)

    private fun coreSrcRoots(): List<File> {
        val base = if (File("src").isDirectory) File("src") else File("core/src")
        assertTrue(base.isDirectory, "Cannot locate core sources from ${File(".").absolutePath}")
        return base.listFiles()!!.filter { it.isDirectory && it.name.endsWith("Main") }
    }

    private fun topLevelName(tokens: List<String>): String? {
        var i = 0
        while (i < tokens.size && tokens[i] in softKeywords) i++
        if (i >= tokens.size || tokens[i] !in hardKeywords) return null
        if (tokens[i] == "companion") return "companion"
        if (i + 1 >= tokens.size) return null
        var j = i + 1
        // Skip generic parameter lists (`fun <T> foo`).
        while (j < tokens.size && tokens[j].startsWith("<")) j++
        if (j >= tokens.size) return null
        val raw = tokens[j].trimStart('(', '@')
        val name = raw.split('(', '<', ':', '=', ',', ' ').firstOrNull().orEmpty()
        if (!name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return null
        return name
    }

    private fun scan(): List<Violation> {
        val violations = mutableListOf<Violation>()
        for (root in coreSrcRoots()) {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    var pkg = ""
                    // Explicit modifier (or "missing") of the enclosing top-level type-like
                    // declaration; null outside member scopes (e.g. inside top-level funs).
                    var currentTop: String? = null
                    val rel = root.toPath().relativize(file.toPath()).toString()
                    file.readLines().forEachIndexed { index, raw ->
                        val lineNo = index + 1
                        val indent = raw.takeWhile { it == ' ' }.length
                        val line = raw.trim().trimStart('﻿')
                        if (line.isEmpty() || line.startsWith("//") || line.startsWith("*") ||
                            line.startsWith("/*") || line.startsWith("@") ||
                            line.startsWith("}") || line.startsWith(")") ||
                            line.startsWith("import ") || line.startsWith("package ")
                        ) {
                            if (line.startsWith("package ")) pkg = line.removePrefix("package ").trim()
                            return@forEachIndexed
                        }
                        val tokens = line.split(Regex("\\s+"))
                        if (indent == 0) {
                            if (tokens[0] !in visibilityModifiers) {
                                violations += Violation(
                                    rel, lineNo, line,
                                    "top-level declaration without explicit visibility " +
                                            "(mark internal, or whitelist as public)",
                                )
                            } else if (tokens[0] == "public") {
                                val name = topLevelName(tokens.drop(1))
                                if (name == null || (name != "companion" && "$pkg.$name" !in publicTopLevelWhitelist)) {
                                    violations += Violation(
                                        rel, lineNo, line,
                                        "public top-level declaration is not on the whitelist " +
                                                "(mark internal, or deliberately list it)",
                                    )
                                }
                            }
                            // Track the enclosing scope: member checks below only apply
                            // inside public top-level types (members of internal types
                            // are exempt, matching the compiler).
                            val headAfterVis = tokens.drop(1).dropWhile { it in softKeywords }.firstOrNull()
                            currentTop = if (headAfterVis != null && headAfterVis.trimStart('(') in typeScopeKeywords) {
                                tokens[0].takeIf { it in visibilityModifiers } ?: "missing"
                            } else {
                                null
                            }
                        } else if (indent == 4) {
                            if ("override" in tokens) return@forEachIndexed
                            if (tokens[0] in visibilityModifiers) return@forEachIndexed
                            // Data-class constructor properties (`val x: T,`) carry no
                            // modifier by design; see the KDoc gap note.
                            if ((tokens[0] == "val" || tokens[0] == "var") && line.endsWith(",")) {
                                return@forEachIndexed
                            }
                            val head = tokens.dropWhile { it in softKeywords }.firstOrNull()?.trimStart('(')
                            if (head != null && head in declarationKeywords && currentTop == "public") {
                                violations += Violation(
                                    rel, lineNo, line,
                                    "member declaration without explicit visibility",
                                )
                            }
                        }
                        // Deeper indents (locals, nested-type members) are out of scope:
                        // the explicit-API compiler warnings still surface them in build output.
                    }
                }
        }
        return violations
    }

    @Test
    fun `public surface matches the whitelist`() {
        val violations = scan()
        assertTrue(
            violations.isEmpty(),
            "GUI boundary violations (${violations.size}):\n" +
                    violations.joinToString("\n") { "  ${it.file}:${it.line}: ${it.reason}\n    ${it.text}" },
        )
    }
}
