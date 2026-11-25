package org.yapyap.wiring

import org.yapyap.config.ConfigValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ConfigInvariantsTest {

    private val defaults = RuntimeConfig()

    private fun assertBreaksOne(config: RuntimeConfig, mentioning: String) {
        val violations = ConfigInvariants.violations(config)
        assertEquals(1, violations.size, "expected one violation, got $violations")
        assertTrue(mentioning in violations.single(), "expected '$mentioning' in ${violations.single()}")
    }

    @Test
    fun defaults_holdEveryRelation() {
        assertEquals(emptyList(), ConfigInvariants.violations(defaults))
    }

    @Test
    fun transferTtl_mustBeShorterThanSupersededSessionRetention() {
        val config =
            defaults.copy(crypto = defaults.crypto.copy(supersededSessionRetention = defaults.router.fileTransferTtl))
        val violations = ConfigInvariants.violations(config)
        assertEquals(2, violations.size, "expected two violations, got $violations")
        assertTrue(violations.any { "File transfer TTL" in it }, "expected file TTL violation in $violations")
        assertTrue(violations.any { "Envelope lifetime" in it }, "expected envelope lifetime violation in $violations")
    }

    @Test
    fun unreferencedRetention_mustCoverTheTransferTtl() = assertBreaksOne(
        defaults.copy(orchestrator = defaults.orchestrator.copy(unreferencedFileRetention = 1.days)),
        "Unreferenced file retention",
    )

    @Test
    fun staging_mustHoldThePushWindow() = assertBreaksOne(
        defaults.copy(router = defaults.router.copy(maxInFlightChunks = 1_000)),
        "push window",
    )

    @Test
    fun fetchWindow_mustExceedACheckpoint() = assertBreaksOne(
        defaults.copy(router = defaults.router.copy(fileStreamWindowBytes = defaults.orchestrator.fileCheckpointBytes)),
        "Fetch window",
    )

    @Test
    fun stallTimeout_mustExceedTheChunkAckTimeout() = assertBreaksOne(
        defaults.copy(orchestrator = defaults.orchestrator.copy(fileStallTimeout = 30.seconds)),
        "chunk ACK timeout",
    )

    @Test
    fun onboardingSession_mustOutliveTheBootstrapIntro() = assertBreaksOne(
        defaults.copy(orchestrator = defaults.orchestrator.copy(onboardingSessionLifetime = 5.minutes)),
        "bootstrap intro lifetime",
    )

    @Test
    fun transportTooSmallForChunks_isAViolationNotACrash() = assertBreaksOne(
        defaults.copy(webRtc = defaults.webRtc.copy(maxPayloadBytes = 1024)),
        "file chunk",
    )

    // Two test-only fields on either side of the first relation; the registry has none.
    private val ttl = PeriodField(
        id = "ttl", title = "", description = "", group = "Test", source = FieldSource.USER,
        readValue = { it.router.fileTransferTtl },
        writeValue = { cfg, v -> cfg.copy(router = cfg.router.copy(fileTransferTtl = v)) },
    )
    private val retention = PeriodField(
        id = "retention", title = "", description = "", group = "Test", source = FieldSource.USER,
        readValue = { it.crypto.supersededSessionRetention },
        writeValue = { cfg, v -> cfg.copy(crypto = cfg.crypto.copy(supersededSessionRetention = v)) },
    )
    private val unreferenced = PeriodField(
        id = "unreferenced", title = "", description = "", group = "Test", source = FieldSource.USER,
        readValue = { it.orchestrator.unreferencedFileRetention },
        writeValue = { cfg, v -> cfg.copy(orchestrator = cfg.orchestrator.copy(unreferencedFileRetention = v)) },
    )
    private val fields = listOf(ttl, retention, unreferenced)

    @Test
    fun derive_landsRelatedOverridesWhateverTheirOrder() {
        // ttl comes first in the field list but only fits once retention and unreferenced have landed.
        val derivation = deriveChecked(
            mapOf(
                "ttl" to ConfigValue.Period(4.days),
                "retention" to ConfigValue.Period(5.days),
                "unreferenced" to ConfigValue.Period(4.days),
            ),
            fields,
        )
        assertEquals(emptyMap(), derivation.rejected)
        assertEquals(4.days, derivation.config.router.fileTransferTtl)
        assertEquals(5.days, derivation.config.crypto.supersededSessionRetention)
    }

    @Test
    fun derive_dropsAnOverrideThatBreaksARelation_atLoad() {
        val derivation = deriveChecked(mapOf("ttl" to ConfigValue.Period(4.days)), fields)
        assertTrue("ttl" in derivation.rejected, "rejected: ${derivation.rejected}")
        assertEquals(defaults, derivation.config)
    }

    @Test
    fun settingsChange_thatBreaksARelation_isRefused() {
        val current = mapOf<String, ConfigValue>("retention" to ConfigValue.Period(5.days))
        assertNotNull(refusalOf("retention", current, mapOf("retention" to ConfigValue.Period(1.days)), fields))
        assertNull(refusalOf("retention", current, mapOf("retention" to ConfigValue.Period(4.days)), fields))
    }

    @Test
    fun clearingASetting_thatAnotherNeeds_isRefused() {
        val current = mapOf<String, ConfigValue>(
            "ttl" to ConfigValue.Period(3.days),
            "retention" to ConfigValue.Period(5.days),
            "unreferenced" to ConfigValue.Period(3.days),
        )
        // Without the retention override, retention falls back to 3 days and ttl no longer fits.
        assertNotNull(refusalOf("retention", current, current - "retention", fields))
    }
}
