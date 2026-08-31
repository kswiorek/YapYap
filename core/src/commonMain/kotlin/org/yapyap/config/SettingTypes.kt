package org.yapyap.config

import net.peanuuutz.tomlkt.*
import org.yapyap.logging.AppLog
import org.yapyap.logging.LogComponent
import org.yapyap.logging.LogEvent
import kotlin.time.Duration

// ---------------------------------------------------------------------------
// Value representation (uniform type for the override map).
// ---------------------------------------------------------------------------

sealed interface ConfigValue {
    data class Number(val value: Long) : ConfigValue
    data class Text(val value: String) : ConfigValue
    data class Toggle(val value: Boolean) : ConfigValue
    data class Period(val value: Duration) : ConfigValue
}

// ---------------------------------------------------------------------------
// Who sets a field.
// ---------------------------------------------------------------------------

enum class FieldSource { USER, NETWORK, READ_ONLY }

// ---------------------------------------------------------------------------
// GUI-facing display model.
// ---------------------------------------------------------------------------

sealed interface Setting {
    val id: String
    val title: String
    val description: String
    val group: String
    val editable: Boolean
}

data class NumberSetting(
    override val id: String,
    override val title: String,
    override val description: String,
    override val group: String,
    override val editable: Boolean,
    val value: Long,
    val unit: String? = null,
    val min: Long? = null,
    val max: Long? = null,
) : Setting

data class TextSetting(
    override val id: String,
    override val title: String,
    override val description: String,
    override val group: String,
    override val editable: Boolean,
    val value: String,
    val isSecret: Boolean = false,
) : Setting

data class ToggleSetting(
    override val id: String,
    override val title: String,
    override val description: String,
    override val group: String,
    override val editable: Boolean,
    val value: Boolean,
) : Setting

data class PeriodSetting(
    override val id: String,
    override val title: String,
    override val description: String,
    override val group: String,
    override val editable: Boolean,
    val value: Duration,
) : Setting

// ---------------------------------------------------------------------------
// Result of applying a value onto a RuntimeConfig.
// ---------------------------------------------------------------------------

sealed interface WriteResult {
    data class Ok(val cfg: RuntimeConfig) : WriteResult
    data class Invalid(val reason: String) : WriteResult
}

// ---------------------------------------------------------------------------
// Field registry entry. A field is defined once and drives derivation,
// persistence and the GUI display model.
// ---------------------------------------------------------------------------

sealed class Field(
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

class NumberField(
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

class TextField(
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

class ToggleField(
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

class PeriodField(
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

typealias Overrides = Map<String, ConfigValue>

fun Overrides.toTomlText(): String {
    val grouped: Map<String, Map<String, Any>> = FIELDS
        .filter { it.source == FieldSource.USER }
        .groupBy { it.group }
        .mapValues { (_, fields) ->
            fields.mapNotNull { f -> this[f.id]?.let { f.id to it.raw } }.toMap()
        }
    return Toml.encodeToString(TomlTable.serializer(), TomlTable(grouped))
}

fun TomlTable.toOverrides(): Overrides = buildMap {
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

private val ConfigValue.raw: Any get() = when (this) {
    is ConfigValue.Number -> value
    is ConfigValue.Text -> value
    is ConfigValue.Toggle -> value
    is ConfigValue.Period -> value.toString()
}

// ---------------------------------------------------------------------------
// Generic derivation / projection, driven by the registry.
// ---------------------------------------------------------------------------

fun derive(overrides: Overrides): RuntimeConfig =
    FIELDS.fold(RuntimeConfig()) { cfg, field ->
        val override = overrides[field.id] ?: return@fold cfg
        when (val result = field.write(cfg, override)) {
            is WriteResult.Ok -> result.cfg
            is WriteResult.Invalid -> {
                AppLog.warn(
                    component = LogComponent.CONFIG,
                    event = LogEvent.CONFIG_INVALID_OVERRIDE,
                    message = "Invalid override for ${field.id}: ${result.reason}",
                    fields = mapOf("override" to override),
                )
                cfg
            }
        }
    }

fun buildSettings(overrides: Overrides): List<Setting> {
    val effective = derive(overrides)
    return FIELDS.map { field -> field.setting(field.read(effective)) }
}

fun projectNetwork(runtime: RuntimeConfig): Overrides =
    FIELDS.filter { it.source == FieldSource.NETWORK }
        .associate { field -> field.id to field.read(runtime) }