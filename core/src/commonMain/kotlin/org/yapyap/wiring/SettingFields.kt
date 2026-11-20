package org.yapyap.wiring

import net.peanuuutz.tomlkt.Toml
import net.peanuuutz.tomlkt.TomlTable
import net.peanuuutz.tomlkt.getLiteralOrNull
import net.peanuuutz.tomlkt.getTableOrNull
import net.peanuuutz.tomlkt.toBoolean
import net.peanuuutz.tomlkt.toLong
import org.yapyap.config.ConfigValue
import org.yapyap.config.NumberSetting
import org.yapyap.config.PeriodSetting
import org.yapyap.config.Setting
import org.yapyap.config.TextSetting
import org.yapyap.config.ToggleSetting
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import kotlin.time.Duration

// ---------------------------------------------------------------------------
// Who sets a field.
// ---------------------------------------------------------------------------

internal enum class FieldSource { USER, NETWORK, READ_ONLY }

// ---------------------------------------------------------------------------
// Result of applying a value onto a RuntimeConfig.
// ---------------------------------------------------------------------------

internal sealed interface WriteResult {
    data class Ok(val cfg: RuntimeConfig) : WriteResult
    data class Invalid(val reason: String) : WriteResult
}

// ---------------------------------------------------------------------------
// Field registry entry. A field is defined once and drives derivation,
// persistence and the GUI display model.
// ---------------------------------------------------------------------------

internal sealed class Field(
    val id: String,
    val title: String,
    val description: String,
    val group: String,
    val source: FieldSource,
) {
    val editable: Boolean get() = source == FieldSource.USER

    abstract fun read(cfg: RuntimeConfig): ConfigValue
    abstract fun write(cfg: RuntimeConfig, value: ConfigValue): WriteResult
    abstract fun setting(value: ConfigValue): Setting
}

internal class NumberField(
    id: String,
    title: String,
    description: String,
    group: String,
    source: FieldSource,
    val unit: String? = null,
    val min: Long? = null,
    val max: Long? = null,
    private val readValue: (RuntimeConfig) -> Long,
    private val writeValue: ((RuntimeConfig, Long) -> RuntimeConfig)? = null,
) : Field(id, title, description, group, source) {

    override fun read(cfg: RuntimeConfig): ConfigValue = ConfigValue.Number(readValue(cfg))

    override fun write(cfg: RuntimeConfig, value: ConfigValue): WriteResult {
        val setter = writeValue ?: return WriteResult.Invalid("$id is read-only")
        val v = (value as? ConfigValue.Number)?.value
            ?: return WriteResult.Invalid("$id expects a number value")
        return try {
            WriteResult.Ok(setter(cfg, v))
        } catch (e: IllegalArgumentException) {
            WriteResult.Invalid(e.message ?: "invalid value for $id")
        }
    }

    override fun setting(value: ConfigValue): Setting = NumberSetting(
        id = id, title = title, description = description, group = group,
        editable = editable,
        value = (value as ConfigValue.Number).value,
        unit = unit, min = min, max = max,
    )
}

internal class TextField(
    id: String,
    title: String,
    description: String,
    group: String,
    source: FieldSource,
    val isSecret: Boolean = false,
    private val readValue: (RuntimeConfig) -> String,
    private val writeValue: ((RuntimeConfig, String) -> RuntimeConfig)? = null,
) : Field(id, title, description, group, source) {

    override fun read(cfg: RuntimeConfig): ConfigValue = ConfigValue.Text(readValue(cfg))

    override fun write(cfg: RuntimeConfig, value: ConfigValue): WriteResult {
        val setter = writeValue ?: return WriteResult.Invalid("$id is read-only")
        val v = (value as? ConfigValue.Text)?.value
            ?: return WriteResult.Invalid("$id expects a text value")
        return try {
            WriteResult.Ok(setter(cfg, v))
        } catch (e: IllegalArgumentException) {
            WriteResult.Invalid(e.message ?: "invalid value for $id")
        }
    }

    override fun setting(value: ConfigValue): Setting = TextSetting(
        id = id, title = title, description = description, group = group,
        editable = editable,
        value = (value as ConfigValue.Text).value,
        isSecret = isSecret,
    )
}

internal class ToggleField(
    id: String,
    title: String,
    description: String,
    group: String,
    source: FieldSource,
    private val readValue: (RuntimeConfig) -> Boolean,
    private val writeValue: ((RuntimeConfig, Boolean) -> RuntimeConfig)? = null,
) : Field(id, title, description, group, source) {

    override fun read(cfg: RuntimeConfig): ConfigValue = ConfigValue.Toggle(readValue(cfg))

    override fun write(cfg: RuntimeConfig, value: ConfigValue): WriteResult {
        val setter = writeValue ?: return WriteResult.Invalid("$id is read-only")
        val v = (value as? ConfigValue.Toggle)?.value
            ?: return WriteResult.Invalid("$id expects a toggle value")
        return try {
            WriteResult.Ok(setter(cfg, v))
        } catch (e: IllegalArgumentException) {
            WriteResult.Invalid(e.message ?: "invalid value for $id")
        }
    }

    override fun setting(value: ConfigValue): Setting = ToggleSetting(
        id = id, title = title, description = description, group = group,
        editable = editable,
        value = (value as ConfigValue.Toggle).value,
    )
}

