package io.dataloom.api.configuration

import kotlin.jvm.JvmInline

/**
 * Canonical identifier for one configuration entry within a
 * [ConfigurationSchema].
 *
 * Same flat, single-field shape as `io.dataloom.api.identifier`'s
 * identifiers and `io.dataloom.api.security.KeyReference`.
 *
 * Ownership: whoever declares the [ConfigurationSchema] this key belongs to
 * — an application or a DataLoom subsystem.
 *
 * Constraints:
 * - [value] must not be blank or whitespace-only.
 * - `toString()` returns [value].
 */
@JvmInline
public value class ConfigurationKey(
    /** Underlying canonical key value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "ConfigurationKey must not be blank." }
    }

    override fun toString(): String = value
}
