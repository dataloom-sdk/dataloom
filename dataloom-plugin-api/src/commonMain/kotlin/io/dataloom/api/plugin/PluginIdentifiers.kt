package io.dataloom.api.plugin

import kotlin.jvm.JvmInline

/**
 * Stable machine-readable plugin identifier.
 *
 * Values are validated as non-blank and preserved exactly as supplied.
 */
@JvmInline
public value class PluginId(
    /** Underlying plugin identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "PluginId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Stable plugin version label.
 *
 * Values are validated as non-blank and preserved exactly as supplied. This
 * type does not parse or compare versions — semantic-version comparison for
 * compatibility validation belongs to the plugin lifecycle engine (#98), not
 * this contract module.
 */
@JvmInline
public value class PluginVersion(
    /** Underlying plugin version value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "PluginVersion must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Stable identifier for a plugin's publishing vendor or author.
 *
 * Values are validated as non-blank and preserved exactly as supplied.
 */
@JvmInline
public value class PluginVendor(
    /** Underlying plugin vendor value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "PluginVendor must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Stable capability label a plugin declares it provides.
 *
 * Values are validated as non-blank and preserved exactly as supplied.
 */
@JvmInline
public value class PluginCapability(
    /** Underlying plugin capability value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "PluginCapability must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Stable permission label a plugin declares it requires.
 *
 * A [PluginPermission] is a request, not a grant — deny-by-default
 * enablement and denied-operation diagnostics are runtime behavior owned by
 * the plugin lifecycle engine (#98), not this contract type. Values are
 * validated as non-blank and preserved exactly as supplied.
 */
@JvmInline
public value class PluginPermission(
    /** Underlying plugin permission value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "PluginPermission must not be blank." }
    }

    override fun toString(): String = value
}