internal class PeriodField(
    id: String,
    title: String,
    description: String,
    group: String,
    source: FieldSource,
    private val readValue: (RuntimeConfig) -> Duration,
    private val writeValue: ((RuntimeConfig, Duration) -> RuntimeConfig)? = null,
) : Field(id, title, description, group, source) {
    override fun read(cfg: RuntimeConfig): ConfigValue = ConfigValue.Period(readValue(cfg))

    override fun write(cfg: RuntimeConfig, value: ConfigValue): WriteResult {
        val setter = writeValue ?: return WriteResult.Invalid("$id is read-only")
        val v = (value as? ConfigValue.Period)?.value
            ?: return WriteResult.Invalid("$id expects a number value")
        return try {
            WriteResult.Ok(setter(cfg, v))
        } catch (e: IllegalArgumentException) {
            WriteResult.Invalid(e.message ?: "invalid value for $id")
        }
    }

    override fun setting(value: ConfigValue): Setting = PeriodSetting(
        id = id, title = title, description = description, group = group,
        editable = editable,
        value = (value as ConfigValue.Period).value,
    )
}

// ---------------------------------------------------------------------------
// Overrides: absence of a key means "use the default".
// ---------------------------------------------------------------------------

internal typealias Overrides = Map<String, ConfigValue>

internal fun Overrides.toTomlText(): String {
    val grouped: Map<String, Map<String, Any>> = FIELDS
        .filter { it.source == FieldSource.USER }
        .groupBy { it.group }
        .mapValues { (_, fields) ->
            fields.mapNotNull { f -> this[f.id]?.let { f.id to it.raw } }.toMap()
        }
    return Toml.encodeToString(TomlTable.serializer(), TomlTable(grouped))
}

internal fun TomlTable.toOverrides(): Overrides = buildMap {
    for (field in FIELDS.filter { it.source == FieldSource.USER }) {
        val literal = getTableOrNull(field.group)?.getLiteralOrNull(field.id) ?: continue
        when (field) {
            is NumberField -> put(field.id, ConfigValue.Number(literal.toLong()))
            is TextField -> put(field.id, ConfigValue.Text(literal.toString()))
            is ToggleField -> put(field.id, ConfigValue.Toggle(literal.toBoolean()))
            is PeriodField -> put(field.id, ConfigValue.Period(Duration.parse(literal.toString())))
        }
    }
}

private val ConfigValue.raw: Any
    get() = when (this) {
        is ConfigValue.Number -> value
        is ConfigValue.Text -> value
        is ConfigValue.Toggle -> value
        is ConfigValue.Period -> value.toString()
    }

// ---------------------------------------------------------------------------
// Generic derivation / projection, driven by the registry.
// ---------------------------------------------------------------------------

/** The config [derive] built, and each override it could not apply, with the reason. */
internal data class Derivation(
    val config: RuntimeConfig,
    val rejected: Map<String, String>,
)

/**
 * Applies [overrides] onto the defaults, keeping the config valid at every step: an override is
 * applied only when its field accepts the value and the result keeps [ConfigInvariants]. Rejected
 * overrides are retried after each pass, so two related overrides land whatever their order in
 * [FIELDS]; whatever still can't land is reported in [Derivation.rejected].
 */
internal fun deriveChecked(overrides: Overrides, fields: List<Field> = FIELDS): Derivation {
    var config = RuntimeConfig()
    val pending = fields.filter { it.id in overrides }.toMutableList()
    val rejected = mutableMapOf<String, String>()
    do {
        var progressed = false
        rejected.clear()
        for (field in pending.toList()) {
            val reason = when (val result = field.write(config, overrides.getValue(field.id))) {
                is WriteResult.Invalid -> result.reason
                is WriteResult.Ok -> {
                    val violations = ConfigInvariants.violations(result.cfg)
                    if (violations.isEmpty()) {
                        config = result.cfg
                        pending -= field
                        progressed = true
                        null
                    } else {
                        violations.joinToString("; ")
                    }
                }
            }
            if (reason != null) rejected[field.id] = reason
        }
    } while (progressed && pending.isNotEmpty())
    return Derivation(config, rejected.toMap())
}

/**
 * Why changing the overrides from [current] to [next] is refused, or null when it is allowed: the
 * changed setting [id] can't land, or the change stops another override from landing.
 */
internal fun refusalOf(id: String, current: Overrides, next: Overrides, fields: List<Field> = FIELDS): String? {
    val before = deriveChecked(current, fields).rejected
    val broken = deriveChecked(next, fields).rejected.filterKeys { it == id || it !in before }
    return broken.takeIf { it.isNotEmpty() }?.entries?.joinToString("; ") { (key, reason) -> "$key: $reason" }
}

/** [deriveChecked], logging each override it had to drop. */
internal fun derive(overrides: Overrides): RuntimeConfig {
    val derivation = deriveChecked(overrides)
    for ((id, reason) in derivation.rejected) {
        AppLog.warn(
            component = LogComponent.CONFIG,
            event = LogEvent.CONFIG_INVALID_OVERRIDE,
            message = "Invalid override for $id: $reason",
            fields = mapOf("override" to overrides[id]),
        )
    }
    return derivation.config
}

internal fun buildSettings(overrides: Overrides): List<Setting> {
    val effective = deriveChecked(overrides).config
    return FIELDS.map { field -> field.setting(field.read(effective)) }
}

internal fun projectNetwork(runtime: RuntimeConfig): Overrides =
    FIELDS.filter { it.source == FieldSource.NETWORK }
        .associate { field -> field.id to field.read(runtime) }