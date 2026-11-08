package org.yapyap.config

import kotlin.time.Duration

// ---------------------------------------------------------------------------
// Value representation (uniform type for the override map).
// ---------------------------------------------------------------------------

public sealed interface ConfigValue {
    public data class Number(val value: Long) : ConfigValue
    public data class Text(val value: String) : ConfigValue
    public data class Toggle(val value: Boolean) : ConfigValue
    public data class Period(val value: Duration) : ConfigValue
}

// ---------------------------------------------------------------------------
// GUI-facing display model.
// ---------------------------------------------------------------------------

public sealed interface Setting {
    public val id: String
    public val title: String
    public val description: String
    public val group: String
    public val editable: Boolean
}

public data class NumberSetting(
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

public data class TextSetting(
    override val id: String,
    override val title: String,
    override val description: String,
    override val group: String,
    override val editable: Boolean,
    val value: String,
    val isSecret: Boolean = false,
) : Setting

public data class ToggleSetting(
    override val id: String,
    override val title: String,
    override val description: String,
    override val group: String,
    override val editable: Boolean,
    val value: Boolean,
) : Setting

public data class PeriodSetting(
    override val id: String,
    override val title: String,
    override val description: String,
    override val group: String,
    override val editable: Boolean,
    val value: Duration,
) : Setting
