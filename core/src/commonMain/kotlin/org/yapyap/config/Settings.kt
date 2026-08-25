package org.yapyap.config

import net.peanuuutz.tomlkt.*

// ---------------------------------------------------------------------------
// Value representation (uniform type for the override map).
// ---------------------------------------------------------------------------

sealed interface ConfigValue {
    data class Number(val value: Long) : ConfigValue
    data class Text(val value: String) : ConfigValue
    data class Toggle(val value: Boolean) : ConfigValue
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
        }
    }
}

private val ConfigValue.raw: Any get() = when (this) {
    is ConfigValue.Number -> value
    is ConfigValue.Text -> value
    is ConfigValue.Toggle -> value
}

// ---------------------------------------------------------------------------
// Generic derivation / projection, driven by the registry.
// ---------------------------------------------------------------------------

fun derive(user: Overrides, network: Overrides): RuntimeConfig =
    FIELDS.fold(RuntimeConfig()) { cfg, field ->
        val override = when (field.source) {
            FieldSource.USER -> user[field.id]
            FieldSource.NETWORK -> network[field.id]
            FieldSource.READ_ONLY -> null
        } ?: return@fold cfg
        when (val result = field.write(cfg, override)) {
            is WriteResult.Ok -> result.cfg
            is WriteResult.Invalid -> cfg // TODO log skipped invalid override
        }
    }

fun buildSettings(user: Overrides, network: Overrides): List<Setting> {
    val effective = derive(user, network)
    return FIELDS.map { field -> field.setting(field.read(effective)) }
}

fun projectNetwork(runtime: RuntimeConfig): Overrides =
    FIELDS.filter { it.source == FieldSource.NETWORK }
        .associate { field -> field.id to field.read(runtime) }

// ---------------------------------------------------------------------------
// The fields. This is the single place a setting is defined.
// ---------------------------------------------------------------------------

val FIELDS: List<Field> = listOf(
    NumberField(
        id = "outboxMaxSizeBytes",
        title = "Outbox max size",
        description = "Maximum bytes of outbound packets buffered while offline.",
        group = "Router",
        source = FieldSource.USER,
        unit = "bytes",
        min = 1L,
        readValue = { it.router.outboxMaxSizeBytes },
        writeValue = { cfg, v -> cfg.copy(router = cfg.router.copy(outboxMaxSizeBytes = v)) },
    ),
    NumberField(
        id = "ackLifetimeSeconds",
        title = "ACK lifetime",
        description = "How long to wait for an acknowledgement before retrying.",
        group = "Router",
        source = FieldSource.USER,
        unit = "seconds",
        min = 1L,
        readValue = { it.router.ackLifetimeSeconds },
        writeValue = { cfg, v -> cfg.copy(router = cfg.router.copy(ackLifetimeSeconds = v)) },
    ),
    NumberField(
        id = "gracePeriodSeconds",
        title = "Sync grace period",
        description = "Delay before an out-of-order message is considered missing.",
        group = "Sync",
        source = FieldSource.USER,
        unit = "seconds",
        min = 0L,
        readValue = { it.sync.gracePeriodSeconds },
        writeValue = { cfg, v -> cfg.copy(sync = cfg.sync.copy(gracePeriodSeconds = v)) },
    ),
    NumberField(
        id = "messageLifetimeSeconds",
        title = "Message lifetime",
        description = "How long messages are retained. Controlled by the network.",
        group = "Router",
        source = FieldSource.NETWORK,
        unit = "seconds",
        min = 1L,
        readValue = { it.router.messageLifetimeSeconds },
        writeValue = { cfg, v -> cfg.copy(router = cfg.router.copy(messageLifetimeSeconds = v)) },
    ),
    NumberField(
        id = "dedupRetentionSeconds",
        title = "Dedup retention",
        description = "How long deduplication entries are retained. Controlled by the network.",
        group = "Router",
        source = FieldSource.NETWORK,
        unit = "seconds",
        readValue = { it.router.dedupRetentionSeconds },
        writeValue = { cfg, v -> cfg.copy(router = cfg.router.copy(dedupRetentionSeconds = v)) },
    ),
)
